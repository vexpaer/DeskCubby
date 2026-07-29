package com.deskcubby.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.NavItemConfig
import com.deskcubby.app.data.model.NavItemId
import com.deskcubby.app.data.model.normalizeMorePageOrder
import com.deskcubby.app.ui.more.morePageDropTargetIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MorePageOrderTest {
    @Test
    fun displaysVisibleCardsByIndependentOrderInsteadOfBottomBarOrder() {
        val allItems = AppSettings().navItems
            .map { item ->
                item.copy(
                    label = if (item.id == NavItemId.AI_CHAT) "current AI" else item.label,
                    showInMore = item.id == NavItemId.THOUGHT ||
                        item.id == NavItemId.AI_CHAT,
                )
            }
            .reversed()
        val independentOrder = normalizeMorePageOrder(
            listOf(NavItemId.AI_CHAT, NavItemId.THOUGHT),
            allItems,
        )

        val displayed = orderedMorePageItems(allItems, independentOrder)

        assertEquals(
            listOf(NavItemId.AI_CHAT, NavItemId.THOUGHT),
            displayed.map(NavItemConfig::id),
        )
        assertEquals("current AI", displayed.first().label)
    }

    @Test
    fun mergesVisibleReorderWithoutMovingHiddenSlots() {
        val allItems = AppSettings().navItems.map { item ->
            item.copy(
                showInMore = item.id == NavItemId.THOUGHT ||
                    item.id == NavItemId.AI_CHAT,
            )
        }
        val current = normalizeMorePageOrder(
            listOf(NavItemId.THOUGHT, NavItemId.RSS, NavItemId.AI_CHAT),
            allItems,
        )

        val merged = mergeVisibleMorePageOrder(
            allItems = allItems,
            currentOrder = current,
            visibleOrder = listOf(NavItemId.AI_CHAT, NavItemId.THOUGHT),
        )

        assertEquals(NavItemId.AI_CHAT, merged[0])
        assertEquals(NavItemId.RSS, merged[1])
        assertEquals(NavItemId.THOUGHT, merged[2])
        assertEquals(current.drop(3), merged.drop(3))
    }

    @Test
    fun ignoresDuplicatesAndHiddenIdsWhenMergingVisibleOrder() {
        val allItems = AppSettings().navItems.map { item ->
            item.copy(
                showInMore = item.id == NavItemId.THOUGHT ||
                    item.id == NavItemId.AI_CHAT,
            )
        }
        val current = normalizeMorePageOrder(
            listOf(NavItemId.THOUGHT, NavItemId.RSS, NavItemId.AI_CHAT),
            allItems,
        )

        val merged = mergeVisibleMorePageOrder(
            allItems = allItems,
            currentOrder = current,
            visibleOrder = listOf(
                NavItemId.AI_CHAT,
                NavItemId.AI_CHAT,
                NavItemId.RSS,
            ),
        )

        assertEquals(NavItemId.AI_CHAT, merged[0])
        assertEquals(NavItemId.RSS, merged[1])
        assertEquals(NavItemId.THOUGHT, merged[2])
    }

    @Test
    fun twoDimensionalDropTargetDoesNotGuessAcrossColumns() {
        val ids = listOf(NavItemId.THOUGHT, NavItemId.AI_CHAT, NavItemId.RSS)
        val bounds = mapOf(
            NavItemId.THOUGHT to Rect(0f, 0f, 100f, 100f),
            NavItemId.AI_CHAT to Rect(120f, 0f, 220f, 100f),
            NavItemId.RSS to Rect(0f, 120f, 100f, 220f),
        )

        assertEquals(
            2,
            morePageDropTargetIndex(
                orderedIds = ids,
                bounds = bounds,
                sourceIndex = 0,
                draggedCenter = Offset(50f, 150f),
            ),
        )
        assertEquals(
            1,
            morePageDropTargetIndex(
                orderedIds = ids,
                bounds = bounds,
                sourceIndex = 0,
                draggedCenter = Offset(170f, 50f),
            ),
        )
        assertNull(
            morePageDropTargetIndex(
                orderedIds = ids,
                bounds = bounds,
                sourceIndex = 0,
                draggedCenter = Offset(110f, 50f),
            ),
        )
    }
}
