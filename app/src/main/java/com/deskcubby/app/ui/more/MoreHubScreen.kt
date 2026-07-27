@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.deskcubby.app.ui.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.NavItemConfig
import com.deskcubby.app.data.model.NavItemId
import com.deskcubby.app.ui.iconFor
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.LocalAppLanguage
import com.deskcubby.app.ui.theme.PanelRole
import com.deskcubby.app.ui.theme.tr

/**
 * A compact launcher for pages that do not need to occupy the bottom navigation bar.
 *
 * [items] is intentionally supplied by the caller. The navigation layer remains the source of
 * truth for deciding which pages belong here (normally hidden bottom-navigation items), while
 * this screen preserves each item's custom label and icon.
 */
@Composable
fun MoreHubScreen(
    padding: PaddingValues,
    items: List<NavItemConfig>,
    showDescriptions: Boolean,
    onOpenPage: (NavItemId) -> Unit,
    onOpenNavigationSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.padding(bottom = padding.calculateBottomPadding()),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = tr("导航", "More"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = tr("快捷入口", "Quick access"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    onOpenNavigationSettings?.let { openSettings ->
                        IconButton(onClick = openSettings) {
                            Icon(
                                imageVector = Icons.Outlined.Tune,
                                contentDescription = tr(
                                    "管理底部导航",
                                    "Manage bottom navigation",
                                ),
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        if (items.isEmpty()) {
            EmptyMorePage(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
                onOpenNavigationSettings = onOpenNavigationSettings,
            )
        } else {
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = 12.dp,
                    end = 16.dp,
                    bottom = 28.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalItemSpacing = 12.dp,
            ) {
                itemsIndexed(
                    items = items,
                    key = { _, item -> item.id.route },
                ) { index, item ->
                    MorePageCard(
                        item = item,
                        index = index,
                        showDescription = showDescriptions,
                        onClick = { onOpenPage(item.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MorePageCard(
    item: NavItemConfig,
    index: Int,
    showDescription: Boolean,
    onClick: () -> Unit,
) {
    val label = item.localizedLabel()
    val iconColor = when (index % 3) {
        1 -> MaterialTheme.colorScheme.secondary
        2 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    val iconContainerColor = when (index % 3) {
        1 -> MaterialTheme.colorScheme.secondaryContainer
        2 -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val panelRole = when (index % 3) {
        1 -> PanelRole.FEATURE
        2 -> PanelRole.MEDIA
        else -> PanelRole.STANDARD
    }

    GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = tr("打开$label", "Open $label"),
                role = Role.Button,
                onClick = onClick,
            ),
        cornerRadius = 22.dp,
        role = panelRole,
        padding = PaddingValues(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(iconContainerColor),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = iconFor(item.iconKey),
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(25.dp),
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            item.localizedDescription()
                .takeIf { showDescription && it.isNotBlank() }
                ?.let { description ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
        }
    }
}

@Composable
private fun EmptyMorePage(
    modifier: Modifier,
    onOpenNavigationSettings: (() -> Unit)?,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        GlassPanel(
            modifier = Modifier.fillMaxWidth(),
            role = PanelRole.FEATURE,
            padding = PaddingValues(24.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = tr("还没有收纳的页面", "No pages here yet"),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = tr(
                        "在底部导航设置中选择要放到这里的页面。",
                        "Choose which pages appear here in bottom navigation settings.",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                onOpenNavigationSettings?.let { openSettings ->
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = openSettings) {
                        Text(tr("管理底部导航", "Manage bottom navigation"))
                    }
                }
            }
        }
    }
}

@Composable
private fun NavItemConfig.localizedLabel(): String {
    val language = LocalAppLanguage.current
    return if (language == AppLanguage.ENGLISH && label.isDefaultLabelFor(id)) {
        id.englishLabel
    } else {
        label
    }
}

private fun String.isDefaultLabelFor(id: NavItemId): Boolean =
    this == id.defaultLabel ||
        (id == NavItemId.BLOG && this == "博客") ||
        (id == NavItemId.THOUGHT && this == "闪思")

@Composable
private fun NavItemConfig.localizedDescription(): String {
    val language = LocalAppLanguage.current
    return if (language == AppLanguage.ENGLISH && moreDescription == id.defaultDescription) {
        id.englishDescription
    } else {
        moreDescription
    }
}
