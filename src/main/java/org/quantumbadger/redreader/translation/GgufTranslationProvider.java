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

import android.os.Build;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/** HY-MT2 prompt adapter backed by the embedded llama.cpp runtime. */
public final class GgufTranslationProvider implements TranslationProvider {

	private final TranslationModelStore store;
	private volatile boolean closed;
	private final IntSupplier threadCount;

	public GgufTranslationProvider(
			final TranslationModelStore store, final IntSupplier threadCount) {
		this.store = store;
		this.threadCount = threadCount;
	}

	@Override
	public String translate(
			final TranslationRequest request,
			final BooleanSupplier isCancelled) throws IOException {

		if(closed) {
			throw new IllegalStateException("Translation provider is closed");
		}
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
			throw new IOException("Local translation requires Android 6.0 or later");
		}
		if(isCancelled.getAsBoolean()) {
			throw new InterruptedIOException("Translation cancelled");
		}
		final String language;
		switch(request.getTargetLanguage()) {
			case "zh-Hans": language = "简体中文"; break;
			case "zh-Hant": language = "繁体中文"; break;
			case "en": language = "English"; break;
			case "ja": language = "Japanese"; break;
			case "ko": language = "Korean"; break;
			case "fr": language = "French"; break;
			case "de": language = "German"; break;
			case "es": language = "Spanish"; break;
			default: throw new IOException("Unsupported target language");
		}
		store.acquireForInference();
		try {
			return translateChunks(request, language, isCancelled, threadCount.getAsInt());
		} finally {
			store.releaseAfterInference();
		}
	}

	private String translateChunks(final TranslationRequest request,
			final String language, final BooleanSupplier isCancelled,
			final int threads) throws IOException {
		final StringBuilder translated = new StringBuilder();
		final String text = request.getText();
		for(int offset = 0; offset < text.length();) {
			if(isCancelled.getAsBoolean()) {
				throw new InterruptedIOException("Translation cancelled");
			}
			int end = text.offsetByCodePoints(offset,
					Math.min(1000, text.codePointCount(offset, text.length())));
			if(end < text.length()) {
				for(int split = end; split > offset + (end - offset) / 2; split--) {
					if(Character.isWhitespace(text.charAt(split - 1))) {
						end = split;
						break;
					}
				}
			}
			final String chunk = text.substring(offset, end);
			if(!chunk.trim().isEmpty()) {
				final int preceding = Math.min(200, text.codePointCount(0, offset));
				final String previous = text.substring(
						text.offsetByCodePoints(offset, -preceding), offset);
				final String context = previous.isEmpty() ? request.getContext()
						: request.getContext() + "\nPreceding source passage: " + previous;
				final String prompt = buildPrompt(language, context, chunk);
				if(translated.length() > 0) {
					translated.append("\n");
				}
				translated.append(generate(prompt, isCancelled, threads));
			}
			offset = end;
		}
		return translated.toString();
	}

	/** Official HY-MT2 default and context-aware (Structured Data 2) templates. */
	static String buildPrompt(final String language, final String context, final String text) {
		if("简体中文".equals(language) || "繁体中文".equals(language)) {
			if(context.trim().isEmpty()) {
				return "将以下文本翻译为" + language
						+ "，注意只需要输出翻译后的结果，不要额外解释：\n" + text;
			}
			return "〖背景信息〗\n" + context + "\n请结合背景信息将以下文本翻译为"
					+ language + "。\n〖待翻译文本〗\n" + text;
		}
		if(context.trim().isEmpty()) {
			return "Translate the following text into " + language
					+ ". Note that you should only output the translated result"
					+ " without any additional explanation:\n" + text;
		}
		return "[Background Information]\n" + context
				+ "\nPlease translate the following text into " + language
				+ ", taking the provided background information into consideration."
				+ "\n[Source Text]\n" + text;
	}

	private String generate(final String prompt, final BooleanSupplier isCancelled,
			final int threads) throws IOException {
		final byte[] path = store.requireModel().getAbsolutePath().getBytes(StandardCharsets.UTF_8);
		try {
			final byte[] result = LlamaNative.generate(
					path, prompt.getBytes(StandardCharsets.UTF_8),
					new LlamaNative.Cancellation(isCancelled), threads);
			final String translated = new String(result, StandardCharsets.UTF_8);
			if(translated.trim().isEmpty()) {
				throw new IOException("The model returned an empty translation");
			}
			return translated;
		} catch(final UnsatisfiedLinkError error) {
			throw new IOException("The local translation runtime is unavailable", error);
		}
	}

	@Override
	public void close() {
		closed = true;
	}
}
