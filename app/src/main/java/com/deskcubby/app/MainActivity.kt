package com.deskcubby.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.deskcubby.app.data.model.LauncherIcon
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
 * selected label and icon. The desired alias is enabled before the others are
 * disabled so a launcher entry always exists while launchers refresh their cache.
 */
fun syncLauncherAlias(
    context: Context,
    useChineseName: Boolean,
    launcherIcon: LauncherIcon,
) {
    val packageManager = context.packageManager
    val aliases = listOf(
        LauncherAlias("LauncherDefault", chinese = false, LauncherIcon.CURRENT),
        LauncherAlias("LauncherChinese", chinese = true, LauncherIcon.CURRENT),
        LauncherAlias("LauncherBookDefault", chinese = false, LauncherIcon.MAGIC_BOOK),
        LauncherAlias("LauncherBookChinese", chinese = true, LauncherIcon.MAGIC_BOOK),
        LauncherAlias("LauncherCubbyDefault", chinese = false, LauncherIcon.DESK_CUBBY),
        LauncherAlias("LauncherCubbyChinese", chinese = true, LauncherIcon.DESK_CUBBY),
    )
    val desired = aliases.first { alias ->
        alias.chinese == useChineseName && alias.icon == launcherIcon
    }

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

    fun component(alias: LauncherAlias) =
        ComponentName(context, "${context.packageName}.${alias.className}")

    setEnabled(component(desired), true)
    aliases.filterNot { it == desired }.forEach { alias ->
        setEnabled(component(alias), false)
    }
}

private data class LauncherAlias(
    val className: String,
    val chinese: Boolean,
    val icon: LauncherIcon,
)
