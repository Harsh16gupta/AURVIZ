package com.aira.agent.ui.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.aira.agent.ui.theme.LocalContentColor
import com.aira.agent.ui.theme.LocalTextStyle

@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
) {
    val resolvedColor =
        when {
            color.isSpecified -> color
            style.color.isSpecified -> style.color
            else -> LocalContentColor.current
        }
    BasicText(
        text = text,
        modifier = modifier,
        style = LocalTextStyle.current.merge(style).copy(color = resolvedColor),
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
    )
}
