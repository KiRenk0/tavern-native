package app.tavern.nativeclient

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
internal fun ChatArchiveSearchDialog(
    scope: ChatArchiveScope, state: TavernState, model: TavernViewModel, close: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    val query = input.trim()
    val words = remember(query) { ChatArchiveQuery(query).terms }
    var hits by remember(scope, query) { mutableStateOf(emptyList<ChatArchiveHit>()) }
    var cursor by remember(scope, query) { mutableStateOf<ChatArchiveCursor?>(null) }
    var next by remember(scope, query) { mutableStateOf<ChatArchiveCursor?>(null) }
    var loading by remember(scope, query) { mutableStateOf(true) }
    var attempt by remember(scope, query) { mutableIntStateOf(0) }
    var failure by remember(scope, query) { mutableStateOf<String?>(null) }
    val cardNames = remember(state.cards) { state.cards.associate { it.id to it.name } }
    val groupNames = remember(state.groups) { state.groups.associate { it.id to it.name } }
    val owner = cardNames[scope.cardId] ?: groupNames[scope.groupId]
    val enabled = !state.busy && state.generation == null && !state.scriptRunning

    LaunchedEffect(scope, query, cursor, attempt) {
        loading = true
        failure = null
        try {
            if (cursor == null && query.isNotEmpty()) delay(260)
            val page = model.searchChats(scope, query, cursor)
            hits = if (cursor == null) page.hits else (hits + page.hits).distinctBy { it.chat.id }
            next = page.next
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e.localizedMessage ?: "无法搜索聊天记录"
        } finally {
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = close,
        title = { Text(owner?.let { "搜索「$it」的聊天" } ?: "搜索聊天记录", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("聊天名称或聊天正文", input, { input = it })
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (failure != null && hits.isEmpty()) {
                    Text(failure.orEmpty(), color = MaterialTheme.colorScheme.error)
                    TextButton({ attempt++ }) { Text("重试") }
                } else if (!loading && hits.isEmpty()) {
                    Text(if (query.isEmpty()) "还没有保存的聊天" else "没有匹配的聊天")
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 385.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(hits, key = { it.chat.id }) { hit ->
                        val label = cardNames[hit.cardId] ?: groupNames[hit.groupId]
                        Column(Modifier.fillMaxWidth().clickable(enabled = enabled) {
                            close()
                            model.openArchiveHit(hit, query)
                        }.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(highlightArchiveMatch(hit.chat.title, words), style = MaterialTheme.typography.titleSmall,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val details = listOfNotNull(label.takeUnless { it == owner }, chatHistoryDate(hit.chat),
                                hit.match?.let { "第 ${it.index + 1} 条" }).joinToString(" · ")
                            Text(details, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            hit.match?.let { match ->
                                val prefix = match.speaker.takeIf(String::isNotBlank)?.let { "$it：" }.orEmpty()
                                Text(highlightArchiveMatch(prefix + match.preview, words),
                                    style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        HorizontalDivider()
                    }
                    next?.let { continuation ->
                        item {
                            TextButton(
                                { if (cursor == continuation) attempt++ else cursor = continuation },
                                enabled = !loading,
                            ) { Text(if (failure == null) "加载更多" else "重试加载") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(close) { Text("关闭") } },
    )
}

@Composable
private fun highlightArchiveMatch(text: String, words: List<String>): AnnotatedString {
    val color = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        append(text)
        for (word in words) {
            var from = 0
            while (from < text.length) {
                val index = text.indexOf(word, from, ignoreCase = true)
                if (index < 0) break
                addStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold), index, index + word.length)
                from = index + word.length
            }
        }
    }
}
