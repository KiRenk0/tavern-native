package app.tavern.nativeclient

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun rememberExpressionFrame(state: TavernState, chat: Chat, subject: ExpressionSubject, model: TavernViewModel,
                                     forScene: Boolean = false): ExpressionFrame? {
    val manual = state.manualExpressions[subject.card.id]
    val connection = state.prefs.connectionFor(chat)
    return produceState<ExpressionFrame?>(null, chat.id, subject, chat.options.expressions, manual,
        connection, state.localExpressionInstalled, forScene) {
        value = model.expressionFrame(chat.id, subject, forScene)
    }.value
}

@Composable
internal fun ExpressionStrip(state: TavernState, chat: Chat, model: TavernViewModel) {
    val settings = chat.options.expressions
    if (settings.mode == ExpressionMode.OFF) return
    val subjects = remember(chat, state.cards, state.group, state.cardId) { state.expressionSubjects(chat) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        subjects.forEach { subject -> key(subject.card.id) {
            val frame = rememberExpressionFrame(state, chat, subject, model)
            frame?.let { shown ->
                Column(Modifier.width(104.dp).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val image = shown.image
                    if (image != null) ChatImageContent(image, model.imageFile(image), 256, Modifier.size(88.dp))
                    else Text(shown.emoji.orEmpty(), fontSize = 38.sp, modifier = Modifier.padding(vertical = 4.dp))
                    Text(subject.card.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        } }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ExpressionDialog(cards: List<Card>, chat: Chat?, model: TavernViewModel, state: TavernState, busy: Boolean, close: () -> Unit) {
    if (cards.isEmpty()) return
    var target by rememberSaveable { mutableStateOf(cards.first().id) }
    val card = cards.firstOrNull { it.id == target } ?: cards.first()
    val sprites = key(card.id) { produceState(emptyList<ExpressionSprite>(), card) { value = model.expressionSprites(card) } }.value
    val initial = chat?.options?.expressions ?: ExpressionSettings()
    var mode by rememberSaveable(chat?.id) { mutableStateOf(initial.mode) }
    var fallback by rememberSaveable(chat?.id) { mutableStateOf(initial.fallback) }
    var instruction by rememberSaveable(chat?.id) { mutableStateOf(initial.instruction) }
    var label by rememberSaveable(card.id) { mutableStateOf("") }
    val photos = rememberLauncherForActivityResult(OpenMultipleDocuments()) { model.importExpressionSprites(card.id, it, false, label) }
    val pack = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) model.importExpressionSprites(card.id, listOf(uri), true, "")
    }
    val localModel = rememberLauncherForActivityResult(OpenDocument()) { uri -> uri?.let(model::importExpressionModel) }
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text("角色表情") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (cards.size > 1) SelectField("角色", card, cards, Card::name, !busy) { target = it.id }
                if (chat != null) {
                    SelectField("切换方式", mode, ExpressionMode.entries, ExpressionMode::label, !busy) { mode = it }
                    val labels = (listOf("", "neutral", EXPRESSION_EMOJI, fallback) + sprites.map(ExpressionSprite::label)).distinct()
                    SelectField("默认表情", fallback, labels, { value ->
                        when (value) { "" -> "隐藏"; EXPRESSION_EMOJI -> "内置表情符号"; else -> value }
                    }, !busy) { fallback = it }
                    if (mode == ExpressionMode.MAIN_API) {
                        Text("每位角色完成回复后，会用当前聊天模型判断表情。")
                        Field("分类提示（选填）", instruction, { instruction = it.take(2000) }, lines = 2, enabled = !busy)
                    }
                    if (mode == ExpressionMode.LOCAL) {
                        Text("使用原版 GoEmotions 模型，主要针对英文。安装模型后，分类在手机上离线运行。")
                        val progress = state.localExpressionProgress
                        if (progress != null) {
                            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("正在获取模型 · $progress%", Modifier.weight(1f))
                                TextButton(model::stop) { Text("取消") }
                            }
                        } else {
                            if (state.localExpressionInstalled) Text("本地模型已安装")
                            state.localExpressionFailure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton({ model.downloadExpressionModel() }, enabled = !busy) {
                                    Text(if (state.localExpressionInstalled) "重新下载" else "下载模型（67.6 MB）")
                                }
                                TextButton({ localModel.launch(arrayOf("*/*")) }, enabled = !busy) { Text("从文件导入") }
                                if (state.localExpressionInstalled) {
                                    TextButton({ model.removeExpressionModel() }, enabled = !busy) { Text("移除模型") }
                                }
                            }
                        }
                    }
                }
                Field("表情标签（留空按文件名）", label, { label = it.take(64) }, enabled = !busy, placeholder = "例如 joy")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton({ photos.launch(arrayOf("image/*")) }, enabled = !busy) { Text("添加图片") }
                    TextButton({ pack.launch(arrayOf("*/*")) }, enabled = !busy) { Text("导入表情 ZIP") }
                }
                if (sprites.isEmpty()) Text("暂无表情图片")
                sprites.forEach { sprite ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChatImageContent(sprite.image, model.imageFile(sprite.image), 128, Modifier.size(52.dp))
                        Text(sprite.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (chat != null) TextButton({ model.showExpression(card.id, sprite, close) }, enabled = !busy) { Text("显示") }
                        ActionIcon(Icons.Outlined.Close, "移除 ${sprite.label} 图片", !busy) { model.removeExpressionSprite(card.id, sprite) }
                    }
                }
            }
        },
        dismissButton = { if (chat != null) TextButton(close, enabled = !busy) { Text("关闭") } },
        confirmButton = {
            if (chat == null) TextButton(close, enabled = !busy) { Text("关闭") }
            else Button({ model.saveExpressionSettings(ExpressionSettings(mode, fallback, instruction)); close() }, enabled = !busy) {
                Text("保存设置")
            }
        },
    )
}
