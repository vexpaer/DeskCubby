package com.deskcubby.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.ui.theme.tr

/**
 * Full-screen first-launch language picker. It renders before the navigation graph so a new user
 * chooses the UI language before seeing any screen; the choice is applied immediately and the
 * device-local flag keeps it from showing again (it is never backed up).
 */
@Composable
internal fun FirstLaunchLanguageScreen(
    onChoose: (AppLanguage) -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Outlined.AutoStories,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "DeskCubby",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                tr(
                    "欢迎！请选择软件语言。",
                    "Welcome! Please choose your language.",
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            FirstLaunchLanguageOption(AppLanguage.CHINESE, "简体中文", "Chinese (Simplified)", onChoose)
            FirstLaunchLanguageOption(AppLanguage.TRADITIONAL_CHINESE, "繁體中文", "Chinese (Traditional)", onChoose)
            FirstLaunchLanguageOption(AppLanguage.ENGLISH, "English", "English", onChoose)
            FirstLaunchLanguageOption(AppLanguage.KOREAN, "한국어", "Korean", onChoose)
            FirstLaunchLanguageOption(AppLanguage.JAPANESE, "日本語", "Japanese", onChoose)
        }
    }
}

@Composable
private fun FirstLaunchLanguageOption(
    language: AppLanguage,
    nativeName: String,
    englishName: String,
    onChoose: (AppLanguage) -> Unit,
) {
    OutlinedButton(
        onClick = { onChoose(language) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(56.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(nativeName, fontWeight = FontWeight.SemiBold)
            Text(
                englishName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
