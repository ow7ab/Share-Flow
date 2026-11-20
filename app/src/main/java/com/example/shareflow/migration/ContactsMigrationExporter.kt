package com.example.shareflow.migration

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.DispatcherProvider
import com.example.shareflow.core.security.ChecksumVerifier
import com.example.shareflow.domain.model.TransferFileType
import com.example.shareflow.domain.model.TransferSourceItem
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import kotlinx.coroutines.withContext

data class ContactsExportArtifact(
    val contactCount: Int,
    val vcfFile: File,
    val sha256Hex: String,
    val transferSourceItem: TransferSourceItem
)

/**
 * Exports device contacts into a standard vCard 3.0 (.vcf) file strictly after verifying
 * explicit runtime `READ_CONTACTS` permission. Never reads private third-party app databases.
 */
class ContactsMigrationExporter(
    private val context: Context,
    private val checksumVerifier: ChecksumVerifier,
    private val dispatchers: DispatcherProvider
) {
    private val appContext = context.applicationContext

    fun hasContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

    suspend fun countContacts(): AppResult<Int> = withContext(dispatchers.io) {
        if (!hasContactsPermission()) {
            return@withContext AppResult.Failure(
                AppError.PermissionDenied("Contacts permission is required to include contacts in migration.")
            )
        }
        runCatching {
            appContext.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID),
                null,
                null,
                null
            )?.use { it.count } ?: 0
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = { AppResult.Failure(AppError.Storage("Unable to query contacts count", it)) }
        )
    }

    suspend fun exportContactsToVcf(): AppResult<ContactsExportArtifact> = withContext(dispatchers.io) {
        if (!hasContactsPermission()) {
            return@withContext AppResult.Failure(
                AppError.PermissionDenied("Contacts permission was not granted.")
            )
        }
        val exportDir = File(appContext.cacheDir, "phone_migration").apply { mkdirs() }
        val vcfFile = File(exportDir, "shareflow_contacts_${System.currentTimeMillis()}.vcf")
        var count = 0

        try {
            val resolver = appContext.contentResolver
            BufferedWriter(OutputStreamWriter(FileOutputStream(vcfFile), Charsets.UTF_8)).use { writer ->
                resolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(
                        ContactsContract.Contacts._ID,
                        ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
                    ),
                    null,
                    null,
                    ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " ASC"
                )?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                    val nameIdx = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                    while (cursor.moveToNext()) {
                        val id = if (idIdx >= 0) cursor.getString(idIdx) else continue
                        val name = (if (nameIdx >= 0) cursor.getString(nameIdx) else null)
                            ?.trim()
                            .orEmpty()
                            .ifEmpty { "Contact $id" }

                        writer.write("BEGIN:VCARD\r\n")
                        writer.write("VERSION:3.0\r\n")
                        writer.write("FN:${escapeVCardValue(name)}\r\n")

                        resolver.query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                            arrayOf(id),
                            null
                        )?.use { phoneCursor ->
                            val numIdx = phoneCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                            while (phoneCursor.moveToNext()) {
                                val number = if (numIdx >= 0) phoneCursor.getString(numIdx)?.trim() else null
                                if (!number.isNullOrBlank()) {
                                    writer.write("TEL:${escapeVCardValue(number)}\r\n")
                                }
                            }
                        }

                        resolver.query(
                            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                            arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
                            "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
                            arrayOf(id),
                            null
                        )?.use { emailCursor ->
                            val addrIdx = emailCursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
                            while (emailCursor.moveToNext()) {
                                val email = if (addrIdx >= 0) emailCursor.getString(addrIdx)?.trim() else null
                                if (!email.isNullOrBlank()) {
                                    writer.write("EMAIL:${escapeVCardValue(email)}\r\n")
                                }
                            }
                        }

                        writer.write("END:VCARD\r\n")
                        count++
                    }
                }
            }

            if (count == 0) {
                vcfFile.delete()
                return@withContext AppResult.Failure(AppError.NotFound("No contacts found on this device."))
            }

            val sha256 = FileInputStream(vcfFile).use { stream ->
                (checksumVerifier.computeSha256(stream) as? AppResult.Success)?.data.orEmpty()
            }

            val sourceItem = TransferSourceItem(
                uriString = Uri.fromFile(vcfFile).toString(),
                fileName = vcfFile.name,
                relativePath = "Contacts/${vcfFile.name}",
                mimeType = "text/vcard",
                fileType = TransferFileType.DOCUMENT,
                fileSizeBytes = vcfFile.length()
            )

            AppResult.Success(
                ContactsExportArtifact(
                    contactCount = count,
                    vcfFile = vcfFile,
                    sha256Hex = sha256,
                    transferSourceItem = sourceItem
                )
            )
        } catch (t: Throwable) {
            runCatching { vcfFile.delete() }
            AppResult.Failure(AppError.Storage("Failed to export contacts: ${t.message}", t))
        }
    }

    private fun escapeVCardValue(raw: String): String =
        raw.replace("\\", "\\\\")
            .replace("\r", "")
            .replace("\n", "\\n")
            .replace(";", "\\;")
            .replace(",", "\\,")
}
