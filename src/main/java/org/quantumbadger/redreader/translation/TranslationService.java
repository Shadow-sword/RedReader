/*******************************************************************************
 * This file is part of RedReader.
 *
 * RedReader is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * RedReader is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with RedReader.  If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/

package org.quantumbadger.redreader.translation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounds concurrent inference across all post and comment requests. */
public final class TranslationService implements AutoCloseable {

	public interface Callback {

		void onSuccess(String translation);

		void onFailure(Throwable error);
	}

	private final TranslationProvider provider;
	private final Executor callbackExecutor;
	private final ThreadPoolExecutor worker;
	private final Set<Job> jobs = new HashSet<>();
	private volatile boolean closed;

	/** Supply the Android main-thread executor when callbacks update a view. */
	public TranslationService(final TranslationProvider provider,
			final Executor callbackExecutor, final int concurrency) {
		this.provider = Objects.requireNonNull(provider);
		this.callbackExecutor = Objects.requireNonNull(callbackExecutor);
		validateConcurrency(concurrency);
		worker = new ThreadPoolExecutor(concurrency, concurrency, 0, TimeUnit.MILLISECONDS,
				new LinkedBlockingQueue<>()) {
			@Override
			protected void terminated() {
				TranslationService.this.provider.close();
			}
		};
	}

	public int getConcurrency() {
		return worker.getMaximumPoolSize();
	}

	/** Existing calls finish normally when reducing the limit. */
	public synchronized void setConcurrency(final int concurrency) {
		validateConcurrency(concurrency);
		if(closed) {
			throw new IllegalStateException("Translation service is closed");
		}
		if(concurrency > worker.getMaximumPoolSize()) {
			worker.setMaximumPoolSize(concurrency);
			worker.setCorePoolSize(concurrency);
		} else {
			worker.setCorePoolSize(concurrency);
			worker.setMaximumPoolSize(concurrency);
		}
	}

	private static void validateConcurrency(final int concurrency) {
		if(concurrency < 1 || concurrency > 4) {
			throw new IllegalArgumentException("Translation concurrency must be between 1 and 4");
		}
	}

	/** Cancel the returned future when its requesting screen is dismissed. */
	public synchronized Future<String> translate(
			final TranslationRequest request,
			final Callback callback) {

		if(closed) {
			throw new IllegalStateException("Translation service is closed");
		}

		final Job job = new Job(
				Objects.requireNonNull(request),
				Objects.requireNonNull(callback),
				new AtomicBoolean());
		jobs.add(job);
		worker.execute(job);
		return job;
	}

	@Override
	public synchronized void close() {
		if(closed) {
			return;
		}
		closed = true;
		for(final Job job : new ArrayList<>(jobs)) {
			job.cancel(true);
		}
		// terminated() closes the provider only after every inference has returned.
		worker.shutdown();
	}

	private final class Job extends FutureTask<String> {

		private final Callback callback;
		private final AtomicBoolean cancellation;

		private Job(
				final TranslationRequest request,
				final Callback callback,
				final AtomicBoolean cancellation) {

			super(() -> {
				final String result = provider.translate(request, cancellation::get);
				if(result == null || result.trim().isEmpty()) {
					throw new IOException("The model returned an empty translation");
				}
				return result;
			});
			this.callback = callback;
			this.cancellation = cancellation;
		}

		@Override
		public boolean cancel(final boolean mayInterruptIfRunning) {
			cancellation.set(true);
			final boolean cancelled = super.cancel(mayInterruptIfRunning);
			worker.remove(this);
			return cancelled;
		}

		@Override
		protected void done() {
			synchronized(TranslationService.this) {
				jobs.remove(this);
			}
			if(isCancelled() || closed) {
				return;
			}

			callbackExecutor.execute(() -> {
				if(cancellation.get() || closed) {
					return;
				}
				final String result;
				try {
					result = get();
				} catch(final ExecutionException e) {
					callback.onFailure(e.getCause());
					return;
				} catch(final InterruptedException e) {
					Thread.currentThread().interrupt();
					callback.onFailure(e);
					return;
				}
				callback.onSuccess(result);
			});
		}
	}
}
