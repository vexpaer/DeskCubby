package com.deskcubby.app.ui.reader

import android.content.ComponentName
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderPdfServiceManifestTest {
    @Suppress("DEPRECATION")
    @Test
    fun enhancedPdfServiceIsPackagedEnabledAndIsolated() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertTrue(readerPdfEnhancedServiceAvailable(context))
        val service = context.packageManager.getServiceInfo(
            ComponentName(context.packageName, "androidx.pdf.service.PdfDocumentServiceImpl"),
            0,
        )
        assertTrue(service.enabled)
        assertTrue(service.flags and ServiceInfo.FLAG_ISOLATED_PROCESS != 0)
    }
}
