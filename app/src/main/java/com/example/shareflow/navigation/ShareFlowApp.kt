package com.example.shareflow.navigation

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
import com.example.shareflow.core.designsystem.components.SFBackground
import com.example.shareflow.core.designsystem.components.SFGlassNavigationBar
import com.example.shareflow.core.designsystem.components.SFNavBarItem
import com.example.shareflow.core.designsystem.motion.AutoHideBottomBarState
import com.example.shareflow.core.designsystem.motion.ElasticOverscrollContainer
import com.example.shareflow.core.designsystem.motion.rememberAutoHideBottomBarState
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMarpleColors
import com.example.shareflow.core.designsystem.theme.ShareFlowTheme
import com.example.shareflow.core.di.LocalAppContainer
import com.example.shareflow.domain.model.AppLanguage
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
import com.example.shareflow.ui.onboarding.OnboardingScreen
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ShareFlowApp(
    navController: NavHostController = rememberNavController(),
    initialSettings: AppSettings? = null,
    pendingExternalRoute: String? = null,
    onConsumeExternalRoute: () -> Unit = {}
) {
    val container = LocalAppContainer.current
    val resolvedInitialSettings = remember(container, initialSettings) {
        initialSettings ?: container.settingsRepository.peekInitialSettings()
    }
    val settings by container.settingsRepository.settings.collectAsStateWithLifecycle(
        initialValue = resolvedInitialSettings
    )
    val approvalScope = androidx.compose.runtime.rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Defer heavy networking/transfer DI graph initialization off the Main Thread until after Frame 1
    var backgroundServicesReady by remember(container) { mutableStateOf(false) }
    var activeTransferCount by remember(container) { mutableIntStateOf(0) }
    var onboardingDismissed by remember(container) {
        mutableStateOf(resolvedInitialSettings.hasCompletedOnboarding)
    }
    var pendingLanguageOverride by remember(container) {
        mutableStateOf<AppLanguage?>(null)
    }
    LaunchedEffect(settings.hasCompletedOnboarding) {
        if (settings.hasCompletedOnboarding) {
            onboardingDismissed = true
        }
    }
    LaunchedEffect(settings.appLanguage) {
        if (pendingLanguageOverride == settings.appLanguage) {
            pendingLanguageOverride = null
        }
    }

    LaunchedEffect(container) {
        val warmupDelayMs = container.devicePerformanceProfile.startupBackgroundWarmupDelayMs
        if (warmupDelayMs > 0L) {
            delay(warmupDelayMs)
        }
        runCatching {
            withContext(container.dispatchers.io) {
                container.connectionRepository
                container.transferManager
                container.unifiedConnectionManager
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
        }
        backgroundServicesReady = true
    }

    var hasEnteredBackground by remember(container) { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, container) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    hasEnteredBackground = true
                    container.localSessionController.stopNetworkMonitoring()
                    approvalScope.launch(container.dispatchers.io) {
                        if (container.isVaultInitialized) {
                            runCatching { container.vaultManager.onAppBackgrounded() }
                                .onFailure { if (it is CancellationException) throw it }
                        }
                        if (container.isSmartCoordinatorInitialized) {
                            runCatching { container.smartConnectionCoordinator.onAppBackgrounded() }
                                .onFailure { if (it is CancellationException) throw it }
                        }
                    }
                }
                Lifecycle.Event.ON_START -> {
                    val returningFromBackground = hasEnteredBackground
                    hasEnteredBackground = false
                    approvalScope.launch(container.dispatchers.io) {
                        runCatching { container.localSessionController.startNetworkMonitoring() }
                            .onFailure { if (it is CancellationException) throw it }
                        if (returningFromBackground) {
                            runCatching { container.fileManagerRepository.notifyStorageChanged() }
                                .onFailure { if (it is CancellationException) throw it }
                        }
                        if (container.isVaultInitialized) {
                            runCatching { container.vaultManager.checkAutoLock() }
                                .onFailure { if (it is CancellationException) throw it }
                        }
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            container.localSessionController.stopNetworkMonitoring()
        }
    }

    val effectiveLanguage = pendingLanguageOverride ?: settings.appLanguage

    ShareFlowTheme(
        themeMode = settings.themeMode,
        useDynamicColor = settings.useDynamicColor,
        accentColor = settings.accentColor,
        appLanguage = effectiveLanguage,
        performanceProfile = container.devicePerformanceProfile
    ) {
        val showOnboarding = !onboardingDismissed && !settings.hasCompletedOnboarding

        Crossfade(
            targetState = showOnboarding,
            animationSpec = tween(durationMillis = 240),
            label = "onboarding_to_main_transition"
        ) { isOnboardingActive ->
            if (isOnboardingActive) {
                OnboardingScreen(
                    selectedLanguage = effectiveLanguage,
                    onSelectLanguage = { selected ->
                        pendingLanguageOverride = selected
                        approvalScope.launch {
                            runCatching { container.settingsRepository.updateAppLanguage(selected) }
                                .onFailure { if (it is CancellationException) throw it }
                        }
                    },
                    onComplete = {
                        onboardingDismissed = true
                        approvalScope.launch {
                            runCatching { container.settingsRepository.markOnboardingCompleted() }
                                .onFailure { if (it is CancellationException) throw it }
                        }
                    }
                )
            } else {
                var bannerUpdate by remember(container) {
                    mutableStateOf<com.example.shareflow.data.update.UpdateDetails?>(null)
                }
                var expandedUpdateVersionCode by remember(container) {
                    mutableStateOf<Int?>(null)
                }

                LaunchedEffect(pendingExternalRoute) {
                    val target = pendingExternalRoute ?: return@LaunchedEffect
                    runCatching {
                        navController.navigate(target) {
                            launchSingleTop = true
                        }
                    }
                    onConsumeExternalRoute()
                }

                if (backgroundServicesReady) {
                    ShareFlowActiveSessionBridge(
                        expandedUpdateVersionCode = expandedUpdateVersionCode,
                        onExpandedUpdateVersionCodeChange = { expandedUpdateVersionCode = it },
                        onOptionalBannerUpdateChanged = { bannerUpdate = it },
                        onActiveTransferCountChanged = { count -> activeTransferCount = count },
                        onOpenUpdatesScreen = {
                            expandedUpdateVersionCode = null
                            bannerUpdate = null
                            runCatching {
                                navController.navigate(ShareFlowRoutes.UPDATES) {
                                    launchSingleTop = true
                                }
                            }
                        }
                    )
                }

                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = backStackEntry?.destination?.route ?: ShareFlowRoutes.HOME
                val isTopLevel = remember(currentRoute) {
                    TopLevelDestination.entries.any { it.route == currentRoute }
                }

                val navigateTopLevel = remember(navController) {
                    { route: String ->
                        runCatching {
                            navController.navigate(route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                        Unit
                    }
                }

                SFBackground(modifier = Modifier.fillMaxSize()) {
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val useNavigationRail = maxWidth >= 600.dp

                        Row(modifier = Modifier.fillMaxSize()) {
                            if (useNavigationRail && isTopLevel) {
                                val semantic = ShareFlowDesign.semanticColors
                                val glass = ShareFlowDesign.glass
                                val inactiveNeutral = if (semantic.isDark) {
                                    ShareFlowMarpleColors.DarkTextSecondary
                                } else {
                                    ShareFlowMarpleColors.LightTextSecondary
                                }
                                NavigationRail(
                                    containerColor = glass.navBase,
                                    contentColor = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.testTag("shareflow_navigation_rail")
                                ) {
                                    TopLevelDestination.entries.forEach { destination ->
                                        val selected = currentRoute == destination.route
                                        NavigationRailItem(
                                            selected = selected,
                                            onClick = { navigateTopLevel(destination.route) },
                                            colors = NavigationRailItemDefaults.colors(
                                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                                indicatorColor = glass.navActivePill,
                                                unselectedIconColor = inactiveNeutral,
                                                unselectedTextColor = inactiveNeutral
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

                            val showFloatingBottomBar = !useNavigationRail && isTopLevel
                            val autoHideNavState = rememberAutoHideBottomBarState(
                                routeKey = currentRoute,
                                enabled = showFloatingBottomBar
                            )
                            val density = LocalDensity.current
                            var measuredNavBarHeightPx by remember { mutableIntStateOf(0) }
                            val fallbackNavClearance = 92.dp
                            val measuredClearanceDp = if (measuredNavBarHeightPx > 0) {
                                with(density) { measuredNavBarHeightPx.toDp() }
                            } else {
                                fallbackNavClearance
                            }

                            val navigateTopLevelWithShow = remember(autoHideNavState, navigateTopLevel) {
                                { route: String ->
                                    autoHideNavState.showImmediately()
                                    navigateTopLevel(route)
                                }
                            }
                            val onMeasuredNavBarHeightPx = remember {
                                { heightPx: Int ->
                                    if (heightPx > 0 && heightPx != measuredNavBarHeightPx) {
                                        measuredNavBarHeightPx = heightPx
                                    }
                                }
                            }

                            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                                Scaffold(
                                    modifier = Modifier.fillMaxSize(),
                                    containerColor = Color.Transparent,
                                    contentWindowInsets = WindowInsets.safeDrawing
                                ) { innerPadding ->
                                    val layoutDirection = LocalLayoutDirection.current
                                    val statusBarTopInset = innerPadding.calculateTopPadding()
                                    val bottomInset = innerPadding.calculateBottomPadding()
                                    val contentBottomClearance = if (showFloatingBottomBar) {
                                        measuredClearanceDp + bottomInset
                                    } else {
                                        0.dp
                                    }
                                    // True Edge-to-Edge: DO NOT clip at the status bar boundary.
                                    // Scrollable screens apply statusBarTopInset inside contentPadding so
                                    // items rest below the status bar and scroll smoothly behind it.
                                    ElasticOverscrollContainer(
                                        resetKey = currentRoute,
                                        revealBackgroundColor = Color.Transparent,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .then(
                                                if (showFloatingBottomBar) {
                                                    Modifier.nestedScroll(autoHideNavState.nestedScrollConnection)
                                                } else {
                                                    Modifier
                                                }
                                            )
                                            .padding(
                                                start = innerPadding.calculateStartPadding(layoutDirection),
                                                end = innerPadding.calculateEndPadding(layoutDirection),
                                                bottom = if (showFloatingBottomBar) 0.dp else bottomInset
                                            )
                                    ) {
                                        ShareFlowNavHost(
                                            navController = navController,
                                            onNavigateTopLevel = navigateTopLevelWithShow,
                                            topLevelStatusBarClearance = statusBarTopInset,
                                            topLevelBottomBarClearance = contentBottomClearance,
                                            activeTransferCount = activeTransferCount,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }

                                // Isolated GPU draw-phase floating frosted bottom bar overlay:
                                // slide animation NEVER recomposes Scaffold or ShareFlowNavHost and
                                // never tears down/recreates nav animations on scroll.
                                if (showFloatingBottomBar) {
                                    FloatingAutoHideNavBarOverlay(
                                        autoHideNavState = autoHideNavState,
                                        currentRoute = currentRoute,
                                        measuredNavBarHeightPx = measuredNavBarHeightPx,
                                        fallbackNavClearance = fallbackNavClearance,
                                        onMeasuredHeightPx = onMeasuredNavBarHeightPx,
                                        onNavigateTopLevel = navigateTopLevelWithShow,
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .zIndex(5f)
                                    )
                                }
                            }
                        }

                        // Non-intrusive floating update banner for optional updates detected on launch
                        com.example.shareflow.ui.update.UpdateAvailableBanner(
                            update = bannerUpdate,
                            onOpenDetails = {
                                bannerUpdate?.let { expandedUpdateVersionCode = it.versionCode }
                            },
                            onDismiss = {
                                expandedUpdateVersionCode = null
                                bannerUpdate = null
                                approvalScope.launch {
                                    container.updateManager.snoozeOptionalUpdate()
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .zIndex(10f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareFlowActiveSessionBridge(
    expandedUpdateVersionCode: Int?,
    onExpandedUpdateVersionCodeChange: (Int?) -> Unit,
    onOptionalBannerUpdateChanged: (com.example.shareflow.data.update.UpdateDetails?) -> Unit,
    onActiveTransferCountChanged: (Int) -> Unit,
    onOpenUpdatesScreen: () -> Unit = {}
) {
    // Isolate each reactive bridge in its own restart scope so high-frequency transfer progress
    // or update download byte ticks NEVER trigger cross-bridge recompositions.
    NetworkAutoDiscoveryBridge()
    TransferSessionMonitorBridge(onActiveTransferCountChanged = onActiveTransferCountChanged)
    IncomingApprovalAndOfferBridge()
    UpdateCoordinatorBridge(
        expandedUpdateVersionCode = expandedUpdateVersionCode,
        onExpandedUpdateVersionCodeChange = onExpandedUpdateVersionCodeChange,
        onOptionalBannerUpdateChanged = onOptionalBannerUpdateChanged,
        onOpenUpdatesScreen = onOpenUpdatesScreen
    )
}

@Composable
private fun NetworkAutoDiscoveryBridge() {
    val container = LocalAppContainer.current
    val networkStatus by container.localSessionController.networkStatus
        .collectAsStateWithLifecycle()
    var lastAutoDiscoveryWifiReady by remember(container) { mutableStateOf(false) }

    // Smart Wi-Fi Startup Management:
    // Automatically starts nearby device discovery when Wi-Fi is active on launch, and
    // seamlessly auto-retries whenever Wi-Fi transitions from OFF -> ON or reconnects.
    LaunchedEffect(
        networkStatus.hasCheckedInitialState,
        networkStatus.isWifiEnabled,
        networkStatus.isWifiConnected
    ) {
        val wifiReady = networkStatus.hasCheckedInitialState &&
            (networkStatus.isWifiEnabled || networkStatus.isWifiConnected)
        if (wifiReady && !lastAutoDiscoveryWifiReady) {
            lastAutoDiscoveryWifiReady = true
            withContext(container.dispatchers.io) {
                runCatching { container.unifiedConnectionManager.startNearbyDiscovery() }
                    .onFailure { if (it is CancellationException) throw it }
            }
        } else if (!wifiReady) {
            lastAutoDiscoveryWifiReady = false
        }
    }
}

@Composable
private fun TransferSessionMonitorBridge(
    onActiveTransferCountChanged: (Int) -> Unit
) {
    val container = LocalAppContainer.current
    val activeTransferSessions by container.transferManager.activeSessions
        .collectAsStateWithLifecycle()
    val activeCount = activeTransferSessions.size
    val isTransferring = activeTransferSessions.any { it.state == TransferState.TRANSFERRING }

    LaunchedEffect(activeCount) {
        onActiveTransferCountChanged(activeCount)
    }

    LaunchedEffect(isTransferring) {
        if (isTransferring || container.isSmartCoordinatorInitialized) {
            runCatching { container.smartConnectionCoordinator.notifyTransferActivity(isTransferring) }
                .onFailure { if (it is CancellationException) throw it }
        }
    }
}

@Composable
private fun IncomingApprovalAndOfferBridge() {
    val container = LocalAppContainer.current
    val approvalScope = androidx.compose.runtime.rememberCoroutineScope()
    val pendingIncoming by container.connectionRepository.pendingIncomingRequest
        .collectAsStateWithLifecycle()
    val pendingTransferOffer by container.unifiedConnectionManager.pendingIncomingOffer
        .collectAsStateWithLifecycle()

    pendingIncoming?.let { request ->
        com.example.shareflow.ui.discovery.IncomingConnectionApprovalDialog(
            request = request,
            onApprove = { rememberTrusted ->
                approvalScope.launch {
                    runCatching {
                        container.connectionRepository.approveIncomingConnection(
                            requestId = request.requestId,
                            rememberAsTrusted = rememberTrusted
                        )
                    }.onFailure { if (it is CancellationException) throw it }
                }
            },
            onReject = {
                approvalScope.launch {
                    runCatching {
                        container.connectionRepository.rejectIncomingConnection(request.requestId)
                    }.onFailure { if (it is CancellationException) throw it }
                }
            }
        )
    }

    pendingTransferOffer?.let { offer ->
        com.example.shareflow.ui.receive.IncomingTransferOfferDialog(
            offer = offer,
            onAccept = {
                runCatching {
                    container.unifiedConnectionManager.approveIncomingTransferOffer(offer.sessionId)
                }
            },
            onReject = {
                runCatching {
                    container.unifiedConnectionManager.rejectIncomingTransferOffer(offer.sessionId)
                }
            }
        )
    }
}

@Composable
private fun UpdateCoordinatorBridge(
    expandedUpdateVersionCode: Int?,
    onExpandedUpdateVersionCodeChange: (Int?) -> Unit,
    onOptionalBannerUpdateChanged: (com.example.shareflow.data.update.UpdateDetails?) -> Unit,
    onOpenUpdatesScreen: () -> Unit
) {
    val container = LocalAppContainer.current
    val detectedUpdate by container.updateManager.detectedUpdate
        .collectAsStateWithLifecycle()
    val foregroundPushVersion by container.updateManager.foregroundPushVersionCode
        .collectAsStateWithLifecycle()

    LaunchedEffect(foregroundPushVersion) {
        val versionCode = foregroundPushVersion ?: return@LaunchedEffect
        onExpandedUpdateVersionCodeChange(versionCode)
        container.updateManager.consumeForegroundPushTrigger()
    }

    // Automatic login-free FCM topic subscription (`share_flow_updates`) + periodic/launch update check
    LaunchedEffect(container) {
        withContext(container.dispatchers.io) {
            runCatching { container.fcmUpdateTopicCoordinator.ensureSubscribed() }
                .onFailure { if (it is CancellationException) throw it }
        }
        val autoCheckEnabled = runCatching {
            container.settingsRepository.settings.first().autoCheckUpdatesOnLaunch
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            true
        }
        if (autoCheckEnabled) {
            runCatching { container.updateManager.checkForUpdateIfDue() }
                .onFailure { if (it is CancellationException) throw it }
        }
    }

    LaunchedEffect(detectedUpdate, expandedUpdateVersionCode) {
        val current = detectedUpdate
        val showBanner = current != null &&
            !current.mandatory &&
            expandedUpdateVersionCode != current.versionCode
        onOptionalBannerUpdateChanged(if (showBanner) current else null)
    }

    detectedUpdate?.let { update ->
        val shouldShowDialog = update.mandatory || expandedUpdateVersionCode == update.versionCode
        if (shouldShowDialog) {
            UpdateAvailableDialogContainer(
                update = update,
                onExpandedUpdateVersionCodeChange = onExpandedUpdateVersionCodeChange,
                onOpenUpdatesScreen = onOpenUpdatesScreen
            )
        }
    }
}

@Composable
private fun UpdateAvailableDialogContainer(
    update: com.example.shareflow.data.update.UpdateDetails,
    onExpandedUpdateVersionCodeChange: (Int?) -> Unit,
    onOpenUpdatesScreen: () -> Unit
) {
    val container = LocalAppContainer.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val approvalScope = androidx.compose.runtime.rememberCoroutineScope()
    val cloudUpdateState by container.cloudUpdateManager.state
        .collectAsStateWithLifecycle()
    var activeUpdateDownloadJob by remember(container) {
        mutableStateOf<kotlinx.coroutines.Job?>(null)
    }

    com.example.shareflow.ui.update.UpdateAvailableDialog(
        update = update,
        downloadState = cloudUpdateState,
        onDownloadNow = { targetUpdate ->
            activeUpdateDownloadJob?.cancel()
            activeUpdateDownloadJob = approvalScope.launch {
                runCatching {
                    container.cloudUpdateManager.downloadAndVerifyUpdate(
                        targetUpdate.toCloudUpdateManifest()
                    )
                }.onFailure { if (it is CancellationException) throw it }
            }
        },
        onPauseDownload = {
            container.cloudUpdateManager.markDownloadPaused()
            activeUpdateDownloadJob?.cancel()
            activeUpdateDownloadJob = null
        },
        onCancelDownload = {
            activeUpdateDownloadJob?.cancel()
            activeUpdateDownloadJob = null
            container.cloudUpdateManager.cancelActiveDownload()
        },
        onViewDetails = onOpenUpdatesScreen,
        onInstallVerifiedApk = { apkPath ->
            when (container.cloudUpdateManager.launchVerifiedUpdateInstaller(apkPath)) {
                com.example.shareflow.data.update.UpdateInstallLaunchResult.PERMISSION_REQUIRED -> {
                    val intent = container.permissionManager.createManageUnknownSourcesIntent()
                    runCatching { context.startActivity(intent) }
                }
                else -> Unit
            }
        },
        onDismiss = {
            onExpandedUpdateVersionCodeChange(null)
            approvalScope.launch {
                container.updateManager.snoozeOptionalUpdate()
            }
        }
    )
}

/**
 * Isolated floating bottom navigation container that reads reveal progress strictly in the
 * GPU `graphicsLayer` draw phase so scrolling never recomposes [Scaffold] or [ShareFlowNavHost].
 */
@Composable
private fun FloatingAutoHideNavBarOverlay(
    autoHideNavState: AutoHideBottomBarState,
    currentRoute: String?,
    measuredNavBarHeightPx: Int,
    fallbackNavClearance: Dp,
    onMeasuredHeightPx: (Int) -> Unit,
    onNavigateTopLevel: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val reducedMotion = ShareFlowDesign.reducedMotion
    val density = LocalDensity.current
    val extraHideMarginPx = remember(density) { with(density) { 18.dp.toPx() } }
    val fallbackPx = remember(density, fallbackNavClearance) { with(density) { fallbackNavClearance.toPx() } }
    val navBarRevealProgressState = animateFloatAsState(
        targetValue = if (autoHideNavState.isVisible) 1f else 0f,
        animationSpec = if (reducedMotion) {
            tween(durationMillis = 0)
        } else {
            spring(
                dampingRatio = 0.88f,
                stiffness = 420f
            )
        },
        label = "sf_auto_hide_nav_progress"
    )

    Box(
        modifier = modifier
            .onSizeChanged { onMeasuredHeightPx(it.height) }
            .graphicsLayer {
                val progress = navBarRevealProgressState.value
                val slideDistance = (if (measuredNavBarHeightPx > 0) measuredNavBarHeightPx.toFloat() else fallbackPx) +
                    extraHideMarginPx
                translationY = (1f - progress) * slideDistance
                alpha = if (progress <= 0.01f) 0f else (0.18f + 0.82f * progress).coerceIn(0f, 1f)
            }
            .testTag("shareflow_floating_nav_container")
    ) {
        ShareFlowFloatingGlassNavBar(
            currentRoute = currentRoute,
            onNavigateTopLevel = onNavigateTopLevel
        )
    }
}

/**
 * Share Flow 2.0 Floating Liquid Glass Bottom Navigation Bar wrapper delegating to [SFGlassNavigationBar].
 */
@Composable
internal fun ShareFlowFloatingGlassNavBar(
    currentRoute: String?,
    onNavigateTopLevel: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val labels = TopLevelDestination.entries.map { stringResource(it.labelRes) }
    val navItems = remember(labels) {
        TopLevelDestination.entries.mapIndexed { index, destination ->
            SFNavBarItem(
                route = destination.route,
                label = labels[index],
                selectedIcon = destination.selectedIcon,
                unselectedIcon = destination.unselectedIcon,
                testTag = destination.testTag
            )
        }
    }
    SFGlassNavigationBar(
        items = navItems,
        currentRoute = currentRoute,
        onSelectRoute = onNavigateTopLevel,
        modifier = modifier
    )
}

@Composable
private fun ShareFlowNavHost(
    navController: NavHostController,
    onNavigateTopLevel: (String) -> Unit,
    topLevelStatusBarClearance: Dp = 0.dp,
    topLevelBottomBarClearance: Dp = 0.dp,
    activeTransferCount: Int = 0,
    modifier: Modifier = Modifier
) {
    val motion = ShareFlowDesign.motion
    val reducedMotion = ShareFlowDesign.reducedMotion
    val navigateToRoute = remember(navController, onNavigateTopLevel) {
        { route: String ->
            if (TopLevelDestination.entries.any { it.route == route }) {
                onNavigateTopLevel(route)
            } else {
                navController.navigate(route) {
                    launchSingleTop = true
                }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = ShareFlowRoutes.HOME,
        enterTransition = { motion.screenEnter(reducedMotion) },
        exitTransition = { motion.screenExit(reducedMotion) },
        popEnterTransition = { motion.screenPopEnter(reducedMotion) },
        popExitTransition = { motion.screenPopExit(reducedMotion) },
        modifier = modifier
    ) {
        // Top-level destinations: Home, Files, Media, Tools, Settings — ALL share the unified floating glass nav bar
        composable(route = ShareFlowRoutes.HOME) {
            HomeDashboardScreen(
                onNavigateToRoute = navigateToRoute,
                topBarClearance = topLevelStatusBarClearance,
                bottomBarClearance = topLevelBottomBarClearance,
                activeTransferCount = activeTransferCount
            )
        }
        composable(route = ShareFlowRoutes.FILES_HUB) {
            FilesScreen(
                onOpenCategory = { category ->
                    navigateToRoute(ShareFlowRoutes.fileCategoryRoute(category))
                },
                onNavigateToSend = { navigateToRoute(ShareFlowRoutes.SEND) },
                onNavigateToStorageAnalyzer = { navigateToRoute(ShareFlowRoutes.STORAGE_ANALYZER) },
                onOpenVault = { navigateToRoute(ShareFlowRoutes.PRIVATE_VAULT) },
                bottomBarClearance = topLevelBottomBarClearance
            )
        }
        composable(route = ShareFlowRoutes.MEDIA_HUB) {
            MediaScreen(
                onNavigateToRoute = navigateToRoute,
                onNavigateToSend = { navigateToRoute(ShareFlowRoutes.SEND) },
                onOpenVault = { navigateToRoute(ShareFlowRoutes.PRIVATE_VAULT) },
                topBarClearance = topLevelStatusBarClearance,
                bottomBarClearance = topLevelBottomBarClearance
            )
        }
        composable(route = ShareFlowRoutes.TOOLS_HUB) {
            ToolsScreen(
                onNavigateToRoute = navigateToRoute,
                topBarClearance = topLevelStatusBarClearance,
                bottomBarClearance = topLevelBottomBarClearance
            )
        }
        composable(route = ShareFlowRoutes.SETTINGS) {
            SettingsScreen(
                onOpenVault = { navigateToRoute(ShareFlowRoutes.PRIVATE_VAULT) },
                onOpenUpdates = { navigateToRoute(ShareFlowRoutes.UPDATES) },
                topBarClearance = topLevelStatusBarClearance,
                bottomBarClearance = topLevelBottomBarClearance
            )
        }
        composable(route = ShareFlowRoutes.UPDATES) {
            com.example.shareflow.ui.update.UpdatesScreen(
                onNavigateBack = { navController.popBackStack() },
                topBarClearance = topLevelStatusBarClearance
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
