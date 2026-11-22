package com.aurviz.agent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.aurviz.agent.ui.theme.ProvideTextStyle
import com.aurviz.agent.ui.theme.Space
import com.aurviz.agent.ui.theme.Theme

@Composable
fun AppBar(
    modifier: Modifier = Modifier,
    navigation: @Composable () -> Unit = {},
    title: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    val gap = Space * 2
    Layout(
        content = {
            Box { navigation() }
            Box { ProvideTextStyle(Theme.typography.title, title) }
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions()
            }
        },
        modifier =
            modifier
                .fillMaxWidth()
                .background(Theme.colors.background)
                .blockTouchesToContentBehind()
                .windowInsetsPadding(
                    WindowInsets.statusBars.union(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
                    )
                )
                .height(56.dp)
                .padding(horizontal = Space * 2),
    ) { (navigationSlot, titleSlot, actionsSlot), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val navigationPlaceable = navigationSlot.measure(loose)
        val actionsPlaceable = actionsSlot.measure(loose)
        val side = maxOf(navigationPlaceable.width, actionsPlaceable.width) + gap.roundToPx()
        val titleWidth = (constraints.maxWidth - side * 2).coerceAtLeast(0)
        val titlePlaceable =
            titleSlot.measure(Constraints(maxWidth = titleWidth, maxHeight = constraints.maxHeight))
        val height = constraints.maxHeight
        layout(constraints.maxWidth, height) {
            navigationPlaceable.place(0, (height - navigationPlaceable.height) / 2)
            titlePlaceable.place(
                (constraints.maxWidth - titlePlaceable.width) / 2,
                (height - titlePlaceable.height) / 2,
            )
            actionsPlaceable.place(
                constraints.maxWidth - actionsPlaceable.width,
                (height - actionsPlaceable.height) / 2,
            )
        }
    }
}

private fun Modifier.blockTouchesToContentBehind() = pointerInput(Unit) {}
