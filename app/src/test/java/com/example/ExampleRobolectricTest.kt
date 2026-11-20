package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `application context resolves ShareFlow app name and DI container`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("ShareFlow", context.getString(R.string.app_name))
        assertTrue(context is ShareFlowApplication)
        val app = context as ShareFlowApplication
        assertNotNull(app.container.settingsRepository)
        assertNotNull(app.container.transferHistoryRepository)
        assertNotNull(app.container.trustedDeviceRepository)
        assertNotNull(app.container.vaultRepository)
    }
}
