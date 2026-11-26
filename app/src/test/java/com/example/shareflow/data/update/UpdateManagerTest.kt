package com.example.shareflow.data.update

import com.example.BuildConfig
import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UpdateManagerTest {

    private fun buildClientReturning(statusCode: Int, jsonBody: String, onRequestUrl: (String) -> Unit = {}): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                onRequestUrl(request.url.toString())
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(statusCode)
                    .message(if (statusCode == 200) "OK" else "Error")
                    .body(jsonBody.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

    private fun manifestJson(
        versionCode: Int,
        versionName: String = "2.0.0",
        mandatory: Boolean = false
    ): String = """
        {
          "versionCode": $versionCode,
          "versionName": "$versionName",
          "apkDownloadUrl": "https://github.com/ow7ab/Share-Flow/releases/latest/download/Share-Flow.apk",
          "sha256": "${"a".repeat(64)}",
          "fileSizeBytes": 24500000,
          "minSupportedAndroidSdk": 24,
          "mandatory": $mandatory,
          "releaseNotesEn": "Faster Wi-Fi Direct transfers and unified glass navigation.",
          "releaseNotesAr": "نقل أسرع عبر Wi-Fi Direct وشريط تنقل زجاجي موحد.",
          "portalUrl": "https://ow7ab.github.io/Share-Flow/"
        }
    """.trimIndent()

    @Test
    fun `fetches BuildConfig manifest URL via OkHttp on background dispatcher and detects newer version`() = runTest {
        val requestedUrls = mutableListOf<String>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = UpdateManager(
            okHttpClient = buildClientReturning(200, manifestJson(versionCode = 99, versionName = "2.1.0", mandatory = true)) {
                requestedUrls.add(it)
            },
            dispatcher = dispatcher,
            currentVersionCode = 12,
            currentSdkInt = 34
        )

        val result = manager.checkForUpdate()
        assertTrue(result is AppResult.Success)
        val details = (result as AppResult.Success).data
        assertEquals(BuildConfig.CLOUD_UPDATE_MANIFEST_URL, requestedUrls.single())
        assertTrue(details.isUpdateAvailable)
        assertEquals(99, details.versionCode)
        assertEquals("2.1.0", details.versionName)
        assertTrue(details.mandatory)
        assertTrue(details.releaseNotes.contains("Faster Wi-Fi Direct"))
        assertNotNull(manager.detectedUpdate.value)

        // Mandatory update cannot be dismissed
        manager.dismissOptionalUpdate()
        assertNotNull(manager.detectedUpdate.value)
    }

    @Test
    fun `returns isUpdateAvailable false when installed version is already current or newer`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = UpdateManager(
            okHttpClient = buildClientReturning(200, manifestJson(versionCode = 12, versionName = "1.0.11")),
            dispatcher = dispatcher,
            currentVersionCode = 12,
            currentSdkInt = 34
        )

        val result = manager.checkForUpdate()
        assertTrue(result is AppResult.Success)
        val details = (result as AppResult.Success).data
        assertFalse(details.isUpdateAvailable)
        assertNull(manager.detectedUpdate.value)
    }

    @Test
    fun `dismissOptionalUpdate clears non-mandatory update and blocks non-HTTPS URLs`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val optionalManager = UpdateManager(
            okHttpClient = buildClientReturning(200, manifestJson(versionCode = 20, versionName = "1.2.0", mandatory = false)),
            dispatcher = dispatcher,
            currentVersionCode = 12,
            currentSdkInt = 34
        )
        optionalManager.checkForUpdate()
        assertNotNull(optionalManager.detectedUpdate.value)
        optionalManager.dismissOptionalUpdate()
        assertNull(optionalManager.detectedUpdate.value)

        val insecureManager = UpdateManager(
            okHttpClient = buildClientReturning(200, manifestJson(versionCode = 20)),
            dispatcher = dispatcher,
            manifestUrl = "http://insecure.example.com/version.json",
            currentVersionCode = 12,
            currentSdkInt = 34
        )
        val failure = insecureManager.checkForUpdate()
        assertTrue(failure is AppResult.Failure && failure.error is AppError.Security)
    }

    @Test
    fun `blocks unsafe redirect targets to private network or HTTP before issuing a second request`() = runTest {
        val requestedUrls = mutableListOf<String>()
        val redirectingClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor { chain ->
                val request = chain.request()
                requestedUrls.add(request.url.toString())
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(302)
                    .message("Found")
                    .header("Location", "https://127.0.0.1/internal/version.json")
                    .body("".toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()

        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = UpdateManager(
            okHttpClient = redirectingClient,
            dispatcher = dispatcher,
            manifestUrl = "https://ow7ab.github.io/Share-Flow/version.json",
            fallbackManifestUrl = "",
            currentVersionCode = 12,
            currentSdkInt = 34
        )

        val result = manager.checkForUpdate()
        assertTrue(result is AppResult.Failure && result.error is AppError.Security)
        // Must never issue a network call to 127.0.0.1
        assertEquals(listOf("https://ow7ab.github.io/Share-Flow/version.json"), requestedUrls)
    }

    @Test
    fun `follows validated public HTTPS redirect cleanly to final manifest`() = runTest {
        val requestedUrls = mutableListOf<String>()
        val targetUrl = "https://github.com/ow7ab/Share-Flow/releases/latest/download/version.json"
        val redirectingClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                requestedUrls.add(url)
                if (url == "https://ow7ab.github.io/Share-Flow/version.json") {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(302)
                        .message("Found")
                        .header("Location", targetUrl)
                        .body("".toResponseBody("text/plain".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(manifestJson(versionCode = 50, versionName = "1.5.0").toResponseBody("application/json".toMediaType()))
                        .build()
                }
            }
            .build()

        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = UpdateManager(
            okHttpClient = redirectingClient,
            dispatcher = dispatcher,
            manifestUrl = "https://ow7ab.github.io/Share-Flow/version.json",
            fallbackManifestUrl = "",
            currentVersionCode = 12,
            currentSdkInt = 34
        )

        val result = manager.checkForUpdate()
        assertTrue(result is AppResult.Success)
        assertEquals(50, (result as AppResult.Success).data.versionCode)
        assertEquals(
            listOf("https://ow7ab.github.io/Share-Flow/version.json", targetUrl),
            requestedUrls
        )
    }
}
