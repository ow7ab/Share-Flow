package com.example.shareflow.ui.vault

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.shareflow.core.common.ShareFlowFormatters
import com.example.shareflow.core.designsystem.components.SecondaryActionButton
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMonoStyle
import com.example.shareflow.domain.model.DecryptedVaultItem
import com.example.shareflow.domain.model.VaultAutoLockTimeout
import com.example.shareflow.domain.model.VaultStatusSnapshot
import com.example.shareflow.vault.BiometricCapability

@Composable
internal fun VaultRecoveryKeyReceiptDialog(
    recoveryKey: String,
    onConfirmSaved: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        modifier = Modifier.testTag("vault_recovery_receipt_dialog"),
        icon = { Icon(imageVector = Icons.Filled.Key, contentDescription = null) },
        title = { Text(stringResource(R.string.vault_recovery_receipt_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.vault_recovery_receipt_body),
                    style = MaterialTheme.typography.bodyMedium
                )
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text(
                        text = recoveryKey,
                        style = ShareFlowMonoStyle,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                            .testTag("vault_recovery_key_value")
                    )
                }
                Text(
                    text = stringResource(R.string.vault_recovery_zero_backdoor_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirmSaved,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("vault_recovery_saved_button")
            ) {
                Text(stringResource(R.string.vault_recovery_saved_cta))
            }
        }
    )
}

@Composable
internal fun VaultRecoverPinDialog(
    onSubmit: (recoveryKey: String, newPin: String, confirmPin: String) -> Unit,
    onDismiss: () -> Unit
) {
    var recoveryKey by rememberSaveable { mutableStateOf("") }
    var newPin by rememberSaveable { mutableStateOf("") }
    var confirmPin by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("vault_recover_pin_dialog"),
        icon = { Icon(imageVector = Icons.Filled.Key, contentDescription = null) },
        title = { Text(stringResource(R.string.vault_recover_pin_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.vault_recovery_zero_backdoor_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = recoveryKey,
                    onValueChange = { recoveryKey = it },
                    label = { Text(stringResource(R.string.vault_recovery_key_input_label)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("vault_recovery_key_input")
                )
                OutlinedTextField(
                    value = newPin,
                    onValueChange = { newPin = it.filter { ch -> ch.isDigit() }.take(12) },
                    label = { Text(stringResource(R.string.vault_new_pin_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("vault_recover_new_pin_input")
                )
                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = { confirmPin = it.filter { ch -> ch.isDigit() }.take(12) },
                    label = { Text(stringResource(R.string.vault_confirm_pin_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("vault_recover_confirm_pin_input")
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(recoveryKey, newPin, confirmPin) },
                enabled = recoveryKey.isNotBlank() && newPin.length >= 4 && confirmPin.length >= 4,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("vault_recover_submit_button")
            ) {
                Text(stringResource(R.string.vault_recover_submit))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
internal fun VaultItemDetailsDialog(
    item: DecryptedVaultItem,
    onPreviewInMemory: () -> Unit,
    onRequestExport: () -> Unit,
    onRequestSecureDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val locale = ShareFlowDesign.locale
    val supportsInMemoryPreview = item.originalMimeType.startsWith("image/", ignoreCase = true) ||
        item.originalMimeType.startsWith("text/", ignoreCase = true)

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("vault_item_details_dialog"),
        icon = { Icon(imageVector = Icons.Filled.Shield, contentDescription = null) },
        title = { Text(text = item.displayName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                VaultDetailRow(stringResource(R.string.vault_detail_category), item.category.name)
                VaultDetailRow(stringResource(R.string.media_meta_mime), item.originalMimeType)
                VaultDetailRow(
                    stringResource(R.string.vault_detail_original_size),
                    ShareFlowFormatters.formatBytes(item.originalSizeBytes, locale)
                )
                VaultDetailRow(
                    stringResource(R.string.vault_detail_encrypted_size),
                    ShareFlowFormatters.formatBytes(item.encryptedSizeBytes, locale)
                )
                VaultDetailRow(stringResource(R.string.vault_detail_cipher), "AES-256-GCM (${item.chunkCount} chunks)")
                VaultDetailRow("SHA-256", item.sha256Checksum.take(24) + "…")

                Spacer(modifier = Modifier.height(6.dp))
                if (supportsInMemoryPreview) {
                    SecondaryActionButton(
                        text = stringResource(R.string.vault_action_preview_memory),
                        onClick = onPreviewInMemory,
                        outlined = false,
                        leadingIcon = Icons.Filled.Visibility,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("vault_detail_preview_button")
                    )
                }
                SecondaryActionButton(
                    text = stringResource(R.string.vault_action_export),
                    onClick = onRequestExport,
                    outlined = true,
                    leadingIcon = Icons.Filled.Download,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("vault_detail_export_button")
                )
                SecondaryActionButton(
                    text = stringResource(R.string.vault_action_secure_delete),
                    onClick = onRequestSecureDelete,
                    outlined = true,
                    isDestructive = true,
                    leadingIcon = Icons.Filled.DeleteForever,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("vault_detail_delete_button")
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

@Composable
internal fun VaultInMemoryPreviewDialog(
    preview: VaultPreviewContent,
    onClose: () -> Unit
) {
    val isImage = preview.item.originalMimeType.startsWith("image/", ignoreCase = true)
    val decodedBitmap = remember(preview.item.id) {
        if (isImage) {
            runCatching {
                BitmapFactory.decodeByteArray(preview.bytes, 0, preview.bytes.size)?.asImageBitmap()
            }.getOrNull()
        } else {
            null
        }
    }
    val textBody = remember(preview.item.id) {
        if (!isImage) String(preview.bytes, Charsets.UTF_8).take(12_000) else null
    }

    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.testTag("vault_preview_dialog"),
        title = { Text(preview.item.displayName) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.vault_preview_memory_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                if (decodedBitmap != null) {
                    Image(
                        bitmap = decodedBitmap,
                        contentDescription = preview.item.displayName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                    )
                } else if (textBody != null) {
                    Text(
                        text = textBody,
                        style = ShareFlowMonoStyle
                    )
                } else {
                    Text(
                        text = stringResource(R.string.vault_preview_unavailable),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onClose,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("vault_preview_close_button")
            ) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VaultSecuritySettingsDialog(
    status: VaultStatusSnapshot,
    biometricCapability: BiometricCapability,
    onToggleBiometric: (Boolean) -> Unit,
    onSelectTimeout: (VaultAutoLockTimeout) -> Unit,
    onOpenChangePin: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("vault_security_settings_dialog"),
        title = { Text(stringResource(R.string.vault_security_settings_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.vault_biometric_unlock_label),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = stringResource(
                                if (biometricCapability == BiometricCapability.AVAILABLE) {
                                    R.string.vault_biometric_available_desc
                                } else {
                                    R.string.vault_biometric_unavailable_desc
                                }
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = status.isBiometricEnabled,
                        onCheckedChange = onToggleBiometric,
                        enabled = biometricCapability == BiometricCapability.AVAILABLE || status.isBiometricEnabled,
                        modifier = Modifier.testTag("vault_settings_biometric_switch")
                    )
                }

                Text(
                    text = stringResource(R.string.vault_autolock_title),
                    style = MaterialTheme.typography.titleSmall
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    VaultAutoLockTimeout.entries.forEach { timeout ->
                        FilterChip(
                            selected = status.autoLockTimeout == timeout,
                            onClick = { onSelectTimeout(timeout) },
                            label = { Text(autoLockLabel(timeout)) },
                            modifier = Modifier.testTag("vault_autolock_${timeout.name.lowercase()}")
                        )
                    }
                }

                SecondaryActionButton(
                    text = stringResource(R.string.vault_change_pin_title),
                    onClick = onOpenChangePin,
                    outlined = true,
                    leadingIcon = Icons.Filled.Key,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("vault_open_change_pin_button")
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

@Composable
internal fun VaultChangePinDialog(
    onSubmit: (currentPin: String, newPin: String, confirmPin: String) -> Unit,
    onDismiss: () -> Unit
) {
    var currentPin by rememberSaveable { mutableStateOf("") }
    var newPin by rememberSaveable { mutableStateOf("") }
    var confirmPin by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("vault_change_pin_dialog"),
        title = { Text(stringResource(R.string.vault_change_pin_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = currentPin,
                    onValueChange = { currentPin = it.filter { ch -> ch.isDigit() }.take(12) },
                    label = { Text(stringResource(R.string.vault_current_pin_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("vault_current_pin_input")
                )
                OutlinedTextField(
                    value = newPin,
                    onValueChange = { newPin = it.filter { ch -> ch.isDigit() }.take(12) },
                    label = { Text(stringResource(R.string.vault_new_pin_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("vault_change_new_pin_input")
                )
                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = { confirmPin = it.filter { ch -> ch.isDigit() }.take(12) },
                    label = { Text(stringResource(R.string.vault_confirm_pin_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("vault_change_confirm_pin_input")
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(currentPin, newPin, confirmPin) },
                enabled = currentPin.length >= 4 && newPin.length >= 4 && confirmPin.length >= 4,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("vault_change_pin_confirm_button")
            ) {
                Text(stringResource(R.string.vault_change_pin_submit))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun VaultDetailRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
internal fun autoLockLabel(timeout: VaultAutoLockTimeout): String = when (timeout) {
    VaultAutoLockTimeout.IMMEDIATE -> stringResource(R.string.vault_autolock_immediate)
    VaultAutoLockTimeout.SECONDS_30 -> stringResource(R.string.vault_autolock_30s)
    VaultAutoLockTimeout.MINUTES_1 -> stringResource(R.string.vault_autolock_1m)
    VaultAutoLockTimeout.MINUTES_5 -> stringResource(R.string.vault_autolock_5m)
    VaultAutoLockTimeout.MINUTES_15 -> stringResource(R.string.vault_autolock_15m)
}
