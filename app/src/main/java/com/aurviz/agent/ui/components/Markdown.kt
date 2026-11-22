package com.aurviz.agent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.aurviz.agent.ui.theme.LocalContentColor
import com.aurviz.agent.ui.theme.Space
import com.aurviz.agent.ui.theme.Theme

@Composable
fun Markdown(
    text: String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(text) { parseBlocks(text) }
    val codeStyle =
        SpanStyle(
            fontFamily = Theme.typography.code.fontFamily,
            fontSize = Theme.typography.code.fontSize,
            background = Theme.colors.surfaceVariant,
        )
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space * 3)) {
        blocks.forEach { block ->
            when (block) {
                is Block.Heading ->
                    Text(inline(block.text, codeStyle).text, style = Theme.typography.title)
                is Block.Paragraph -> InlineText(inline(block.text, codeStyle))
                is Block.ListItems ->
                    Column(verticalArrangement = Arrangement.spacedBy(Space * 2)) {
                        block.items.forEachIndexed { index, item ->
                            Row {
                                Text(
                                    if (block.ordered) "${index + 1}." else "•",
                                    color = Theme.colors.onBackgroundMuted,
                                    modifier = Modifier.widthIn(min = Space * 6),
                                )
                                InlineText(inline(item, codeStyle))
                            }
                        }
                    }
                is Block.Code ->
                    Text(
                        block.code,
                        style = Theme.typography.code,
                        softWrap = false,
                        modifier =
                            Modifier.fillMaxWidth()
                                .clip(Theme.shapes.small)
                                .background(Theme.colors.surface)
                                .horizontalScroll(rememberScrollState())
                                .padding(Space * 4),
                    )
            }
        }
    }
}

@Composable
private fun InlineText(text: AnnotatedString) {
    BasicText(text, style = Theme.typography.body.copy(color = LocalContentColor.current))
}

private sealed interface Block {
    data class Heading(val text: String) : Block

    data class Paragraph(val text: String) : Block

    data class ListItems(val ordered: Boolean, val items: List<String>) : Block

    data class Code(val code: String) : Block
}

private val orderedItem = Regex("""^\d+\.\s+""")

private fun parseBlocks(text: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val lines = text.lines()
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        when {
            line.startsWith("```") -> {
                val code = mutableListOf<String>()
                index++
                while (index < lines.size && !lines[index].startsWith("```")) code += lines[index++]
                blocks += Block.Code(code.joinToString("\n"))
            }
            line.startsWith("#") -> blocks += Block.Heading(line.trimStart('#').trim())
            line.startsWith("- ") || orderedItem.containsMatchIn(line) -> {
                val ordered = !line.startsWith("- ")
                val items = mutableListOf<String>()
                while (
                    index < lines.size &&
                        (if (ordered) orderedItem.containsMatchIn(lines[index])
                        else lines[index].startsWith("- "))
                ) {
                    items +=
                        if (ordered) lines[index].replace(orderedItem, "")
                        else lines[index].removePrefix("- ")
                    index++
                }
                blocks += Block.ListItems(ordered, items)
                continue
            }
            line.isNotBlank() -> {
                val paragraph = mutableListOf(line)
                while (
                    index + 1 < lines.size &&
                        lines[index + 1].isNotBlank() &&
                        !startsBlock(lines[index + 1])
                ) paragraph += lines[++index]
                blocks += Block.Paragraph(paragraph.joinToString(" "))
            }
        }
        index++
    }
    return blocks
}

private fun startsBlock(line: String) =
    line.startsWith("```") ||
        line.startsWith("#") ||
        line.startsWith("- ") ||
        orderedItem.containsMatchIn(line)

private fun inline(text: String, codeStyle: SpanStyle): AnnotatedString = buildAnnotatedString {
    var index = 0
    while (index < text.length) {
        when {
            text.startsWith("**", index) -> {
                val end = text.indexOf("**", index + 2).takeIf { it >= 0 } ?: text.length
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                    append(text.substring(index + 2, end))
                }
                index = end + 2
            }
            text[index] == '`' -> {
                val end = text.indexOf('`', index + 1).takeIf { it >= 0 } ?: text.length
                withStyle(codeStyle) { append(" ${text.substring(index + 1, end)} ") }
                index = end + 1
            }
            else -> append(text[index++])
        }
    }
}
