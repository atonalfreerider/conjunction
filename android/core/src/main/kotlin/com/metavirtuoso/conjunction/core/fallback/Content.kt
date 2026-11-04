package com.metavirtuoso.conjunction.core.fallback

import com.metavirtuoso.conjunction.core.json.Json

/**
 * The bundled reading text (`fallback/content_en.json`, shared byte-for-byte with the iOS app):
 * sign, planet, house, aspect, animal, element, day-master, blood-type, palm and compatibility
 * fragments used by [FallbackReadings] and [com.metavirtuoso.conjunction.core.blood.BloodTypeReading].
 */
object Content {
    val root: Map<String, Any?> by lazy {
        val stream = Content::class.java.getResourceAsStream("content_en.json")
            ?: error("Missing bundled resource content_en.json")
        Json.obj(Json.parse(stream.bufferedReader(Charsets.UTF_8).use { it.readText() }))
    }

    fun obj(v: Any?): Map<String, Any?> = Json.obj(v)
    fun strings(v: Any?): List<String> = Json.arr(v).map { it as String }
    fun arrOfObj(v: Any?): List<Map<String, Any?>> = Json.arr(v).map(Json::obj)

    /** root[section][key] as a map. */
    fun entry(section: String, key: String): Map<String, Any?> =
        obj(obj(root[section])[key] ?: error("content_en.json: missing $section.$key"))

    fun text(section: String, key: String, field: String): String =
        entry(section, key)[field] as? String ?: error("content_en.json: missing $section.$key.$field")

    fun list(section: String, key: String): List<String> = strings(obj(root[section])[key])
}
