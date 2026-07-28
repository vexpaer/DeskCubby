package com.deskcubby.app.ui.poetry

import android.graphics.Typeface
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads a user-selected SAF font without converting its content URI into a filesystem path.
 * A missing permission, removed document, or invalid font safely falls back to the app font.
 */
@Composable
fun rememberPoetryFontFamily(fontUri: String?): FontFamily? {
    val context = LocalContext.current
    val family by produceState<FontFamily?>(
        initialValue = null,
        key1 = fontUri,
    ) {
        value = fontUri?.let { raw ->
            withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openFileDescriptor(Uri.parse(raw), "r")?.use { file ->
                        FontFamily(Typeface.Builder(file.fileDescriptor).build())
                    }
                }.getOrNull()
            }
        }
    }
    return family
}
