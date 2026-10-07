package com.westly.ludo.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp

/**
 * A full-screen navy page used by the Tie-break and Winner screens. Content is centred, limited to
 * 420dp wide, and scrolls on small phones or with large fonts.
 * [onBack] runs on the Back button. Pass null to block Back.
 */
@Composable
internal fun FullScreenGold(
    onBack: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit
) {
    DialogWindow(onBack = onBack) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(GoldTheme.Navy1, GoldTheme.Navy2, GoldTheme.Bg)))
        ) {
            BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
                val minHeight = maxHeight
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        Column(
                            Modifier
                                .widthIn(max = 420.dp)
                                .fillMaxWidth()
                                .heightIn(min = minHeight)
                                .padding(horizontal = 20.dp, vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            content = content
                        )
                    }
                }
            }
        }
    }
}
