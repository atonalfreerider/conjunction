package com.metavirtuoso.conjunction.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/** Who wrote a reading: the phone's model, or the bundled fallback templates. */
enum class ReadingSource { ON_DEVICE_AI, FALLBACK }

data class CachedReading(val text: String, val source: ReadingSource, val createdAt: Instant)

private val Context.readingDataStore by preferencesDataStore(name = "readings")

/**
 * Local cache of generated readings, one entry per (kind, day) — e.g. "daily:2026-11-03" — so a
 * day's reading is written once and stays the same all day. Natal and Eastern readings are keyed
 * by a fingerprint of the birth data so editing birth details regenerates them. Not backed up.
 */
class ReadingCache(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.readingDataStore)

    suspend fun get(key: String): CachedReading? {
        val prefs = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.first()
        val raw = prefs[stringPreferencesKey(key)] ?: return null
        return runCatching {
            val o = JSONObject(raw)
            CachedReading(o.getString("text"), ReadingSource.valueOf(o.getString("source")), Instant.parse(o.getString("createdAt")))
        }.getOrNull()
    }

    suspend fun put(key: String, reading: CachedReading) {
        store.edit { prefs ->
            prefs[stringPreferencesKey(key)] = JSONObject()
                .put("text", reading.text)
                .put("source", reading.source.name)
                .put("createdAt", reading.createdAt.toString())
                .toString()
        }
    }

    suspend fun remove(key: String) {
        store.edit { it.remove(stringPreferencesKey(key)) }
    }

    /** Drops daily entries older than [keepDays] (keys "daily:YYYY-MM-DD"). */
    suspend fun prune(today: LocalDate, keepDays: Long = 14) {
        store.edit { prefs ->
            prefs.asMap().keys.filter { key ->
                val date = key.name.substringAfter("daily:", "").let { runCatching { LocalDate.parse(it.substringBefore('#')) }.getOrNull() }
                date != null && date.isBefore(today.minusDays(keepDays))
            }.forEach { prefs.remove(it) }
        }
    }

    suspend fun clear() {
        store.edit { it.clear() }
    }

    companion object {
        fun dailyKey(date: LocalDate, fingerprint: String) = "daily:$date#$fingerprint"
        fun natalKey(section: String, fingerprint: String) = "natal:$section#$fingerprint"
        fun easternKey(fingerprint: String) = "eastern#$fingerprint"
    }
}
