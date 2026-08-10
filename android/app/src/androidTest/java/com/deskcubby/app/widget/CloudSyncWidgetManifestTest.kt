package com.deskcubby.app.widget

import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskcubby.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmlpull.v1.XmlPullParser
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudSyncWidgetManifestTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val packageManager = context.packageManager

    @Test
    fun launcherWidgetsAreExportedAndHaveProviderMetadata() {
        listOf(
            DeskCubbyWidgetProvider::class.java to R.xml.desktop_widget_info,
            CloudSyncNowWidgetProvider::class.java to R.xml.cloud_sync_now_widget_info,
            CloudSyncForceWidgetProvider::class.java to R.xml.cloud_sync_force_widget_info,
        ).forEach { (providerClass, expectedMetadata) ->
            val info = receiverInfo(providerClass)
            assertTrue(providerClass.simpleName, info.enabled)
            assertTrue(providerClass.simpleName, info.exported)
            assertEquals(
                expectedMetadata,
                info.metaData.getInt("android.appwidget.provider"),
            )
        }
    }

    @Test
    fun syncActionReceiverIsPrivateToPendingIntentsCreatedByThisApp() {
        val info = receiverInfo(CloudSyncWidgetActionReceiver::class.java)

        assertTrue(info.enabled)
        assertFalse(info.exported)
    }

    @Test
    fun mutablePinResultReceiverIsPrivate() {
        val info = receiverInfo(DesktopWidgetPinResultReceiver::class.java)

        assertTrue(info.enabled)
        assertFalse(info.exported)
    }

    @Test
    fun transientManualQueueStateIsExcludedFromBackupAndTransfer() {
        assertEquals(1, countSharedPreferenceExcludes(R.xml.backup_rules, "cloud_sync_manual_queue.xml"))
        assertEquals(
            2,
            countSharedPreferenceExcludes(R.xml.data_extraction_rules, "cloud_sync_manual_queue.xml"),
        )
    }

    private fun countSharedPreferenceExcludes(xmlId: Int, path: String): Int {
        val parser = context.resources.getXml(xmlId)
        var count = 0
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (
                parser.eventType == XmlPullParser.START_TAG &&
                parser.name == "exclude" &&
                parser.getAttributeValue(null, "domain") == "sharedpref" &&
                parser.getAttributeValue(null, "path") == path
            ) count += 1
            parser.next()
        }
        parser.close()
        return count
    }

    private fun receiverInfo(receiverClass: Class<*>): android.content.pm.ActivityInfo {
        val component = ComponentName(context, receiverClass)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getReceiverInfo(
                component,
                PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getReceiverInfo(component, PackageManager.GET_META_DATA)
        }
    }
}
