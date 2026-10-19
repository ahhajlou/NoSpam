// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.thread

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.IntOffset
import androidx.core.net.toUri
import kotlin.math.roundToInt
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A message body with its links, phone numbers and email addresses tappable,
 * underlined in the text's own color, as Google Messages draws them.
 *
 * @param links where they are, from [findMessageLinks]; none draws plain text
 * @param onLinkLongPress a long press on one of them: the same as a long press
 * anywhere else in the bubble, which selects the message
 * @param menuLink the link whose menu is open, drawn under that link
 */
@Composable
internal fun MessageText(
    body: String,
    links: List<MessageLink>,
    color: Color,
    onLinkClick: (MessageLink) -> Unit,
    onLinkLongPress: () -> Unit,
    menuLink: MessageLink?,
    onDismissMenu: () -> Unit,
    menuItems: @Composable ColumnScope.(MessageLink) -> Unit,
) {
    val currentOnLinkClick by rememberUpdatedState(onLinkClick)
    val currentOnLongPress by rememberUpdatedState(onLinkLongPress)
    val haptics = LocalHapticFeedback.current
    val text = remember(body, links, color) {
        // Links take no tap highlight of their own, so a pressed one shades.
        val styles = TextLinkStyles(
            style = SpanStyle(textDecoration = TextDecoration.Underline),
            pressedStyle = SpanStyle(textDecoration = TextDecoration.Underline, background = color.copy(alpha = 0.2f)),
        )
        buildAnnotatedString {
            append(body)
            for (link in links) {
                addLink(LinkAnnotation.Clickable(link.target, styles) { currentOnLinkClick(link) }, link.start, link.end)
            }
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Box {
        Text(
            text,
            color = color,
            // The message decides its own direction: an English message in a
            // Persian thread would otherwise have its punctuation moved.
            style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
            onTextLayout = { layout = it },
            modifier = if (links.isEmpty()) Modifier else Modifier.longPressOnLinks {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                currentOnLongPress()
            },
        )
        val open = menuLink
        val bounds = open?.let { link -> layout?.getPathForRange(link.start, link.end)?.getBounds() }
        if (open != null && bounds != null) {
            // An anchor the size of the link, so the menu opens at the link
            // rather than under the whole bubble. Layout coordinates run from
            // the left in both directions, hence absolute.
            val density = LocalDensity.current
            Box(
                Modifier
                    .align(AbsoluteAlignment.TopLeft)
                    .absoluteOffset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
                    .size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() }),
            ) {
                DropdownMenu(expanded = true, onDismissRequest = onDismissMenu) { menuItems(open) }
            }
        }
    }
}

/**
 * Hands a long press on a link to [onLongPress] instead of to the link.
 *
 * Compose gives each link a clickable of its own, without a long-press action,
 * and it takes the touch before the bubble's combinedClickable sees it: left
 * alone, a long press on a link opened the link on release, where anywhere else
 * in the bubble it selects the message, as it does on a link in Google
 * Messages. The links are children of the text, so this runs after them in the
 * Main pass, where a consumed down means the touch landed on a link, and before
 * them in the Initial pass, where it takes the rest of the gesture once the
 * long-press timeout passes, so the link never sees the release.
 */
private fun Modifier.longPressOnLinks(onLongPress: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!down.isConsumed) return@awaitEachGesture
        val endedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                // Lifted (a tap, the link's) or dragged (a scroll, the list's).
                if (change == null || !change.pressed ||
                    (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                ) break
            }
        }
        if (endedEarly != null) return@awaitEachGesture
        onLongPress()
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

/**
 * Opens a link from a message. [findMessageLinks] passes only http and https,
 * and only an activity that declares itself browsable may take it, as a browser
 * does: whoever sent the message wrote the link. False when nothing can open it.
 */
internal fun openLink(context: Context, url: String): Boolean =
    startOrFalse(context, Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))

/** A new email to [address] in the user's mail app. False when there is none. */
internal fun composeEmail(context: Context, address: String): Boolean =
    startOrFalse(context, Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", address, null)))

private fun startOrFalse(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
}
