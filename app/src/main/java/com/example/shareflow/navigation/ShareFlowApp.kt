package com.example.shareflow.navigation

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.shareflow.core.designsystem.theme.ShareFlowTheme
import com.example.shareflow.core.di.LocalAppContainer
import com.example.shareflow.domain.model.AppSettings
import com.example.shareflow.domain.model.ShareFlowModuleId
import com.example.shareflow.domain.model.TransferState
import com.example.shareflow.ui.apps.AppSharingScreen
import com.example.shareflow.ui.discovery.ConnectionDiagnosticsScreen
import com.example.shareflow.ui.files.FileCategoryDetailScreen
import com.example.shareflow.ui.files.FilesHubCategory
import com.example.shareflow.ui.files.FilesScreen
import com.example.shareflow.ui.history.TransferHistoryScreen
import com.example.shareflow.ui.home.HomeDashboardScreen
import com.example.shareflow.ui.media.ImageGalleryScreen
import com.example.shareflow.ui.media.MediaScreen
import com.example.shareflow.ui.media.MusicPlayerScreen
import com.example.shareflow.ui.media.VideoPlayerScreen
import com.example.shareflow.ui.migration.PhoneMigrationScreen
import com.example.shareflow.ui.module.ModuleFoundationScreen
import com.example.shareflow.ui.receive.QrPairingScreen
import com.example.shareflow.ui.receive.ReceiveScreen
import com.example.shareflow.ui.send.SendConfirmScreen
import com.example.shareflow.ui.send.SendScreen
import com.example.shareflow.ui.settings.SettingsScreen
import com.example.shareflow.ui.tools.StorageAnalyzerScreen
import com.example.shareflow.ui.tools.ToolsScreen
import com.example.shareflow.ui.transfer.TransferProgressScreen
import com.example.shareflow.ui.utilities.CompressionScreen
import com.example.shareflow.ui.utilities.DuplicateFinderScreen
import com.example.shareflow.ui.utilities.FavoritesScreen
import com.example.shareflow.ui.utilities.LargeFilesScreen
import com.example.shareflow.ui.utilities.QrScannerScreen
import com.example.shareflow.ui.utilities.RecentFilesScreen
import com.example.shareflow.ui.vault.VaultScreen
import kotlinx.coroutines.launch

@Composable
fun ShareFlowApp(
    navController: NavHostController = rememberNavController()
) {
    val container = LocalAppContainer.current
    val settings by container.settingsRepository.settings.collectAsStateWithLifecycle(
        initialValue = AppSettings()
    )
    val pendingIncoming by container.connectionRepository.pendingIncomingRequest
        .collectAsStateWithLifecycle()
    val approvalScope = androidx.compose.runtime.rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    val activeTransferSessions by container.transferManager.activeSessions
        .collectAsStateWithLifecycle()

    LaunchedEffect(activeTransferSessions) {
        val isTransferring = activeTransferSessions.any { it.state == TransferState.TRANSFERRING }
        container.smartConnectionCoordinator.notifyTransferActivity(isTransferring)
    }

    DisposableEffect(lifecycleOwner, container) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    approvalScope.launch {
                        container.vaultManager.onAppBackgrounded()
                        container.smartConnectionCoordinator.onAppBackgrounded()
                    }
                }
                Lifecycle.Event.ON_START -> {
                    approvalScope.launch { container.vaultManager.checkAutoLock() }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    ShareFlowTheme(
        themeMode = settings.themeMode,
        useDynamicColor = settings.useDynamicColor,
        accentColor = settings.accentColor,
        appLanguage = settings.appLanguage
    ) {
        pendingIncoming?.let { request ->
            com.example.shareflow.ui.discovery.IncomingConnectionApprovalDialog(
                request = request,
                onApprove = { rememberTrusted ->
                    approvalScope.launch {
                        container.connectionRepository.approveIncomingConnection(
                            requestId = request.requestId,
                            rememberAsTrusted = rememberTrusted
                        )
                    }
                },
                onReject = {
                    approvalScope.launch {
                        container.connectionRepository.rejectIncomingConnection(request.requestId)
                    }
                }
            )
        }
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route
        val isTopLevel = TopLevelDestination.entries.any { it.route == currentRoute }

        fun navigateTopLevel(route: String) {
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useNavigationRail = maxWidth >= 600.dp

            Row(modifier = Modifier.fillMaxSize()) {
                if (useNavigationRail && isTopLevel) {
                    NavigationRail(
                        modifier = Modifier.testTag("shareflow_navigation_rail")
                    ) {
                        TopLevelDestination.entries.forEach { destination ->
                            val selected = currentRoute == destination.route
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTopLevel(destination.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                                        contentDescription = stringResource(destination.labelRes)
                                    )
                                },
                                label = { Text(text = stringResource(destination.labelRes)) },
                                modifier = Modifier.testTag(destination.testTag)
                            )
                        }
                    }
                }

                Scaffold(
                    modifier = Modifier.weight(1f),
                    contentWindowInsets = WindowInsets.safeDrawing,
                    bottomBar = {
                        if (!useNavigationRail && isTopLevel) {
                            NavigationBar(
                                modifier = Modifier.testTag("shareflow_navigation_bar")
                            ) {
                                TopLevelDestination.entries.forEach { destination ->
                                    val selected = currentRoute == destination.route
                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = { navigateTopLevel(destination.route) },
                                        icon = {
                                            Icon(
                                                imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                                                contentDescription = stringResource(destination.labelRes)
                                            )
                                        },
                                        label = { Text(text = stringResource(destination.labelRes)) },
                                        modifier = Modifier.testTag(destination.testTag)
                                    )
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    ShareFlowNavHost(
                        navController = navController,
                        onNavigateTopLevel = ::navigateTopLevel,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
private fun ShareFlowNavHost(
    navController: NavHostController,
    onNavigateTopLevel: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    fun navigateToRoute(route: String) {
        if (TopLevelDestination.entries.any { it.route == route }) {
            onNavigateTopLevel(route)
        } else {
            navController.navigate(route) {
                launchSingleTop = true
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = ShareFlowRoutes.HOME,
        modifier = modifier
    ) {
        // Top-level destinations: Home, Files, Media, Tools, Settings
        composable(route = ShareFlowRoutes.HOME) {
            HomeDashboardScreen(onNavigateToRoute = ::navigateToRoute)
        }
        composable(route = ShareFlowRoutes.FILES_HUB) {
            FilesScreen(
                onOpenCategory = { category ->
                    navigateToRoute(ShareFlowRoutes.fileCategoryRoute(category))
                },
                onNavigateToSend = { navigateToRoute(ShareFlowRoutes.SEND) },
                onNavigateToStorageAnalyzer = { navigateToRoute(ShareFlowRoutes.STORAGE_ANALYZER) }
            )
        }
        composable(route = ShareFlowRoutes.MEDIA_HUB) {
            MediaScreen(onNavigateToRoute = ::navigateToRoute)
        }
        composable(route = ShareFlowRoutes.TOOLS_HUB) {
            ToolsScreen(onNavigateToRoute = ::navigateToRoute)
        }
        composable(route = ShareFlowRoutes.SETTINGS) {
            SettingsScreen(
                onOpenVault = { navigateToRoute(ShareFlowRoutes.PRIVATE_VAULT) }
            )
        }

        // Send & Receive flows
        composable(route = ShareFlowRoutes.SEND) {
            SendScreen(
                onNavigateBack = { navController.popBackStack() },
                onContinueToConfirm = { navigateToRoute(ShareFlowRoutes.SEND_CONFIRM) },
                onOpenAppSharing = { navigateToRoute(ShareFlowRoutes.APPLICATION_SHARING) }
            )
        }
        composable(route = ShareFlowRoutes.SEND_CONFIRM) {
            SendConfirmScreen(
                onNavigateBack = { navController.popBackStack() },
                onOpenQrPairing = { navigateToRoute(ShareFlowRoutes.QR_PAIRING) },
                onOpenTransferProgress = { navigateToRoute(ShareFlowRoutes.TRANSFER_PROGRESS) }
            )
        }
        composable(route = ShareFlowRoutes.RECEIVE) {
            ReceiveScreen(
                onNavigateBack = { navController.popBackStack() },
                onOpenQrPairing = { navigateToRoute(ShareFlowRoutes.QR_PAIRING) },
                onOpenTransferProgress = { navigateToRoute(ShareFlowRoutes.TRANSFER_PROGRESS) },
                onOpenDiagnostics = { navigateToRoute(ShareFlowRoutes.CONNECTION_DIAGNOSTICS) }
            )
        }
        composable(route = ShareFlowRoutes.QR_PAIRING) {
            QrPairingScreen(
                onNavigateBack = { navController.popBackStack() },
                onOpenDiagnostics = { navigateToRoute(ShareFlowRoutes.CONNECTION_DIAGNOSTICS) }
            )
        }
        composable(route = ShareFlowRoutes.CONNECTION_DIAGNOSTICS) {
            ConnectionDiagnosticsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(route = ShareFlowRoutes.DISCOVERY) {
            com.example.shareflow.ui.discovery.DeviceDiscoveryScreen(
                onNavigateBack = { navController.popBackStack() },
                onOpenQrPairing = { navigateToRoute(ShareFlowRoutes.QR_PAIRING) },
                showApprovalDialog = false
            )
        }

        // Files Category Detail
        composable(
            route = ShareFlowRoutes.FILE_CATEGORY_PATTERN,
            arguments = listOf(
                navArgument("category") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val rawCategory = backStackEntry.arguments?.getString("category")
            val category = rawCategory?.let {
                runCatching { FilesHubCategory.valueOf(it) }.getOrNull()
            } ?: FilesHubCategory.OTHER_FILES

            FileCategoryDetailScreen(
                category = category,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToSend = { navigateToRoute(ShareFlowRoutes.SEND) }
            )
        }

        // Real Utilities & History
        composable(route = ShareFlowRoutes.STORAGE_ANALYZER) {
            StorageAnalyzerScreen(
                onNavigateBack = { navController.popBackStack() },
                onOpenLargeFiles = { navigateToRoute(ShareFlowRoutes.LARGE_FILES) }
            )
        }
        composable(route = ShareFlowRoutes.TRANSFER_HISTORY) {
            TransferHistoryScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(route = ShareFlowRoutes.TRANSFER_PROGRESS) {
            TransferProgressScreen(
                onNavigateBack = { navController.popBackStack() },
                onOpenSend = { navigateToRoute(ShareFlowRoutes.SEND) },
                onOpenHistory = { navigateToRoute(ShareFlowRoutes.TRANSFER_HISTORY) }
            )
        }
        composable(route = ShareFlowRoutes.APPLICATION_SHARING) {
            AppSharingScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToSendConfirm = { navigateToRoute(ShareFlowRoutes.SEND_CONFIRM) },
                onNavigateToTransferProgress = { navigateToRoute(ShareFlowRoutes.TRANSFER_PROGRESS) }
            )
        }
        composable(route = ShareFlowRoutes.IMAGE_GALLERY) {
            ImageGalleryScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(route = ShareFlowRoutes.VIDEO_PLAYER) {
            VideoPlayerScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(route = ShareFlowRoutes.MUSIC_PLAYER) {
            MusicPlayerScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(route = ShareFlowRoutes.PRIVATE_VAULT) {
            VaultScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(route = ShareFlowRoutes.PHONE_MIGRATION) {
            PhoneMigrationScreen(
                onNavigateBack = { navController.popBackStack() },
                onOpenDiscovery = { navigateToRoute(ShareFlowRoutes.DISCOVERY) },
                onOpenQrPairing = { navigateToRoute(ShareFlowRoutes.QR_PAIRING) },
                onOpenReceive = { navigateToRoute(ShareFlowRoutes.RECEIVE) },
                onOpenTransferProgress = { navigateToRoute(ShareFlowRoutes.TRANSFER_PROGRESS) },
                onOpenHistory = { navigateToRoute(ShareFlowRoutes.TRANSFER_HISTORY) }
            )
        }
        composable(route = ShareFlowRoutes.DUPLICATE_FINDER) {
            DuplicateFinderScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(route = ShareFlowRoutes.LARGE_FILES) {
            LargeFilesScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToSend = { navigateToRoute(ShareFlowRoutes.SEND) }
            )
        }
        composable(route = ShareFlowRoutes.COMPRESSION) {
            CompressionScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(route = ShareFlowRoutes.RECENT_FILES) {
            RecentFilesScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToSend = { navigateToRoute(ShareFlowRoutes.SEND) }
            )
        }
        composable(route = ShareFlowRoutes.FAVORITES) {
            FavoritesScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToSend = { navigateToRoute(ShareFlowRoutes.SEND) }
            )
        }
        composable(route = ShareFlowRoutes.QR_SCANNER) {
            QrScannerScreen(
                onNavigateBack = { navController.popBackStack() },
                onOpenFullQrPairing = { navigateToRoute(ShareFlowRoutes.QR_PAIRING) },
                onOpenDiagnostics = { navigateToRoute(ShareFlowRoutes.CONNECTION_DIAGNOSTICS) }
            )
        }

        // Scaffolded Module Detail
        composable(
            route = ShareFlowRoutes.MODULE_DETAIL_PATTERN,
            arguments = listOf(
                navArgument("moduleId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val rawId = backStackEntry.arguments?.getString("moduleId")
            val moduleId = rawId?.let {
                runCatching { ShareFlowModuleId.valueOf(it) }.getOrNull()
            } ?: ShareFlowModuleId.HOME_DASHBOARD

            ModuleFoundationScreen(
                moduleId = moduleId,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
