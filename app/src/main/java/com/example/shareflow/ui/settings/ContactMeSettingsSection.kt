package com.example.shareflow.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues
import com.example.R
import com.example.shareflow.core.designsystem.components.SFGlassCard
import com.example.shareflow.core.designsystem.theme.ShareFlowBrandGradients
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMarpleColors
import com.example.shareflow.core.designsystem.theme.findActivity

/**
 * Official personal contact & social media channels displayed in Settings -> Contact Me.
 */
enum class ContactChannel(
    val uriString: String,
    val expectedScheme: String,
    val expectedHost: String?,
    val testTag: String,
    @param:DrawableRes val iconRes: Int,
    @param:StringRes val labelRes: Int,
    @param:StringRes val contentDescriptionRes: Int
) {
    FACEBOOK(
        uriString = "https://www.facebook.com/ow7ab",
        expectedScheme = "https",
        expectedHost = "www.facebook.com",
        testTag = "settings_contact_facebook",
        iconRes = R.drawable.ic_social_facebook,
        labelRes = R.string.settings_contact_facebook,
        contentDescriptionRes = R.string.settings_contact_facebook_cd
    ),
    LINKEDIN(
        uriString = "https://www.linkedin.com/in/ow7ab/",
        expectedScheme = "https",
        expectedHost = "www.linkedin.com",
        testTag = "settings_contact_linkedin",
        iconRes = R.drawable.ic_social_linkedin,
        labelRes = R.string.settings_contact_linkedin,
        contentDescriptionRes = R.string.settings_contact_linkedin_cd
    ),
    WHATSAPP(
        uriString = "https://wa.me/249963003301",
        expectedScheme = "https",
        expectedHost = "wa.me",
        testTag = "settings_contact_whatsapp",
        iconRes = R.drawable.ic_social_whatsapp,
        labelRes = R.string.settings_contact_whatsapp,
        contentDescriptionRes = R.string.settings_contact_whatsapp_cd
    ),
    EMAIL(
        uriString = "mailto:oaw7ab@gmail.com",
        expectedScheme = "mailto",
        expectedHost = null,
        testTag = "settings_contact_email",
        iconRes = R.drawable.ic_social_email,
        labelRes = R.string.settings_contact_email,
        contentDescriptionRes = R.string.settings_contact_email_cd
    )
}

/**
 * Result of attempting to open an external contact channel.
 */
sealed interface ContactLaunchResult {
    data object Launched : ContactLaunchResult
    data object NoHandlerAvailable : ContactLaunchResult
    data object InvalidTarget : ContactLaunchResult
}

/**
 * Safe Android intent builder and launcher for official external social/email links.
 *
 * Security & Android 11+ compatibility:
 * - Validates strict URI schemes (`https` or `mailto`) and expected hosts before launching.
 * - Uses native `ACTION_VIEW` / `ACTION_SENDTO` directly (never WebView, never extra permissions)
 *   so Android automatically hands off to the installed native app (Facebook, LinkedIn, WhatsApp,
 *   Email client) or falls back cleanly to the default browser when the native app is absent.
 * - Catches `ActivityNotFoundException` and `SecurityException` gracefully without crashing.
 */
object ExternalContactLauncher {

    const val CLICK_DEBOUNCE_MS: Long = 650L

    fun isValidChannelUri(channel: ContactChannel, rawUri: String = channel.uriString): Boolean {
        val parsed = runCatching { Uri.parse(rawUri.trim()) }.getOrNull() ?: return false
        val scheme = parsed.scheme?.lowercase() ?: return false
        if (scheme != channel.expectedScheme) return false
        return if (channel.expectedHost != null) {
            parsed.host.equals(channel.expectedHost, ignoreCase = true)
        } else {
            !parsed.schemeSpecificPart.isNullOrBlank()
        }
    }

    fun buildPrimaryIntent(channel: ContactChannel, needsNewTaskFlag: Boolean): Intent {
        val uri = Uri.parse(channel.uriString)
        val intent = when (channel) {
            ContactChannel.EMAIL -> Intent(Intent.ACTION_SENDTO, uri)
            ContactChannel.FACEBOOK,
            ContactChannel.LINKEDIN,
            ContactChannel.WHATSAPP -> Intent(Intent.ACTION_VIEW, uri)
                .addCategory(Intent.CATEGORY_BROWSABLE)
        }
        if (needsNewTaskFlag) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return intent
    }

    fun buildFallbackIntent(channel: ContactChannel, needsNewTaskFlag: Boolean): Intent? {
        if (channel != ContactChannel.EMAIL) return null
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(channel.uriString))
        if (needsNewTaskFlag) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return intent
    }

    fun openChannel(
        context: Context,
        channel: ContactChannel,
        startActivityFn: (Intent) -> Unit = { intent -> context.startActivity(intent) }
    ): ContactLaunchResult {
        if (!isValidChannelUri(channel)) return ContactLaunchResult.InvalidTarget

        val needsNewTask = context.findActivity() == null
        val primaryIntent = buildPrimaryIntent(channel, needsNewTaskFlag = needsNewTask)
        try {
            startActivityFn(primaryIntent)
            return ContactLaunchResult.Launched
        } catch (_: ActivityNotFoundException) {
            val fallbackIntent = buildFallbackIntent(channel, needsNewTaskFlag = needsNewTask)
                ?: return ContactLaunchResult.NoHandlerAvailable
            return try {
                startActivityFn(fallbackIntent)
                ContactLaunchResult.Launched
            } catch (_: ActivityNotFoundException) {
                ContactLaunchResult.NoHandlerAvailable
            } catch (_: SecurityException) {
                ContactLaunchResult.NoHandlerAvailable
            }
        } catch (_: SecurityException) {
            return ContactLaunchResult.NoHandlerAvailable
        }
    }
}

/**
 * Personal Contact & Social Media section rendered at the bottom of Settings directly below About.
 *
 * Adapts automatically between a balanced 4-column row on wide screens and a clean 2x2 grid on
 * compact phones, supporting Arabic RTL, English LTR, Light/Dark themes, and ShareFlow Marple
 * visual identity (#CA2851, #FF6766, #FFB173, #FFE3B3).
 */
@Composable
fun ContactMeSettingsSection(
    onShowFeedback: (String) -> Unit,
    modifier: Modifier = Modifier,
    onChannelClick: ((ContactChannel) -> Unit)? = null,
    elapsedRealtimeProvider: () -> Long = { SystemClock.elapsedRealtime() }
) {
    val context = LocalContext.current
    val spacing = ShareFlowDesign.spacing
    val semantic = ShareFlowDesign.semanticColors
    var lastTapElapsedMs by remember { mutableLongStateOf(0L) }

    val handleChannelTap: (ContactChannel) -> Unit = { channel ->
        val nowMs = elapsedRealtimeProvider()
        if (nowMs - lastTapElapsedMs >= ExternalContactLauncher.CLICK_DEBOUNCE_MS) {
            lastTapElapsedMs = nowMs
            if (onChannelClick != null) {
                onChannelClick(channel)
            } else {
                val result = ExternalContactLauncher.openChannel(context, channel)
                if (result !is ContactLaunchResult.Launched) {
                    val message = if (channel == ContactChannel.EMAIL) {
                        context.getString(R.string.settings_contact_open_email_error)
                    } else {
                        val label = context.getString(channel.labelRes)
                        context.getString(R.string.settings_contact_open_link_error, label)
                    }
                    onShowFeedback(message)
                }
            }
        }
    }

    SFGlassCard(
        contentPadding = PaddingValues(spacing.cardPadding),
        modifier = modifier
            .fillMaxWidth()
            .testTag("settings_section_contact_me")
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            // Subtle official 4-color ShareFlow Marple accent strip
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.28f)
                    .height(3.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(ShareFlowBrandGradients.signatureHorizontal())
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.settings_section_contact_me),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("settings_contact_me_title")
                )
                Text(
                    text = stringResource(R.string.settings_contact_me_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("settings_contact_me_subtitle")
                )
            }

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val useFourColumnRow = maxWidth >= 420.dp
                val channels = ContactChannel.entries
                if (useFourColumnRow) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        channels.forEach { channel ->
                            ContactChannelTile(
                                channel = channel,
                                isDark = semantic.isDark,
                                onClick = { handleChannelTap(channel) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        channels.chunked(2).forEach { rowChannels ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                rowChannels.forEach { channel ->
                                    ContactChannelTile(
                                        channel = channel,
                                        isDark = semantic.isDark,
                                        onClick = { handleChannelTap(channel) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactChannelTile(
    channel: ContactChannel,
    isDark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val label = stringResource(channel.labelRes)
    val contentDesc = stringResource(channel.contentDescriptionRes)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "contact_tile_scale_${channel.name}"
    )

    val (badgeBackground, iconTint, accentBorderColor) = remember(channel, isDark) {
        contactChannelVisualTokens(channel = channel, isDark = isDark)
    }

    val tileBackground = if (isDark) {
        MaterialTheme.colorScheme.surfaceContainer
    } else {
        MaterialTheme.colorScheme.surface
    }

    val tileBorderColor = if (isPressed) {
        accentBorderColor.copy(alpha = 0.72f)
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = RoundedCornerShape(18.dp),
        color = tileBackground,
        tonalElevation = if (isDark) 2.dp else 1.dp,
        border = BorderStroke(1.dp, tileBorderColor),
        modifier = modifier
            .heightIn(min = 92.dp)
            .minimumInteractiveComponentSize()
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = contentDesc
            }
            .testTag(channel.testTag)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(badgeBackground)
            ) {
                Icon(
                    painter = painterResource(id = channel.iconRes),
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(24.dp)
                )
            }

            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private data class ContactTileTokens(
    val badgeBackground: Color,
    val iconTint: Color,
    val accentBorder: Color
)

/**
 * Harmonizes each social channel icon container with ShareFlow's 4 official Marple colors:
 * #CA2851 (Ruby), #FF6766 (Coral), #FFB173 (Peach), and #FFE3B3 (Cream),
 * preserving high contrast in both Light and Dark modes.
 */
private fun contactChannelVisualTokens(
    channel: ContactChannel,
    isDark: Boolean
): ContactTileTokens = when (channel) {
    ContactChannel.FACEBOOK -> if (isDark) {
        ContactTileTokens(
            badgeBackground = ShareFlowMarpleColors.Ruby.copy(alpha = 0.28f),
            iconTint = ShareFlowMarpleColors.DarkPrimary,
            accentBorder = ShareFlowMarpleColors.DarkPrimary
        )
    } else {
        ContactTileTokens(
            badgeBackground = ShareFlowMarpleColors.Ruby.copy(alpha = 0.13f),
            iconTint = ShareFlowMarpleColors.Ruby,
            accentBorder = ShareFlowMarpleColors.Ruby
        )
    }

    ContactChannel.LINKEDIN -> if (isDark) {
        ContactTileTokens(
            badgeBackground = ShareFlowMarpleColors.Coral.copy(alpha = 0.24f),
            iconTint = ShareFlowMarpleColors.Coral,
            accentBorder = ShareFlowMarpleColors.Coral
        )
    } else {
        ContactTileTokens(
            badgeBackground = ShareFlowMarpleColors.Coral.copy(alpha = 0.16f),
            iconTint = ShareFlowMarpleColors.Ruby,
            accentBorder = ShareFlowMarpleColors.Coral
        )
    }

    ContactChannel.WHATSAPP -> if (isDark) {
        ContactTileTokens(
            badgeBackground = ShareFlowMarpleColors.Peach.copy(alpha = 0.22f),
            iconTint = ShareFlowMarpleColors.Peach,
            accentBorder = ShareFlowMarpleColors.Peach
        )
    } else {
        ContactTileTokens(
            badgeBackground = ShareFlowMarpleColors.Peach.copy(alpha = 0.28f),
            iconTint = ShareFlowMarpleColors.LightOnPeachContainer,
            accentBorder = ShareFlowMarpleColors.Peach
        )
    }

    ContactChannel.EMAIL -> if (isDark) {
        ContactTileTokens(
            badgeBackground = ShareFlowMarpleColors.Cream.copy(alpha = 0.18f),
            iconTint = ShareFlowMarpleColors.Cream,
            accentBorder = ShareFlowMarpleColors.Cream
        )
    } else {
        ContactTileTokens(
            badgeBackground = ShareFlowMarpleColors.Cream.copy(alpha = 0.72f),
            iconTint = ShareFlowMarpleColors.Ruby,
            accentBorder = ShareFlowMarpleColors.Ruby
        )
    }
}
