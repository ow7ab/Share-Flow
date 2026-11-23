package com.example.shareflow.navigation

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
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
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMarpleColors
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
import kotlinx.coroutines.withContext

@Composable
fun ShareFlowApp(
    navController: NavHostController = rememberNavController(),
    initialSettings: AppSettings? = null
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

    LaunchedEffect(container) {
        withContext(container.dispatchers.io) {
            container.connectionRepository
            container.transferManager
            container.unifiedConnectionManager
        }
        backgroundServicesReady = true
    }

    DisposableEffect(lifecycleOwner, container) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    approvalScope.launch(container.dispatchers.io) {
                        if (container.isVaultInitialized) {
                            container.vaultManager.onAppBackgrounded()
                        }
                        if (container.isSmartCoordinatorInitialized) {
                            container.smartConnectionCoordinator.onAppBackgrounded()
                        }
                    }
                }
                Lifecycle.Event.ON_START -> {
                    if (container.isVaultInitialized) {
                        approvalScope.launch(container.dispatchers.io) {
                            container.vaultManager.checkAutoLock()
                        }
                    }
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
        if (backgroundServicesReady) {
            ShareFlowActiveSessionBridge(
                onActiveTransferCountChanged = { count -> activeTransferCount = count }
            )
        }

        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route ?: ShareFlowRoutes.HOME
        val isTopLevel = remember(currentRoute) {
            TopLevelDestination.entries.any { it.route == currentRoute }
        }

        val navigateTopLevel = remember(navController) {
            { route: String ->
                navController.navigate(route) {
                    popUpTo(navController.graph.findStartDestination().id) {
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
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
                    val semantic = ShareFlowDesign.semanticColors
                    val inactiveNeutral = if (semantic.isDark) Color(0xFFC4B9BE) else Color(0xFF6E6469)
                    NavigationRail(
                        containerColor = semantic.glassNavBackground,
                        contentColor = ShareFlowMarpleColors.Ruby,
                        modifier = Modifier.testTag("shareflow_navigation_rail")
                    ) {
                        TopLevelDestination.entries.forEach { destination ->
                            val selected = currentRoute == destination.route
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTopLevel(destination.route) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = ShareFlowMarpleColors.Ruby,
                                    selectedTextColor = if (semantic.isDark) ShareFlowMarpleColors.DarkPrimary else ShareFlowMarpleColors.Ruby,
                                    indicatorColor = semantic.glassNavActivePill,
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

                Scaffold(
                    modifier = Modifier.weight(1f),
                    containerColor = MaterialTheme.colorScheme.background,
                    contentWindowInsets = WindowInsets.safeDrawing,
                    bottomBar = {
                        if (!useNavigationRail && isTopLevel) {
                            ShareFlowFloatingGlassNavBar(
                                currentRoute = currentRoute,
                                onNavigateTopLevel = navigateTopLevel
                            )
                        }
                    }
                ) { innerPadding ->
                    val layoutDirection = LocalLayoutDirection.current
                    // Float the unified glass bottom bar over ALL 5 top-level tabs so scrollable
                    // content visibly shines behind the translucent glass capsule on every tab.
                    val floatOverTopLevel = isTopLevel && !useNavigationRail
                    val bottomInset = innerPadding.calculateBottomPadding()
                    ShareFlowNavHost(
                        navController = navController,
                        onNavigateTopLevel = navigateTopLevel,
                        topLevelBottomBarClearance = if (floatOverTopLevel) bottomInset else 0.dp,
                        activeTransferCount = activeTransferCount,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                top = innerPadding.calculateTopPadding(),
                                start = innerPadding.calculateStartPadding(layoutDirection),
                                end = innerPadding.calculateEndPadding(layoutDirection),
                                bottom = if (floatOverTopLevel) 0.dp else bottomInset
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun ShareFlowActiveSessionBridge(
    onActiveTransferCountChanged: (Int) -> Unit
) {
    val container = LocalAppContainer.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val approvalScope = androidx.compose.runtime.rememberCoroutineScope()
    val pendingIncoming by container.connectionRepository.pendingIncomingRequest
        .collectAsStateWithLifecycle()
    val pendingTransferOffer by container.unifiedConnectionManager.pendingIncomingOffer
        .collectAsStateWithLifecycle()
    val activeTransferSessions by container.transferManager.activeSessions
        .collectAsStateWithLifecycle()
    val detectedUpdate by container.updateManager.detectedUpdate
        .collectAsStateWithLifecycle()
    val cloudUpdateState by container.cloudUpdateManager.state
        .collectAsStateWithLifecycle()
    var activeUpdateDownloadJob by remember(container) {
        mutableStateOf<kotlinx.coroutines.Job?>(null)
    }

    // Background update check via OkHttp UpdateManager strictly after Frame 1 on IO dispatcher
    LaunchedEffect(container) {
        runCatching { container.updateManager.checkForUpdate() }
    }

    LaunchedEffect(activeTransferSessions) {
        onActiveTransferCountChanged(activeTransferSessions.size)
        val isTransferring = activeTransferSessions.any { it.state == TransferState.TRANSFERRING }
        if (isTransferring || container.isSmartCoordinatorInitialized) {
            container.smartConnectionCoordinator.notifyTransferActivity(isTransferring)
        }
    }

    detectedUpdate?.let { update ->
        com.example.shareflow.ui.update.UpdateAvailableDialog(
            update = update,
            downloadState = cloudUpdateState,
            onDownloadNow = { targetUpdate ->
                activeUpdateDownloadJob?.cancel()
                activeUpdateDownloadJob = approvalScope.launch {
                    container.cloudUpdateManager.downloadAndVerifyUpdate(
                        targetUpdate.toCloudUpdateManifest()
                    )
                }
            },
            onCancelDownload = {
                activeUpdateDownloadJob?.cancel()
                activeUpdateDownloadJob = null
                container.cloudUpdateManager.resetState()
            },
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
                container.updateManager.dismissOptionalUpdate()
            }
        )
    }

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
}

/**
 * Premium iOS-Inspired Glassmorphism Floating Bottom Navigation Bar for ShareFlow:
 * - Floating rounded capsule container with soft ambient shadow and proper Android gesture navigation insets.
 * - Real GPU-accelerated RenderEffect blur (`Modifier.blur`) on Android 12+ (API 31+) scoped exclusively to
 *   the translucent glass backdrop layer so foreground icons/text remain razor-sharp with low GPU overhead.
 * - Graceful multi-layer translucent frosted glass fallback on Android 7.0–11 (API 24–30) — never flat opaque.
 * - Light Mode: Translucent white and warm cream (#FFE3B3) glass with delicate specular white-to-rose bevel border.
 * - Dark Mode: Translucent dark glass (#211A1E / #2B2026) with subtle luminous rim border.
 * - Exact Ruby (#CA2851) active icons, neutral inactive icons, and smooth spring selection animations.
 */
@Composable
internal fun ShareFlowFloatingGlassNavBar(
    currentRoute: String?,
    onNavigateTopLevel: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val semantic = ShareFlowDesign.semanticColors
    val isDark = semantic.isDark
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val supportsRealBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val capsuleShape = remember { RoundedCornerShape(32.dp) }
    val pillShape = remember { RoundedCornerShape(20.dp) }

    val destinations = TopLevelDestination.entries
    val selectedIndex = destinations.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0)
    val rawSelectedFraction = if (destinations.size > 1) {
        selectedIndex.toFloat() / (destinations.size - 1).toFloat()
    } else {
        0.5f
    }
    val visualTargetFraction = if (isRtl) 1f - rawSelectedFraction else rawSelectedFraction
    val animatedBloomFraction by animateFloatAsState(
        targetValue = visualTargetFraction,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "glass_nav_bloom_position"
    )

    // Theme-specific iOS Glassmorphism palette (single unified frosted-glass card, never flat opaque)
    val activeIconColor = if (isDark) ShareFlowMarpleColors.DarkPrimary else ShareFlowMarpleColors.Ruby
    val activeLabelColor = if (isDark) ShareFlowMarpleColors.DarkPrimary else ShareFlowMarpleColors.Ruby
    val activeIndicatorColor = ShareFlowMarpleColors.Ruby
    val neutralInactiveIcon = if (isDark) Color(0xFFB8ADB2).copy(alpha = 0.88f) else Color(0xFF6B6267).copy(alpha = 0.90f)
    val neutralInactiveLabel = if (isDark) Color(0xFFABA0A5).copy(alpha = 0.86f) else Color(0xFF72686D).copy(alpha = 0.90f)

    val ambientShadowColor = if (isDark) {
        Color(0xFF000000).copy(alpha = 0.56f)
    } else {
        ShareFlowMarpleColors.LightTextPrimary.copy(alpha = 0.16f)
    }
    val spotShadowColor = if (isDark) {
        ShareFlowMarpleColors.Ruby.copy(alpha = 0.22f)
    } else {
        ShareFlowMarpleColors.Ruby.copy(alpha = 0.14f)
    }

    val specularBorderBrush = remember(isDark) {
        if (isDark) {
            Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.24f),
                    ShareFlowMarpleColors.DarkBorder.copy(alpha = 0.52f),
                    Color.White.copy(alpha = 0.08f)
                )
            )
        } else {
            Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.94f),
                    ShareFlowMarpleColors.LightBorder.copy(alpha = 0.72f),
                    ShareFlowMarpleColors.LightBorder.copy(alpha = 0.88f)
                )
            )
        }
    }

    // Balanced specular top-glaze brush so combined Layer 1 + Layer 2 stays truly translucent
    // (letting scrolled content softly appear behind the frosted glass on every tab).
    val frostedGlazeBrush = remember(isDark, supportsRealBlur) {
        if (isDark) {
            if (supportsRealBlur) {
                Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.12f),
                        ShareFlowMarpleColors.DarkSurface.copy(alpha = 0.34f),
                        ShareFlowMarpleColors.DarkBackground.copy(alpha = 0.44f)
                    )
                )
            } else {
                Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.08f),
                        ShareFlowMarpleColors.DarkSurface.copy(alpha = 0.52f),
                        ShareFlowMarpleColors.DarkBackground.copy(alpha = 0.62f)
                    )
                )
            }
        } else {
            if (supportsRealBlur) {
                Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.44f),
                        Color.White.copy(alpha = 0.28f),
                        ShareFlowMarpleColors.LightBackground.copy(alpha = 0.24f)
                    )
                )
            } else {
                Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.58f),
                        ShareFlowMarpleColors.LightBackground.copy(alpha = 0.46f),
                        ShareFlowMarpleColors.Cream.copy(alpha = 0.22f)
                    )
                )
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .shadow(
                    elevation = if (isDark) 18.dp else 14.dp,
                    shape = capsuleShape,
                    clip = false,
                    ambientColor = ambientShadowColor,
                    spotColor = spotShadowColor
                )
                .clip(capsuleShape)
                .border(
                    width = 1.dp,
                    brush = specularBorderBrush,
                    shape = capsuleShape
                )
                .testTag("shareflow_navigation_bar")
        ) {
            // Layer 1: Real GPU-accelerated RenderEffect blur backdrop on API 31+ (translucent fallback on API < 31)
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .then(
                        if (supportsRealBlur) {
                            Modifier.blur(
                                radiusX = 24.dp,
                                radiusY = 24.dp,
                                edgeTreatment = BlurredEdgeTreatment.Rectangle
                            )
                        } else {
                            Modifier
                        }
                    )
                    .drawBehind {
                        // Base translucent glass tint
                        drawRect(
                            color = if (isDark) {
                                ShareFlowMarpleColors.DarkSurface.copy(alpha = if (supportsRealBlur) 0.44f else 0.62f)
                            } else {
                                Color.White.copy(alpha = if (supportsRealBlur) 0.44f else 0.62f)
                            }
                        )
                        // Subtle warm cream/plum chromatic refraction wash
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = if (isDark) {
                                    listOf(
                                        ShareFlowMarpleColors.DarkSurfaceElevated.copy(alpha = 0.22f),
                                        ShareFlowMarpleColors.DarkSurfaceContainer.copy(alpha = 0.16f),
                                        ShareFlowMarpleColors.DarkSurfaceElevated.copy(alpha = 0.22f)
                                    )
                                } else {
                                    listOf(
                                        ShareFlowMarpleColors.Cream.copy(alpha = 0.24f),
                                        Color.White.copy(alpha = 0.16f),
                                        ShareFlowMarpleColors.Cream.copy(alpha = 0.24f)
                                    )
                                }
                            )
                        )
                        // Smooth gliding Ruby ambient glass refraction bloom under the selected destination
                        val bloomCenterX = (0.10f + 0.80f * animatedBloomFraction) * size.width
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = if (isDark) {
                                    listOf(
                                        ShareFlowMarpleColors.Ruby.copy(alpha = 0.34f),
                                        ShareFlowMarpleColors.Cream.copy(alpha = 0.10f),
                                        Color.Transparent
                                    )
                                } else {
                                    listOf(
                                        ShareFlowMarpleColors.Ruby.copy(alpha = 0.16f),
                                        ShareFlowMarpleColors.Cream.copy(alpha = 0.36f),
                                        Color.Transparent
                                    )
                                },
                                center = Offset(bloomCenterX, size.height * 0.52f),
                                radius = size.height * 1.45f
                            ),
                            center = Offset(bloomCenterX, size.height * 0.52f),
                            radius = size.height * 1.45f
                        )
                    }
            )

            // Layer 2: Unblurred translucent glass surface glaze + crisp top inner specular sheen
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(brush = frostedGlazeBrush)
            )

            // Layer 3: Crisp Material 3 interactive navigation items inside ONE unified glass card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                destinations.forEach { destination ->
                    val isSelected = currentRoute == destination.route
                    val label = stringResource(destination.labelRes)

                    val iconTint by animateColorAsState(
                        targetValue = if (isSelected) activeIconColor else neutralInactiveIcon,
                        animationSpec = tween(durationMillis = 200),
                        label = "nav_icon_tint_${destination.route}"
                    )
                    val textTint by animateColorAsState(
                        targetValue = if (isSelected) activeLabelColor else neutralInactiveLabel,
                        animationSpec = tween(durationMillis = 200),
                        label = "nav_text_tint_${destination.route}"
                    )
                    val activeWashColor by animateColorAsState(
                        targetValue = when {
                            isSelected && isDark -> ShareFlowMarpleColors.DarkPrimary.copy(alpha = 0.14f)
                            isSelected -> ShareFlowMarpleColors.Ruby.copy(alpha = 0.09f)
                            else -> Color.Transparent
                        },
                        animationSpec = tween(durationMillis = 220),
                        label = "nav_pill_bg_${destination.route}"
                    )
                    val iconScale by animateFloatAsState(
                        targetValue = if (isSelected) 1.10f else 1.0f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium
                        ),
                        label = "nav_icon_scale_${destination.route}"
                    )
                    val iconLift by animateDpAsState(
                        targetValue = if (isSelected) (-1.5).dp else 0.dp,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium
                        ),
                        label = "nav_icon_lift_${destination.route}"
                    )
                    val indicatorWidth by animateDpAsState(
                        targetValue = if (isSelected) 16.dp else 0.dp,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        ),
                        label = "nav_indicator_${destination.route}"
                    )

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(pillShape)
                            .background(activeWashColor)
                            .minimumInteractiveComponentSize()
                            .semantics { selected = isSelected }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = androidx.compose.material3.ripple(
                                    color = activeIndicatorColor
                                ),
                                role = Role.Tab,
                                onClick = { onNavigateTopLevel(destination.route) }
                            )
                            .testTag(destination.testTag)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Icon(
                                imageVector = if (isSelected) destination.selectedIcon else destination.unselectedIcon,
                                contentDescription = label,
                                tint = iconTint,
                                modifier = Modifier
                                    .size(22.dp)
                                    .offset(y = iconLift)
                                    .graphicsLayer {
                                        scaleX = iconScale
                                        scaleY = iconScale
                                    }
                            )
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                                ),
                                color = textTint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (indicatorWidth > 1.dp) {
                                Box(
                                    modifier = Modifier
                                        .width(indicatorWidth)
                                        .height(2.5.dp)
                                        .clip(CircleShape)
                                        .background(activeIndicatorColor)
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
    topLevelBottomBarClearance: Dp = 0.dp,
    activeTransferCount: Int = 0,
    modifier: Modifier = Modifier
) {
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
        // Top-level destinations: Home, Files, Media, Tools, Settings — ALL share the unified floating glass nav bar
        composable(route = ShareFlowRoutes.HOME) {
            HomeDashboardScreen(
                onNavigateToRoute = navigateToRoute,
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
                bottomBarClearance = topLevelBottomBarClearance
            )
        }
        composable(route = ShareFlowRoutes.TOOLS_HUB) {
            ToolsScreen(
                onNavigateToRoute = navigateToRoute,
                bottomBarClearance = topLevelBottomBarClearance
            )
        }
        composable(route = ShareFlowRoutes.SETTINGS) {
            SettingsScreen(
                onOpenVault = { navigateToRoute(ShareFlowRoutes.PRIVATE_VAULT) },
                bottomBarClearance = topLevelBottomBarClearance
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
