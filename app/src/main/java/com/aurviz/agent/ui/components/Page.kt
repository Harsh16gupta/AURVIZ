package com.aurviz.agent.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.aurviz.agent.ui.theme.CaretLeft
import com.aurviz.agent.ui.theme.Icons
import com.aurviz.agent.ui.theme.Space

internal val PageWidth = 640.dp

@Composable
fun Page(
    title: String,
    modifier: Modifier = Modifier,
    // Null on the 5 root screens (Command, Browser, Schedules, Memory,
    // Settings): no stray back chevron. Pushed sub-views pass a callback
    // and get the chevron.
    onBack: (() -> Unit)? = null,
    backIcon: ImageVector = Icons.CaretLeft,
    backLabel: String = "Back",
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: (@Composable () -> Unit)? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val horizontalSafe = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
    ScreenScaffold(modifier) {
        Column(Modifier.fillMaxSize()) {
            AppBar(
                navigation = {
                    val goBack = onBack
                    if (goBack != null) {
                        IconButton(onClick = goBack, contentDescription = backLabel) {
                            Icon(backIcon, contentDescription = null, modifier = Modifier.size(22.dp))
                        }
                    }
                },
                title = { Text(title, maxLines = 1) },
                actions = actions,
            )
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        // Keyboard can cover TextFields in Schedules/Memory/Settings.
                        .imePadding()
                        .windowInsetsPadding(
                            horizontalSafe.union(
                                if (bottomBar == null) WindowInsets.navigationBars
                                else WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
                            )
                        )
                        .padding(horizontal = Space * 4, vertical = Space * 2)
                        .padding(bottom = Space * 6),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(
                        Modifier.widthIn(max = PageWidth).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(Space * 6),
                        content = content,
                    )
                }
            }
            if (bottomBar != null) {
                Box(
                    Modifier.fillMaxWidth()
                        .windowInsetsPadding(
                            WindowInsets.navigationBars
                                .union(WindowInsets.ime)
                                .union(horizontalSafe)
                        )
                        .padding(horizontal = Space * 4, vertical = Space * 3),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.widthIn(max = PageWidth).fillMaxWidth()) { bottomBar() }
                }
            }
        }
        overlay()
    }
}
