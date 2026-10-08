package com.westly.ludo.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A full-screen page (Tie-break, Round result, Winner, You're out). Like the HTML ".full" page it
 * has up to three parts: a [header] at the top, the [main] content centred in the space between,
 * and a [footer] with the buttons at the bottom. [belowMain] is an optional line between the
 * two (the tie-break status text). There is no solid background: the page sits on
 * the same dark scrim as the dialogs, so the game shows through faintly. The content is limited
 * to 420dp wide with a 16dp margin, and [main] scrolls on small phones or with large fonts.
 *
 * [onBack] runs on the Back button. Pass null to block Back.
 */
@Composable
internal fun FullScreenGold(
    onBack: (() -> Unit)?,
    header: (@Composable ColumnScope.() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    belowMain: (@Composable ColumnScope.() -> Unit)? = null,
    mainGap: Dp = 10.dp,
    main: @Composable ColumnScope.() -> Unit
) {
    DialogWindow(onBack = onBack, rise = true) {
        Box(
            Modifier.fillMaxSize().systemBarsPadding(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (header != null) {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        content = header
                    )
                }
                Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(mainGap),
                        content = main
                    )
                }
                if (belowMain != null) {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        content = belowMain
                    )
                }
                if (footer != null) {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        content = footer
                    )
                }
            }
        }
    }
}

/**
 * The round badge of a full-screen page: 64dp with a 5dp solid edge underneath. [big] makes it
 * 88dp with a 3dp gold ring (the crown on the winner page). The icon is 30dp (44dp when big).
 */
@Composable
internal fun FullBadge(
    icon: LudoIcon,
    tone: ChunkyTone,
    modifier: Modifier = Modifier,
    big: Boolean = false
) {
    val badge = if (big) 88.dp else 64.dp
    Box(
        modifier
            .size(badge)
            .drawBehind {
                drawCircle(
                    color = tone.edge,
                    radius = this.size.minDimension / 2f,
                    center = Offset(this.size.width / 2f, this.size.height / 2f + 5.dp.toPx())
                )
            }
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(tone.top, tone.bottom)))
            .then(if (big) Modifier.border(3.dp, GoldTheme.Gold, CircleShape) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        LudoIconView(icon, iconSize = if (big) 44.dp else 30.dp, tint = tone.content)
    }
}
