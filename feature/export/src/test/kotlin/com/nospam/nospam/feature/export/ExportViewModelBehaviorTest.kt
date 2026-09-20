// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.export

import android.content.Context
import android.net.Uri
import com.nospam.nospam.core.data.ExportRepository
import com.nospam.nospam.core.model.Message
import com.nospam.nospam.core.model.MessageId
import com.nospam.nospam.core.model.MessageType
import com.nospam.nospam.core.model.ThreadId
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Behaviour tests for ExportViewModel per feature-export.md.
 *
 * `export()` fires a real `viewModelScope.launch(Dispatchers.IO)` and its
 * terminal state updates run via `withContext(Dispatchers.Main)` — a real,
 * un-injected dispatcher pair, not the DispatcherProvider seam other modules
 * use. That means a virtual-time TestDispatcher can't safely observe
 * completion (nothing pumps the real IO dispatcher), so these tests set
 * `Dispatchers.Main` to `Dispatchers.Unconfined` and wait for a terminal
 * `uiState` value via `Flow.first`, which suspends until the real background
 * work actually publishes it rather than racing a fixed advance/sleep.
 *
 * Context/Uri/ExportRepository are mocked with mockk: Context/Uri are Android
 * framework surface with no fake in core:testing, and ExportRepository is a
 * concrete class with no fake either — this module already carries mockk plus
 * the dynamic agent-loading jvmArgs needed to mock final classes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExportViewModelBehaviorTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun message(id: Long, address: String, body: String, date: Long) = Message(
        MessageId(id), ThreadId(1), address, body, date, MessageType.INBOX, true,
    )

    private fun mockContext(getStringValue: String = "no messages"): Context {
        val context = mockk<Context>()
        every { context.getString(R.string.export_empty) } returns getStringValue
        return context
    }

    /** Waits for export()'s fire-and-forget coroutine to reach a terminal state. */
    private fun awaitTerminal(vm: ExportViewModel) = runBlocking {
        withTimeout(5_000) { vm.uiState.first { it.error != null || it.successFile != null } }
    }

    @Test
    fun `loadInstallId calls the provider only once even when called twice`() = runTest {
        var calls = 0
        val vm = ExportViewModel(repository = null, installIdProvider = { calls++; "id-$calls" })

        vm.loadInstallId()
        vm.loadInstallId()

        assertEquals(1, calls)
        assertEquals("id-1", vm.uiState.value.installId)
    }

    @Test
    fun `clearStatus resets only error and successFile`() {
        val vm = ExportViewModel(repository = null, installIdProvider = { "id" })
        vm.onToggleIncludeLabels(false)
        // Drive the null-repository error branch so error is non-default before clearing.
        vm.export(mockContext(), mockk<Uri>(relaxed = true))
        val settled = awaitTerminal(vm)
        assertTrue(settled.error != null)

        vm.clearStatus()
        val state = vm.uiState.value
        assertNull(state.error)
        assertNull(state.successFile)
        assertEquals(false, state.includeLabels)
        assertEquals("id", state.installId)
    }

    @Test
    fun `export with a null repository takes the empty-messages error branch`() {
        val vm = ExportViewModel(repository = null, installIdProvider = { "id" })
        val context = mockContext("nothing to export")

        vm.export(context, mockk<Uri>(relaxed = true))
        val state = awaitTerminal(vm)

        assertEquals(false, state.isExporting)
        assertEquals("nothing to export", state.error)
        assertNull(state.successFile)
    }

    @Test
    fun `export with an empty corpus takes the same empty-messages error branch`() {
        val repository = mockk<ExportRepository> {
            coEvery { allMessages() } returns emptyList()
        }
        val vm = ExportViewModel(repository = repository, installIdProvider = { "id" })
        val context = mockContext("nothing to export")

        vm.export(context, mockk<Uri>(relaxed = true))
        val state = awaitTerminal(vm)

        assertEquals(false, state.isExporting)
        assertEquals("nothing to export", state.error)
        assertEquals(0, state.exportedCount)
    }

    @Test
    fun `successful export resolves installId via the fallback without loadInstallId being called`() {
        val repository = mockk<ExportRepository> {
            coEvery { allMessages() } returns listOf(message(1, "111", "hi", 1000L))
            coEvery { labelFor(any()) } returns null
        }
        val vm = ExportViewModel(repository = repository, installIdProvider = { "fallback-id" })

        val output = ByteArrayOutputStream()
        val context = mockContext()
        val uri = mockk<Uri> { every { lastPathSegment } returns "export.jsonl" }
        every { context.contentResolver } returns mockk {
            every { openOutputStream(uri) } returns output
        }

        vm.export(context, uri)
        val state = awaitTerminal(vm)

        assertEquals(false, state.isExporting)
        assertNull(state.error)
        assertEquals(1, state.exportedCount)
        assertEquals("export.jsonl", state.successFile)
        assertEquals("fallback-id", state.installId)
        assertTrue(output.toString(Charsets.UTF_8.name()).contains("fallback-id"))
    }

    @Test
    fun `successful export falls back to the uri string when there is no path segment`() {
        val repository = mockk<ExportRepository> {
            coEvery { allMessages() } returns listOf(message(1, "111", "hi", 1000L))
            coEvery { labelFor(any()) } returns null
        }
        val vm = ExportViewModel(repository = repository, installIdProvider = { "id" })

        val output = ByteArrayOutputStream()
        val context = mockContext()
        val uri = mockk<Uri>()
        every { uri.lastPathSegment } returns null
        every { uri.toString() } returns "content://no-segment"
        every { context.contentResolver } returns mockk {
            every { openOutputStream(uri) } returns output
        }

        vm.export(context, uri)
        val state = awaitTerminal(vm)

        assertEquals("content://no-segment", state.successFile)
    }

    @Test
    fun `export surfaces an explicit message when the output stream cannot be opened`() {
        val repository = mockk<ExportRepository> {
            coEvery { allMessages() } returns listOf(message(1, "111", "hi", 1000L))
        }
        val vm = ExportViewModel(repository = repository, installIdProvider = { "id" })

        val context = mockContext()
        val uri = mockk<Uri>(relaxed = true)
        every { context.contentResolver } returns mockk {
            every { openOutputStream(uri) } returns null
        }

        vm.export(context, uri)
        val state = awaitTerminal(vm)

        assertTrue(state.error != null && state.error!!.contains("Cannot open output stream"))
    }

    @Test
    fun `export surfaces the exception message when writing fails`() {
        // openOutputStream throwing exercises the outer try/catch just as a
        // failing labelProvider would; both land in the same catch branch.
        val repository = mockk<ExportRepository> {
            coEvery { allMessages() } returns listOf(message(1, "111", "hi", 1000L))
        }
        val vm = ExportViewModel(repository = repository, installIdProvider = { "id" })

        val context = mockContext()
        val uri = mockk<Uri>(relaxed = true)
        every { context.contentResolver } returns mockk {
            every { openOutputStream(uri) } throws IllegalStateException("disk full")
        }

        vm.export(context, uri)
        val state = awaitTerminal(vm)

        assertEquals("disk full", state.error)
    }
}
