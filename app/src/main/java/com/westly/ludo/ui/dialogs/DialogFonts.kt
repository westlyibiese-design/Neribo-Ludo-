package com.westly.ludo.ui.dialogs

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/** Display font (titles, buttons) and body font (everything else). */
class LudoFontSet(val display: FontFamily, val body: FontFamily)

val LocalLudoFonts = staticCompositionLocalOf { LudoFontSet(FontFamily.SansSerif, FontFamily.SansSerif) }

/** false when the phone has animations switched off (animator duration scale = 0). */
val LocalMotionEnabled = staticCompositionLocalOf { true }

/**
 * Looks for the font files in res/font by name. If a file is not there the system sans is used,
 * so the app builds and runs with or without the font files.
 *
 * Expected files (all lowercase):
 *   res/font/bricolage_grotesque_extrabold.ttf
 *   res/font/figtree_medium.ttf, figtree_semibold.ttf, figtree_bold.ttf
 */
internal fun loadLudoFonts(context: Context): LudoFontSet {
    fun family(parts: List<Pair<String, FontWeight>>): FontFamily {
        val fonts = ArrayList<Font>()
        for ((name, weight) in parts) {
            val id = context.resources.getIdentifier(name, "font", context.packageName)
            if (id != 0) fonts.add(Font(id, weight))
        }
        return if (fonts.isEmpty()) FontFamily.SansSerif else FontFamily(fonts)
    }

    val display = family(listOf("bricolage_grotesque_extrabold" to FontWeight.ExtraBold))
    val body = family(
        listOf(
            "figtree_medium" to FontWeight.Medium,
            "figtree_semibold" to FontWeight.SemiBold,
            "figtree_bold" to FontWeight.Bold
        )
    )
    return LudoFontSet(display, body)
}

internal fun animationsEnabled(context: Context): Boolean = try {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
} catch (e: Exception) {
    true
}
