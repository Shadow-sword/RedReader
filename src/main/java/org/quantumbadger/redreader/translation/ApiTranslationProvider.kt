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


package org.quantumbadger.redreader.translation

import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import org.json.JSONException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BooleanSupplier

/** Non-streaming HTTP with interruptible waits and complete-result-only caching. */
class ApiTranslationProvider(
	private val config: ApiTranslationConfig,
	private val cache: TranslationCache,
	useTor: Boolean
) : TranslationProvider {
	private val client = OkHttpClient.Builder()
		.proxy(if(useTor) Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 8118)) else null)
		.retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
		.readTimeout(0, TimeUnit.SECONDS)
		.addNetworkInterceptor { chain ->
			val sent = chain.request().tag(AtomicBoolean::class.java)!!
			if(!sent.compareAndSet(false, true)) throw IOException("Automatic repeat requests are disabled")
			chain.proceed(chain.request())
		}.build()

	@Volatile private var closed = false

	override fun translate(request: TranslationRequest, isCancelled: BooleanSupplier): String {
		checkCancellation(isCancelled)
		config.validate()
		val namespace = config.cacheNamespace()
		val cached = cache.get(request, namespace)
		checkCancellation(isCancelled)
		if(cached != null) return cached
		val httpRequest = try {
			ApiTranslationProtocol.request(config, request).newBuilder().tag(AtomicBoolean::class.java, AtomicBoolean()).build()
		} catch(error: JSONException) {
			throw IOException("Could not encode translation request", error)
		}
		val call = client.newCall(httpRequest)
		call.timeout().timeout(config.value("timeout").toLong(), TimeUnit.SECONDS)
		val result = ArrayBlockingQueue<Result<String>>(1)
		call.enqueue(object : Callback {
			override fun onFailure(call: Call, e: IOException) {
				// Server bodies, URLs and exception messages can contain credentials or source text.
				result.add(Result.failure(IOException("Translation network request failed or timed out")))
			}

			override fun onResponse(call: Call, response: Response) {
				try {
					val translated = response.use {
						if(!it.isSuccessful) throw IOException(httpError(it.code))
						val body = it.body ?: throw IOException("Translation service returned no response body")
						// Bound memory even when Content-Length is absent or incorrect.
						if(body.source().request(MAX_RESPONSE_BYTES + 1)) {
							throw IOException("Translation response exceeded 2 MiB")
						}
						ApiTranslationProtocol.response(config.format, body.string())
					}
					result.add(Result.success(translated))
				} catch(error: JSONException) {
					result.add(Result.failure(IOException("Translation service returned an invalid response")))
				} catch(error: IOException) {
					result.add(Result.failure(error))
				}
			}
		})
		var received = false
		val translated = try {
			awaitResult(result, isCancelled).also { received = true }
		} catch(error: InterruptedException) {
			call.cancel()
			Thread.currentThread().interrupt()
			throw InterruptedIOException("Translation cancelled")

		} finally {
			if(!received) call.cancel()
		}
		checkCancellation(isCancelled)
		cache.put(request, translated, namespace)
		return translated
	}

	private fun awaitResult(result: ArrayBlockingQueue<Result<String>>, cancelled: BooleanSupplier): String {
		while(!closed) {
			checkCancellation(cancelled)
			// Poll only the cancellation flag; the HTTP request is never repeated.
			val completed = result.poll(100, TimeUnit.MILLISECONDS)
			if(completed != null) return completed.getOrThrow()
		}
		throw InterruptedIOException("Translation cancelled")
	}

	private fun checkCancellation(cancelled: BooleanSupplier) {
		if(closed || cancelled.asBoolean || Thread.currentThread().isInterrupted) {
			throw InterruptedIOException("Translation cancelled")
		}
	}

	override fun close() {
		closed = true
		client.dispatcher.cancelAll()
		client.connectionPool.evictAll()
		client.dispatcher.executorService.shutdown()
	}

	companion object {
		private const val MAX_RESPONSE_BYTES = 2L * 1024 * 1024

		private fun httpError(code: Int): String = when(code) {
			401, 403 -> "Translation API authentication failed (HTTP $code); check credentials and permissions"
			429 -> "Translation API rate or quota limit reached (HTTP 429); retry manually later"
			else -> "Translation API request failed (HTTP $code); check the address, model and API settings"
		}
	}
}
