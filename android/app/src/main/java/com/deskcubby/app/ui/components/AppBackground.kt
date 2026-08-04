package com.deskcubby.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.deskcubby.app.data.model.AppSettings

/**
 * Draws the persisted SAF background below the whole navigation graph.
 *
 * The selected image is never copied: Coil reads the persisted content URI directly, and a
 * missing grant naturally falls back to the opaque theme color underneath.
 */
@Composable
fun AppBackground(
    settings: AppSettings,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val uri = settings.backgroundImageUri
    val blurDp = settings.backgroundImageBlurDp.coerceIn(0f, 40f)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        if (uri != null) {
            AsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(blurDp.dp)
                    .graphicsLayer {
                        alpha = settings.backgroundImageOpacity.coerceIn(0f, 1f)
                        // Overscan grows with the blur radius so transparent blur edges remain
                        // outside even a compact phone viewport.
                        val overscan = 1f + blurDp / 160f
                        scaleX = overscan
                        scaleY = overscan
                    },
            )
        }
        content()
    }
}
