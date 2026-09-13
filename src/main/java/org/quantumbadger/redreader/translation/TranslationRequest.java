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

import java.util.Objects;

/** Model-independent input; providers own prompt formatting and language mapping. */
public final class TranslationRequest {

	private final String text;
	private final String targetLanguage;
	private final String context;

	/**
	 * @param text Original text, including any Markdown, without a model prompt.
	 * @param context Supporting discussion context, not text to translate.
	 * @param targetLanguage BCP 47 language tag, for example zh-Hans or en.
	 */
	public TranslationRequest(
			final String text, final String targetLanguage, final String context) {

		this.text = Objects.requireNonNull(text);
		this.context = Objects.requireNonNull(context);
		this.targetLanguage = Objects.requireNonNull(targetLanguage);

		if(text.trim().isEmpty()) {
			throw new IllegalArgumentException("Translation text must not be empty");
		}
		if(targetLanguage.trim().isEmpty()) {
			throw new IllegalArgumentException("Target language must not be empty");
		}
	}

	public String getContext() {
		return context;
	}

	public String getText() {
		return text;
	}

	public String getTargetLanguage() {
		return targetLanguage;
	}
}
