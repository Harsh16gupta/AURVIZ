package com.aira.agent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.aira.agent.ui.theme.ProvideContentColor
import com.aira.agent.ui.theme.Theme
import com.aira.agent.ui.theme.VelaTheme

@Composable
fun AppScaffold(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    VelaTheme {
        Box(modifier.fillMaxSize().background(Theme.colors.background)) {
            ProvideContentColor(Theme.colors.onBackground, content)
        }
    }
}
