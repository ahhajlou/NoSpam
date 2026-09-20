package com.nospam.nospam.core.designsystem.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Circular sender avatar.
 *
 * Shows the first letter of [name] when it has one, and a person icon
 * otherwise — so a bare number renders an icon, not "+". The color is derived
 * from [colorKey] (pass the normalised address), so one sender keeps one color
 * everywhere and across launches. With [selected] it flips to a check mark,
 * which is how selection mode marks a row.
 *
 * Decorative: its semantics are cleared, because the row beside it already
 * announces the name. The row, not the avatar, must announce selection.
 */
@Composable
fun Avatar(
    name: String?,
    colorKey: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    selected: Boolean = false,
) {
    val palette = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) AvatarPaletteDark else AvatarPaletteLight
    val (container, content) = palette[avatarPaletteIndex(colorKey, palette.size)]
    val initial = avatarInitial(name)
    AnimatedContent(
        targetState = selected,
        label = "avatarSelected",
        modifier = modifier.clearAndSetSemantics {},
    ) { isSelected ->
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(if (isSelected) MaterialTheme.colorScheme.primary else container),
            contentAlignment = Alignment.Center,
        ) {
            when {
                isSelected -> Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(size * 0.5f),
                )
                initial != null -> Text(
                    text = initial,
                    style = if (size >= 48.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                    color = content,
                )
                else -> Icon(
                    Icons.Filled.Person,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(size * 0.6f),
                )
            }
        }
    }
}

/** First letter of [name], upper-cased; null when there is no letter to show. */
internal fun avatarInitial(name: String?): String? {
    if (name.isNullOrBlank()) return null
    val index = name.indexOfFirst { it.isLetter() }
    if (index < 0) return null
    val cp = name.codePointAt(index)
    return String(Character.toChars(cp)).uppercase()
}

/** Stable palette slot for [key]; `String.hashCode` is specified, so this survives restarts. */
internal fun avatarPaletteIndex(key: String, paletteSize: Int): Int =
    Math.floorMod(key.hashCode(), paletteSize)

// Container/on-container pairs at M3 container tones (90/10 light, 30/90 dark).
// Deliberately independent of the color scheme: avatars identify senders, so
// they must not collapse to one hue under dynamic color.
private val AvatarPaletteLight = listOf(
    Color(0xFFD8E2FF) to Color(0xFF001A41), // blue
    Color(0xFFC4EED0) to Color(0xFF00210E), // green
    Color(0xFFFFDBCB) to Color(0xFF341100), // orange
    Color(0xFFF3DAFF) to Color(0xFF2B0A3D), // purple
    Color(0xFFB2EBF2) to Color(0xFF001F24), // cyan
    Color(0xFFFFD9E2) to Color(0xFF3E001D), // pink
)
private val AvatarPaletteDark = listOf(
    Color(0xFF004493) to Color(0xFFD8E2FF),
    Color(0xFF0F5223) to Color(0xFFC4EED0),
    Color(0xFF773200) to Color(0xFFFFDBCB),
    Color(0xFF5A3D6B) to Color(0xFFF3DAFF),
    Color(0xFF004F58) to Color(0xFFB2EBF2),
    Color(0xFF7B2949) to Color(0xFFFFD9E2),
)
