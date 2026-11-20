package com.example.shareflow.domain.model

/**
 * Canonical identifiers for all 17 ShareFlow application modules.
 */
enum class ShareFlowModuleId {
    HOME_DASHBOARD,
    SEND_FILES,
    RECEIVE_FILES,
    DEVICE_DISCOVERY,
    CONNECTION_MANAGER,
    FILE_TRANSFER_ENGINE,
    FILE_MANAGER,
    APPLICATION_SHARING,
    IMAGE_GALLERY,
    VIDEO_PLAYER,
    MUSIC_PLAYER,
    PRIVATE_VAULT,
    PHONE_MIGRATION,
    STORAGE_ANALYZER,
    FILE_COMPRESSION,
    DUPLICATE_FINDER,
    TRANSFER_HISTORY,
    SETTINGS
}

enum class ModuleReadiness {
    READY,
    FOUNDATION_SCAFFOLDED
}

enum class ModuleCategory {
    TRANSFER,
    FILES_AND_UTILITIES,
    MEDIA,
    PRIVACY_AND_SYSTEM
}

data class ShareFlowModuleDescriptor(
    val id: ShareFlowModuleId,
    val category: ModuleCategory,
    val readiness: ModuleReadiness,
    val route: String
)
