package com.example.shareflow.ui.viewer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.MainActivity
import com.example.R
import com.example.ShareFlowApplication
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.SafDocumentInspector
import com.example.shareflow.core.common.ShareFlowFormatters
import com.example.shareflow.core.designsystem.components.PrimaryActionButton
import com.example.shareflow.core.designsystem.components.SFGlassCard
import com.example.shareflow.core.designsystem.components.SFSnackbar
import com.example.shareflow.core.designsystem.components.SecondaryActionButton
import com.example.shareflow.core.designsystem.components.ShareFlowSignatureProgressBar
import com.example.shareflow.core.designsystem.components.ShareFlowTopBar
import com.example.shareflow.core.designsystem.components.StatusPill
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMonoStyle
import com.example.shareflow.core.designsystem.theme.ShareFlowTheme
import com.example.shareflow.core.di.LocalAppContainer
import com.example.shareflow.data.apps.AppInstallSessionState
import com.example.shareflow.data.apps.ApplicationInstallationManager
import com.example.shareflow.data.repository.AndroidApkArchiveInspector
import com.example.shareflow.data.repository.AndroidAppSharingRepository
import com.example.shareflow.network.transfer.ChunkProcessor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipFile
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared security & routing helpers for launching Android's system Package Installer without
 * creating a self-dispatch loop back into [ApkInstallIntentActivity].
 */
object ApkInstallerIntentHelper {

    private const val MAX_STAGED_APK_BYTES = 2L * 1024L * 1024L * 1024L // 2 GiB safety cap

    /**
     * Resolves an external (non-ShareFlow) activity capable of handling `ACTION_VIEW` for
     * `application/vnd.android.package-archive`, preferring Android's system package installer
     * so fallback installs never loop back into ShareFlow itself.
     */
    fun targetExternalSystemInstaller(context: Context, intent: Intent): Intent {
        val pm = context.packageManager
        val resolved = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }
        }.getOrDefault(emptyList())

        val externalCandidates = resolved
            .mapNotNull { it.activityInfo }
            .filter { it.packageName != context.packageName && it.exported }

        val preferred = externalCandidates.firstOrNull { info ->
            info.packageName.contains("packageinstaller", ignoreCase = true)
        } ?: externalCandidates.firstOrNull()

        if (preferred != null) {
            intent.component = ComponentName(preferred.packageName, preferred.name)
        }
        return intent
    }

    internal fun inspectAndStageExternalApk(
        context: Context,
        incomingUri: Uri,
        checksumVerifier: com.example.shareflow.core.security.ChecksumVerifier
    ): StagedIncomingApk? {
        val scheme = incomingUri.scheme?.lowercase().orEmpty()
        if (scheme != "content" && scheme != "file") return null

        val receiveRoot = ChunkProcessor.defaultReceiveRoot(context)
        val canonicalReceiveRoot = runCatching { receiveRoot.canonicalFile }.getOrNull() ?: return null
        val rootPrefix = canonicalReceiveRoot.path.trimEnd(File.separatorChar) + File.separator

        // Proactively clean up stale imported APK staging files older than 24 hours
        cleanupStaleStagedApks(canonicalReceiveRoot)

        val stagedFile: File = if (scheme == "file") {
            val rawPath = incomingUri.path ?: return null
            val sourceFile = File(rawPath)
            if (!sourceFile.exists() || !sourceFile.isFile || sourceFile.length() <= 0L) return null
            val canonicalSource = runCatching { sourceFile.canonicalFile }.getOrNull() ?: return null

            // Security boundary: NEVER read or stage files residing inside ShareFlow's private app
            // directories (databases, SharedPreferences, DataStore, vault blobs, internal cache)
            // unless the canonical path is strictly inside the designated ShareFlow receive root.
            val isInsideReceiveRoot = canonicalSource.path.startsWith(rootPrefix)
            if (!isInsideReceiveRoot && isInsidePrivateAppStorage(context, canonicalSource)) {
                return null
            }

            if (isInsideReceiveRoot &&
                canonicalSource.extension.equals("apk", ignoreCase = true)
            ) {
                canonicalSource
            } else {
                // Pre-validate APK archive structure BEFORE copying any external file into staging
                if (!isValidApkZip(canonicalSource)) return null
                copyStreamToConfinedStaging(
                    receiveRoot = canonicalReceiveRoot,
                    displayName = sourceFile.name,
                    openStream = { FileInputStream(canonicalSource) }
                ) ?: return null
            }
        } else {
            val inspected = runCatching {
                SafDocumentInspector.inspectUris(context, listOf(incomingUri)).firstOrNull()
            }.getOrNull()
            val displayName = inspected?.displayName?.ifBlank { null } ?: "package.apk"
            copyStreamToConfinedStaging(
                receiveRoot = canonicalReceiveRoot,
                displayName = displayName,
                openStream = { context.contentResolver.openInputStream(incomingUri) }
            ) ?: return null
        }

        if (!isValidApkZip(stagedFile)) {
            if (!stagedFile.canonicalPath.startsWith(rootPrefix) ||
                stagedFile.parentFile?.name == STAGING_SUBDIR
            ) {
                runCatching { stagedFile.delete() }
            }
            return null
        }

        val sha256 = runCatching {
            FileInputStream(stagedFile).use { stream ->
                when (val res = checksumVerifier.computeSha256(stream)) {
                    is AppResult.Success -> res.data
                    is AppResult.Failure -> null
                }
            }
        }.getOrNull()

        val metadata = runCatching {
            AndroidApkArchiveInspector(context).inspectApkArchive(stagedFile)
        }.getOrNull()

        return StagedIncomingApk(
            stagedFile = stagedFile,
            originalUri = incomingUri,
            displayName = stagedFile.name,
            appLabel = metadata?.appLabel ?: stagedFile.name.removeSuffix(".apk"),
            packageName = metadata?.packageName,
            versionName = metadata?.versionName,
            versionCode = metadata?.versionCode,
            sizeBytes = stagedFile.length(),
            sha256 = sha256
        )
    }

    internal fun isInsidePrivateAppStorage(context: Context, canonicalFile: File): Boolean {
        val candidatePath = canonicalFile.path
        val privateRoots = buildList {
            runCatching { context.filesDir?.canonicalFile }.getOrNull()?.let { add(it) }
            runCatching { context.cacheDir?.canonicalFile }.getOrNull()?.let { add(it) }
            runCatching { context.noBackupFilesDir?.canonicalFile }.getOrNull()?.let { add(it) }
            runCatching { context.codeCacheDir?.canonicalFile }.getOrNull()?.let { add(it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                runCatching { context.dataDir?.canonicalFile }.getOrNull()?.let { add(it) }
            }
            runCatching { context.filesDir?.parentFile?.canonicalFile }.getOrNull()?.let { add(it) }
        }
        return privateRoots.any { root ->
            val prefix = root.path.trimEnd(File.separatorChar) + File.separator
            candidatePath == root.path || candidatePath.startsWith(prefix)
        }
    }

    internal fun cleanupStaleStagedApks(
        receiveRoot: File,
        nowEpochMillis: Long = System.currentTimeMillis(),
        maxAgeMillis: Long = STALE_STAGING_MAX_AGE_MS
    ): Int {
        val stagingDir = File(receiveRoot, STAGING_SUBDIR)
        if (!stagingDir.exists() || !stagingDir.isDirectory) return 0
        var removed = 0
        stagingDir.listFiles()?.forEach { child ->
            if (child.isFile && (nowEpochMillis - child.lastModified()) > maxAgeMillis) {
                if (runCatching { child.delete() }.getOrDefault(false)) {
                    removed++
                }
            }
        }
        return removed
    }

    private fun copyStreamToConfinedStaging(
        receiveRoot: File,
        displayName: String,
        openStream: () -> java.io.InputStream?
    ): File? {
        val stagingDir = File(receiveRoot, STAGING_SUBDIR).apply { mkdirs() }
        val canonicalStagingPrefix = runCatching { stagingDir.canonicalPath + File.separator }.getOrNull()
            ?: return null
        val sanitizedBase = displayName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(64)
            .ifBlank { "incoming_package" }
        val apkName = if (sanitizedBase.endsWith(".apk", ignoreCase = true)) {
            sanitizedBase
        } else {
            "$sanitizedBase.apk"
        }
        val target = File(stagingDir, "${System.currentTimeMillis()}_$apkName")
        val canonicalTarget = runCatching { target.canonicalFile }.getOrNull() ?: return null
        if (!canonicalTarget.path.startsWith(canonicalStagingPrefix)) return null

        val input = runCatching { openStream() }.getOrNull() ?: return null
        return runCatching {
            var totalBytes = 0L
            input.use { src ->
                FileOutputStream(canonicalTarget).use { dst ->
                    val buffer = ByteArray(64 * 1024)
                    var read = src.read(buffer)
                    while (read != -1) {
                        totalBytes += read
                        if (totalBytes > MAX_STAGED_APK_BYTES) {
                            throw IllegalStateException("APK exceeds maximum supported size")
                        }
                        dst.write(buffer, 0, read)
                        read = src.read(buffer)
                    }
                    dst.flush()
                }
            }
            if (totalBytes <= 0L) {
                canonicalTarget.delete()
                null
            } else {
                canonicalTarget
            }
        }.getOrElse {
            runCatching { canonicalTarget.delete() }
            null
        }
    }

    private fun isValidApkZip(file: File): Boolean = runCatching {
        ZipFile(file).use { zip ->
            zip.getEntry("AndroidManifest.xml") != null
        }
    }.getOrDefault(false)

    private const val STAGING_SUBDIR = "external_apk_imports"
    private const val STALE_STAGING_MAX_AGE_MS = 24L * 60L * 60L * 1000L
}

data class StagedIncomingApk(
    val stagedFile: File,
    val originalUri: Uri,
    val displayName: String,
    val appLabel: String,
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long?,
    val sizeBytes: Long,
    val sha256: String?
)

/**
 * Edge-to-Edge Intent Receiver Activity for launching & installing APK files (`ACTION_VIEW` for
 * `application/vnd.android.package-archive`).
 *
 * Explicitly calls [WindowCompat.setDecorFitsSystemWindows] with `false`, sets transparent system
 * bars, and applies real Jetpack Compose [WindowInsets] (`statusBars`, `navigationBars`, `ime`,
 * `displayCutout`) so installer action buttons never overlap Gesture Navigation or 3-Button
 * Navigation bars.
 */
class ApkInstallIntentActivity : FragmentActivity() {

    private var activeIntentUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appContainer = (application as ShareFlowApplication).container
        val initialSettings = appContainer.settingsRepository.peekInitialSettings()
        val systemInDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val initialIsDark = MainActivity.resolveInitialDarkTheme(
            themeMode = initialSettings.themeMode,
            systemInDark = systemInDark
        )
        val targetWindowBg = if (initialIsDark) {
            MainActivity.WINDOW_BG_DARK
        } else {
            MainActivity.WINDOW_BG_LIGHT
        }

        val barStyle = if (initialIsDark) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        enableEdgeToEdge(
            statusBarStyle = barStyle,
            navigationBarStyle = barStyle
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        window.decorView.setBackgroundColor(targetWindowBg)

        activeIntentUri = intent?.data

        setContent {
            val settings by appContainer.settingsRepository.settings
                .collectAsStateWithLifecycle(initialValue = initialSettings)
            CompositionLocalProvider(LocalAppContainer provides appContainer) {
                ShareFlowTheme(
                    themeMode = settings.themeMode,
                    useDynamicColor = settings.useDynamicColor,
                    accentColor = settings.accentColor,
                    appLanguage = settings.appLanguage
                ) {
                    ApkInstallIntentScreen(
                        incomingUri = activeIntentUri,
                        onClose = { finish() }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        activeIntentUri = intent.data
    }
}

@Composable
internal fun ApkInstallIntentScreen(
    incomingUri: Uri?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val spacing = ShareFlowDesign.spacing
    val semantic = ShareFlowDesign.semanticColors
    val locale = ShareFlowDesign.locale

    val installState by container.applicationInstallationManager.installState.collectAsStateWithLifecycle()
    var isInspecting by remember(incomingUri) { mutableStateOf(incomingUri != null) }
    var stagedApk by remember(incomingUri) { mutableStateOf<StagedIncomingApk?>(null) }
    var inspectError by remember(incomingUri) { mutableStateOf<String?>(null) }
    var launchedConfirmationSessionId by remember { mutableStateOf(-1) }

    val invalidUriMessage = stringResource(R.string.apk_intent_invalid_uri)
    val fallbackFailedMessage = stringResource(R.string.apk_intent_system_fallback_failed)

    // Inspect and stage incoming APK safely off the main thread
    LaunchedEffect(incomingUri) {
        container.applicationInstallationManager.resetState()
        if (incomingUri == null) {
            isInspecting = false
            stagedApk = null
            inspectError = invalidUriMessage
            return@LaunchedEffect
        }
        isInspecting = true
        inspectError = null
        val staged = withContext(Dispatchers.IO) {
            ApkInstallerIntentHelper.inspectAndStageExternalApk(
                context = context,
                incomingUri = incomingUri,
                checksumVerifier = container.checksumVerifier
            )
        }
        stagedApk = staged
        isInspecting = false
        if (staged == null) {
            inspectError = invalidUriMessage
        }
    }

    // Resume installation automatically when user returns from Unknown Sources settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    container.applicationInstallationManager.resumePendingInstallIfPermitted()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            container.applicationInstallationManager.resetState()
        }
    }

    // Automatically launch Android's official PackageInstaller confirmation dialog when ready
    LaunchedEffect(installState) {
        val pending = installState as? AppInstallSessionState.AwaitingUserConfirmation
            ?: return@LaunchedEffect
        if (launchedConfirmationSessionId != pending.sessionId) {
            launchedConfirmationSessionId = pending.sessionId
            runCatching {
                context.startActivity(pending.confirmationIntent)
            }
        }
    }

    fun launchExternalSystemInstaller(file: File?) {
        val intent = if (file != null) {
            container.applicationInstallationManager.createSystemInstallerFallbackIntent(file)
        } else {
            null
        } ?: incomingUri?.let { uri ->
            ApkInstallerIntentHelper.targetExternalSystemInstaller(
                context,
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, AndroidAppSharingRepository.APK_MIME_TYPE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
        val launched = intent != null && runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
        if (!launched) {
            scope.launch { snackbarHostState.showSnackbar(fallbackFailedMessage) }
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .testTag("apk_intent_receiver_screen"),
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = {
                SnackbarHost(hostState = snackbarHostState) { data ->
                    SFSnackbar(snackbarData = data)
                }
            },
            topBar = {
                ShareFlowTopBar(
                    title = stringResource(R.string.apk_intent_title),
                    onNavigateBack = onClose
                )
            },
            bottomBar = {
                // Edge-to-Edge bottom action dock strictly padded above Navigation Bars + IME + Cutouts
                // so Gesture Navigation and 3-Button Navigation bars NEVER overlap installer buttons.
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("apk_intent_bottom_dock"),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = ShareFlowDesign.elevation.raised,
                    shadowElevation = 6.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(
                                WindowInsets.navigationBars
                                    .union(WindowInsets.ime)
                                    .union(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                            )
                            .padding(horizontal = spacing.screenHorizontal, vertical = spacing.md),
                        verticalArrangement = Arrangement.spacedBy(spacing.xs)
                    ) {
                        val currentStaged = stagedApk
                        when (val state = installState) {
                            is AppInstallSessionState.NeedsUnknownSourcesPermission -> {
                                PrimaryActionButton(
                                    text = stringResource(R.string.apk_intent_open_settings),
                                    onClick = {
                                        val settingsIntent = container.permissionManager
                                            .createManageUnknownSourcesIntent()
                                        runCatching { context.startActivity(settingsIntent) }
                                    },
                                    leadingIcon = Icons.Filled.Settings,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("apk_intent_settings_button")
                                )
                            }

                            is AppInstallSessionState.Succeeded -> {
                                val launchIntent = remember(state.packageName) {
                                    container.applicationInstallationManager
                                        .resolveLaunchIntent(state.packageName)
                                }
                                if (launchIntent != null) {
                                    PrimaryActionButton(
                                        text = stringResource(R.string.apk_intent_open_app),
                                        onClick = {
                                            runCatching { context.startActivity(launchIntent) }
                                            onClose()
                                        },
                                        leadingIcon = Icons.AutoMirrored.Filled.OpenInNew,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("apk_intent_open_app_button")
                                    )
                                }
                            }

                            else -> {
                                val isBusy = isInspecting ||
                                    state is AppInstallSessionState.Validating ||
                                    state is AppInstallSessionState.Staging
                                PrimaryActionButton(
                                    text = stringResource(R.string.viewer_action_install_apk),
                                    onClick = {
                                        val target = currentStaged ?: return@PrimaryActionButton
                                        scope.launch {
                                            container.applicationInstallationManager.startInstallationFromFile(
                                                apkFile = target.stagedFile,
                                                expectedSha256 = target.sha256
                                            )
                                        }
                                    },
                                    enabled = currentStaged != null && !isBusy,
                                    leadingIcon = Icons.Filled.InstallMobile,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("apk_intent_install_button")
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
                            if (currentStaged != null || incomingUri != null) {
                                SecondaryActionButton(
                                    text = stringResource(R.string.receive_app_install_fallback_action),
                                    onClick = { launchExternalSystemInstaller(currentStaged?.stagedFile) },
                                    outlined = true,
                                    leadingIcon = Icons.AutoMirrored.Filled.OpenInNew,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("apk_intent_system_installer_button")
                                )
                            }
                            SecondaryActionButton(
                                text = stringResource(R.string.action_close),
                                onClick = onClose,
                                outlined = true,
                                leadingIcon = Icons.Filled.Close,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("apk_intent_close_button")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = spacing.maxContentWidth)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = spacing.screenHorizontal, vertical = spacing.md),
                    verticalArrangement = Arrangement.spacedBy(spacing.md)
                ) {
                    if (isInspecting) {
                        SFGlassCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("apk_intent_loading_card")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(spacing.lg),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(spacing.md)
                            ) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.5.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp)
                                )
                                Text(
                                    text = stringResource(R.string.apk_intent_inspecting),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    } else if (inspectError != null && stagedApk == null) {
                        SFGlassCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("apk_intent_error_card")
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(spacing.lg),
                                verticalArrangement = Arrangement.spacedBy(spacing.sm),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(44.dp)
                                )
                                Text(
                                    text = inspectError.orEmpty(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    } else if (stagedApk != null) {
                        val apk = stagedApk!!
                        SFGlassCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("apk_intent_metadata_card")
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(spacing.lg),
                                verticalArrangement = Arrangement.spacedBy(spacing.sm)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(spacing.md)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        modifier = Modifier.size(56.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Filled.Android,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.size(30.dp)
                                            )
                                        }
                                    }
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = apk.appLabel,
                                            style = MaterialTheme.typography.titleLarge.copy(
                                                fontWeight = FontWeight.Bold
                                            ),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        StatusPill(
                                            label = stringResource(R.string.apk_intent_ready_badge),
                                            containerColor = semantic.verifiedContainer,
                                            contentColor = semantic.verifiedBadge
                                        )
                                    }
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                                apk.packageName?.let { pkg ->
                                    ApkMetadataLine(
                                        label = stringResource(R.string.apk_intent_package_label),
                                        value = pkg
                                    )
                                }
                                if (!apk.versionName.isNullOrBlank() || apk.versionCode != null) {
                                    val verText = buildString {
                                        if (!apk.versionName.isNullOrBlank()) append("v${apk.versionName}")
                                        if (apk.versionCode != null) {
                                            if (isNotEmpty()) append(" ")
                                            append("(${apk.versionCode})")
                                        }
                                    }
                                    ApkMetadataLine(
                                        label = stringResource(R.string.apk_intent_version_label),
                                        value = verText
                                    )
                                }
                                ApkMetadataLine(
                                    label = stringResource(R.string.apk_intent_size_label),
                                    value = ShareFlowFormatters.formatBytes(apk.sizeBytes, locale)
                                )
                                apk.sha256?.let { hash ->
                                    ApkMetadataLine(
                                        label = stringResource(R.string.apk_intent_sha256_label),
                                        value = hash.take(24) + "…"
                                    )
                                }
                            }
                        }

                        // Live PackageInstaller session status card
                        ApkInstallStateBanner(installState = installState)

                        Surface(
                            shape = ShareFlowDesign.shapes.card,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(spacing.md),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Security,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = stringResource(R.string.apk_intent_security_note),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(spacing.md))
                }
            }
        }
    }
}

@Composable
private fun ApkMetadataLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = ShareFlowMonoStyle,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ApkInstallStateBanner(installState: AppInstallSessionState) {
    val spacing = ShareFlowDesign.spacing
    val semantic = ShareFlowDesign.semanticColors
    when (installState) {
        is AppInstallSessionState.Validating -> {
            Surface(
                shape = ShareFlowDesign.shapes.card,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Text(
                        text = stringResource(R.string.apk_intent_inspecting),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        is AppInstallSessionState.Staging -> {
            val percent = (installState.progressFraction * 100f).roundToInt().coerceIn(0, 100)
            Surface(
                shape = ShareFlowDesign.shapes.card,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(spacing.md),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs)
                ) {
                    Text(
                        text = stringResource(R.string.apk_intent_staging, percent),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    ShareFlowSignatureProgressBar(
                        progress = installState.progressFraction,
                        modifier = Modifier.fillMaxWidth(),
                        height = 8.dp
                    )
                }
            }
        }

        is AppInstallSessionState.NeedsUnknownSourcesPermission -> {
            Surface(
                shape = ShareFlowDesign.shapes.card,
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.apk_intent_permission_needed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(spacing.md)
                )
            }
        }

        is AppInstallSessionState.AwaitingUserConfirmation -> {
            Surface(
                shape = ShareFlowDesign.shapes.card,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.apk_intent_awaiting_confirm),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(spacing.md)
                )
            }
        }

        is AppInstallSessionState.Succeeded -> {
            Surface(
                shape = ShareFlowDesign.shapes.card,
                color = semantic.verifiedContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("apk_intent_success_banner")
            ) {
                Row(
                    modifier = Modifier.padding(spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = semantic.verifiedBadge
                    )
                    Text(
                        text = stringResource(R.string.apk_intent_installed_success),
                        style = MaterialTheme.typography.bodyMedium,
                        color = semantic.verifiedBadge
                    )
                }
            }
        }

        is AppInstallSessionState.Failed -> {
            Surface(
                shape = ShareFlowDesign.shapes.card,
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("apk_intent_failed_banner")
            ) {
                Row(
                    modifier = Modifier.padding(spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                ) {
                    Icon(
                        imageVector = Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = installState.reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        AppInstallSessionState.Idle -> Unit
    }
}
