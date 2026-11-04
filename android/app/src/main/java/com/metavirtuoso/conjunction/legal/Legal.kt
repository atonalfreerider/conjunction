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

    /** Bundled versions of every document that can be read (missing assets are left out). */
    fun bundledVersions(context: Context): Map<LegalDoc, String> =
        LegalDoc.entries.mapNotNull { doc -> runCatching { doc to load(context, doc).version }.getOrNull() }.toMap()
}

/**
 * One accepted consent, in the shape IN_APP_CONSENT_COPY.md "Rules for the apps" requires:
 * key, version, UTC timestamp, app version, platform and the SHA-256 of the text displayed.
 * [docSha256] is the SHA-256 of the full bundled document the record is about (Terms, Waiver,
 * Community Guidelines, Privacy Policy), so the record shows which text was accepted (Waiver §19.2);
 * null for consents that are not about a document.
 */
data class ConsentRecord(
    val key: String,
    val version: String,
    val acceptedAtUtc: String,
    val appVersion: String,
    val platform: String,
    val textSha256: String,
    /** True for a withdrawal (Settings › Privacy & legal). */
    val withdrawn: Boolean = false,
    val docSha256: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("key", key)
        .put("version", version)
        .put("acceptedAt", acceptedAtUtc)
        .put("appVersion", appVersion)
        .put("platform", platform)
        .put("textSha256", textSha256)
        .put("withdrawn", withdrawn)
        .apply { docSha256?.let { put("docSha256", it) } }

    companion object {
        fun fromJson(o: JSONObject) = ConsentRecord(
            key = o.getString("key"),
            version = o.getString("version"),
            acceptedAtUtc = o.getString("acceptedAt"),
            appVersion = o.optString("appVersion"),
            platform = o.optString("platform", "android"),
            textSha256 = o.optString("textSha256"),
            withdrawn = o.optBoolean("withdrawn", false),
            docSha256 = o.optString("docSha256").takeIf { it.isNotEmpty() },
        )

        fun now(key: String, version: String, displayedText: String, withdrawn: Boolean = false, docSha256: String? = null) = ConsentRecord(
            key = key,
            version = version,
            acceptedAtUtc = Instant.now().toString(),
            appVersion = BuildConfig.VERSION_NAME,
            platform = "android",
            textSha256 = LegalTexts.sha256(displayedText),
            withdrawn = withdrawn,
            docSha256 = docSha256,
        )
    }
}

/** Consent keys from IN_APP_CONSENT_COPY.md. */
object ConsentKeys {
    const val AGE_18_CONFIRMED = "AGE_18_CONFIRMED"
    const val BLOOD_TYPE_MATCHING = "CONSENT_BLOOD_TYPE_MATCHING"
    const val ORIENTATION_DATA = "CONSENT_ORIENTATION_DATA"
    /** Kept readable in old ledgers; no longer written (copy 2.1: honesty is part of the Terms). */
    const val PROFILE_HONESTY = "PROFILE_HONESTY_CONFIRMED"
    /** Consent copy 2.0 (serverless Bluetooth build); replaces the server build's CONSENT_NEARBY_LOCATION. */
    const val NEARBY_BLUETOOTH = "CONSENT_NEARBY_BLUETOOTH"

    /** Version of the short in-app consent copy (legal/IN_APP_CONSENT_COPY.md). */
    const val COPY_VERSION = "2.1"

    /** `<DOC>_NOTICE_SEEN`: the minor-update notice for that document was shown (SPEC 5d.1.4). */
    fun noticeSeen(doc: LegalDoc): String = "${doc.key}_NOTICE_SEEN"
}

/** What the app must show about document versions after an update (SPEC 5d.1.4). */
sealed interface LegalUpdate {
    data object None : LegalUpdate
    /** A major version changed (or a document was never accepted): compact re-accept sheet (L4). */
    data class Reaccept(val docs: List<LegalDoc>) : LegalUpdate
    /** Only minor versions changed: one-time notice sheet. */
    data class Notice(val docs: List<LegalDoc>) : LegalUpdate
}

object LegalVersions {
    /** The four documents whose versions are checked. */
    val ACCEPTED_DOCS = listOf(LegalDoc.TERMS, LegalDoc.WAIVER, LegalDoc.PRIVACY, LegalDoc.COMMUNITY)

    /**
     * The Privacy Policy is read, never agreed to (GDPR Art. 7(2): consent is not bundled with the
     * Terms). Any change to it, major or minor, is a notice; it never enters re-acceptance.
     */
    val READ_ONLY_DOCS = setOf(LegalDoc.PRIVACY)

    fun major(version: String?): String? = version?.trim()?.substringBefore('.')?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }

    /** Last non-withdrawn version recorded for [key], or null. */
    fun accepted(records: List<ConsentRecord>, key: String): String? =
        records.lastOrNull { it.key == key }?.takeIf { !it.withdrawn }?.version

    /**
     * Compares the major version of each accepted document with the bundled one. A missing
     * COMMUNITY_GUIDELINES record is covered by an accepted TERMS 2.x (the Guidelines are part of
     * the Terms). [bundled] maps each document to the version in the app.
     */
    fun evaluate(records: List<ConsentRecord>, bundled: Map<LegalDoc, String>): LegalUpdate {
        val reaccept = ArrayList<LegalDoc>()
        val notice = ArrayList<LegalDoc>()
        for (doc in ACCEPTED_DOCS) {
            val have = bundled[doc] ?: continue
            if (doc in READ_ONLY_DOCS) {
                // Seen at onboarding (PRIVACY_NOTICE_SEEN) or, for records made before 2.1, "PRIVACY".
                val seen = accepted(records, ConsentKeys.noticeSeen(doc)) ?: accepted(records, doc.key)
                if (seen != have) notice += doc
                continue
            }
            var mine = accepted(records, doc.key)
            if (mine == null && doc == LegalDoc.COMMUNITY) {
                // Covered by Terms accepted at the bundled Terms' major version.
                val terms = accepted(records, LegalDoc.TERMS.key)
                if (terms != null && major(terms) == major(bundled[LegalDoc.TERMS])) mine = have
            }
            when {
                mine == null || major(mine) != major(have) -> reaccept += doc
                mine != have && accepted(records, ConsentKeys.noticeSeen(doc)) != have -> notice += doc
            }
        }
        return when {
            reaccept.isNotEmpty() -> LegalUpdate.Reaccept(reaccept)
            notice.isNotEmpty() -> LegalUpdate.Notice(notice)
            else -> LegalUpdate.None
        }
    }

    /** L1 already accepted (a 2.0 user migrated mid-onboarding): WAIVER and TERMS 2.x and AGE_18_CONFIRMED. */
    fun clickwrapAlreadyAccepted(records: List<ConsentRecord>, bundled: Map<LegalDoc, String>): Boolean =
        listOf(LegalDoc.WAIVER, LegalDoc.TERMS).all { doc ->
            val mine = accepted(records, doc.key)
            mine != null && major(mine) == major(bundled[doc])
        } && accepted(records, ConsentKeys.AGE_18_CONFIRMED) != null

    /** Both L2 records present and the latest of each not withdrawn. */
    fun nearbyConsentGiven(records: List<ConsentRecord>): Boolean =
        accepted(records, ConsentKeys.NEARBY_BLUETOOTH) != null && accepted(records, ConsentKeys.ORIENTATION_DATA) != null

    /** When the current L2 consent was given (the later of the two records), or null. */
    fun nearbyConsentAt(records: List<ConsentRecord>): String? {
        if (!nearbyConsentGiven(records)) return null
        return listOf(ConsentKeys.NEARBY_BLUETOOTH, ConsentKeys.ORIENTATION_DATA).mapNotNull { k -> records.lastOrNull { it.key == k }?.acceptedAtUtc }.maxOrNull()
    }
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
