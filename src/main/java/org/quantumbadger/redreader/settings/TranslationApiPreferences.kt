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


package org.quantumbadger.redreader.settings

import android.text.InputType
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.edit
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import org.quantumbadger.redreader.R
import org.quantumbadger.redreader.translation.ApiTranslationConfig
import org.quantumbadger.redreader.translation.ApiTranslationConfig.Format
import java.io.IOException

/** Dynamic fields keep unrelated protocols and their credentials separate. */
class TranslationApiPreferences(private val fragment: PreferenceFragmentCompat) {
	fun bind() {
		val context = fragment.requireContext()
		val config = ApiTranslationConfig.read(context)
		val prefs = ApiTranslationConfig.preferences(context)
		val selector = fragment.findPreference<ListPreference>("translation_format")!!
		selector.entries = context.resources.getTextArray(R.array.translation_api_formats)
		selector.entryValues = Format.entries.map { it.name }.toTypedArray()
		selector.value = config.format.name
		selector.setOnPreferenceChangeListener { _, value ->
			prefs.edit { putString(ApiTranslationConfig.FORMAT, value as String) }
			bind()
			true
		}
		val local = config.format == Format.LOCAL
		listOf("translation_status", "translation_import", "translation_remove").forEach {
			fragment.findPreference<Preference>(it)!!.isVisible = local
		}
		val category = fragment.findPreference<PreferenceCategory>("translation_api_settings")!!
		category.removeAll()
		category.isVisible = !local
		if(local) return
		category.addPreference(Preference(context).apply {
			setTitle(R.string.translation_api_privacy_title)
			setSummary(if(config.format.isLlm() || config.format == Format.DEEPL) {
				R.string.translation_api_privacy_context
			} else R.string.translation_api_privacy_text)
			isSelectable = false
		})
		for(field in config.format.allFields()) {
			category.addPreference(EditTextPreference(context).apply {
				key = "translation_api_${config.format.name}_$field"
				isPersistent = false
				setTitle(if(field == "key" && config.format == Format.GOOGLE_ADVANCED) {
					R.string.translation_api_access_token
				} else ApiTranslationConfig.fieldTitle(field))
				text = config.value(field)
				summaryProvider = Preference.SummaryProvider<EditTextPreference> { preference ->
					if(field == "key") context.getString(if(preference.text.isNullOrEmpty()) {
						R.string.translation_api_not_set
					} else R.string.translation_api_secret_saved)
					else preference.text?.takeIf { it.isNotEmpty() } ?: context.getString(R.string.translation_api_not_set)
				}
				dialogMessage = context.getString(when(field) {
					"endpoint" -> if(config.format == Format.GOOGLE_BASIC) R.string.translation_api_endpoint_exact
						else R.string.translation_api_endpoint_help
					"key" -> if(config.format == Format.GOOGLE_ADVANCED) R.string.translation_api_access_token_help
						else R.string.translation_api_key_help
					"model" -> if(config.format == Format.GOOGLE_ADVANCED) R.string.translation_api_google_model_help
						else R.string.translation_api_model_help
					"token_field" -> R.string.translation_api_token_field_help
					"region" -> R.string.translation_api_region_help
					"formality" -> R.string.translation_api_formality_help
					else -> R.string.translation_api_field_help
				})
				setOnBindEditTextListener { edit ->
					edit.inputType = when(field) {
						"key" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
						"timeout", "tokens" -> InputType.TYPE_CLASS_NUMBER
						else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
					}
					edit.setSingleLine(true)
					if(field == "key") edit.post {
						// The editor window must not expose credentials in screenshots or recents.
						(this@TranslationApiPreferences.fragment.parentFragmentManager.findFragmentByTag("androidx.preference.PreferenceFragment.DIALOG")
							as? androidx.fragment.app.DialogFragment)?.dialog?.window
							?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
					}
				}
				setOnPreferenceChangeListener { _, value ->
					val entered = (value as String).trim()
					try {
						config.validateField(field, entered)
						prefs.edit { putString("${config.format.name}.$field", entered) }
						text = entered
						false // Already assigned normalized text; do not persist in default preferences.
					} catch(error: IOException) {
						Toast.makeText(context, error.message, Toast.LENGTH_LONG).show()
						false
					}
				}
			})
		}
	}
}
