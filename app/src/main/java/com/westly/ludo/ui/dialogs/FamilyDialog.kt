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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.graphics.Brush
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
 * Family "Who's playing?": a 2 to 4 stepper, one name field per seat, and a row of quick-pick names.
 * Tapping a quick-pick name fills the selected seat and moves on to the next one. A name that is
 * already used is greyed out. Save is only enabled when every seat has a different name.
 *
 * [onSave] gets the trimmed names of the seats in play (2 to 4 of them), then [onClose] is called.
 * Cancel, the X button and Back call [onClose] only.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GoldFamilyDialog(
    onSave: (List<String>) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    initialCount: Int = 3,
    initialNames: List<String> = emptyList(),
    roster: List<String> = DefaultFamilyRoster,
    maxLength: Int = 12
) {
    var count by remember { mutableIntStateOf(initialCount.coerceIn(2, 4)) }
    val names = remember {
        mutableStateListOf<String>().apply { for (i in 0 until 4) add(initialNames.getOrElse(i) { "" }) }
    }
    var selected by remember { mutableIntStateOf(0) }
    val focusManager = LocalFocusManager.current

    val active = names.take(count).map { it.trim() }
    val duplicate = active.indices.firstOrNull { i ->
        active[i].isNotEmpty() && active.indexOfFirst { it.equals(active[i], ignoreCase = true) } != i
    }
    val allNamed = active.all { it.isNotEmpty() }
    val canSave = allNamed && duplicate == null

    GoldDialog(
        title = "Who\u2019s playing?",
        lead = "Choose how many play, then name every seat.",
        badgeIcon = LudoIcon.Users,
        badgeTone = ChunkyTones.Green,
        onClose = onClose,
        modifier = modifier
    ) {
        // --- stepper -------------------------------------------------------------------------
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            StepperButton(LudoIcon.Minus, "Fewer players", enabled = count > 2) {
                count -= 1
                if (selected > count - 1) selected = count - 1
            }
            BasicText(
                "$count players",
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .semantics { contentDescription = "$count players" },
                style = displayStyle(24.sp, GoldTheme.Cream, TextAlign.Center)
            )
            StepperButton(LudoIcon.Plus, "More players", enabled = count < 4) { count += 1 }
        }

        Spacer(Modifier.height(16.dp))

        // --- seat fields ---------------------------------------------------------------------
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (i in 0 until count) {
                FamilyField(
                    seat = i,
                    value = names[i],
                    selected = selected == i,
                    maxLength = maxLength,
                    isLast = i == count - 1,
                    onFocused = { selected = i },
                    onValueChange = { names[i] = it }
                )
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 22.dp)
                .padding(top = 8.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            contentAlignment = Alignment.Center
        ) {
            when {
                duplicate != null -> BasicText(
                    "\u201C${active[duplicate]}\u201D is already taken.",
                    style = bodyStyle(14.sp, FontWeight.Medium, GoldTheme.ErrorText, TextAlign.Center)
                )
                !allNamed -> BasicText(
                    "Every seat needs a name.",
                    style = bodyStyle(14.sp, FontWeight.Medium, GoldTheme.Muted, TextAlign.Center)
                )
                else -> {}
            }
        }

        // --- quick picks ---------------------------------------------------------------------
        Spacer(Modifier.height(6.dp))
        DialogSectionLabel("Tap a name to fill the highlighted seat")
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (name in roster) {
                val used = active.any { it.equals(name, ignoreCase = true) }
                OptionChip(
                    label = name,
                    selected = false,
                    enabled = !used,
                    onClick = {
                        focusManager.clearFocus()
                        names[selected] = name
                        if (selected < count - 1) selected += 1
                    }
                )
            }
        }

        DialogButtons {
            ChunkyButton(
                text = "Save",
                onClick = {
                    onSave(active)
                    onClose()
                },
                tone = ChunkyTones.Green,
                enabled = canSave
            )
            GhostButton("Cancel", onClose)
        }
    }
}

/** Round 48dp +/- button. */
@Composable
private fun StepperButton(icon: LudoIcon, description: String, enabled: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(48.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(ChunkyTones.Navy.top, ChunkyTones.Navy.bottom)))
            .border(2.dp, GoldTheme.GoldChip, CircleShape)
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
        LudoIconView(icon, iconSize = 24.dp, tint = androidx.compose.ui.graphics.Color.White)
    }
}

/** Avatar, text input with a "Player n" hint, live counter. The selected seat gets the gold ring. */
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
            .heightIn(min = 56.dp)
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
                .semantics { contentDescription = "${SeatColors.name(seat)} player name" },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        BasicText(
                            "Player ${seat + 1}",
                            style = bodyStyle(18.sp, FontWeight.Bold, GoldTheme.Muted.copy(alpha = 0.6f))
                        )
                    }
                    inner()
                }
            }
        )
        BasicText(
            "${value.length}/$maxLength",
            style = bodyStyle(13.sp, FontWeight.Medium, GoldTheme.Muted),
            maxLines = 1
        )
    }
}
