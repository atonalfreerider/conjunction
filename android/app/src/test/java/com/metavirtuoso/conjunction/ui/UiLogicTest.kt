package com.metavirtuoso.conjunction.ui

import com.metavirtuoso.conjunction.ui.chart.spreadLongitudes
import com.metavirtuoso.conjunction.ui.chart.wheelAngle
import com.metavirtuoso.conjunction.ui.components.MarkdownParser
import com.metavirtuoso.conjunction.ui.components.MdBlock
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiLogicTest {
    @Test
    fun spreadKeepsGlyphsApartAndNearTheirPlanets() {
        val input = listOf(10.0, 11.0, 12.0, 200.0, 359.0, 0.5)
        val out = spreadLongitudes(input, minGap = 7.0)
        val sorted = out.map { ((it % 360) + 360) % 360 }.sorted()
        val gaps = sorted.zipWithNext { a, b -> b - a } + (sorted.first() + 360 - sorted.last())
        assertTrue("gaps $gaps", gaps.all { it >= 6.99 })
        input.indices.forEach { i ->
            val d = abs(((out[i] - input[i]) % 360 + 540) % 360 - 180)
            assertTrue("moved too far: ${input[i]} -> ${out[i]}", d < 20)
        }
    }

    @Test
    fun ascendantIsOnTheLeft() {
        assertEquals(180.0, wheelAngle(123.0, 123.0), 1e-9)
        assertEquals(180.0, wheelAngle(0.0, null), 1e-9)
        // Counter-clockwise: 90 degrees later in the zodiac is a quarter turn on.
        assertEquals(270.0, wheelAngle(213.0, 123.0), 1e-9)
    }

    @Test
    fun markdownBlocks() {
        val blocks = MarkdownParser.parse(
            """
            # Title

            | | |
            |---|---|
            | Version | 1.0 |

            Some **bold** text
            continues here.

            - first
            1. numbered
            > quoted
            ---
            """.trimIndent(),
        )
        assertEquals(MdBlock.Heading(1, "Title"), blocks[0])
        assertEquals(MdBlock.TableRow(listOf("Version", "1.0")), blocks[1])
        assertEquals(MdBlock.Paragraph("Some **bold** text continues here."), blocks[2])
        assertEquals(MdBlock.Bullet("first", null, 0), blocks[3])
        assertEquals(MdBlock.Bullet("numbered", "1", 0), blocks[4])
        assertEquals(MdBlock.Quote("quoted"), blocks[5])
        assertEquals(MdBlock.Rule, blocks[6])
        assertEquals("Some bold text", MarkdownParser.inline("Some **bold** text").text)
        assertEquals("see Terms", MarkdownParser.inline("see [Terms](TERMS.md)").text)
    }
}
