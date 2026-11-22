package app.tavern.nativeclient

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun WelcomeScreen(state: TavernState, model: TavernViewModel, busy: Boolean, draft: String,
                            changeDraft: (String) -> Unit, configure: () -> Unit, loadTemporary: () -> Unit) {
    val assistant = state.welcomeAssistant
    val prefs = state.prefs.welcome
    val configured = state.prefs.connectionFor() != null
    var expanded by rememberSaveable { mutableStateOf(false) }
    val pinned = state.recentChats.count { it.summary.id in prefs.pinnedIds }
    val visible = if (expanded) state.recentChats else state.recentChats.take(maxOf(prefs.recentCount, pinned))
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 740.dp).fillMaxWidth(), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Portrait(assistant, model.portrait(assistant))
                            Text(assistant.name, style = MaterialTheme.typography.headlineSmall, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                        assistant.greetings.firstOrNull()?.let { greeting ->
                            Text(expand(greeting, assistant, state.prefs.personaFor(subjectId = assistant.id).name),
                                maxLines = 4, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedTextField(draft, changeDraft, Modifier.fillMaxWidth(), enabled = !busy,
                            label = { Text("给${assistant.name}发消息") }, maxLines = 4)
                        Button({
                            if (draft.isNotBlank() && !configured) configure() else model.startWelcomeChat(draft) { changeDraft("") }
                        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text(when {
                                draft.isBlank() -> "开始聊天"
                                configured -> "发送消息"
                                else -> "配置模型"
                            })
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ model.openTemporaryChat() }, Modifier.weight(1f), enabled = !busy) {
                        Text("临时聊天")
                    }
                    OutlinedButton(loadTemporary, Modifier.weight(1f), enabled = !busy) {
                        Icon(Icons.Outlined.FileDownload, null)
                        Spacer(Modifier.width(4.dp))
                        Text("载入临时聊天")
                    }
                }
            }
            if (prefs.showRecent && state.recentChats.isNotEmpty()) {
                item { Text("最近聊天", style = MaterialTheme.typography.titleLarge) }
                items(visible, key = { it.summary.id }) { entry ->
                    val group = state.groups.firstOrNull { it.id == entry.groupId }
                    val owner = state.cards.firstOrNull { it.id == entry.cardId }?.name
                        ?: group?.name.orEmpty()
                    val saved = entry.summary.id in prefs.pinnedIds
                    Card(onClick = { model.openChat(entry.summary.id) }, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        ListItem(
                            headlineContent = { Text(entry.summary.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(owner, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = {
                                if (group == null) Icon(Icons.Outlined.ChatBubbleOutline, null)
                                else GroupPortrait(group, state.cards, model::portrait, 40.dp)
                            },
                            trailingContent = {
                                IconButton({ model.toggleRecentPin(entry.summary.id) }, enabled = !busy) {
                                    Icon(Icons.Outlined.PushPin, if (saved) "取消置顶 ${entry.summary.title}" else "置顶 ${entry.summary.title}",
                                        tint = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                        )
                    }
                }
                if (state.recentChats.size > maxOf(prefs.recentCount, pinned)) {
                    item {
                        TextButton({ expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (expanded) "收起最近聊天" else "显示更多聊天")
                        }
                    }
                }
            }
        }
    }
}
