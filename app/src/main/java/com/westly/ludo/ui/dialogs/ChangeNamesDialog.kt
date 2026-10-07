package com.westly.ludo.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Four name fields (one per seat), with live counter and errors for empty or duplicate names
 * (duplicates are checked ignoring upper/lower case).
 *
 * [currentNames] = the saved names (seat 0 first). [defaultNames] = what Reset puts back.
 * Save is only enabled when every name is valid and something changed. Save calls [onSave] with
 * the trimmed names and then [onClose]. Cancel, the X button and Back call [onClose] only.
 */
@Composable
fun GoldChangeNamesDialog(
    currentNames: List<String>,
    defaultNames: List<String>,
    onSave: (List<String>) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    maxLength: Int = 12
) {
    val edits = remember {
        mutableStateListOf<String>().apply { addAll(currentNames) }
    }
    val trimmed = edits.map { it.trim() }
    val errors: List<String?> = trimmed.mapIndexed { i, v ->
        when {
            v.isEmpty() -> "${SeatColors.name(i)} needs a name."
            trimmed.indexOfFirst { it.equals(v, ignoreCase = true) } != i -> "\u201C$v\u201D is already taken."
            else -> null
        }
    }
    val firstError = errors.firstOrNull { it != null }
    val changed = trimmed.indices.any { i -> trimmed[i] != currentNames.getOrElse(i) { "" } }
    val canSave = firstError == null && changed

    GoldDialog(
        title = "Change names",
        lead = "Names show on the board and the scoreboard.",
        badgeIcon = LudoIcon.Pen,
        badgeTone = ChunkyTones.Blue,
        onClose = onClose,
        modifier = modifier
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (i in edits.indices) {
                NameField(
                    seat = i,
                    value = edits[i],
                    onValueChange = { edits[i] = it },
                    hasError = errors[i] != null,
                    maxLength = maxLength,
                    isLast = i == edits.lastIndex
                )
            }
        }

        // Error message line (always reserves one line so the dialog does not jump)
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 22.dp)
                .padding(top = 8.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            contentAlignment = Alignment.Center
        ) {
            BasicText(
                firstError ?: "",
                style = bodyStyle(14.sp, FontWeight.Medium, GoldTheme.ErrorText, TextAlign.Center)
            )
        }

        DialogButtons(topGap = 6.dp) {
            ChunkyButton(
                text = "Save names",
                onClick = {
                    onSave(trimmed)
                    onClose()
                },
                tone = ChunkyTones.Green,
                enabled = canSave
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GhostButton("Cancel", onClose, Modifier.weight(1f))
                GhostButton(
                    text = "Reset",
                    onClick = {
                        edits.clear()
                        edits.addAll(defaultNames.take(currentNames.size))
                    },
                    modifier = Modifier.weight(1f),
                    icon = LudoIcon.Redo
                )
            }
        }
    }
}

/** Avatar with the first letter, text input, and a live "n/12" counter. */
@Composable
private fun NameField(
    seat: Int,
    value: String,
    onValueChange: (String) -> Unit,
    hasError: Boolean,
    maxLength: Int,
    isLast: Boolean
) {
    val focusManager = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    val borderColor = when {
        hasError -> SeatColors.of(0).main
        focused -> GoldTheme.Focus
        else -> GoldTheme.GoldLine
    }
    val tapSource = remember { MutableInteractionSource() }

    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                if (focused) {
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
            .border(1.5.dp, borderColor, shape)
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
                .onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = "${SeatColors.name(seat)} player name" }
                .padding(vertical = 8.dp)
        )
        BasicText(
            "${value.length}/$maxLength",
            style = bodyStyle(13.sp, FontWeight.Medium, GoldTheme.Muted)
        )
    }
}
