// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test

class TokensTest {
    @Test fun `light scheme uses Stitch seed tokens`() {
        assertEquals(Color(0xFF005BBF), LightColors.primary)
        assertEquals(Color(0xFFF8F9FA), LightColors.background)
        assertEquals(Color(0xFFBA1A1A), LightColors.error)
        assertEquals(md_light_primary, LightColors.primary)
        assertEquals(md_light_surfaceContainerHighest, LightColors.surfaceContainerHighest)
    }

    @Test fun `dark scheme differs from light`() {
        assertNotEquals(LightColors.primary, DarkColors.primary)
        assertNotEquals(LightColors.background, DarkColors.background)
        assertEquals(md_dark_primary, DarkColors.primary)
    }

    @Test fun `typography follows DESIGN tokens`() {
        assertEquals(28, NoSpamTypography.headlineLarge.fontSize.value.toInt())
        assertEquals(16, NoSpamTypography.bodyLarge.fontSize.value.toInt())
        // DESIGN.md label-lg is M3 labelMedium; labelLarge is the 14sp button role.
        assertEquals(12, NoSpamTypography.labelMedium.fontSize.value.toInt())
        assertEquals(14, NoSpamTypography.labelLarge.fontSize.value.toInt())
    }

    @Test fun `every type role is defined explicitly, not left to library defaults`() {
        val defaults = androidx.compose.material3.Typography()
        val ours = NoSpamTypography
        listOf(
            ours.displayLarge to defaults.displayLarge, ours.displayMedium to defaults.displayMedium,
            ours.displaySmall to defaults.displaySmall, ours.headlineLarge to defaults.headlineLarge,
            ours.headlineMedium to defaults.headlineMedium, ours.headlineSmall to defaults.headlineSmall,
            ours.titleLarge to defaults.titleLarge, ours.titleMedium to defaults.titleMedium,
            ours.titleSmall to defaults.titleSmall, ours.bodyLarge to defaults.bodyLarge,
            ours.bodyMedium to defaults.bodyMedium, ours.bodySmall to defaults.bodySmall,
            ours.labelLarge to defaults.labelLarge, ours.labelMedium to defaults.labelMedium,
            ours.labelSmall to defaults.labelSmall,
        ).forEach { (mine, library) ->
            // Library defaults use Material's own typeface token; every role here must
            // carry an app family instead, so a later font swap reaches all of them.
            assertNotEquals(library.fontFamily, mine.fontFamily)
            assertEquals(androidx.compose.ui.text.font.FontFamily.Default, mine.fontFamily)
        }
    }

    @Test fun `dark surface sits below its containers`() {
        fun lum(c: Color) = c.luminance()
        assertTrue(lum(DarkColors.surface) < lum(DarkColors.surfaceContainerLow))
        assertTrue(lum(DarkColors.surfaceContainerLow) < lum(DarkColors.surfaceContainer))
        assertTrue(lum(DarkColors.surfaceContainer) < lum(DarkColors.surfaceContainerHigh))
        assertTrue(lum(DarkColors.surfaceContainerHigh) < lum(DarkColors.surfaceContainerHighest))
        assertEquals(DarkColors.surface, DarkColors.background)
    }

    @Test fun `shapes follow DESIGN radii`() {
        assertEquals(NoSpamShapes.small, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
        assertEquals(NoSpamShapes.extraLarge, androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
        assertEquals(MessageBubbleShapeIncoming.topStart, MessageBubbleShapeOutgoing.topEnd)
    }
}
