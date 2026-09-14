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

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.appcompat.app.AppCompatActivity;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;

/** Activity-owned translations survive view recycling and configuration changes. */
public final class TranslationViewModel extends AndroidViewModel {
	public static final class Entry {
		public final MutableLiveData<Entry> changes = new MutableLiveData<>();
		public String text;
		public String language;
		public String context;
		public String result;
		public String error;
		public boolean busy;
		private Future<String> task;
		private String modelRevision;
	}

	private final Map<String, Entry> entries = new HashMap<>();
	private final Observer<Long> configurationObserver = revision -> {
		for(final Entry entry : entries.values()) {
			cancel(entry);
			entry.result = null;
			entry.error = null;
			entry.changes.setValue(entry);
		}
	};

	public TranslationViewModel(@NonNull final Application app) {
		super(app);
		LocalTranslation.getInstance(app).getConfigurationChanges()
				.observeForever(configurationObserver);
	}

	public static TranslationViewModel get(final AppCompatActivity activity) {
		return new ViewModelProvider(activity).get(TranslationViewModel.class);
	}

	public Entry entry(final String key) {
		Entry entry = entries.get(key);
		if(entry == null) {
			entry = new Entry();
			entries.put(key, entry);
		}
		return entry;
	}

	public Entry translate(final String key, final String text, final String context) {
		final Entry entry = entry(key);
		final String language = LocalTranslation.getTargetLanguage(getApplication());
		final String revision = LocalTranslation.getInstance(getApplication()).getRevision();
		if(revision.equals(entry.modelRevision) && text.equals(entry.text)
				&& language.equals(entry.language)
				&& context.equals(entry.context) && (entry.busy || entry.result != null)) {
			return entry;
		}
		cancel(entry);
		entry.text = text;
		entry.modelRevision = revision;
		entry.language = language;
		entry.context = context;
		entry.result = null;
		entry.error = null;
		entry.busy = true;
		entry.changes.setValue(entry);
		entry.task = LocalTranslation.getInstance(getApplication()).getService().translate(
				new TranslationRequest(text, language, context), new TranslationService.Callback() {
			@Override
			public void onSuccess(final String result) {
				entry.result = result;
				entry.busy = false;
				entry.changes.setValue(entry);
			}

			@Override
			public void onFailure(final Throwable error) {
				entry.error = error.toString();
				entry.busy = false;
				entry.changes.setValue(entry);
			}
		});
		return entry;
	}

	public void cancel(final Entry entry) {
		if(entry.task != null) {
			entry.task.cancel(true);
		}
		if(entry.busy) {
			entry.busy = false;
			entry.error = getApplication().getString(
					org.quantumbadger.redreader.R.string.translation_cancelled);
			entry.changes.setValue(entry);
		}
	}

	@Override
	protected void onCleared() {
		LocalTranslation.getInstance(getApplication()).getConfigurationChanges()
				.removeObserver(configurationObserver);
		for(final Entry entry : entries.values()) {
			cancel(entry);
		}
	}
}
