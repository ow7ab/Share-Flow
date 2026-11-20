package com.example.shareflow.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PermMedia
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.R
import com.example.shareflow.domain.model.ModuleCategory
import com.example.shareflow.domain.model.ModuleReadiness
import com.example.shareflow.domain.model.ShareFlowModuleDescriptor
import com.example.shareflow.domain.model.ShareFlowModuleId
import com.example.shareflow.ui.files.FilesHubCategory

/**
 * Unique Navigation Compose route constants for ShareFlow screens and modules.
 */
object ShareFlowRoutes {
    const val HOME = "home_dashboard"
    const val FILES_HUB = "files_hub"
    const val MEDIA_HUB = "media_hub"
    const val TOOLS_HUB = "tools_hub"
    const val SETTINGS = "settings"

    const val SEND = "send_files"
    const val SEND_CONFIRM = "send_confirm"
    const val RECEIVE = "receive_files"
    const val QR_PAIRING = "qr_pairing"
    const val DISCOVERY = "device_discovery"
    const val STORAGE_ANALYZER = "storage_analyzer"
    const val TRANSFER_HISTORY = "transfer_history"
    const val TRANSFER_PROGRESS = "transfer_progress"
    const val APPLICATION_SHARING = "application_sharing"
    const val IMAGE_GALLERY = "image_gallery"
    const val VIDEO_PLAYER = "video_player"
    const val MUSIC_PLAYER = "music_player"
    const val PRIVATE_VAULT = "private_vault"
    const val PHONE_MIGRATION = "phone_migration"
    const val DUPLICATE_FINDER = "duplicate_finder"
    const val LARGE_FILES = "large_files"
    const val COMPRESSION = "compression"
    const val RECENT_FILES = "recent_files"
    const val FAVORITES = "favorites"
    const val QR_SCANNER = "qr_scanner"
    const val CONNECTION_DIAGNOSTICS = "connection_diagnostics"

    const val FILE_CATEGORY_PATTERN = "file_category/{category}"
    const val MODULE_DETAIL_PATTERN = "module_foundation/{moduleId}"

    fun fileCategoryRoute(category: FilesHubCategory): String =
        "file_category/${category.name}"

    fun moduleDetailRoute(moduleId: ShareFlowModuleId): String =
        "module_foundation/${moduleId.name}"
}

enum class TopLevelDestination(
    val route: String,
    @param:StringRes val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
) {
    HOME(
        route = ShareFlowRoutes.HOME,
        labelRes = R.string.nav_home,
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Outlined.Home,
        testTag = "nav_item_home"
    ),
    FILES(
        route = ShareFlowRoutes.FILES_HUB,
        labelRes = R.string.nav_files,
        selectedIcon = Icons.Filled.Folder,
        unselectedIcon = Icons.Outlined.Folder,
        testTag = "nav_item_files"
    ),
    MEDIA(
        route = ShareFlowRoutes.MEDIA_HUB,
        labelRes = R.string.nav_media,
        selectedIcon = Icons.Filled.PermMedia,
        unselectedIcon = Icons.Outlined.PermMedia,
        testTag = "nav_item_media"
    ),
    TOOLS(
        route = ShareFlowRoutes.TOOLS_HUB,
        labelRes = R.string.nav_tools,
        selectedIcon = Icons.Filled.Build,
        unselectedIcon = Icons.Outlined.Build,
        testTag = "nav_item_tools"
    ),
    SETTINGS(
        route = ShareFlowRoutes.SETTINGS,
        labelRes = R.string.nav_settings,
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
        testTag = "nav_item_settings"
    )
}

data class ModuleUiCatalogEntry(
    val descriptor: ShareFlowModuleDescriptor,
    @param:StringRes val titleRes: Int,
    @param:StringRes val descriptionRes: Int,
    val icon: ImageVector
)

object ShareFlowModuleCatalog {
    val entries: List<ModuleUiCatalogEntry> = listOf(
        ModuleUiCatalogEntry(
            descriptor = ShareFlowModuleDescriptor(
                id = ShareFlowModuleId.SEND_FILES,
                category = ModuleCategory.TRANSFER,
                readiness = ModuleReadiness.READY,
                route = ShareFlowRoutes.SEND
            ),
            titleRes = R.string.module_send_title,
            descriptionRes = R.string.module_send_desc,
            icon = Icons.AutoMirrored.Filled.Send
        ),
        ModuleUiCatalogEntry(
            descriptor = ShareFlowModuleDescriptor(
                id = ShareFlowModuleId.RECEIVE_FILES,
                category = ModuleCategory.TRANSFER,
                readiness = ModuleReadiness.READY,
                route = ShareFlowRoutes.RECEIVE
            ),
            titleRes = R.string.module_receive_title,
            descriptionRes = R.string.module_receive_desc,
            icon = Icons.Filled.CloudDownload
        ),
        ModuleUiCatalogEntry(
            descriptor = ShareFlowModuleDescriptor(
                id = ShareFlowModuleId.DEVICE_DISCOVERY,
                category = ModuleCategory.TRANSFER,
                readiness = ModuleReadiness.READY,
                route = ShareFlowRoutes.DISCOVERY
            ),
            titleRes = R.string.module_discovery_title,
            descriptionRes = R.string.module_discovery_desc,
            icon = Icons.Filled.Radar
        ),
        ModuleUiCatalogEntry(
            descriptor = ShareFlowModuleDescriptor(
                id = ShareFlowModuleId.CONNECTION_MANAGER,
                category = ModuleCategory.TRANSFER,
                readiness = ModuleReadiness.READY,
                route = ShareFlowRoutes.DISCOVERY
            ),
            titleRes = R.string.module_connection_title,
            descriptionRes = R.string.module_connection_desc,
            icon = Icons.Filled.Devices
        ),
        ModuleUiCatalogEntry(
            descriptor = ShareFlowModuleDescriptor(
                id = ShareFlowModuleId.FILE_TRANSFER_ENGINE,
                category = ModuleCategory.TRANSFER,
                readiness = ModuleReadiness.READY,
                route = ShareFlowRoutes.TRANSFER_PROGRESS
            ),
            titleRes = R.string.module_transfer_engine_title,
            descriptionRes = R.string.module_transfer_engine_desc,
            icon = Icons.Filled.SyncAlt
        ),
        ModuleUiCatalogEntry(
            descriptor = ShareFlowModuleDescriptor(
                id = ShareFlowModuleId.PHONE_MIGRATION,
                category = ModuleCategory.TRANSFER,
                readiness = ModuleReadiness.READY,
                route = ShareFlowRoutes.PHONE_MIGRATION
            ),
            titleRes = R.string.module_phone_migration_title,
            descriptionRes = R.string.module_phone_migration_desc,
            icon = Icons.Filled.SwapHoriz
        ),
        ModuleUiCatalogEntry(
            descriptor = ShareFlowModuleDescriptor(
                id = ShareFlowModuleId.FILE_MANAGER,
                category = ModuleCategory.FILES_AND_UTILITIES,
                readiness = ModuleReadiness.READY,
                route = ShareFlowRoutes.FILES_HUB
            ),
            titleRes = R.string.module_file_manager_title,
            descriptionRes = R.string.module_file_manager_desc,
            icon = Icons.Filled.Folder
        ),
        ModuleUiCatalogEntry(
            descriptor = ShareFlowModuleDescriptor(
                id = ShareFlowModuleId.APPLICATION_SHARING,
                category = ModuleCategory.FILES_AND_UTILITIES,
                readiness = ModuleReadiness.READY,
                route = ShareFlowRoutes.APPLICATION_SHARING
            ),
            titleRes = R.string.module_app_sharing_title,
            descriptionRes = R.string.module_app_sharing_desc,
            icon = Icons.Filled.InstallMobile
        ),
        ModuleUiCatalogEntry(
            descriptor = ShareFlowModuleDescriptor(
                id = ShareFlowModuleId.STORAGE_ANALYZER,
                category = ModuleCategory.FILES_AND_UTILITIES,
                readiness = ModuleReadiness.READY,
                route = ShareFlowRoutes.STORAGE_ANALYZER
            ),
            titleRes = R.string.module_storage_analyzer_title,
            descriptionRes = R.string.module_storage_analyzer_desc,
            icon = Icons.Filled.Storage
        ),
        ModuleUiCatalogEntry(
            descriptor = ShareFlowModuleDescriptor(
                id = ShareFlowModuleId.FILE_COMPRESSION,
                category = ModuleCategory.FILES_AND_UTILITIES,
                readiness = ModuleReadiness.READY,
                route = ShareFlowRoutes.COMPRESSION
            ),
            titleRes = R.string.module_file_compression_title,
            descriptionRes = R.string.module_file_compression_desc,
            icon = Icons.Filled.Archive
        ),
        ModuleUiCatalogEntry(
            descriptor = ShareFlowModuleDescriptor(
                id = ShareFlowModuleId.DUPLICATE_FINDER,
                category = ModuleCategory.FILES_AND_UTILITIES,
                readiness = ModuleReadiness.READY,
                route = ShareFlowRoutes.DUPLICATE_FINDER
            ),
            titleRes = R.string.module_duplicate_finder_title,
            descriptionRes = R.string.module_duplicate_finder_desc,
            icon = Icons.Filled.ContentCopy
        ),
        ModuleUiCatalogEntry(
            descriptor = ShareFlowModuleDescriptor(
                id = ShareFlowModuleId.PRIVATE_VAULT,
                category = ModuleCategory.PRIVACY_AND_SYSTEM,
                readiness = ModuleReadiness.READY,
                route = ShareFlowRoutes.PRIVATE_VAULT
            ),
            titleRes = R.string.module_private_vault_title,
            descriptionRes = R.string.module_private_vault_desc,
            icon = Icons.Filled.Lock
        ),
        ModuleUiCatalogEntry(
            descriptor = ShareFlowModuleDescriptor(
                id = ShareFlowModuleId.IMAGE_GALLERY,
                category = ModuleCategory.MEDIA,
                readiness = ModuleReadiness.READY,
                route = ShareFlowRoutes.IMAGE_GALLERY
            ),
            titleRes = R.string.module_image_gallery_title,
            descriptionRes = R.string.module_image_gallery_desc,
            icon = Icons.Filled.PhotoLibrary
        ),
        ModuleUiCatalogEntry(
            descriptor = ShareFlowModuleDescriptor(
                id = ShareFlowModuleId.VIDEO_PLAYER,
                category = ModuleCategory.MEDIA,
                readiness = ModuleReadiness.READY,
                route = ShareFlowRoutes.VIDEO_PLAYER
            ),
            titleRes = R.string.module_video_player_title,
            descriptionRes = R.string.module_video_player_desc,
            icon = Icons.Filled.PlayCircle
        ),
        ModuleUiCatalogEntry(
            descriptor = ShareFlowModuleDescriptor(
                id = ShareFlowModuleId.MUSIC_PLAYER,
                category = ModuleCategory.MEDIA,
                readiness = ModuleReadiness.READY,
                route = ShareFlowRoutes.MUSIC_PLAYER
            ),
            titleRes = R.string.module_music_player_title,
            descriptionRes = R.string.module_music_player_desc,
            icon = Icons.Filled.LibraryMusic
        ),
        ModuleUiCatalogEntry(
            descriptor = ShareFlowModuleDescriptor(
                id = ShareFlowModuleId.TRANSFER_HISTORY,
                category = ModuleCategory.PRIVACY_AND_SYSTEM,
                readiness = ModuleReadiness.READY,
                route = ShareFlowRoutes.TRANSFER_HISTORY
            ),
            titleRes = R.string.module_transfer_history_title,
            descriptionRes = R.string.module_transfer_history_desc,
            icon = Icons.Filled.History
        ),
        ModuleUiCatalogEntry(
            descriptor = ShareFlowModuleDescriptor(
                id = ShareFlowModuleId.SETTINGS,
                category = ModuleCategory.PRIVACY_AND_SYSTEM,
                readiness = ModuleReadiness.READY,
                route = ShareFlowRoutes.SETTINGS
            ),
            titleRes = R.string.module_settings_title,
            descriptionRes = R.string.module_settings_desc,
            icon = Icons.Filled.Settings
        )
    )

    fun findById(id: ShareFlowModuleId): ModuleUiCatalogEntry? =
        entries.firstOrNull { it.descriptor.id == id }

    fun byCategory(category: ModuleCategory): List<ModuleUiCatalogEntry> =
        entries.filter { it.descriptor.category == category }
}
