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

import androidx.appcompat.app.AppCompatActivity;
import org.quantumbadger.redreader.reddit.RedditCommentListItem;
import org.quantumbadger.redreader.reddit.kthings.RedditComment;
import org.quantumbadger.redreader.reddit.prepared.RedditPreparedPost;

/** Source text and bounded discussion context, separate from model-specific prompting. */
public final class RedditTranslation {
	private RedditTranslation() { }

	public static String titleKey(final RedditPreparedPost post) {
		return post.src.getIdAndType().toString() + ":title";
	}

	public static String bodyKey(final RedditPreparedPost post) {
		return post.src.getIdAndType().toString() + ":body";
	}

	public static String commentKey(final RedditComment comment) {
		return comment.getIdAndType().toString();
	}

	public static void post(final AppCompatActivity activity, final RedditPreparedPost post) {
		final TranslationViewModel model = TranslationViewModel.get(activity);
		final String title = post.src.getTitle();
		final String body = post.src.getRawSelfTextMarkdown();
		if(title != null && !title.trim().isEmpty()) {
			model.translate(titleKey(post), title, "Post body: " + excerpt(body, 1200));
		}
		if(body != null && !body.trim().isEmpty()) {
			model.translate(bodyKey(post), body, "Post title: " + excerpt(title, 400));
		}
	}

	public static String context(final RedditPreparedPost post, final RedditCommentListItem item) {
		final StringBuilder context = new StringBuilder();
		if(post != null) {
			context.append("Post title: ").append(excerpt(post.src.getTitle(), 300));
			context.append("\nPost body: ").append(excerpt(post.src.getRawSelfTextMarkdown(), 600));
		}
		RedditCommentListItem parent = item == null ? null : item.getParent();
		for(int depth = 0; parent != null && depth < 3; depth++, parent = parent.getParent()) {
			if(parent.isComment()) {
				final RedditComment raw = parent.asComment().getParsedComment().getRawComment();
				context.append("\nAncestor (nearest first), ").append(raw.getAuthor().getDecoded())
						.append(": ").append(excerpt(
								raw.getBody() == null ? null : raw.getBody().getDecoded(), 400));
			}
		}
		return context.toString();
	}

	private static String excerpt(final String text, final int limit) {
		if(text == null) {
			return "";
		}
		final int count = text.codePointCount(0, text.length());
		return count <= limit ? text
				: text.substring(0, text.offsetByCodePoints(0, limit)) + " […]";
	}
}
