@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.deskcubby.app.ui.thought

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.LabelOff
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.deskcubby.app.data.local.FlashThoughtEntity
import com.deskcubby.app.data.local.ThoughtCategoryEntity
import com.deskcubby.app.data.model.VisualStyle
import com.deskcubby.app.data.repository.ThoughtRepository
import com.deskcubby.app.ui.theme.LocalVisualStyle
import com.deskcubby.app.ui.theme.deskCubbyVisuals
import com.deskcubby.app.ui.theme.tr

private val categoryColors = listOf(
    0xFFE05252.toInt(),
    0xFFEB8C3A.toInt(),
    0xFFE0B72F.toInt(),
    0xFF4E9A62.toInt(),
    0xFF3C9A9A.toInt(),
    0xFF4C78C2.toInt(),
    0xFF8166C2.toInt(),
    0xFFC45E91.toInt(),
    0xFF7B716A.toInt(),
)

private val organicCategoryColors = listOf(
    0xFF2E7D4B.toInt(),
    0xFF5C7A3E.toInt(),
    0xFF3D7665.toInt(),
    0xFF7E8352.toInt(),
    0xFF5F7467.toInt(),
    0xFF8A6F50.toInt(),
    0xFF456B50.toInt(),
    0xFF6B7E62.toInt(),
    0xFF756F5E.toInt(),
)

internal val defaultThoughtCategoryColor: Int = categoryColors.first()

@Composable
internal fun ThoughtCategoryDrawer(
    categories: List<ThoughtCategoryEntity>,
    thoughts: List<FlashThoughtEntity>,
    selected: ThoughtCategoryFilter,
    bottomPadding: Dp,
    onSelect: (ThoughtCategoryFilter) -> Unit,
    onAdd: () -> Unit,
    onEdit: (ThoughtCategoryEntity) -> Unit,
) {
    ModalDrawerSheet(modifier = Modifier.padding(bottom = bottomPadding)) {
        Column(Modifier.fillMaxHeight().padding(vertical = 12.dp)) {
            Text(
                tr("小巧思分类", "Thought categories"),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            ThoughtCategoryDrawerItem(
                label = tr("全部", "All"),
                count = thoughts.size,
                selected = selected == ThoughtCategoryFilter.All,
                leading = { Icon(Icons.Outlined.SelectAll, null) },
                onClick = { onSelect(ThoughtCategoryFilter.All) },
            )
            ThoughtCategoryDrawerItem(
                label = tr("未分类", "Uncategorized"),
                count = thoughts.count { it.categoryId == null },
                selected = selected == ThoughtCategoryFilter.Uncategorized,
                leading = { Icon(Icons.Outlined.LabelOff, null) },
                onClick = { onSelect(ThoughtCategoryFilter.Uncategorized) },
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                items(categories, key = { it.id }) { category ->
                    ThoughtCategoryDrawerItem(
                        label = category.name,
                        count = thoughts.count { it.categoryId == category.id },
                        selected = selected == ThoughtCategoryFilter.Category(category.id),
                        leading = { CategoryColorDot(category.colorArgb) },
                        onClick = { onSelect(ThoughtCategoryFilter.Category(category.id)) },
                        onLongClick = { onEdit(category) },
                    )
                }
                item {
                    TextButton(
                        onClick = onAdd,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Icon(Icons.Outlined.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text(tr("新增分类", "New category"), modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ThoughtCategoryDrawerItem(
    label: String,
    count: Int,
    selected: Boolean,
    leading: @Composable () -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val organic = LocalVisualStyle.current == VisualStyle.ORGANIC_FUTURE
    val visuals = deskCubbyVisuals
    val background = if (selected) {
        if (organic) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.secondaryContainer
    } else {
        Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(if (organic) visuals.listShape else RoundedCornerShape(24.dp))
            .background(background)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { leading() }
        Spacer(Modifier.width(12.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun ThoughtCategoryEditorDialog(
    category: ThoughtCategoryEntity?,
    categories: List<ThoughtCategoryEntity>,
    onDismiss: () -> Unit,
    onSave: (String, Int, (Boolean) -> Unit) -> Unit,
    onDelete: ((ThoughtCategoryEntity) -> Unit)? = null,
) {
    val organic = LocalVisualStyle.current == VisualStyle.ORGANIC_FUTURE
    val palette = if (organic) organicCategoryColors else categoryColors
    var name by remember(category?.id) { mutableStateOf(category?.name.orEmpty()) }
    var colorArgb by remember(category?.id, organic) {
        mutableIntStateOf(category?.colorArgb ?: palette.first())
    }
    var duplicateName by remember(category?.id) { mutableStateOf(false) }
    var confirmingDelete by remember(category?.id) { mutableStateOf(false) }

    if (confirmingDelete && category != null) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(tr("删除分类？", "Delete category?")) },
            text = {
                Text(
                    tr(
                        "“${category.name}”中的所有小巧思会保留，并归入“未分类”。",
                        "All thoughts in “${category.name}” will be kept and moved to Uncategorized.",
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete?.invoke(category)
                        onDismiss()
                    },
                ) {
                    Text(tr("删除分类", "Delete category"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text(tr("取消", "Cancel")) }
            },
        )
        return
    }

    val normalizedName = name.trim()
    val duplicateInUi = categories.any { existing ->
        existing.id != category?.id && existing.name.equals(normalizedName, ignoreCase = true)
    }
    val canSave = normalizedName.isNotBlank() && !duplicateInUi
    val availableColors = remember(category?.colorArgb, organic) {
        listOfNotNull(category?.colorArgb).plus(palette).distinct()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (category == null) tr("新增分类", "New category") else tr("编辑分类", "Edit category"))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it.take(ThoughtRepository.MAX_CATEGORY_NAME_LENGTH)
                        duplicateName = false
                    },
                    label = { Text(tr("分类名称", "Category name")) },
                    supportingText = if (duplicateInUi || duplicateName) {
                        { Text(tr("已有同名分类", "A category with this name already exists")) }
                    } else {
                        null
                    },
                    isError = duplicateInUi || duplicateName,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(tr("分类颜色", "Category color"), style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    availableColors.forEach { choice ->
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color(choice))
                                .border(
                                    width = if (choice == colorArgb) 3.dp else 1.dp,
                                    color = if (choice == colorArgb) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                    shape = CircleShape,
                                )
                                .combinedClickable(onClick = { colorArgb = choice }),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (choice == colorArgb) {
                                val swatch = Color(choice)
                                Icon(
                                    Icons.Outlined.Check,
                                    null,
                                    tint = if (swatch.luminance() > 0.42f) Color.Black else Color.White,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    onSave(normalizedName, colorArgb) { success ->
                        if (success) onDismiss() else duplicateName = true
                    }
                },
            ) { Text(tr("保存", "Save")) }
        },
        dismissButton = {
            Row {
                if (category != null && onDelete != null) {
                    TextButton(onClick = { confirmingDelete = true }) {
                        Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(4.dp))
                        Text(tr("删除", "Delete"), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) }
            }
        },
    )
}

@Composable
internal fun ThoughtCategoryPickerDialog(
    title: String,
    categories: List<ThoughtCategoryEntity>,
    currentCategoryId: Long?,
    onDismiss: () -> Unit,
    onSelect: (ThoughtCategoryFilter) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                item {
                    CategoryPickerItem(
                        label = tr("未分类", "Uncategorized"),
                        selected = currentCategoryId == null,
                        leading = { Icon(Icons.Outlined.LabelOff, null) },
                        onClick = { onSelect(ThoughtCategoryFilter.Uncategorized) },
                    )
                }
                items(categories, key = { it.id }) { category ->
                    CategoryPickerItem(
                        label = category.name,
                        selected = currentCategoryId == category.id,
                        leading = { CategoryColorDot(category.colorArgb) },
                        onClick = { onSelect(ThoughtCategoryFilter.Category(category.id)) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) } },
    )
}

@Composable
private fun CategoryPickerItem(
    label: String,
    selected: Boolean,
    leading: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { leading() }
        Spacer(Modifier.width(12.dp))
        Text(label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (selected) Icon(Icons.Outlined.Check, tr("当前分类", "Current category"))
    }
}

@Composable
internal fun CategoryColorDot(colorArgb: Int, modifier: Modifier = Modifier) {
    Box(modifier.size(14.dp).clip(CircleShape).background(Color(colorArgb)))
}

internal fun categoryLabel(
    selected: ThoughtCategoryFilter,
    categories: List<ThoughtCategoryEntity>,
    allLabel: String,
    uncategorizedLabel: String,
): String = when (selected) {
    ThoughtCategoryFilter.All -> allLabel
    ThoughtCategoryFilter.Uncategorized -> uncategorizedLabel
    is ThoughtCategoryFilter.Category -> categories.firstOrNull { it.id == selected.id }?.name ?: uncategorizedLabel
}

internal fun ThoughtCategoryFilter.categoryIdOrNullForUi(): Long? =
    (this as? ThoughtCategoryFilter.Category)?.id
