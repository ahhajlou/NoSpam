package com.nospam.nospam.core.designsystem.theme

import androidx.compose.ui.graphics.Color
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
        assertEquals(12, NoSpamTypography.labelLarge.fontSize.value.toInt())
    }

    @Test fun `shapes follow DESIGN radii`() {
        assertEquals(NoSpamShapes.small, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
        assertEquals(NoSpamShapes.extraLarge, androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
        assertEquals(MessageBubbleShapeIncoming.topStart, MessageBubbleShapeOutgoing.topEnd)
    }
}
