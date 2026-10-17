// SPDX-License-Identifier: GPL-3.0-or-later

package com.nospam.nospam.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.nospam.nospam.core.designsystem.component.NoSpamTopAppBar
import com.nospam.nospam.core.designsystem.component.SettingsItem
import com.nospam.nospam.core.designsystem.component.SettingsSectionHeader
import com.nospam.nospam.core.designsystem.component.TopBarNavigation

private const val HAZM_KEY = "hazm"

/**
 * Settings → About → Open-source licenses: hazm's notice, the crates in the Rust core and
 * the Android and Kotlin libraries, each opening its license text.
 *
 * [loadLicenses] reads the generated license data and is called once, off the main
 * thread by its caller; nothing here runs until the page is opened. A lazy list, since
 * the page lists about 150 entries.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(
    loadLicenses: suspend () -> OpenSourceLicenses,
    onNavigateUp: () -> Unit = {},
) {
    val licenses by produceState<OpenSourceLicenses?>(null) { value = loadLicenses() }
    // Which entry's license is open: "hazm", or "rust/<name>/<version>" and "android/...".
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            NoSpamTopAppBar(
                title = stringResource(R.string.about_licenses),
                navigation = TopBarNavigation.Back(onNavigateUp),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().consumeWindowInsets(padding),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
        ) {
            item { SettingsSectionHeader(stringResource(R.string.licenses_section_data)) }
            item {
                SettingsItem(
                    title = "hazm",
                    supportingText = stringResource(R.string.licenses_hazm_summary),
                    onClick = { open = HAZM_KEY },
                )
            }
            val loaded = licenses
            if (loaded == null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else {
                componentSection(R.string.licenses_section_rust, "rust", loaded.rustCrates) { open = it }
                componentSection(R.string.licenses_section_android, "android", loaded.androidLibraries) { open = it }
            }
        }
    }

    when (val key = open) {
        null -> Unit
        HAZM_KEY -> HazmNoticeDialog(onDismiss = { open = null })
        else -> licenses?.find(key)?.let { LicenseDialog(it, onDismiss = { open = null }) }
    }
}

private fun keyOf(section: String, component: LicensedComponent) =
    "$section/${component.name}/${component.version}"

private fun OpenSourceLicenses.find(key: String): LicensedComponent? =
    rustCrates.firstOrNull { keyOf("rust", it) == key } ?: androidLibraries.firstOrNull { keyOf("android", it) == key }

private fun androidx.compose.foundation.lazy.LazyListScope.componentSection(
    title: Int,
    section: String,
    components: List<LicensedComponent>,
    onOpen: (String) -> Unit,
) {
    item { SettingsSectionHeader(stringResource(title)) }
    items(components, key = { keyOf(section, it) }) { component ->
        SettingsItem(
            title = listOfNotNull(component.name, component.version).joinToString(" "),
            supportingText = component.license,
            onClick = { onOpen(keyOf(section, component)) },
        )
    }
}

@Composable
private fun LicenseDialog(component: LicensedComponent, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(listOfNotNull(component.name, component.version).joinToString(" ")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                component.authors?.let {
                    Text(stringResource(R.string.licenses_authors, it))
                    Spacer(Modifier.height(12.dp))
                }
                LicenseText(component.text)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

/**
 * hazm's MIT notice: the spam filter's Rust core compiles in normalization rules derived
 * from hazm (persian-sms-spam's `core/spam-core/rules/hazm.json`). `res/raw/hazm_license.txt`
 * is that repository's `HAZM-LICENSE.txt`, one line per paragraph; HazmLicenseTest keeps the
 * two the same.
 */
@Composable
private fun HazmNoticeDialog(onDismiss: () -> Unit) {
    val resources = LocalResources.current
    // A raw resource is read from the APK by AssetManager: about 1 KB, no disk read.
    val notice = remember(resources) {
        resources.openRawResource(R.raw.hazm_license).bufferedReader().use { it.readText() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("hazm") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.licenses_hazm_intro))
                Spacer(Modifier.height(12.dp))
                LicenseText(notice)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

/** License texts are English and stay untranslated, so always left to right. */
@Composable
private fun LicenseText(text: String) {
    val shown = remember(text) { reflowParagraphs(text) }
    Text(shown, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
}

/**
 * Joins the line breaks inside each paragraph and keeps the blank lines between them.
 * License files wrap at about 80 columns, which a narrower dialog would wrap again
 * mid-sentence. Only what is shown changes; the stored text stays as the file has it.
 */
internal fun reflowParagraphs(text: String): String =
    text.trim().split(Regex("\\n\\s*\\n")).joinToString("\n\n") { paragraph ->
        paragraph.lines().joinToString(" ") { it.trim() }.replace(Regex(" {2,}"), " ")
    }
