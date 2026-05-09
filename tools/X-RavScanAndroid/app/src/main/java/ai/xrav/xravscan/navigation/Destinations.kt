package ai.xrav.xravscan.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.ui.graphics.vector.ImageVector

enum class Destination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
) {
    Dashboard(
        route = "dashboard",
        labelRes = ai.xrav.xravscan.R.string.tab_dashboard,
        icon = Icons.Outlined.Insights,
    ),
    Providers(
        route = "providers",
        labelRes = ai.xrav.xravscan.R.string.tab_providers,
        icon = Icons.Outlined.Storage,
    ),
    Discovery(
        route = "discovery",
        labelRes = ai.xrav.xravscan.R.string.tab_discovery,
        icon = Icons.Outlined.Explore,
    ),
    Results(
        route = "results",
        labelRes = ai.xrav.xravscan.R.string.tab_results,
        icon = Icons.Outlined.ListAlt,
    ),
    Settings(
        route = "settings",
        labelRes = ai.xrav.xravscan.R.string.tab_settings,
        icon = Icons.Outlined.Settings,
    ),
}
