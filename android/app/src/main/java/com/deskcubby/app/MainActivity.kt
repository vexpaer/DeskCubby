package com.deskcubby.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deskcubby.app.data.model.LauncherIcon
import com.deskcubby.app.ui.DeskCubbyRoot
import com.deskcubby.app.widget.DesktopWidgetRenderer
import com.deskcubby.app.widget.DesktopWidgetNavigationTokenStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val externalNavigation = MutableStateFlow(ExternalNavigationRequest())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        externalNavigation.value = intent.toExternalNavigationRequest()
        enableEdgeToEdge()
        setContent {
            val request = externalNavigation.collectAsStateWithLifecycle().value
            DeskCubbyRoot(
                externalNavigationRoute = request.route,
                externalDiaryUri = request.diaryUri,
                externalGameId = request.gameId,
                onExternalNavigationHandled = {
                    if (externalNavigation.value == request) {
                        externalNavigation.value = ExternalNavigationRequest()
                    }
                },
            )
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        externalNavigation.value = intent.toExternalNavigationRequest()
    }

    private fun android.content.Intent?.toExternalNavigationRequest() = ExternalNavigationRequest(
        route = this?.getStringExtra(DesktopWidgetRenderer.EXTRA_START_ROUTE),
        diaryUri = DesktopWidgetNavigationTokenStore.consumeDiaryUri(
            this?.getStringExtra(DesktopWidgetRenderer.EXTRA_DIARY_TOKEN),
        ),
        gameId = this?.getStringExtra(DesktopWidgetRenderer.EXTRA_GAME_ID),
    )
}

private data class ExternalNavigationRequest(
    val route: String? = null,
    val diaryUri: String? = null,
    val gameId: String? = null,
)

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
