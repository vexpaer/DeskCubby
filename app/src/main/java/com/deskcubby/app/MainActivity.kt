package com.deskcubby.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.deskcubby.app.ui.DeskCubbyRoot
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DeskCubbyRoot() }
    }
}

/**
 * Keeps exactly one launcher alias enabled so the launcher label matches the
 * "Desk Cubby" / "桌洞" choice in About settings. The desired alias is enabled
 * before the other is disabled so a launcher entry always exists.
 */
fun syncLauncherAlias(context: Context, useChineseName: Boolean) {
    val packageManager = context.packageManager
    val defaultAlias = ComponentName(context, "com.deskcubby.app.LauncherDefault")
    val chineseAlias = ComponentName(context, "com.deskcubby.app.LauncherChinese")

    fun setEnabled(component: ComponentName, enabled: Boolean) {
        val target = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        if (packageManager.getComponentEnabledSetting(component) != target) {
            packageManager.setComponentEnabledSetting(component, target, PackageManager.DONT_KILL_APP)
        }
    }

    if (useChineseName) {
        setEnabled(chineseAlias, true)
        setEnabled(defaultAlias, false)
    } else {
        setEnabled(defaultAlias, true)
        setEnabled(chineseAlias, false)
    }
}
