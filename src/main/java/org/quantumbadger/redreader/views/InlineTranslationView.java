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


package org.quantumbadger.redreader.views;

import android.content.Context;
import androidx.lifecycle.Observer;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.textview.MaterialTextView;
import org.quantumbadger.redreader.R;
import org.quantumbadger.redreader.common.General;
import org.quantumbadger.redreader.translation.TranslationViewModel;

/** Observes only while attached, so recycled rows never receive another item's result. */
public final class InlineTranslationView extends MaterialTextView {
	private TranslationViewModel.Entry entry;
	private String source;
	private final Observer<TranslationViewModel.Entry> observer = value -> render();

	public InlineTranslationView(final Context context) {
		super(context);
		setVisibility(GONE);
		setPadding(0, General.dpToPixels(context, 6), 0, 0);
		setTextIsSelectable(true);
	}

	public void bind(final TranslationViewModel.Entry value, final String original) {
		if(entry != null) {
			entry.changes.removeObserver(observer);
		}
		entry = value;
		source = original;
		if(isAttachedToWindow()) {
			entry.changes.observeForever(observer);
		}
		render();
	}

	private void render() {
		if(entry == null || !java.util.Objects.equals(source, entry.text)
				|| (!entry.busy && entry.result == null && entry.error == null)) {
			setVisibility(GONE);
			return;
		}
		setVisibility(VISIBLE);
		setOnClickListener(entry.busy ? view ->
				TranslationViewModel.get((AppCompatActivity)getContext()).cancel(entry) : null);
		if(entry.busy) {
			setText(R.string.translation_running);
		} else if(entry.error != null) {
			setText(getContext().getString(R.string.translation_failed, entry.error));
		} else {
			setText(getContext().getString(
					R.string.translation_inline, entry.language, entry.result));
		}
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		if(entry != null) {
			entry.changes.observeForever(observer);
		}
		render();
	}

	@Override
	protected void onDetachedFromWindow() {
		if(entry != null) {
			entry.changes.removeObserver(observer);
		}
		super.onDetachedFromWindow();
	}
}
