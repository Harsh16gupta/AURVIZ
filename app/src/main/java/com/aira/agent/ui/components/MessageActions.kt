package com.aira.agent.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.aira.agent.ui.theme.ArrowClockwise
import com.aira.agent.ui.theme.Copy
import com.aira.agent.ui.theme.Export
import com.aira.agent.ui.theme.Icons
import com.aira.agent.ui.theme.SpeakerHigh
import com.aira.agent.ui.theme.Theme
import com.aira.agent.ui.theme.ThumbsDown
import com.aira.agent.ui.theme.ThumbsDownFill
import com.aira.agent.ui.theme.ThumbsUp
import com.aira.agent.ui.theme.ThumbsUpFill

enum class Rating {
    None,
    Liked,
    Disliked,
}

@Composable
fun MessageActions(
    rating: Rating,
    onCopy: () -> Unit,
    onListen: () -> Unit,
    onRate: (Rating) -> Unit,
    onRegenerate: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Action(Icons.Copy, "Copy", onCopy)
        Action(Icons.SpeakerHigh, "Read aloud", onListen)
        Action(
            if (rating == Rating.Liked) Icons.ThumbsUpFill else Icons.ThumbsUp,
            "Good response",
            { onRate(if (rating == Rating.Liked) Rating.None else Rating.Liked) },
        )
        Action(
            if (rating == Rating.Disliked) Icons.ThumbsDownFill else Icons.ThumbsDown,
            "Bad response",
            { onRate(if (rating == Rating.Disliked) Rating.None else Rating.Disliked) },
        )
        Action(Icons.ArrowClockwise, "Regenerate", onRegenerate)
        Action(Icons.Export, "Share", onShare)
    }
}

@Composable
private fun Action(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        contentDescription = label,
        size = IconButtonSize.Small,
        modifier = modifier,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = Theme.colors.onBackgroundMuted,
        )
    }
}
