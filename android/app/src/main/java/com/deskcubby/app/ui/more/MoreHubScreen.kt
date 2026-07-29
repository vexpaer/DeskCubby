@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.deskcubby.app.ui.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.NavItemConfig
import com.deskcubby.app.data.model.NavItemId
import com.deskcubby.app.ui.components.FourDotDragHandle
import com.deskcubby.app.ui.iconFor
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.LocalAppLanguage
import com.deskcubby.app.ui.theme.PanelRole
import com.deskcubby.app.ui.theme.tr

/**
 * Resolves a staggered-grid drop by the dragged card's two-dimensional center.
 *
 * A gap between cards intentionally has no target; the caller can retain the last valid target
 * until the drag ends. This avoids vertical-only guesses selecting a card in the wrong column.
 */
internal fun morePageDropTargetIndex(
    orderedIds: List<NavItemId>,
    bounds: Map<NavItemId, Rect>,
    sourceIndex: Int,
    draggedCenter: Offset,
): Int? {
    if (sourceIndex !in orderedIds.indices) return null
    return orderedIds.indices
        .filter { index -> bounds[orderedIds[index]]?.contains(draggedCenter) == true }
        .minByOrNull { index ->
            val center = bounds.getValue(orderedIds[index]).center
            val deltaX = center.x - draggedCenter.x
            val deltaY = center.y - draggedCenter.y
            deltaX * deltaX + deltaY * deltaY
        }
}

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
    onItemsReordered: (List<NavItemId>, (Boolean) -> Unit) -> Unit,
    onOpenNavigationSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var orderedItems by remember { mutableStateOf(items) }
    val cardBounds = remember { mutableStateMapOf<NavItemId, Rect>() }
    val snackbarHostState = remember { SnackbarHostState() }
    val latestItems by rememberUpdatedState(items)
    val latestOnItemsReordered by rememberUpdatedState(onItemsReordered)
    var draggingItemId by remember { mutableStateOf<NavItemId?>(null) }
    var dragOffsetPx by remember { mutableStateOf(Offset.Zero) }
    var dragOrigin by remember { mutableStateOf<Offset?>(null) }
    var dragTargetIndex by remember { mutableStateOf<Int?>(null) }
    var saveInFlight by remember { mutableStateOf(false) }
    var queuedOrder by remember { mutableStateOf<List<NavItemId>?>(null) }
    var awaitingCommittedOrder by remember { mutableStateOf<List<NavItemId>?>(null) }
    var saveFailureCount by remember { mutableStateOf(0) }
    val saveFailureMessage = tr(
        "导航页顺序保存失败，已恢复上次保存的顺序。",
        "Could not save the navigation-page order. The last saved order was restored.",
    )
    val dragSourceIndex = draggingItemId?.let { id ->
        orderedItems.indexOfFirst { it.id == id }.takeIf { it >= 0 }
    }

    LaunchedEffect(items) {
        val incomingOrder = items.map(NavItemConfig::id)
        when {
            incomingOrder == awaitingCommittedOrder -> {
                orderedItems = items
                awaitingCommittedOrder = null
            }
            !saveInFlight && queuedOrder == null && awaitingCommittedOrder == null -> {
                orderedItems = items
            }
        }
        val incomingIds = items.mapTo(mutableSetOf(), NavItemConfig::id)
        cardBounds.keys.toList()
            .filterNot(incomingIds::contains)
            .forEach(cardBounds::remove)
    }

    LaunchedEffect(saveFailureCount) {
        if (saveFailureCount > 0) {
            snackbarHostState.showSnackbar(saveFailureMessage)
        }
    }

    fun clearDrag() {
        draggingItemId = null
        dragOffsetPx = Offset.Zero
        dragOrigin = null
        dragTargetIndex = null
    }

    fun targetIndexFor(sourceIndex: Int, offsetPx: Offset): Int? {
        val origin = dragOrigin
            ?: orderedItems.getOrNull(sourceIndex)?.id?.let(cardBounds::get)?.center
            ?: return null
        return morePageDropTargetIndex(
            orderedIds = orderedItems.map(NavItemConfig::id),
            bounds = cardBounds,
            sourceIndex = sourceIndex,
            draggedCenter = origin + offsetPx,
        )
    }

    fun flushQueuedOrder() {
        if (saveInFlight) return
        val order = queuedOrder ?: return
        queuedOrder = null
        saveInFlight = true
        latestOnItemsReordered(order) { success ->
            saveInFlight = false
            if (success) {
                val nextOrder = queuedOrder
                if (nextOrder != null) {
                    flushQueuedOrder()
                } else {
                    awaitingCommittedOrder = order
                    if (latestItems.map(NavItemConfig::id) == order) {
                        orderedItems = latestItems
                        awaitingCommittedOrder = null
                    }
                }
            } else {
                queuedOrder = null
                awaitingCommittedOrder = null
                orderedItems = latestItems
                saveFailureCount += 1
            }
        }
    }

    fun persistOrder(order: List<NavItemId>) {
        awaitingCommittedOrder = null
        queuedOrder = order
        flushQueuedOrder()
    }

    fun moveItem(fromIndex: Int, toIndex: Int): Boolean {
        if (fromIndex !in orderedItems.indices ||
            toIndex !in orderedItems.indices ||
            fromIndex == toIndex
        ) {
            return false
        }
        orderedItems = orderedItems.toMutableList().apply {
            val moved = removeAt(fromIndex)
            add(toIndex, moved)
        }
        persistOrder(orderedItems.map(NavItemConfig::id))
        return true
    }

    Scaffold(
        modifier = modifier.padding(bottom = padding.calculateBottomPadding()),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                                    "设置导航页",
                                    "Navigation page settings",
                                ),
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        if (orderedItems.isEmpty()) {
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
                    items = orderedItems,
                    key = { _, item -> item.id.route },
                ) { index, item ->
                    key(item.id) {
                        val isDragging = draggingItemId == item.id
                        val isDropTarget = draggingItemId != null &&
                            dragTargetIndex == index &&
                            dragSourceIndex != index
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .zIndex(if (isDragging) 2f else 0f)
                                .onGloballyPositioned {
                                    cardBounds[item.id] = it.boundsInRoot()
                                },
                        ) {
                            MorePageCard(
                                modifier = Modifier.graphicsLayer {
                                    translationX = if (isDragging) dragOffsetPx.x else 0f
                                    translationY = if (isDragging) dragOffsetPx.y else 0f
                                    alpha = if (isDragging) 0.7f else 1f
                                },
                                item = item,
                                index = index,
                                totalItems = orderedItems.size,
                                showDescription = showDescriptions,
                                clickEnabled = draggingItemId == null,
                                onClick = { onOpenPage(item.id) },
                                onDragStarted = {
                                    draggingItemId = item.id
                                    dragOffsetPx = Offset.Zero
                                    dragOrigin = cardBounds[item.id]?.center
                                    dragTargetIndex = index
                                },
                                onDragChanged = { offset ->
                                    dragOffsetPx = offset
                                    targetIndexFor(index, offset)?.let { target ->
                                        dragTargetIndex = target
                                    }
                                },
                                onDragCancelled = ::clearDrag,
                                onMoveUp = if (index > 0) {
                                    { moveItem(index, index - 1) }
                                } else {
                                    null
                                },
                                onMoveDown = if (index < orderedItems.lastIndex) {
                                    { moveItem(index, index + 1) }
                                } else {
                                    null
                                },
                                onDragFinished = { offset ->
                                    val target = targetIndexFor(index, offset)
                                        ?: dragTargetIndex
                                    clearDrag()
                                    if (target != null) moveItem(index, target)
                                },
                            )
                            if (isDropTarget) {
                                HorizontalDivider(
                                    modifier = Modifier
                                        .align(
                                            if (index > (dragSourceIndex ?: index)) {
                                                Alignment.BottomCenter
                                            } else {
                                                Alignment.TopCenter
                                            },
                                        )
                                        .fillMaxWidth(),
                                    thickness = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MorePageCard(
    modifier: Modifier = Modifier,
    item: NavItemConfig,
    index: Int,
    totalItems: Int,
    showDescription: Boolean,
    clickEnabled: Boolean,
    onClick: () -> Unit,
    onDragStarted: () -> Unit,
    onDragChanged: (Offset) -> Unit,
    onDragCancelled: () -> Unit,
    onMoveUp: (() -> Boolean)?,
    onMoveDown: (() -> Boolean)?,
    onDragFinished: (Offset) -> Unit,
) {
    val label = item.localizedLabel()
    val orderDescription = tr(
        "第 ${index + 1} 项，共 $totalItems 项",
        "${index + 1} of $totalItems",
    )
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
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                enabled = clickEnabled,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
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
                FourDotDragHandle(
                    modifier = Modifier.semantics {
                        stateDescription = orderDescription
                    },
                    enabled = totalItems > 1,
                    translateSelf = false,
                    onDragStarted = onDragStarted,
                    onDragOffsetChanged = onDragChanged,
                    onDragCancelled = onDragCancelled,
                    onMoveUp = onMoveUp,
                    onMoveDown = onMoveDown,
                    onDragFinished = {},
                    onDragOffsetFinished = onDragFinished,
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
                        "在导航页设置中选择要放到这里的页面。",
                        "Choose which pages appear here in navigation page settings.",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                onOpenNavigationSettings?.let { openSettings ->
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = openSettings) {
                        Text(tr("设置导航页", "Navigation page settings"))
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
