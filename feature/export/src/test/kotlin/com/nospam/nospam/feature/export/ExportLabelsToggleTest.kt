package com.nospam.nospam.feature.export

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportLabelsToggleTest {
    @Test fun `toggle flips includeLabels`() {
        val vm = ExportViewModel()
        assertEquals(true, vm.uiState.value.includeLabels)
        vm.onToggleIncludeLabels(false)
        assertEquals(false, vm.uiState.value.includeLabels)
        vm.onToggleIncludeLabels(true)
        assertEquals(true, vm.uiState.value.includeLabels)
    }
}