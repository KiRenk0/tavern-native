package app.tavern.nativeclient.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tavern.nativeclient.*

@Composable
internal fun ChatComposerPanel(
    chat: Chat,
    state: TavernState,
    model: TavernViewModel,
    keyboard: SoftwareKeyboardController?,
    selected: Set<Int>?,
    busy: Boolean,
    group: TavernGroup?,
    auto: Boolean,
    configured: Boolean,
    canSend: Boolean,
    generation: Generation?,
    impersonating: Boolean,
    quickConfig: QuickReplies,
    quickButtons: List<Pair<QuickReplySet, QuickReply>>,
    attachedImages: List<ChatImage>,
    attachedDocuments: List<ChatDocument>,
    dictation: DictationProgress?,
    draft: String,
    draftSelection: TextRange,
    onDraftChange: (String, TextRange) -> Unit,
    onClearSelection: () -> Unit,
    onRequestDeleteSelected: (Set<Int>) -> Unit,
    onPickImages: () -> Unit,
    onPickDocuments: () -> Unit,
    onOpenDrawing: () -> Unit,
    onDictationProgress: (DictationProgress?) -> Unit,
    onPreviewImage: (ChatImage) -> Unit,
    onPreviewDocument: (ChatDocument) -> Unit,
    onBeforeSend: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 1.dp) {
        if (selected != null) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("已选 ${selected.size} 条", Modifier.weight(1f))
                TextButton(onClearSelection) { Text("取消") }
                Button({ onRequestDeleteSelected(selected) }, enabled = selected.isNotEmpty()) { Text("删除") }
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                if (quickButtons.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(quickButtons, key = { (set, item) -> "${set.id}:${item.id}" }) { (set, item) ->
                        AssistChip(
                            onClick = { if (model.runQuickReply(set, item, draft)) keyboard?.hide() },
                            enabled = !busy && dictation == null,
                            label = { Text(item.label) },
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "以 ${state.persona.name} 发言",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (group != null) FilterChip(
                        selected = auto,
                        onClick = model::toggleAuto,
                        enabled = auto || (!busy && dictation == null && configured && group.participants(state.cards).isNotEmpty()),
                        label = { Text(if (auto) "关闭自动发言" else "自动发言") },
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onPickImages, enabled = !busy && dictation == null && attachedImages.size < MAX_CHAT_IMAGES) {
                        Icon(Icons.Outlined.AddPhotoAlternate, "添加图片")
                    }
                    IconButton(onPickDocuments, enabled = !busy && dictation == null && attachedDocuments.size < MAX_CHAT_DOCUMENTS) {
                        Icon(Icons.Outlined.AttachFile, "添加文档")
                    }
                    IconButton(onOpenDrawing, enabled = !busy && dictation == null) {
                        Icon(Icons.Outlined.AutoAwesome, "生成图片")
                    }
                    if (state.prefs.voice.dictationEnabled) DictationButton(
                        state.prefs.voice,
                        !busy,
                        pausedPlayback = { model.stopAuto(); model.stopNarration(); keyboard?.hide() },
                        onTranscript = { recognized ->
                            val result = state.prefs.voice.dictated(recognized, draft)
                            if (result.send && model.send(result.text, interpretCommands = result.shortcut)) {
                                onDraftChange("", TextRange.Zero)
                            } else {
                                onDraftChange(result.text, TextRange(result.text.length))
                            }
                        },
                        onError = model::notice,
                        onProgress = onDictationProgress,
                    )
                    Spacer(Modifier.weight(1f))
                    if (state.speaking) TextButton(model::stopNarration) {
                        Icon(Icons.Outlined.Stop, null)
                        Text("停止朗读")
                    }
                }
                dictation?.let { progress ->
                    Text(
                        progress.partial.ifBlank { if (progress.finishing) "正在转写…" else "正在听…" },
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (attachedImages.isNotEmpty() || attachedDocuments.isNotEmpty()) LazyRow(
                    Modifier.padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(attachedImages, key = { "image:${it.id}" }) { image ->
                        DraftImageTile(
                            image, model.imageFile(image), !busy,
                            { onPreviewImage(image) },
                            { model.removeDraftImage(chat.id, image) },
                        )
                    }
                    items(attachedDocuments, key = { "document:${it.id}" }) { document ->
                        ChatDocumentChip(
                            document,
                            { onPreviewDocument(document) },
                            { model.removeDraftDocument(chat.id, document) },
                            !busy,
                        )
                    }
                }
                if (state.generatingImage) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("正在生成图片…", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 4.dp))
                }
                (state.utility ?: state.toolProgress?.takeUnless { state.generatingImage })?.let {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(it, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 4.dp))
                }
                val input = generation?.text?.takeIf { impersonating && it.isNotBlank() } ?: draft
                val safeSelection = TextRange(
                    draftSelection.start.coerceIn(0, input.length),
                    draftSelection.end.coerceIn(0, input.length),
                )
                val readOnlyInput = impersonating || state.scriptRunning || state.utility != null
                val activeQuickItems = remember(quickConfig, chat) {
                    if (!quickConfig.enabled) emptyList() else quickConfig.selected(chat).flatMap { it.items }
                }
                val autoQuery = remember(input, safeSelection, readOnlyInput) {
                    if (readOnlyInput) null else detectAutocomplete(input, safeSelection.start)
                }
                val suggestions = remember(autoQuery, activeQuickItems, chat.options.variables, state.prefs.scriptVariables) {
                    autoQuery?.let {
                        suggestAutocomplete(it, activeQuickItems, chat.options.variables, state.prefs.scriptVariables)
                    }.orEmpty()
                }
                if (suggestions.isNotEmpty() && autoQuery != null) {
                    AutocompleteStrip(suggestions) { item ->
                        val (updated, nextCursor) = applyAutocomplete(draft, autoQuery, item)
                        onDraftChange(updated, TextRange(nextCursor))
                        model.stopAuto()
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = TextFieldValue(input, safeSelection),
                        onValueChange = { updated ->
                            if (!readOnlyInput) {
                                onDraftChange(updated.text, updated.selection)
                                model.stopAuto()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        readOnly = readOnlyInput,
                        placeholder = { Text(if (impersonating) "正在代写…" else "输入消息（/ 命令 · {{ 宏）") },
                        shape = RoundedCornerShape(24.dp),
                        maxLines = 5,
                    )
                    if (generation != null || state.scriptRunning || state.generatingImage || state.utility != null) {
                        FilledTonalIconButton(onClick = model::stop, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.Stop, when {
                                state.scriptRunning -> "停止脚本"
                                state.generatingImage -> "停止图像生成"
                                state.utility != null -> "停止当前操作"
                                else -> "停止生成"
                            })
                        }
                    } else {
                        FilledIconButton(
                            enabled = (canSend || draft.trimStart().startsWith('/')) && !busy && dictation == null &&
                                (draft.isNotBlank() || attachedImages.isNotEmpty() || attachedDocuments.isNotEmpty()),
                            modifier = Modifier.size(48.dp),
                            onClick = {
                                onBeforeSend()
                                if (model.send(draft)) {
                                    onDraftChange("", TextRange.Zero)
                                    keyboard?.hide()
                                }
                            },
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.Send, "发送")
                        }
                    }
                }
            }
        }
    }
}
