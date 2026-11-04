package com.metavirtuoso.conjunction.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.metavirtuoso.conjunction.ui.theme.Palette

/** A parsed block of the small Markdown subset the legal documents use. */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Bullet(val text: String, val ordered: String?, val indent: Int) : MdBlock
    data class TableRow(val cells: List<String>) : MdBlock
    data class Quote(val text: String) : MdBlock
    data object Rule : MdBlock
}

/** Minimal Markdown parser: headings, paragraphs, bullet / numbered lists, tables, quotes, rules. */
object MarkdownParser {
    fun parse(markdown: String): List<MdBlock> {
        val out = mutableListOf<MdBlock>()
        val paragraph = StringBuilder()
        fun flush() {
            if (paragraph.isNotBlank()) out += MdBlock.Paragraph(paragraph.toString().trim())
            paragraph.clear()
        }
        for (raw in markdown.lines()) {
            val line = raw.trimEnd()
            val trimmed = line.trimStart()
            val indent = (line.length - trimmed.length) / 2
            when {
                trimmed.isEmpty() -> flush()
                trimmed.startsWith("#") -> {
                    flush()
                    val level = trimmed.takeWhile { it == '#' }.length
                    out += MdBlock.Heading(level, trimmed.drop(level).trim())
                }
                trimmed.matches(Regex("""^(-{3,}|\*{3,}|_{3,})$""")) -> { flush(); out += MdBlock.Rule }
                trimmed.startsWith("|") -> {
                    flush()
                    val cells = trimmed.trim('|').split("|").map { it.trim() }
                    if (cells.all { it.matches(Regex("""^:?-{2,}:?$""")) || it.isEmpty() }) continue
                    if (cells.all { it.isEmpty() }) continue
                    out += MdBlock.TableRow(cells)
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    flush(); out += MdBlock.Bullet(trimmed.drop(2), null, indent)
                }
                Regex("""^(\d+|[a-z])[.)]\s+""").containsMatchIn(trimmed) -> {
                    flush()
                    val m = Regex("""^(\d+|[a-z])[.)]\s+""").find(trimmed)!!
                    out += MdBlock.Bullet(trimmed.substring(m.range.last + 1), m.groupValues[1], indent)
                }
                trimmed.startsWith(">") -> { flush(); out += MdBlock.Quote(trimmed.removePrefix(">").trim()) }
                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append(' ')
                    paragraph.append(trimmed)
                }
            }
        }
        flush()
        return out
    }

    /** Inline **bold**, *italic* / _italic_, `code` and [links](url) (shown as text). */
    fun inline(text: String): AnnotatedString = buildAnnotatedString {
        var i = 0
        val s = text.replace(Regex("""\[([^\]]+)]\(([^)]+)\)"""), "$1")
        while (i < s.length) {
            when {
                s.startsWith("**", i) -> {
                    val end = s.indexOf("**", i + 2)
                    if (end < 0) { append(s.substring(i)); i = s.length } else {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Palette.GoldPale)) { append(s.substring(i + 2, end)) }
                        i = end + 2
                    }
                }
                (s[i] == '*' || s[i] == '_') && i + 1 < s.length && s[i + 1] != ' ' && (i == 0 || !s[i - 1].isLetterOrDigit()) -> {
                    val end = s.indexOf(s[i], i + 1)
                    if (end < 0) { append(s[i]); i++ } else {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.substring(i + 1, end)) }
                        i = end + 1
                    }
                }
                s[i] == '`' -> {
                    val end = s.indexOf('`', i + 1)
                    if (end < 0) { append('`'); i++ } else {
                        withStyle(SpanStyle(color = Palette.GoldPale)) { append(s.substring(i + 1, end)) }
                        i = end + 1
                    }
                }
                else -> { append(s[i]); i++ }
            }
        }
    }
}

/** Renders legal Markdown in the app's typography (headings in Cinzel, body in Cormorant). */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val blocks = MarkdownParser.parse(markdown)
    Column(modifier) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    MarkdownParser.inline(block.text),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    },
                    color = Palette.Gold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 6.dp).semantics { heading() },
                )
                is MdBlock.Paragraph -> Text(
                    MarkdownParser.inline(block.text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.Moonstone,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                is MdBlock.Bullet -> Row(Modifier.padding(start = (block.indent * 14).dp, top = 2.dp, bottom = 2.dp)) {
                    Text(block.ordered?.let { "$it." } ?: "✦", color = Palette.Gold, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(26.dp))
                    Text(MarkdownParser.inline(block.text), style = MaterialTheme.typography.bodyMedium, color = Palette.Moonstone)
                }
                is MdBlock.TableRow -> Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    block.cells.forEachIndexed { index, cell ->
                        Text(
                            MarkdownParser.inline(cell),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (index == 0) Palette.GoldPale else Palette.Moonstone,
                            modifier = Modifier.weight(if (index == 0) 0.8f else 1.2f).padding(end = 8.dp),
                        )
                    }
                }
                is MdBlock.Quote -> Text(
                    MarkdownParser.inline(block.text),
                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    color = Palette.MoonstoneDim,
                    modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                )
                MdBlock.Rule -> OrnateDivider(Modifier.padding(vertical = 10.dp))
            }
        }
    }
}
