package app.tavern.nativeclient

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

private enum class MemoryPanelTab(val label: String) {
    CANDIDATES("候选召回"),
    PINNED("已置顶"),
    BLOCKED("已屏蔽"),
}

@Composable
internal fun SharedWorldMemoryDialog(
    world: WorldBook?,
    card: Card?,
    recall: SharedMemoryRecallResult?,
    busy: Boolean,
    dismiss: () -> Unit,
    refresh: () -> Unit,
    pinItem: (WorldBook, Card, RecalledWorldMemory) -> Unit,
    unpinItem: (WorldBook, Card, String) -> Unit,
    editItem: (WorldBook, Card, String, String) -> Unit,
    blockItem: (WorldBook, Card, String, Boolean) -> Unit,
    promoteItem: (WorldBook, Card, String, String) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(MemoryPanelTab.CANDIDATES) }
    var editingTarget by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(world?.id, card?.id) {
        if (world != null && card != null) refresh()
    }

    AlertDialog(
        onDismissRequest = { if (!busy) dismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("跨聊角色记忆", Modifier.weight(1f))
                if (world != null && card != null) {
                    ActionIcon(Icons.Outlined.Refresh, "刷新记忆候选", !busy, refresh)
                }
            }
        },
        text = {
            if (world == null || card == null) {
                Text("当前对话尚未绑定开启「联通角色记忆」的世界书与角色卡。")
            } else if (!world.sharedMemory) {
                Text("世界书「${world.name}」尚未开启「联通角色记忆」。请在世界书详情页开启。")
            } else {
                val overrides = world.memoryOverridesFor(card.id)
                val recallItems = recall?.takeIf { it.worldId == world.id && it.cardId == card.id }
                val candidates = recallItems?.candidates.orEmpty()
                val injectedKeys = recallItems?.items?.mapTo(hashSetOf()) { it.turn.replyKey }.orEmpty()
                val blocked = recallItems?.blockedItems.orEmpty()
                Column(Modifier.heightIn(max = 470.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        buildString {
                            append("世界书: ").append(world.name).append(" · 角色: ").append(card.name)
                            if (recallItems != null) {
                                append(if (recallItems.usedVectors) " · 混合检索(向量+关键词)" else " · 关键词检索")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MemoryPanelTab.entries.forEach { currentTab ->
                            val count = when (currentTab) {
                                MemoryPanelTab.CANDIDATES -> candidates.size
                                MemoryPanelTab.PINNED -> overrides.pinned.size
                                MemoryPanelTab.BLOCKED -> overrides.blockedKeys.size
                            }
                            FilterChip(
                                selected = tab == currentTab,
                                onClick = { tab = currentTab },
                                label = { Text("${currentTab.label}($count)") },
                            )
                        }
                    }
                    LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (tab) {
                            MemoryPanelTab.CANDIDATES -> {
                                if (candidates.isEmpty()) {
                                    item { Text("暂无其他关联对话的记忆片段。", style = MaterialTheme.typography.bodyMedium) }
                                } else {
                                    items(candidates, key = { "${it.turn.chatId}:${it.turn.replyKey}" }) { item ->
                                        val isInjected = item.turn.replyKey in injectedKeys
                                        MemoryEntryCard(
                                            title = item.turn.title,
                                            subtitle = buildString {
                                                append(if (item.turn.group) "群聊" else "单聊")
                                                if (isInjected) append(" · 本轮注入")
                                                if (item.pinned) append(" · 已置顶")
                                                if (item.edited) append(" · 已编辑")
                                                append(" · 分数 ${String.format(Locale.US, "%.2f", item.hybridScore)}")
                                            },
                                            body = item.displayText,
                                            actions = {
                                                if (item.pinned && item.pinId != null) {
                                                    TextButton({ unpinItem(world, card, item.pinId) }, enabled = !busy) { Text("取消置顶") }
                                                } else {
                                                    TextButton({ pinItem(world, card, item) }, enabled = !busy) { Text("置顶") }
                                                }
                                                TextButton({ editingTarget = item.turn.replyKey to item.displayText }, enabled = !busy) { Text("编辑") }
                                                TextButton({ blockItem(world, card, item.turn.replyKey, true) }, enabled = !busy) { Text("屏蔽") }
                                                TextButton({ promoteItem(world, card, item.displayText, item.turn.title) }, enabled = !busy) {
                                                    Text("写入世界书")
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                            MemoryPanelTab.PINNED -> {
                                if (overrides.pinned.isEmpty()) {
                                    item { Text("暂无已置顶的跨聊记忆。", style = MaterialTheme.typography.bodyMedium) }
                                } else {
                                    items(overrides.pinned, key = PinnedWorldMemory::id) { pin ->
                                        val key = pin.text.memoryReplyKey()
                                        val body = overrides.editedTexts[key] ?: pin.text
                                        MemoryEntryCard(
                                            title = pin.sourceTitle.ifBlank { "置顶记忆" },
                                            subtitle = if (pin.group) "群聊 · 置顶优先" else "单聊 · 置顶优先",
                                            body = body,
                                            actions = {
                                                TextButton({ editingTarget = key to body }, enabled = !busy) { Text("编辑") }
                                                TextButton({ unpinItem(world, card, pin.id) }, enabled = !busy) { Text("取消置顶") }
                                                TextButton({ promoteItem(world, card, body, pin.sourceTitle) }, enabled = !busy) { Text("写入世界书") }
                                            },
                                        )
                                    }
                                }
                            }
                            MemoryPanelTab.BLOCKED -> {
                                if (overrides.blockedKeys.isEmpty()) {
                                    item { Text("暂无已屏蔽的记忆片段。", style = MaterialTheme.typography.bodyMedium) }
                                } else {
                                    val blockedByKey = blocked.associateBy { it.turn.replyKey }
                                    items(overrides.blockedKeys.toList(), key = { it }) { key ->
                                        val found = blockedByKey[key]
                                        MemoryEntryCard(
                                            title = found?.turn?.title ?: "已屏蔽片段",
                                            subtitle = if (found?.turn?.group == true) "群聊" else "已从召回排除",
                                            body = found?.displayText ?: key.substringAfter(':'),
                                            actions = {
                                                TextButton({ blockItem(world, card, key, false) }, enabled = !busy) { Text("恢复召回") }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(dismiss, enabled = !busy) { Text("关闭") } },
    )

    editingTarget?.let { (replyKey, initialText) ->
        if (world != null && card != null) {
            var editedText by rememberSaveable(replyKey) { mutableStateOf(initialText) }
            AlertDialog(
                onDismissRequest = { editingTarget = null },
                title = { Text("编辑记忆摘要") },
                text = {
                    OutlinedTextField(
                        value = editedText,
                        onValueChange = { editedText = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6,
                        label = { Text("用于后续召回的记忆文本") },
                    )
                },
                dismissButton = { TextButton({ editingTarget = null }) { Text("取消") } },
                confirmButton = {
                    TextButton(
                        onClick = {
                            editItem(world, card, replyKey, editedText.trim())
                            editingTarget = null
                        },
                        enabled = !busy && editedText.isNotBlank(),
                    ) { Text("保存") }
                },
            )
        }
    }
}

@Composable
private fun MemoryEntryCard(
    title: String,
    subtitle: String,
    body: String,
    actions: @Composable RowScope.() -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(body, style = MaterialTheme.typography.bodySmall, maxLines = 5, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                actions()
            }
        }
    }
}
