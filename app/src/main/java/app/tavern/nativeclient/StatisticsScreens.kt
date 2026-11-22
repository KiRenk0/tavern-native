package app.tavern.nativeclient

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val detailedDate = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

private fun localTime(epoch: Long) = detailedDate.format(Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()))

private fun timeSpent(millis: Long): String {
    return when {
        millis >= 3_600_000 -> "${millis / 3_600_000} 小时 ${millis / 60_000 % 60} 分钟"
        millis >= 60_000 -> "${millis / 60_000} 分钟 ${millis / 1000 % 60} 秒"
        else -> String.format(Locale.ROOT, "%.1f 秒", millis / 1000.0)
    }
}

@Composable
private fun messageTextTokenCount(message: Message): Long? {
    val recorded = message.replyMetrics?.bodyTokens
    if (recorded != null) return recorded
    val text = message.requestText
    if (text.isBlank()) return null
    return key(text) {
        val count by produceState<Long?>(null) {
            value = withContext(Dispatchers.Default) { estimatedTokens.count(text).toLong() }
        }
        count
    }
}

@Composable
internal fun MessageMetadataLine(message: Message, appearance: ChatAppearance, messageIndex: Int? = null) {
    val fields = mutableListOf<String>()
    if (appearance.showMessageIds && messageIndex != null) fields += "#$messageIndex"
    if (appearance.showMessageTime) {
        val timestamp = remember(message.date) { messageEpoch(message.date)?.let(::localTime) }
        if (timestamp != null) fields += timestamp
    }
    if (appearance.showGenerationTimer) message.replyMetrics?.elapsed?.let { fields += timeSpent(it) }
    if (appearance.showMessageTokens) {
        val approximate = message.replyMetrics?.bodyTokens == null || message.replyMetrics.counter.contains("估算")
        messageTextTokenCount(message)?.let { fields += if (approximate) "≈ $it Token" else "$it Token" }
    }
    if (fields.isNotEmpty()) Text(fields.joinToString(" · "),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (appearance.showModelIcon && message.role == "assistant") message.replyMetrics?.let { metrics ->
        val protocol = metrics.api.lowercase(Locale.ROOT)
        val service = metrics.provider.ifBlank { ApiProtocol.from(protocol)?.label ?: metrics.api }
        val model = metrics.model.removePrefix("models/").ifBlank { service }
        if (model.isNotBlank()) {
            val icon = when (protocol) {
                "gemini", "google", "makersuite" -> Icons.Outlined.AutoAwesome
                "anthropic", "claude" -> Icons.Outlined.AutoStories
                "openrouter", "horde" -> Icons.Outlined.Hub
                "ollama", "kobold", "textgenerationwebui" -> Icons.Outlined.Memory
                else -> Icons.Outlined.SmartToy
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(icon, contentDescription = service.ifBlank { "回复模型" }, Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(model, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun MessageStatisticsDialog(message: Message, dismiss: () -> Unit) {
    val metrics = message.replyMetrics
    val tokens = messageTextTokenCount(message)
    val source = if (metrics?.bodyTokens == null) estimatedTokens.label else metrics.counter.ifBlank { "历史记录" }
    AlertDialog(onDismissRequest = dismiss, title = { Text("消息详情") },
        text = {
            SelectionContainer {
                Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    StatRow("发言者", message.name)
                    if (message.variants.size > 1) StatRow("备选回复", "${message.index + 1} / ${message.variants.size}")
                    messageEpoch(message.date)?.let { StatRow("消息时间", localTime(it)) }
                    tokens?.let { StatRow("正文 Token · $source", it.toString()) }
                    metrics?.let { data ->
                        if (data.provider.isNotBlank()) StatRow("最近使用的提供商", data.provider)
                        if (data.model.isNotBlank()) StatRow("最近使用的模型", data.model)
                        data.elapsed?.let { StatRow("生成用时", timeSpent(it)) }
                        data.firstText?.let { StatRow("收到第一段正文", timeSpent(it)) }
                        data.reasoningElapsed?.let { StatRow("历史推理用时", timeSpent(it)) }
                        if (data.usage.reported > 0) {
                            HorizontalDivider()
                            Text("服务返回的用量", style = MaterialTheme.typography.titleSmall)
                            data.usage.tokens.input?.let { StatRow("输入 Token", it.toString()) }
                            data.usage.tokens.output?.let { StatRow("输出 Token", it.toString()) }
                            data.usage.tokens.reasoning?.let { StatRow("其中推理 Token", it.toString()) }
                            if (data.usage.reported < data.usage.requests) {
                                Text("${data.usage.reported} / ${data.usage.requests} 段请求返回了用量",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(dismiss) { Text("关闭") } })
}

@Composable
internal fun ConversationStatisticsDialog(
    title: String, model: TavernViewModel, chat: Chat? = null, cardId: String? = null, groupId: String? = null,
    dismiss: () -> Unit,
) {
    val loaded by produceState<Result<ConversationStatistics>?>(null, chat, cardId, groupId) {
        value = try {
            Result.success(model.conversationStatistics(chat, cardId, groupId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
        val result = loaded
        val stats = result?.getOrNull()
        when {
            result == null -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text("正在读取聊天记录…")
            }
            stats == null -> Text("无法读取聊天统计")
            else -> Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatRow("聊天数", stats.chats.toString())
                StatRow("用户消息", stats.userMessages.toString())
                StatRow("角色消息", stats.assistantMessages.toString())
                StatRow("用户正文词数", stats.userWords.toString())
                StatRow("角色正文词数", stats.assistantWords.toString())
                StatRow("其他备选回复", stats.alternatives.toString())
                stats.firstMessage?.let { StatRow("首次消息", localTime(it)) }
                stats.lastMessage?.let { StatRow("最近消息", localTime(it)) }
                StatRow("已记录的累计生成时间", stats.generationMillis?.let(::timeSpent) ?: "未记录")
                if (stats.skippedChats > 0) Text("有 ${stats.skippedChats} 段聊天无法读取。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }, confirmButton = { TextButton(dismiss) { Text("关闭") } })
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
