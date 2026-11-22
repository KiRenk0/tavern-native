package app.tavern.nativeclient.chat

import android.view.InputDevice
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
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
    canContinue: Boolean,
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
    val compact = state.prefs.chatAppearance.compactInput
    val handling = state.prefs.chatHandling
    val canAttach = !busy && dictation == null
    val canPickImages = canAttach && attachedImages.size < MAX_CHAT_IMAGES
    val canPickDocuments = canAttach && attachedDocuments.size < MAX_CHAT_DOCUMENTS
    val canAuto = auto || (!busy && dictation == null && configured && group?.participants(state.cards)?.isNotEmpty() == true)
    val attachmentsPending = attachedImages.isNotEmpty() || attachedDocuments.isNotEmpty()
    val sendContinues = canContinue && handling.shouldContinueOnSend(chat, draft, attachmentsPending)
    val hasInput = draft.isNotBlank() || attachmentsPending ||
        state.presetFor(chat).emptyChatInput(state.prefs.connectionFor(chat)).isNotBlank()
    val canSubmit = canAttach && (sendContinues || hasInput && (canSend || draft.trimStart().startsWith('/')))
    val canImpersonate = canAttach && configured && (state.card != null || group?.members(state.cards)?.isNotEmpty() == true)
    var editorValue by remember(chat.id) { mutableStateOf(TextFieldValue()) }
    var toolsOpen by remember { mutableStateOf(false) }
    fun submitDraft() {
        if (!canSubmit) return
        onBeforeSend()
        if (model.submitChatDraft(draft)) {
            editorValue = TextFieldValue()
            onDraftChange("", TextRange.Zero)
            keyboard?.hide()
        }
    }
    val voiceButton: @Composable () -> Unit = {
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
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 1.dp) {
        if (selected != null) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("已选 ${selected.size} 条", Modifier.weight(1f))
                TextButton(onClearSelection) { Text("取消") }
                Button({ onRequestDeleteSelected(selected) }, enabled = selected.isNotEmpty()) { Text("删除") }
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                if (handling.quickContinue || handling.quickImpersonate || quickButtons.isNotEmpty()) LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (handling.quickContinue) item(key = "continue") {
                        AssistChip(onClick = { keyboard?.hide(); onBeforeSend(); model.continueReply() },
                            enabled = canAttach && canContinue, label = { Text("续写") },
                            leadingIcon = { Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp)) })
                    }
                    if (handling.quickImpersonate) item(key = "impersonate") {
                        AssistChip(onClick = { keyboard?.hide(); model.impersonate() },
                            enabled = canImpersonate, label = { Text("代写") },
                            leadingIcon = { Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp)) })
                    }
                    items(quickButtons, key = { (set, item) -> "${set.id}:${item.id}" }) { (set, item) ->
                        AssistChip(
                            onClick = { if (model.runQuickReply(set, item, draft)) keyboard?.hide() },
                            enabled = !busy && dictation == null,
                            label = { Text(item.label) },
                        )
                    }
                }
                if (!compact) Row(
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
                        enabled = canAuto,
                        label = { Text(if (auto) "关闭自动发言" else "自动发言") },
                    )
                }
                if (!compact) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onPickImages, enabled = canPickImages) {
                        Icon(Icons.Outlined.AddPhotoAlternate, "添加图片")
                    }
                    IconButton(onPickDocuments, enabled = canPickDocuments) {
                        Icon(Icons.Outlined.AttachFile, "添加文档")
                    }
                    IconButton(onOpenDrawing, enabled = canAttach) {
                        Icon(Icons.Outlined.AutoAwesome, "生成图片")
                    }
                    voiceButton()
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
                val progressLabel = state.utility ?: if (state.generatingImage) "正在生成图片…" else state.toolProgress
                progressLabel?.let {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(it, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 4.dp))
                }
                val input = generation?.text?.takeIf { impersonating && it.isNotBlank() } ?: draft
                val safeSelection = TextRange(
                    draftSelection.start.coerceIn(0, input.length),
                    draftSelection.end.coerceIn(0, input.length),
                )
                val readOnlyInput = impersonating || state.scriptRunning || state.utility != null
                val fieldValue = if (!readOnlyInput && editorValue.text == input && editorValue.selection == safeSelection)
                    editorValue else TextFieldValue(input, safeSelection)
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
                    if (compact) Box {
                        IconButton({ toolsOpen = true }, enabled = canAttach || canAuto || state.speaking) {
                            Icon(Icons.Outlined.AddCircleOutline, "输入栏操作")
                        }
                        DropdownMenu(toolsOpen, onDismissRequest = { toolsOpen = false }) {
                            MenuItem("添加图片", Icons.Outlined.AddPhotoAlternate, enabled = canPickImages) {
                                toolsOpen = false; onPickImages()
                            }
                            MenuItem("添加文档", Icons.Outlined.AttachFile, enabled = canPickDocuments) {
                                toolsOpen = false; onPickDocuments()
                            }
                            MenuItem("生成图片", Icons.Outlined.AutoAwesome, enabled = canAttach) {
                                toolsOpen = false; onOpenDrawing()
                            }
                            if (group != null) MenuItem(if (auto) "关闭自动发言" else "自动发言", Icons.Outlined.Groups, enabled = canAuto) {
                                toolsOpen = false; model.toggleAuto()
                            }
                            if (state.speaking) MenuItem("停止朗读", Icons.Outlined.Stop) {
                                toolsOpen = false; model.stopNarration()
                            }
                        }
                    }
                    OutlinedTextField(
                        value = fieldValue,
                        onValueChange = { updated ->
                            if (!readOnlyInput) {
                                editorValue = updated
                                onDraftChange(updated.text, updated.selection)
                                model.stopAuto()
                            }
                        },
                        modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
                            if (handling.markdownHotkeys && !readOnlyInput && fieldValue.composition == null) {
                                val marker = markdownKey(event)
                                if (marker != null) {
                                    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                                        val update = fieldValue.toggleMarkdown(marker)
                                        editorValue = update
                                        onDraftChange(update.text, update.selection)
                                        model.stopAuto()
                                    }
                                    return@onPreviewKeyEvent true
                                }
                            }
                            val bareEnter = (event.key == Key.Enter || event.key == Key.NumPadEnter) &&
                                !event.isShiftPressed && !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed
                            if (!bareEnter || readOnlyInput || fieldValue.composition != null) return@onPreviewKeyEvent false
                            val device = event.nativeKeyEvent.device
                            val hardwareKeyboard = device?.let { !it.isVirtual && it.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC } == true
                            if (!handling.enterToSend.acceptsEnter(hardwareKeyboard)) return@onPreviewKeyEvent false
                            if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) submitDraft()
                            true
                        },
                        readOnly = readOnlyInput,
                        keyboardOptions = KeyboardOptions(imeAction = if (handling.enterToSend == EnterToSend.ENABLED) ImeAction.Send else ImeAction.Default),
                        keyboardActions = KeyboardActions(onSend = { submitDraft() }),
                        placeholder = { Text(if (impersonating) "正在代写…" else if (compact) "输入消息" else "输入消息（/ 命令 · {{ 宏）") },
                        shape = RoundedCornerShape(24.dp),
                        maxLines = if (compact) 1 else 5,
                    )
                    if (compact) voiceButton()
                    if (generation != null || state.scriptRunning || state.generatingImage || state.utility != null) {
                        FilledTonalIconButton(onClick = model::stop, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.Stop, when {
                                state.scriptRunning -> "停止脚本"
                                state.generatingImage -> if (state.utility != null) "停止等待视频" else "停止图像生成"
                                state.utility != null -> "停止当前操作"
                                else -> "停止生成"
                            })
                        }
                    } else {
                        FilledIconButton(
                            enabled = canSubmit,
                            modifier = Modifier.size(48.dp),
                            onClick = ::submitDraft,
                        ) {
                            if (sendContinues) Icon(Icons.Outlined.PlayArrow, "续写上一条回复")
                            else Icon(Icons.AutoMirrored.Outlined.Send, "发送")
                        }
                    }
                }
            }
        }
    }
}
