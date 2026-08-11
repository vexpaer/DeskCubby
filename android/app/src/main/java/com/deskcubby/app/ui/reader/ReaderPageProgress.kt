package com.deskcubby.app.ui.reader

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshotFlow
import com.deskcubby.app.data.repository.normalizePageOffsetPercent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal data class ReaderPagePosition(
    val pageIndex: Int,
    val pageOffsetPercent: Int,
)

internal fun quantizeReaderPageOffsetPercent(
    scrollOffsetPx: Int,
    itemSizePx: Int,
): Int {
    if (scrollOffsetPx <= 0 || itemSizePx <= 0) return 0
    val rawPercent = (scrollOffsetPx.toLong() * 100L / itemSizePx.toLong()).toInt()
    return normalizePageOffsetPercent(rawPercent)
}

internal fun LazyListState.currentReaderPagePosition(itemCount: Int): ReaderPagePosition {
    if (itemCount <= 0) return ReaderPagePosition(pageIndex = 0, pageOffsetPercent = 0)
    val pageIndex = firstVisibleItemIndex.coerceIn(0, itemCount - 1)
    val itemSize = layoutInfo.visibleItemsInfo.firstOrNull { item ->
        item.index == pageIndex
    }?.size ?: 0
    return ReaderPagePosition(
        pageIndex = pageIndex,
        pageOffsetPercent = quantizeReaderPageOffsetPercent(
            scrollOffsetPx = firstVisibleItemScrollOffset,
            itemSizePx = itemSize,
        ),
    )
}

internal suspend fun LazyListState.restoreReaderPagePosition(
    position: ReaderPagePosition,
    itemCount: Int,
) {
    if (itemCount <= 0) return
    val pageIndex = position.pageIndex.coerceIn(0, itemCount - 1)
    val pageOffsetPercent = normalizePageOffsetPercent(position.pageOffsetPercent)
    scrollToItem(pageIndex)
    if (pageOffsetPercent == 0) return

    val itemSize = withTimeoutOrNull(READER_PAGE_RESTORE_TIMEOUT_MILLIS) {
        snapshotFlow {
            layoutInfo.visibleItemsInfo.firstOrNull { item -> item.index == pageIndex }?.size ?: 0
        }.first { size -> size > 0 }
    } ?: return
    val pixelOffset = (itemSize.toLong() * pageOffsetPercent / 100L)
        .toInt()
        .coerceIn(0, itemSize - 1)
    scrollToItem(pageIndex, pixelOffset)
}

private const val READER_PAGE_RESTORE_TIMEOUT_MILLIS = 5_000L
