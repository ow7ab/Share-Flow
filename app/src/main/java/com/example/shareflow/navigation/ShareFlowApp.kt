package com.example.shareflow.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.example.shareflow.core.designsystem.theme.ShareFlowBrandColors
import com.example.shareflow.core.designsystem.theme.ShareFlowBrandGradients
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
    val pendingTransferOffer by container.unifiedConnectionManager.pendingIncomingOffer
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
        pendingTransferOffer?.let { offer ->
            com.example.shareflow.ui.receive.IncomingTransferOfferDialog(
                offer = offer,
                onAccept = {
                    container.unifiedConnectionManager.approveIncomingTransferOffer(offer.sessionId)
                },
                onReject = {
                    container.unifiedConnectionManager.rejectIncomingTransferOffer(offer.sessionId)
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

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            val useNavigationRail = maxWidth >= 600.dp

            Row(modifier = Modifier.fillMaxSize()) {
                if (useNavigationRail && isTopLevel) {
                    NavigationRail(
                        containerColor = ShareFlowBrandColors.GlassNavBackground,
                        contentColor = ShareFlowBrandColors.SoftCream,
                        modifier = Modifier.testTag("shareflow_navigation_rail")
                    ) {
                        TopLevelDestination.entries.forEach { destination ->
                            val selected = currentRoute == destination.route
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTopLevel(destination.route) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = ShareFlowBrandColors.SoftCream,
                                    selectedTextColor = ShareFlowBrandColors.SoftCream,
                                    indicatorColor = ShareFlowBrandColors.RoyalBlue,
                                    unselectedIconColor = ShareFlowBrandColors.SkyBlue.copy(alpha = 0.80f),
                                    unselectedTextColor = ShareFlowBrandColors.SkyBlue.copy(alpha = 0.78f)
                                ),
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
                    containerColor = MaterialTheme.colorScheme.background,
                    contentWindowInsets = WindowInsets.safeDrawing,
                    bottomBar = {
                        if (!useNavigationRail && isTopLevel) {
                            ShareFlowFloatingGlassNavBar(
                                currentRoute = currentRoute,
                                onNavigateTopLevel = ::navigateTopLevel
                            )
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

/**
 * Floating Liquid Glass bottom navigation capsule implementing the Official Share Flow Brand System:
 * - Translucent Deep Navy (#0A1821) glass surface
 * - Subtle Sky Blue (#76B9FF) outer border
 * - Royal Blue (#1D59BB) active-state pill with Signature Gradient hairline
 * - Soft Cream (#FEF3B3) selected icon & label accents
 * - Subtle Golden Yellow (#FEBD19) active highlight dot
 */
@Composable
private fun ShareFlowFloatingGlassNavBar(
    currentRoute: String?,
    onNavigateTopLevel: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val capsuleShape = RoundedCornerShape(28.dp)
    val pillShape = RoundedCornerShape(20.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = capsuleShape,
            color = ShareFlowBrandColors.GlassNavBackground,
            border = BorderStroke(1.dp, ShareFlowBrandColors.GlassNavBorder),
            shadowElevation = 10.dp,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .testTag("shareflow_navigation_bar")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TopLevelDestination.entries.forEach { destination ->
                    val isSelected = currentRoute == destination.route
                    val label = stringResource(destination.labelRes)
                    val iconTint by animateColorAsState(
                        targetValue = if (isSelected) {
                            ShareFlowBrandColors.SoftCream
                        } else {
                            ShareFlowBrandColors.SkyBlue.copy(alpha = 0.78f)
                        },
                        animationSpec = tween(durationMillis = 180),
                        label = "nav_icon_tint_${destination.route}"
                    )
                    val textTint by animateColorAsState(
                        targetValue = if (isSelected) {
                            ShareFlowBrandColors.SoftCream
                        } else {
                            ShareFlowBrandColors.InkSecondaryOnDeepNavy.copy(alpha = 0.82f)
                        },
                        animationSpec = tween(durationMillis = 180),
                        label = "nav_text_tint_${destination.route}"
                    )
                    val indicatorDotWidth by animateDpAsState(
                        targetValue = if (isSelected) 16.dp else 0.dp,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        ),
                        label = "nav_dot_${destination.route}"
                    )

                    Surface(
                        shape = pillShape,
                        color = if (isSelected) {
                            ShareFlowBrandColors.RoyalBlue.copy(alpha = 0.92f)
                        } else {
                            Color.Transparent
                        },
                        border = if (isSelected) {
                            BorderStroke(
                                width = 1.dp,
                                brush = ShareFlowBrandGradients.signatureBorder(alpha = 0.72f)
                            )
                        } else {
                            null
                        },
                        modifier = Modifier
                            .weight(1f)
                            .clip(pillShape)
                            .minimumInteractiveComponentSize()
                            .semantics { selected = isSelected }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = androidx.compose.material3.ripple(
                                    color = ShareFlowBrandColors.SkyBlue
                                ),
                                role = Role.Tab,
                                onClick = { onNavigateTopLevel(destination.route) }
                            )
                            .testTag(destination.testTag)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 7.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Icon(
                                imageVector = if (isSelected) destination.selectedIcon else destination.unselectedIcon,
                                contentDescription = label,
                                tint = iconTint,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.5.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                                ),
                                color = textTint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (indicatorDotWidth > 1.dp) {
                                Box(
                                    modifier = Modifier
                                        .width(indicatorDotWidth)
                                        .height(2.5.dp)
                                        .clip(CircleShape)
                                        .background(ShareFlowBrandColors.GoldenYellow)
                                )
                            }
                        }
                    }
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
        enterTransition = {
            androidx.compose.animation.fadeIn(
                animationSpec = androidx.compose.animation.core.tween(
                    durationMillis = 220,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing
                )
            ) + androidx.compose.animation.slideInHorizontally(
                animationSpec = androidx.compose.animation.core.tween(
                    durationMillis = 220,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing
                ),
                initialOffsetX = { fullWidth -> fullWidth / 14 }
            )
        },
        exitTransition = {
            androidx.compose.animation.fadeOut(
                animationSpec = androidx.compose.animation.core.tween(
                    durationMillis = 180,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing
                )
            )
        },
        popEnterTransition = {
            androidx.compose.animation.fadeIn(
                animationSpec = androidx.compose.animation.core.tween(
                    durationMillis = 220,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing
                )
            ) + androidx.compose.animation.slideInHorizontally(
                animationSpec = androidx.compose.animation.core.tween(
                    durationMillis = 220,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing
                ),
                initialOffsetX = { fullWidth -> -fullWidth / 14 }
            )
        },
        popExitTransition = {
            androidx.compose.animation.fadeOut(
                animationSpec = androidx.compose.animation.core.tween(
                    durationMillis = 180,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing
                )
            )
        },
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
                onNavigateToStorageAnalyzer = { navigateToRoute(ShareFlowRoutes.STORAGE_ANALYZER) },
                onOpenVault = { navigateToRoute(ShareFlowRoutes.PRIVATE_VAULT) }
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
                onOpenAppSharing = { navigateToRoute(ShareFlowRoutes.APPLICATION_SHARING) },
                onOpenVault = { navigateToRoute(ShareFlowRoutes.PRIVATE_VAULT) }
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
                onNavigateToSend = { navigateToRoute(ShareFlowRoutes.SEND) },
                onOpenVault = { navigateToRoute(ShareFlowRoutes.PRIVATE_VAULT) }
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
                onNavigateBack = { navController.popBackStack() },
                onNavigateToSend = { navigateToRoute(ShareFlowRoutes.SEND) },
                onOpenVault = { navigateToRoute(ShareFlowRoutes.PRIVATE_VAULT) }
            )
        }
        composable(route = ShareFlowRoutes.TRANSFER_PROGRESS) {
            TransferProgressScreen(
                onNavigateBack = { navController.popBackStack() },
                onOpenSend = { navigateToRoute(ShareFlowRoutes.SEND) },
                onOpenHistory = { navigateToRoute(ShareFlowRoutes.TRANSFER_HISTORY) },
                onOpenVault = { navigateToRoute(ShareFlowRoutes.PRIVATE_VAULT) }
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
                onNavigateBack = { navController.popBackStack() },
                onNavigateToSend = { navigateToRoute(ShareFlowRoutes.SEND) },
                onOpenVault = { navigateToRoute(ShareFlowRoutes.PRIVATE_VAULT) }
            )
        }
        composable(route = ShareFlowRoutes.VIDEO_PLAYER) {
            VideoPlayerScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToSend = { navigateToRoute(ShareFlowRoutes.SEND) },
                onOpenVault = { navigateToRoute(ShareFlowRoutes.PRIVATE_VAULT) }
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
