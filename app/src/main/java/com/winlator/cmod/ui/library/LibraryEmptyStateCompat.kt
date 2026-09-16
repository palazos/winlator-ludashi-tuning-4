package com.winlator.cmod.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.cmod.MainActivity
import com.winlator.cmod.R

@Composable
internal fun LibraryRootWithoutEmptyDescription(
    items: List<LibraryItem>,
    grid: Boolean,
    query: String,
    selectedShortcutPath: MutableState<String?>,
    callbacks: LibraryCallbacks
) {
    if (items.isNotEmpty() || query.isNotBlank()) {
        LibraryRoot(items, grid, query, selectedShortcutPath, callbacks)
        return
    }

    val activity = LocalContext.current as? MainActivity
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp

    // The bottom navigation (Library/Containers/Input Controls/Settings) is hidden
    // in landscape in favor of a per-screen Compose header. LibraryRoot renders its
    // own header for that, but this empty-state screen bypasses LibraryRoot entirely,
    // so without this it left no way to reach Settings (or anything else) while the
    // library has no shortcuts yet and the device is in landscape.
    DisposableEffect(activity, landscape) {
        activity?.setBottomNavigationVisible(!landscape)
        activity?.setMainToolbarVisible(!landscape)
        onDispose {
            val stillOnLibrary = activity?.supportFragmentManager
                ?.findFragmentById(R.id.FLFragmentContainer) is com.winlator.cmod.ShortcutsFragment
            if (!stillOnLibrary) {
                activity?.setBottomNavigationVisible(true)
                activity?.setMainToolbarVisible(true)
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 14.dp)
    ) {
        if (landscape) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Library",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.weight(1f))
                LibraryTopIcon(Icons.Outlined.Home, true) {}
                LibraryTopIcon(Icons.Outlined.SportsEsports, false) { activity?.navigateToMainDestination(R.id.main_menu_input_controls) }
                LibraryTopIcon(Icons.Outlined.Settings, false) { activity?.navigateToMainDestination(R.id.main_menu_settings) }
                LibraryOrientationMenu(activity)
            }
            Spacer(Modifier.height(7.dp))
        }
        Row(
            Modifier.padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            EmptyFilterChip("All", true)
            EmptyFilterChip("Favorites", false)
            EmptyFilterChip("Recent", false)
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Row(
                modifier = Modifier.fillMaxWidth(0.90f).widthIn(max = 520.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                EmptyStateActionCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.Add,
                    label = "Add games",
                    onClick = { activity?.navigateToMainDestination(R.id.main_menu_file_manager) }
                )
                EmptyStateActionCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.Search,
                    label = "Scan games",
                    onClick = { callbacks.onScanGames() }
                )
            }
        }
    }
}

@Composable
internal fun EmptyStateActionCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                modifier = Modifier.size(72.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, modifier = Modifier.size(34.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                label,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
private fun EmptyFilterChip(label: String, selected: Boolean) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.surfaceVariant else androidx.compose.ui.graphics.Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge
        )
    }
}
