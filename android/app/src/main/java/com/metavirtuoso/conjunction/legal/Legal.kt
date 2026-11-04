package com.metavirtuoso.conjunction.legal

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.metavirtuoso.conjunction.BuildConfig
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/** The full legal documents bundled from legal/ (see :app:copyLegalDocs). */
enum class LegalDoc(val key: String, val asset: String, val title: String) {
    WAIVER("WAIVER", "legal/WAIVER.md", "Release & Waiver of Liability"),
    TERMS("TERMS", "legal/TERMS.md", "Terms of Service"),
    PRIVACY("PRIVACY", "legal/PRIVACY.md", "Privacy Policy"),
    COMMUNITY("COMMUNITY_GUIDELINES", "legal/COMMUNITY_GUIDELINES.md", "Community Guidelines"),
    SAFETY("SAFETY_TIPS", "legal/SAFETY_TIPS.md", "Safety Tips"),
}

/** A loaded document: its markdown, the version from its header table and the text's SHA-256. */
data class LegalText(val doc: LegalDoc, val markdown: String, val version: String, val sha256: String)

object LegalTexts {
    private val VERSION = Regex("""^\|\s*Version\s*\|\s*([^|(]+?)\s*(\(|\|)""", RegexOption.MULTILINE)

    fun parseVersion(markdown: String): String = VERSION.find(markdown)?.groupValues?.get(1)?.trim() ?: "unversioned"

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun load(context: Context, doc: LegalDoc): LegalText {
        val markdown = context.assets.open(doc.asset).bufferedReader(Charsets.UTF_8).use { it.readText() }
        return LegalText(doc, markdown, parseVersion(markdown), sha256(markdown))
    }
}

/**
 * One accepted consent, in the shape IN_APP_CONSENT_COPY.md "Rules for the apps" requires:
 * key, version, UTC timestamp, app version, platform and the SHA-256 of the text displayed.
 */
data class ConsentRecord(
    val key: String,
    val version: String,
    val acceptedAtUtc: String,
    val appVersion: String,
    val platform: String,
    val textSha256: String,
    /** True for a withdrawal (Settings → Privacy); the backend then deletes the covered data. */
    val withdrawn: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("key", key)
        .put("version", version)
        .put("acceptedAt", acceptedAtUtc)
        .put("appVersion", appVersion)
        .put("platform", platform)
        .put("textSha256", textSha256)
        .put("withdrawn", withdrawn)

    companion object {
        fun fromJson(o: JSONObject) = ConsentRecord(
            key = o.getString("key"),
            version = o.getString("version"),
            acceptedAtUtc = o.getString("acceptedAt"),
            appVersion = o.optString("appVersion"),
            platform = o.optString("platform", "android"),
            textSha256 = o.optString("textSha256"),
            withdrawn = o.optBoolean("withdrawn", false),
        )

        fun now(key: String, version: String, displayedText: String, withdrawn: Boolean = false) = ConsentRecord(
            key = key,
            version = version,
            acceptedAtUtc = Instant.now().toString(),
            appVersion = BuildConfig.VERSION_NAME,
            platform = "android",
            textSha256 = LegalTexts.sha256(displayedText),
            withdrawn = withdrawn,
        )
    }
}

/** Consent keys from IN_APP_CONSENT_COPY.md. */
object ConsentKeys {
    const val AGE_18_CONFIRMED = "AGE_18_CONFIRMED"
    const val BLOOD_TYPE_MATCHING = "CONSENT_BLOOD_TYPE_MATCHING"
    const val ORIENTATION_DATA = "CONSENT_ORIENTATION_DATA"
    const val PROFILE_HONESTY = "PROFILE_HONESTY_CONFIRMED"
    /** Consent copy 2.0 (serverless Bluetooth build); replaces the server build's CONSENT_NEARBY_LOCATION. */
    const val NEARBY_BLUETOOTH = "CONSENT_NEARBY_BLUETOOTH"

    /** Version of the short in-app consent copy (legal/IN_APP_CONSENT_COPY.md). */
    const val COPY_VERSION = "2.0"
}

private val Context.consentDataStore by preferencesDataStore(name = "consents")

/**
 * Append-only local ledger of accepted consents, kept only on this phone (there is no server;
 * Settings → Legal → My acceptance records shows it). Withdrawals are records with
 * [ConsentRecord.withdrawn] set.
 */
class ConsentLedger(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.consentDataStore)

    private val recordsKey = stringPreferencesKey("records")

    val records: Flow<List<ConsentRecord>> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> decode(prefs[recordsKey]) }

    suspend fun all(): List<ConsentRecord> = records.first()

    suspend fun record(record: ConsentRecord) {
        store.edit { prefs ->
            val list = decode(prefs[recordsKey]) + record
            prefs[recordsKey] = JSONArray(list.map { it.toJson() }).toString()
        }
    }

    /** The most recent version accepted for [key], or null (withdrawn or never). */
    suspend fun acceptedVersion(key: String): String? {
        val list = all()
        val last = list.lastOrNull { it.key == key } ?: return null
        return if (last.withdrawn) null else last.version
    }

    suspend fun clear() {
        store.edit { it.clear() }
    }

    private fun decode(raw: String?): List<ConsentRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map { ConsentRecord.fromJson(array.getJSONObject(it)) }
    }
}
