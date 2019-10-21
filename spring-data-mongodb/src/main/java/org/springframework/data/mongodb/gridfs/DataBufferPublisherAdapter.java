/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.mongodb.gridfs;

import lombok.RequiredArgsConstructor;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.util.context.Context;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;

import com.mongodb.reactivestreams.client.gridfs.AsyncInputStream;

/**
 * Utility to adapt a {@link AsyncInputStream} to a {@link Publisher} emitting {@link DataBuffer}.
 *
 * @author Mark Paluch
 * @author Christoph Strobl
 * @since 2.2
 */
class DataBufferPublisherAdapter {

	/**
	 * Creates a {@link Publisher} emitting {@link DataBuffer}s by reading binary chunks from {@link AsyncInputStream}.
	 * Closes the {@link AsyncInputStream} once the {@link Publisher} terminates.
	 *
	 * @param inputStream must not be {@literal null}.
	 * @param dataBufferFactory must not be {@literal null}.
	 * @param bufferSize read {@code n} bytes per iteration.
	 * @return the resulting {@link Publisher}.
	 */
	static Flux<DataBuffer> createBinaryStream(AsyncInputStream inputStream, DataBufferFactory dataBufferFactory,
			int bufferSize) {

		State state = new State(inputStream, dataBufferFactory, bufferSize);

		return Flux.usingWhen(Mono.just(inputStream), it -> {

			return Flux.<DataBuffer> create((sink) -> {

				sink.onDispose(state::close);
				sink.onCancel(state::close);

				sink.onRequest(n -> {
					state.request(sink, n);
				});
			});
		}, AsyncInputStream::close, (it, err) -> it.close(), AsyncInputStream::close) //
				.concatMap(Flux::just, 1);
	}

	@RequiredArgsConstructor
	static class State {

		private static final AtomicLongFieldUpdater<State> DEMAND = AtomicLongFieldUpdater.newUpdater(State.class,
				"demand");

		private static final AtomicIntegerFieldUpdater<State> STATE = AtomicIntegerFieldUpdater.newUpdater(State.class,
				"state");

		private static final AtomicIntegerFieldUpdater<State> READ = AtomicIntegerFieldUpdater.newUpdater(State.class,
				"read");

		private static final int STATE_OPEN = 0;
		private static final int STATE_CLOSED = 1;

		private static final int READ_NONE = 0;
		private static final int READ_IN_PROGRESS = 1;

		final AsyncInputStream inputStream;
		final DataBufferFactory dataBufferFactory;
		final int bufferSize;

		// see DEMAND
		volatile long demand;

		// see STATE
		volatile int state = STATE_OPEN;

		// see READ_IN_PROGRESS
		volatile int read = READ_NONE;

		void request(FluxSink<DataBuffer> sink, long n) {

			Operators.addCap(DEMAND, this, n);
			drainLoop(sink);
		}

		/**
		 * Loops while we have demand and while no read is in progress.
		 *
		 * @param sink
		 */
		void drainLoop(FluxSink<DataBuffer> sink) {
			while (onShouldRead()) {
				emitNext(sink);
			}
		}

		boolean onShouldRead() {
			return !isClosed() && getDemand() > 0 && onWantRead();
		}

		boolean onWantRead() {
			return READ.compareAndSet(this, READ_NONE, READ_IN_PROGRESS);
		}

		void onReadDone() {
			READ.compareAndSet(this, READ_IN_PROGRESS, READ_NONE);
		}

		long getDemand() {
			return DEMAND.get(this);
		}

		void decrementDemand() {
			DEMAND.decrementAndGet(this);
		}

		void close() {
			STATE.compareAndSet(this, STATE_OPEN, STATE_CLOSED);
		}

		boolean isClosed() {
			return STATE.get(this) == STATE_CLOSED;
		}

		/**
		 * Emit the next {@link DataBuffer}.
		 *
		 * @param sink
		 * @return
		 */
		private void emitNext(FluxSink<DataBuffer> sink) {

			ByteBuffer transport = ByteBuffer.allocate(bufferSize);
			BufferCoreSubscriber bufferCoreSubscriber = new BufferCoreSubscriber(sink, dataBufferFactory, transport);
			try {
				inputStream.read(transport).subscribe(bufferCoreSubscriber);
			} catch (Throwable e) {
				sink.error(e);
			}
		}

		private class BufferCoreSubscriber implements CoreSubscriber<Integer> {

			private final FluxSink<DataBuffer> sink;
			private final DataBufferFactory factory;
			private final ByteBuffer transport;
			private final Thread subscribeThread = Thread.currentThread();
			private volatile Subscription subscription;

			BufferCoreSubscriber(FluxSink<DataBuffer> sink, DataBufferFactory factory, ByteBuffer transport) {

				this.sink = sink;
				this.factory = factory;
				this.transport = transport;
			}

			@Override
			public Context currentContext() {
				return sink.currentContext();
			}

			@Override
			public void onSubscribe(Subscription s) {
				this.subscription = s;
				s.request(1);
			}

			@Override
			public void onNext(Integer bytes) {

				if (isClosed()) {

					onReadDone();
					return;
				}

				if (bytes > 0) {

					transport.flip();

					DataBuffer dataBuffer = factory.allocateBuffer(transport.remaining());
					dataBuffer.write(transport);

					transport.clear();
					sink.next(dataBuffer);

					decrementDemand();
				}

				try {
					if (bytes == -1) {
						sink.complete();
						return;
					}
				} finally {
					onReadDone();
				}

				subscription.request(1);
			}

			@Override
			public void onError(Throwable t) {

				if (isClosed()) {

					Operators.onErrorDropped(t, sink.currentContext());
					return;
				}

				onReadDone();
				sink.error(t);
			}

			@Override
			public void onComplete() {

				if (subscribeThread != Thread.currentThread()) {
					drainLoop(sink);
				}
			}
		}
	}
}
