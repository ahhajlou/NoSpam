// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.core.designsystem.component

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.nospam.nospam.core.designsystem.theme.NoSpamTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Written from the task spec, independently of Avatar's own implementation:
 * covers the new photoUri/LocalContactPhotoLoader contract layered on top of
 * the existing letter/icon avatar. See CLAUDE.md §9 for why this runs through
 * Robolectric, and AvatarRobolectricTest for the baseline decorative-letter
 * behaviour this does not repeat.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class AvatarPhotoTest {
    @get:Rule val rule = createComposeRule()

    // `ImageBitmap(width, height)` throws under this project's Robolectric
    // setup (NPE inside android.graphics.Bitmap.createBitmap -- see report);
    // going through a real android.graphics.Bitmap first works.
    private fun fakeImageBitmap(): ImageBitmap =
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap()

    @Test fun `a resolved photo replaces the letter`() {
        val loader = ContactPhotoLoader { _, _ -> fakeImageBitmap() }
        rule.setContent {
            NoSpamTheme {
                CompositionLocalProvider(LocalContactPhotoLoader provides loader) {
                    Avatar(name = "Alice", colorKey = "alice", photoUri = "content://x/1")
                }
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("A", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun `with no loader provided, the letter is shown even with a photoUri set`() {
        rule.setContent {
            NoSpamTheme {
                // LocalContactPhotoLoader defaults to null; no provider here.
                Avatar(name = "Alice", colorKey = "alice", photoUri = "content://x/1")
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("A", useUnmergedTree = true).assertExists()
    }

    @Test fun `when the loader resolves null, the letter is shown`() {
        val loader = ContactPhotoLoader { _, _ -> null }
        rule.setContent {
            NoSpamTheme {
                CompositionLocalProvider(LocalContactPhotoLoader provides loader) {
                    Avatar(name = "Alice", colorKey = "alice", photoUri = "content://x/1")
                }
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("A", useUnmergedTree = true).assertExists()
    }

    @Test fun `when the loader throws, the letter is shown and composition does not crash`() {
        val loader = ContactPhotoLoader { _, _ -> throw RuntimeException("boom") }
        rule.setContent {
            NoSpamTheme {
                CompositionLocalProvider(LocalContactPhotoLoader provides loader) {
                    Avatar(name = "Alice", colorKey = "alice", photoUri = "content://x/1")
                }
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("A", useUnmergedTree = true).assertExists()
    }

    @Test fun `while the loader is still pending, the letter is shown`() {
        val gate = CompletableDeferred<Unit>()
        val loader = ContactPhotoLoader { _, _ -> gate.await(); fakeImageBitmap() }
        rule.setContent {
            NoSpamTheme {
                CompositionLocalProvider(LocalContactPhotoLoader provides loader) {
                    Avatar(name = "Alice", colorKey = "alice", photoUri = "content://x/1")
                }
            }
        }

        rule.onNodeWithText("A", useUnmergedTree = true).assertExists()

        gate.complete(Unit)
        rule.waitForIdle()
        rule.onNodeWithText("A", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun `selected still shows the check mark, not the photo`() {
        val loader = ContactPhotoLoader { _, _ -> fakeImageBitmap() }
        rule.setContent {
            NoSpamTheme {
                CompositionLocalProvider(LocalContactPhotoLoader provides loader) {
                    Avatar(name = "Alice", colorKey = "alice", selected = true, photoUri = "content://x/1")
                }
            }
        }
        rule.waitForIdle()

        // The letter is cleared for the check mark exactly as in the unselected
        // baseline (AvatarRobolectricTest); selection is not overridden by a photo.
        rule.onNodeWithText("A", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun `the loader is asked with the avatar's rendered size in pixels`() {
        val received = mutableListOf<Pair<String, Int>>()
        val loader = ContactPhotoLoader { uri, sizePx -> received += uri to sizePx; fakeImageBitmap() }
        rule.setContent {
            NoSpamTheme {
                CompositionLocalProvider(LocalContactPhotoLoader provides loader) {
                    Avatar(name = "Alice", colorKey = "alice", photoUri = "content://x/1", size = 56.dp)
                }
            }
        }
        rule.waitForIdle()

        assertEquals(1, received.size)
        assertEquals("content://x/1", received.first().first)
        assertTrue("expected a positive pixel size, got ${received.first().second}", received.first().second > 0)
    }
}
