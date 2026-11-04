package com.metavirtuoso.conjunction.core

import com.metavirtuoso.conjunction.core.json.Json
import java.io.File

/** Locates the repo-level testdata/ directory (passed by Gradle as -Dconjunction.testdata). */
object TestData {
    val dir: File by lazy {
        System.getProperty("conjunction.testdata")?.let(::File)?.takeIf { it.isDirectory }
            ?: generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, "testdata") }.firstOrNull { File(it, "golden_charts.json").isFile }
            ?: error("testdata directory not found; run through Gradle")
    }

    fun json(name: String): Map<String, Any?> = Json.obj(Json.parse(File(dir, name).readText()))
}
