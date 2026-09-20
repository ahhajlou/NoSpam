// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.designsystem.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// DESIGN.md §8: sm=4dp, default=8dp, md=12dp, lg=16dp, xl=24dp, full=CIRCLE
val NoSpamShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

// Extra aliases for semantic use
val ShapeFull = CircleShape

// Bubbles use the `lg` radius with the sender-side bottom corner sharpened to
// `sm`. start/end corners, so the sharp corner follows the sender in RTL too.
val MessageBubbleShapeIncoming = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 4.dp)
val MessageBubbleShapeOutgoing = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 4.dp, bottomStart = 16.dp)
