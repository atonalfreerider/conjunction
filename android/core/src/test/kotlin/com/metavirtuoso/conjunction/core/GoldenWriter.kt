package com.metavirtuoso.conjunction.core

import com.metavirtuoso.conjunction.core.compat.Compatibility
import com.metavirtuoso.conjunction.core.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Writes the Kotlin-reference golden files. Only runs via `./gradlew :core:writeGoldens`
 * (-Dconjunction.writeGoldens=true); a normal test run skips it.
 */
class GoldenWriter {
    @Test
    fun write() {
        assumeTrue(System.getProperty("conjunction.writeGoldens") == "true")
        val profiles = CompatFixtures.allProfiles()
        val cases = CompatFixtures.pairs.mapIndexed { i, (x, y, note) ->
            val a = profiles.getValue(x)
            val b = profiles.getValue(y)
            linkedMapOf(
                "id" to "pair-${(i + 1).toString().padStart(2, '0')}-$x-$y",
                "note" to note,
                "a" to CompatWire.profileToMap(a),
                "b" to CompatWire.profileToMap(b),
                "expected" to CompatWire.resultToMap(Compatibility.score(a, b)),
                "synastry" to CompatWire.synastryToList(Compatibility.synastry(a, b)),
            )
        }
        val compat = linkedMapOf(
            "description" to "Compatibility.score golden vectors. Kotlin is the reference; Swift and the TypeScript backend " +
                "must return identical values for every field. Algorithm: docs/COMPATIBILITY.md.",
            "generator" to "./gradlew :core:writeGoldens (android/core/src/test/.../GoldenWriter.kt)",
            "cases" to cases,
        )
        File(TestData.dir, "golden_compat.json").writeText(Json.write(compat) + "\n")
        File(TestData.dir, "golden_prompts.json").writeText(Json.write(PromptFixtures.build()) + "\n")
        println("wrote ${cases.size} compat cases and prompt goldens to ${TestData.dir}")
    }
}
