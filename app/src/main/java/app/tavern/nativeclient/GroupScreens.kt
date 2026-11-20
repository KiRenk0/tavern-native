package app.tavern.nativeclient

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
internal fun GroupEditor(group: TavernGroup, state: TavernState, save: (TavernGroup) -> Unit) {
    var name by rememberSaveable { mutableStateOf(group.name) }
    var chosen by rememberSaveable { mutableStateOf(group.memberIds.toTypedArray()) }
    var muted by rememberSaveable { mutableStateOf(group.mutedIds.toTypedArray()) }
    var order by rememberSaveable { mutableStateOf(group.order) }
    var mode by rememberSaveable { mutableStateOf(group.promptMode) }
    var allowSelf by rememberSaveable { mutableStateOf(group.allowSelf) }
    var hideMutedSprites by rememberSaveable { mutableStateOf(group.hideMutedSprites) }
    var prefix by rememberSaveable { mutableStateOf(group.prefix) }
    var suffix by rememberSaveable { mutableStateOf(group.suffix) }
    var autoDelay by rememberSaveable { mutableStateOf(group.autoDelaySeconds.toString()) }
    var query by rememberSaveable { mutableStateOf("") }
    val delaySeconds = autoDelay.toIntOrNull()?.takeIf { it in 1..999 }
    val cards = state.cards.associateBy(Card::id)
    Form {
        Field("群组名称", name, { name = it }, enabled = !state.busy)
        SelectField("发言顺序", order, ReplyOrder.entries, ReplyOrder::label, !state.busy) { order = it }
        SelectField("角色上下文", mode, GroupPromptMode.entries, GroupPromptMode::label, !state.busy) { mode = it }
        if (mode != GroupPromptMode.SWAP) {
            Field("合并角色设定前缀", prefix, { prefix = it }, enabled = !state.busy)
            Field("合并角色设定后缀", suffix, { suffix = it }, enabled = !state.busy)
        }
        if (order == ReplyOrder.NATURAL) GroupToggle("允许同一角色连续发言", allowSelf, !state.busy) { allowSelf = it }
        GroupToggle("视觉小说模式隐藏已静音成员", hideMutedSprites, !state.busy) { hideMutedSprites = it }
        Field("自动发言间隔（秒，1–999）", autoDelay, { autoDelay = it }, enabled = !state.busy, type = KeyboardType.Number)
        FormHeading("成员", "可调整顺序、静音或移除角色。")
        chosen.forEachIndexed { index, id ->
            val character = cards[id] ?: return@forEachIndexed
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(character.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val isMuted = id in muted
                ActionIcon(if (isMuted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                    if (isMuted) "恢复 ${character.name} 发言" else "静音 ${character.name}", !state.busy) {
                    muted = if (isMuted) muted.filterNot { it == id }.toTypedArray() else muted + id
                }
                ActionIcon(Icons.Outlined.KeyboardArrowUp, "上移 ${character.name}", !state.busy && index > 0) {
                    chosen = chosen.toMutableList().apply { add(index - 1, removeAt(index)) }.toTypedArray()
                }
                ActionIcon(Icons.Outlined.Close, "移除 ${character.name}", !state.busy) {
                    chosen = chosen.filterNot { it == id }.toTypedArray()
                    muted = muted.filterNot { it == id }.toTypedArray()
                }
            }
        }
        Field("查找并添加角色", query, { query = it }, enabled = !state.busy)
        state.cards.filter { it.id !in chosen && it.name.contains(query.trim(), true) }.forEach { card ->
            TextButton(onClick = { chosen += card.id }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text(card.name)
            }
        }
        Button(onClick = { save(group.copy(name = name, memberIds = chosen.toList(), mutedIds = muted.toSet(),
            order = order, promptMode = mode, allowSelf = allowSelf, prefix = prefix, suffix = suffix,
            autoDelaySeconds = requireNotNull(delaySeconds), hideMutedSprites = hideMutedSprites)) },
            enabled = !state.busy && name.isNotBlank() && chosen.isNotEmpty() && delaySeconds != null,
            modifier = Modifier.fillMaxWidth()) {
            Text("保存群组")
        }
    }
}

@Composable
internal fun GroupDetails(group: TavernGroup, state: TavernState, newChat: () -> Unit,
                          openChat: (String) -> Unit, deleteChat: (ChatSummary) -> Unit, importHistory: () -> Unit,
                          tags: List<LibraryTag>) {
    GroupList {
        item {
            FormHeading(group.name, "${group.order.label} · ${group.promptMode.label}")
            LibraryDetailsTags(tags)
            val members = group.members(state.cards).joinToString("、") {
                if (it.id in group.mutedIds) "${it.name}（静音）" else it.name
            }.ifBlank { "群组还没有角色。" }
            Text(members, Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(newChat, enabled = !state.busy && group.memberIds.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.AddComment, null); Spacer(Modifier.width(8.dp)); Text("新建群聊")
            }
        }
        item { ChatHistoryImportButton(state.busy, importHistory) }
        item { Text("聊天记录", style = MaterialTheme.typography.titleLarge) }
        if (state.history.isEmpty()) item { Text("这里还没有聊天记录。") }
        items(state.history, key = ChatSummary::id) { entry ->
            ChatHistoryRow(entry, state.busy, openChat, deleteChat)
        }
    }
}

@Composable
private fun GroupList(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 740.dp).fillMaxWidth(), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
internal fun <T> SelectField(label: String, value: T, options: List<T>, name: (T) -> String,
                             enabled: Boolean = true, change: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton({ expanded = true }, modifier = Modifier.fillMaxWidth(), enabled = enabled) {
                Text(name(value), Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Outlined.ArrowDropDown, null)
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 340.dp)) {
                options.forEach { item ->
                    DropdownMenuItem(text = { Text(name(item)) }, onClick = { expanded = false; change(item) })
                }
            }
        }
    }
}

@Composable
internal fun GroupToggle(label: String, checked: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onCheckedChange = change, enabled = enabled)
    }
}
