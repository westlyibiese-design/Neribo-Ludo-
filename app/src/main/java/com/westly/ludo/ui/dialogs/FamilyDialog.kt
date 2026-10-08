package com.westly.ludo.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val DefaultFamilyRoster = listOf("Mum", "Dad", "Tunde", "Ada", "Gran", "Uncle Emeka", "Chidi", "Zainab")

/**
 * Family "Who's playing?": a 2 to 4 stepper, one name field per seat (the seat colour is shown
 * on the right), and a row of quick-pick names. Tapping a quick-pick name fills the selected
 * seat and moves on to the next one (after the last seat it goes back to the first). A name that
 * is already used is greyed out and struck through.
 *
 * Save is always tappable, like the HTML. If a seat is empty it shows "Every seat needs a name"
 * under the fields; if two seats have the same name (ignoring upper/lower case) it says that
 * name is already taken. Otherwise [onSave] gets the trimmed names of the seats in play (2 to 4
 * of them), then [onClose] is called. Cancel, the X button and Back call [onClose] only.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GoldFamilyDialog(
    onSave: (List<String>) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    initialCount: Int = 4,
    initialNames: List<String> = DefaultFamilyRoster.take(4),
    roster: List<String> = DefaultFamilyRoster,
    maxLength: Int = 12
) {
    var count by remember { mutableIntStateOf(initialCount.coerceIn(2, 4)) }
    val names = remember {
        mutableStateListOf<String>().apply { for (i in 0 until 4) add(initialNames.getOrElse(i) { "" }) }
    }
    var selected by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current

    val active = names.take(count).map { it.trim() }

    GoldDialog(
        title = "Who\u2019s playing?",
        lead = "Pick a seat, then tap a name. You can also type one.",
        badgeIcon = LudoIcon.Users,
        badgeTone = ChunkyTones.Yellow,
        onClose = onClose,
        modifier = modifier
    ) {
        // --- "Players tonight" + stepper (centred, like the HTML) ---------------------------
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            BasicText(
                "Players tonight",
                style = bodyStyle(15.sp, FontWeight.Bold, GoldTheme.Gold, lineHeight = 18.sp)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StepKey("\u2212", "Fewer players", enabled = count > 2) {
                    count -= 1
                    if (selected > count - 1) selected = count - 1
                    message = null
                }
                BasicText(
                    "$count",
                    modifier = Modifier
                        .widthIn(min = 24.dp)
                        .semantics { contentDescription = "$count players"; liveRegion = LiveRegionMode.Polite },
                    style = displayStyle(20.sp, GoldTheme.Cream, TextAlign.Center)
                )
                StepKey("+", "More players", enabled = count < 4) {
                    count += 1
                    message = null
                }
            }
        }

        // --- seat fields -----------------------------------------------------------------------
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (i in 0 until count) {
                FamilyField(
                    seat = i,
                    value = names[i],
                    selected = selected == i,
                    maxLength = maxLength,
                    isLast = i == count - 1,
                    onFocused = { selected = i },
                    onValueChange = {
                        names[i] = it
                        message = null
                    }
                )
            }
        }

        val shownMessage = message
        if (shownMessage != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                contentAlignment = Alignment.Center
            ) {
                BasicText(
                    shownMessage,
                    style = bodyStyle(14.sp, FontWeight.Medium, GoldTheme.ErrorText, TextAlign.Center)
                )
            }
        }

        // --- quick picks -----------------------------------------------------------------------
        DialogSectionLabel("Family names", topGap = 16.dp)
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (name in roster) {
                val used = active.any { it.equals(name, ignoreCase = true) }
                RosterChip(
                    label = name,
                    enabled = !used,
                    onClick = {
                        focusManager.clearFocus()
                        names[selected] = name
                        selected = (selected + 1) % count
                        message = null
                    }
                )
            }
        }

        DialogButtons {
            ChunkyButton(
                text = "Save players",
                onClick = {
                    val duplicate = active.indices.firstOrNull { i ->
                        active[i].isNotEmpty() &&
                            active.indexOfFirst { it.equals(active[i], ignoreCase = true) } != i
                    }
                    when {
                        active.any { it.isEmpty() } -> message = "Every seat needs a name"
                        duplicate != null -> message = "\u201C${active[duplicate]}\u201D is already taken."
                        else -> {
                            onSave(active)
                            onClose()
                        }
                    }
                },
                tone = ChunkyTones.Green
            )
            GhostButton("Cancel", onClose)
        }
    }
}

/** The 40dp square minus / plus key of the stepper: rounded 12dp, white 6% fill, thin line border. */
@Composable
private fun StepKey(symbol: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(40.dp)
            .alpha(if (enabled) 1f else 0.3f)
            .clip(shape)
            .background(Color(0x0FFFFFFF))
            .border(1.5.dp, GoldTheme.Line, shape)
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        BasicText(symbol, style = displayStyle(20.sp, GoldTheme.Cream, TextAlign.Center))
    }
}

/** Avatar, text input, and the seat colour name on the right. The selected seat gets the yellow ring. */
@Composable
private fun FamilyField(
    seat: Int,
    value: String,
    selected: Boolean,
    maxLength: Int,
    isLast: Boolean,
    onFocused: () -> Unit,
    onValueChange: (String) -> Unit
) {
    val focusManager = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    val shape = RoundedCornerShape(18.dp)
    val tapSource = remember { MutableInteractionSource() }

    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                if (selected) {
                    val half = 1.5.dp.toPx()
                    drawRoundRect(
                        color = GoldTheme.FocusRing,
                        topLeft = Offset(-half, -half),
                        size = Size(this.size.width + half * 2f, this.size.height + half * 2f),
                        cornerRadius = CornerRadius(18.dp.toPx() + half),
                        style = Stroke(width = 3.dp.toPx())
                    )
                }
            }
            .background(GoldTheme.PanelFill, shape)
            .border(1.5.dp, if (selected) GoldTheme.Focus else GoldTheme.GoldLine, shape)
            .clickable(interactionSource = tapSource, indication = null) { requester.requestFocus() }
            .padding(start = 8.dp, top = 8.dp, end = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SeatAvatar(seat, value)
        BasicTextField(
            value = value,
            onValueChange = { onValueChange(it.take(maxLength)) },
            singleLine = true,
            textStyle = bodyStyle(18.sp, FontWeight.Bold, GoldTheme.Cream),
            cursorBrush = SolidColor(GoldTheme.Focus),
            keyboardOptions = KeyboardOptions(imeAction = if (isLast) ImeAction.Done else ImeAction.Next),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Down) },
                onDone = { focusManager.clearFocus() }
            ),
            modifier = Modifier
                .weight(1f)
                .focusRequester(requester)
                .onFocusChanged { if (it.isFocused) onFocused() }
                .semantics { contentDescription = "${SeatColors.name(seat)} seat name" }
                .padding(vertical = 8.dp)
        )
        BasicText(
            SeatColors.name(seat),
            style = bodyStyle(13.sp, FontWeight.Medium, GoldTheme.Muted),
            maxLines = 1
        )
    }
}
