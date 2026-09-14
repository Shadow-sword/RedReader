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
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.quantumbadger.redreader.R;

import androidx.preference.PreferenceManager;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.io.IOException;

/** Process-wide inference limit shared by individual and thread translation. */
public final class LocalTranslation {

	private static LocalTranslation instance;

	private final TranslationModelStore models;
	private TranslationService service;
	private final Application context;
	private long configurationRevision;
	private final MutableLiveData<Long> configurationChanges = new MutableLiveData<>();
	// Keep a strong reference: SharedPreferences stores listeners weakly.
	@SuppressWarnings("PMD.SingularField")
	private final SharedPreferences.OnSharedPreferenceChangeListener apiPreferenceListener;
	// SharedPreferences keeps only a weak reference to registered listeners.
	@SuppressWarnings("PMD.SingularField")
	private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener;

	private LocalTranslation(final Application context) {
		this.context = context;
		models = new TranslationModelStore(context);
		final SharedPreferences preferences =
				PreferenceManager.getDefaultSharedPreferences(context);
		service = createService();
		apiPreferenceListener = (prefs, key) -> configurationChanged();
		ApiTranslationConfig.preferences(context)
				.registerOnSharedPreferenceChangeListener(apiPreferenceListener);
		preferenceListener = (prefs, key) -> {
			if("translation_concurrency".equals(key)) {
				service.setConcurrency(getConcurrency(context));
			} else if("translation_target_language".equals(key)
					|| "pref_network_tor".equals(key)) {
				configurationChanged();
			}
		};
		preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
	}

	private TranslationService createService() {
		final ApiTranslationConfig config = ApiTranslationConfig.read(context);
		final TranslationProvider provider = config.getFormat() == ApiTranslationConfig.Format.LOCAL
				? new GgufTranslationProvider(models,
						() -> Math.max(1, 4 / getConcurrency(context)))
				: new ApiTranslationProvider(config, TranslationCache.getInstance(context),
						PreferenceManager.getDefaultSharedPreferences(context)
								.getBoolean("pref_network_tor", false));
		return new TranslationService(provider, new Handler(Looper.getMainLooper())::post,
				getConcurrency(context));
	}

	private void configurationChanged() {
		service.close();
		service = createService();
		configurationRevision++;
		configurationChanges.setValue(configurationRevision);
	}

	public LiveData<Long> getConfigurationChanges() {
		return configurationChanges;
	}

	public String getRevision() {
		return configurationRevision + ":" + models.getRevision();
	}

	public String getConfigurationError() {
		final ApiTranslationConfig config = ApiTranslationConfig.read(context);
		if(config.getFormat() == ApiTranslationConfig.Format.LOCAL) {
			return models.getModelSize() == 0
					? context.getString(R.string.translation_model_missing)
					: null;
		}
		try {
			config.validate();
			return null;
		} catch(final IOException error) {
			return error.getMessage();
		}
	}

	public static synchronized LocalTranslation getInstance(final Context context) {
		if(instance == null) {
			instance = new LocalTranslation((Application)context.getApplicationContext());
		}
		return instance;
	}

	public TranslationModelStore getModels() {
		return models;
	}

	public TranslationService getService() {
		return service;
	}

	public static int getConcurrency(final Context context) {
		final String value = PreferenceManager.getDefaultSharedPreferences(context)
				.getString("translation_concurrency", "1");
		final int concurrency = Integer.parseInt(value);
		if(concurrency < 1 || concurrency > 4) {
			throw new IllegalArgumentException("Translation concurrency must be between 1 and 4");
		}
		return concurrency;
	}

	public static String getTargetLanguage(final Context context) {
		return PreferenceManager.getDefaultSharedPreferences(context)
				.getString("translation_target_language", "zh-Hans");
	}
}
