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

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;

/** Persistent, disposable results, expiring 30 days after successful translation. */
public final class TranslationCache extends SQLiteOpenHelper {

	private static final long MAX_AGE_MS = TimeUnit.DAYS.toMillis(30);
	private static final int EDGE_BYTES = 8;
	private static TranslationCache instance;

	private TranslationCache(final Context context) {
		super(context, new File(context.getCacheDir(), "translations.db").getAbsolutePath(),
				null, 2);
	}

	public static synchronized TranslationCache getInstance(final Context context) {
		if(instance == null) {
			instance = new TranslationCache(context.getApplicationContext());
		}
		return instance;
	}

	@Override
	public void onCreate(final SQLiteDatabase database) {
		database.execSQL("CREATE TABLE translations (cache_key TEXT NOT NULL, "
				+ "language TEXT NOT NULL, context TEXT NOT NULL, source TEXT NOT NULL, "
				+ "result TEXT NOT NULL, created_at INTEGER NOT NULL, namespace TEXT NOT NULL, "
				+ "PRIMARY KEY (cache_key, language, context, namespace))");
		database.execSQL("CREATE INDEX translations_created_at ON translations (created_at)");
	}

	@Override
	public void onUpgrade(final SQLiteDatabase database, final int oldVersion,
			final int newVersion) {
		if(oldVersion != 1 || newVersion != 2) {
			throw new IllegalStateException("Unsupported translation cache schema upgrade");
		}
		// Disposable cache: v1 results have no engine identity and must not cross providers.
		database.execSQL("DROP TABLE translations");
		onCreate(database);
	}

	@Override
	public void onOpen(final SQLiteDatabase database) {
		super.onOpen(database);
		prune(database);
	}

	String get(final TranslationRequest request) {
		return get(request, "local");
	}

	String get(final TranslationRequest request, final String namespace) {
		try(Cursor cursor = getReadableDatabase().query("translations", new String[]{"result"},
				"cache_key = ? AND language = ? AND context = ? AND source = ? "
						+ "AND namespace = ? AND created_at > ?",
				new String[]{key(request.getText()), request.getTargetLanguage(),
						request.getContext(), request.getText(), namespace,
						Long.toString(System.currentTimeMillis() - MAX_AGE_MS)},
				null, null, null)) {
			return cursor.moveToFirst() ? cursor.getString(0) : null;
		}
	}

	void put(final TranslationRequest request, final String result) {
		put(request, result, "local");
	}

	void put(final TranslationRequest request, final String result, final String namespace) {
		if(result == null || result.trim().isEmpty()) {
			throw new IllegalArgumentException("Cannot cache an empty translation");
		}
		final ContentValues values = new ContentValues();
		values.put("cache_key", key(request.getText()));
		values.put("namespace", namespace);
		values.put("language", request.getTargetLanguage());
		values.put("context", request.getContext());
		values.put("source", request.getText());
		values.put("result", result);
		values.put("created_at", System.currentTimeMillis());
		getWritableDatabase().replaceOrThrow("translations", null, values);
	}

	void clear() {
		getWritableDatabase().delete("translations", null, null);
	}

	public void prune() {
		prune(getWritableDatabase());
	}

	private static void prune(final SQLiteDatabase database) {
		database.delete("translations", "created_at <= ?",
				new String[]{Long.toString(System.currentTimeMillis() - MAX_AGE_MS)});
	}

	/** Byte fragments are hexadecimal, so even split UTF-8 characters stay unambiguous. */
	static String key(final String text) {
		final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
		final byte[] digest;
		try {
			digest = MessageDigest.getInstance("MD5").digest(bytes);
		} catch(final NoSuchAlgorithmException error) {
			throw new IllegalStateException("MD5 is unavailable", error);
		}
		return hex(digest, 0, digest.length) + ":"
				+ hex(bytes, 0, Math.min(EDGE_BYTES, bytes.length)) + ":"
				+ hex(bytes, Math.max(0, bytes.length - EDGE_BYTES), bytes.length);
	}

	private static String hex(final byte[] bytes, final int start, final int end) {
		final StringBuilder result = new StringBuilder((end - start) * 2);
		for(int i = start; i < end; i++) {
			result.append(Character.forDigit((bytes[i] & 0xff) >>> 4, 16));
			result.append(Character.forDigit(bytes[i] & 0x0f, 16));
		}
		return result.toString();
	}
}
