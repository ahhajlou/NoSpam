package com.nospam.nospam.navigation

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Upload
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.NavHostController
import com.nospam.nospam.AppContainer
import com.nospam.nospam.R
import com.nospam.nospam.core.designsystem.component.DrawerDestinationScaffold
import com.nospam.nospam.feature.export.ExportScreen
import com.nospam.nospam.feature.export.ExportViewModel
import com.nospam.nospam.feature.mldebug.MlDebugScreen
import com.nospam.nospam.feature.mldebug.MlDebugViewModel
import com.nospam.nospam.feature.settings.SpamPreferences
import kotlinx.serialization.Serializable

@Serializable object ExportRoute
@Serializable object MlDebugRoute

/** Debug builds surface both developer tools in the drawer. */
val debugTools: List<DebugTool> = listOf(
    DebugTool(
        labelRes = R.string.drawer_export,
        icon = Icons.Filled.Upload,
        routeTag = "Export",
        navigate = { it.navigate(ExportRoute) { launchSingleTop = true } },
    ),
    DebugTool(
        labelRes = R.string.drawer_mldebug,
        icon = Icons.Filled.Science,
        routeTag = "MlDebug",
        navigate = { it.navigate(MlDebugRoute) { launchSingleTop = true } },
    ),
)

fun NavGraphBuilder.debugToolDestinations(container: AppContainer?, context: Context, onOpenDrawer: () -> Unit) {
    composable<ExportRoute> {
        val vm: ExportViewModel = viewModel(
            factory = vmFactory {
                container?.let {
                    ExportViewModel(
                        repository = it.exportRepository,
                        installIdProvider = { SpamPreferences.installId(context) },
                    )
                } ?: ExportViewModel()
            }
        )
        DrawerDestinationScaffold(stringResource(R.string.drawer_export), onOpenDrawer) {
            ExportScreen(viewModel = vm)
        }
    }
    composable<MlDebugRoute> {
        val vm: MlDebugViewModel = viewModel(
            factory = vmFactory {
                container?.let { MlDebugViewModel(it.classifier) } ?: MlDebugViewModel()
            }
        )
        DrawerDestinationScaffold(stringResource(R.string.drawer_mldebug), onOpenDrawer) {
            MlDebugScreen(viewModel = vm)
        }
    }
}
