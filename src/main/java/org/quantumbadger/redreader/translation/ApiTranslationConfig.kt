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

import android.content.Context
import android.content.SharedPreferences
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.quantumbadger.redreader.R
import java.io.IOException

/** Each protocol retains its own settings. Credentials never enter the normal prefs backup. */
class ApiTranslationConfig(val format: Format, private val values: Map<String, String>) {
	enum class Format(val endpoint: String, val fields: List<String>) {
		LOCAL("", emptyList()),
		OPENAI_CHAT("https://api.openai.com/v1", listOf("model", "tokens", "token_field", "organization", "project")),
		OPENAI_RESPONSES("https://api.openai.com/v1", listOf("model", "tokens", "organization", "project")),
		ANTHROPIC("https://api.anthropic.com/v1", listOf("model", "tokens", "version")),
		GEMINI("https://generativelanguage.googleapis.com/v1beta", listOf("model", "tokens")),
		DEEPL("https://api-free.deepl.com/v2", listOf("formality")),
		GOOGLE_BASIC("https://translation.googleapis.com/language/translate/v2", emptyList()),
		GOOGLE_ADVANCED("https://translation.googleapis.com/v3", listOf("project", "location", "model")),
		AZURE("https://api.cognitive.microsofttranslator.com", listOf("region", "category"));

		fun isLlm() = this in listOf(OPENAI_CHAT, OPENAI_RESPONSES, ANTHROPIC, GEMINI)
		fun allFields() = if(this == LOCAL) emptyList() else listOf("endpoint", "key", "timeout") + fields
	}

	fun value(field: String): String = values[field] ?: defaultValue(format, field)

	@Throws(IOException::class)
	fun url(): HttpUrl {
		val url = value("endpoint").toHttpUrlOrNull()
		if(url == null || !url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty()
			|| url.query != null || url.fragment != null) {
			throw IOException("API address must be an HTTPS base URL without credentials, query or fragment")
		}
		return url
	}

	@Throws(IOException::class)
	fun validate() {
		if(format == Format.LOCAL) return
		url()
		for(field in format.allFields()) {
			validateField(field, value(field))
		}
		if(format != Format.OPENAI_CHAT && format != Format.OPENAI_RESPONSES && value("key").isEmpty()) {
			throw IOException("API credential is required in Settings → Translation")
		}
		if(format.isLlm() && value("model").isEmpty()) throw IOException("API model is required")
		if(format == Format.GOOGLE_ADVANCED && value("project").isEmpty()) {
			throw IOException("Google Cloud project is required")
		}
	}

	@Throws(IOException::class)
	fun validateField(field: String, value: String) {
		if(value.any { it < ' ' || it == '\u007f' }) throw IOException("Use a single-line value")
		if(field in listOf("key", "organization", "project", "region", "version") && value.any { it > '~' }) {
			throw IOException("HTTP credentials and header values must use ASCII characters")
		}
		when(field) {
			"endpoint" -> ApiTranslationConfig(format, values + (field to value)).url()
			"timeout", "tokens" -> if(value.toIntOrNull()?.let { it > 0 } != true) {
				throw IOException("Enter a positive integer")
			}
			"token_field" -> if(value !in listOf("max_tokens", "max_completion_tokens")) {
				throw IOException("Use max_tokens or max_completion_tokens")
			}
			"formality" -> if(value !in listOf("default", "more", "less", "prefer_more", "prefer_less")) {
				throw IOException("Use default, more, less, prefer_more or prefer_less")
			}
			"location", "version" -> if(value.isEmpty()) throw IOException("This field is required")
			"model" -> if(format.isLlm() && value.isEmpty()
				|| format == Format.GEMINI && value.contains('/')) {
				throw IOException("Enter a model ID; Gemini IDs must omit the models/ prefix")
			}
		}
	}

	/** No credential, credential hash or timeout is written to the translation database. */
	fun cacheNamespace(): String = org.json.JSONArray().apply {
		put("api-prompt-v1")
		put(format.name)
		format.allFields().filter { it != "key" && it != "timeout" }.forEach {
			put(it)
			put(if(it == "endpoint") url().toString().trimEnd('/') else value(it))
		}
	}.toString()

	companion object {
		const val PREFERENCES = "translation_api"
		const val FORMAT = "format"

		@JvmStatic
		fun preferences(context: Context): SharedPreferences =
			context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

		@JvmStatic
		fun read(context: Context): ApiTranslationConfig {
			val prefs = preferences(context)
			val format = Format.valueOf(prefs.getString(FORMAT, Format.LOCAL.name)!!)
			return ApiTranslationConfig(format, format.allFields().associateWith {
				prefs.getString("${format.name}.$it", defaultValue(format, it))!!
			})
		}

		fun defaultValue(format: Format, field: String): String = when(field) {
			"endpoint" -> format.endpoint
			"timeout" -> "120"
			"tokens" -> "4096"
			"token_field" -> "max_tokens"
			"version" -> "2023-06-01"
			"location" -> "global"
			"formality" -> "default"
			else -> ""
		}

		fun fieldTitle(field: String): Int = when(field) {
			"endpoint" -> R.string.translation_api_endpoint
			"key" -> R.string.translation_api_key
			"timeout" -> R.string.translation_api_timeout
			"model" -> R.string.translation_api_model
			"tokens" -> R.string.translation_api_tokens
			"token_field" -> R.string.translation_api_token_field
			"organization" -> R.string.translation_api_organization
			"project" -> R.string.translation_api_project
			"version" -> R.string.translation_api_version
			"location" -> R.string.translation_api_location
			"region" -> R.string.translation_api_region
			"category" -> R.string.translation_api_category
			"formality" -> R.string.translation_api_formality
			else -> error("Unknown API setting")
		}
	}
}
