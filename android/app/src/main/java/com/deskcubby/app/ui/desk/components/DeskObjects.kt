package com.deskcubby.app.ui.desk.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.deskcubby.app.ui.desk.model.DeskItem

/**
 * The Today Diary object — a paper-like sheet with a hairline of elevation. This is the primary
 * object on the desk: it opens the existing diary editor via a container-style transition.
 */
@Composable
internal fun DeskDiaryObject(
    item: DeskItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val sheet = scheme.surfaceContainer
    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayerRotation(item.rotationDeg)
            .shadow(3.dp, RoundedCornerShape(4.dp), spotColor = Color.Black.copy(alpha = 0.18f))
            .clip(RoundedCornerShape(4.dp))
            .background(sheet)
            .clickable { onClick() }
            .padding(horizontal = 24.dp, vertical = 22.dp),
    ) {
        Column {
            Text(
                text = "TODAY DIARY",
                color = scheme.onSurfaceVariant,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 2.2.sp,
            )
            Spacer(Modifier.height(14.dp))
            if (item.excerpt.isNotBlank()) {
                Text(
                    text = item.excerpt,
                    color = scheme.onSurface,
                    fontSize = 17.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = item.title,
                    color = scheme.onSurface,
                    fontSize = 17.sp,
                    lineHeight = 26.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(22.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = item.meta,
                    color = scheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/**
 * A small idea slip pinned to the desk. No heavy card chrome — typography, indentation, and a
 * hairline separators build the structure instead of a filled rectangle.
 */
@Composable
internal fun DeskIdeaObject(
    item: DeskItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .graphicsLayerRotation(item.rotationDeg)
            .clickable { onClick() }
            .padding(vertical = 10.dp),
    ) {
        Text(
            text = "╱ IDEA ╱",
            color = scheme.onSurfaceVariant,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 2.0.sp,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = item.title,
            color = scheme.onSurface,
            fontSize = 20.sp,
            lineHeight = 30.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(12.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.meta,
                color = scheme.onSurfaceVariant,
                fontSize = 12.sp,
                letterSpacing = 0.4.sp,
            )
        }
    }
}

/**
 * A photo placed on the desk like a print — varied size, hairline rotation, and a small time
 * caption. Tapping opens the containing diary via the existing editor flow.
 */
@Composable
internal fun DeskPhotoObject(
    item: DeskItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier.graphicsLayerRotation(item.rotationDeg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .shadow(2.dp, RoundedCornerShape(2.dp))
                .clip(RoundedCornerShape(2.dp))
                .background(scheme.surfaceContainer)
                .clickable { onClick() },
        ) {
            if (item.imageUri != null) {
                AsyncImage(
                    model = item.imageUri,
                    contentDescription = item.title.ifBlank { "Photo" },
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                Text(
                    text = "□",
                    color = scheme.onSurfaceVariant,
                    fontSize = 28.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = item.meta.ifBlank { "photo" },
            color = scheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
    }
}

private fun Modifier.graphicsLayerRotation(degrees: Float): Modifier =
    rotate(degrees)
