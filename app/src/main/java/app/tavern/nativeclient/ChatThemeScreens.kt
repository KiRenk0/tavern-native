package app.tavern.nativeclient

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tavern.nativeclient.chat.TextDialog
import org.json.JSONObject
import kotlin.math.roundToInt

@Composable
internal fun ChatThemeSettingsScreen(state: TavernState, model: TavernViewModel, close: () -> Unit) {
    val prefs = state.prefs
    val appearance = prefs.chatAppearance
    val themes = prefs.chatThemes
    val builtIn by produceState<List<ChatTheme>>(emptyList(), model) { value = model.builtInChatThemes() }
    val availablePresets = builtIn.filter { preset -> themes.items.none { it.id == preset.id } }
    val active = themes.items.firstOrNull { it.id == themes.activeId }
    val modified = active != null && (appearance != active.appearance || prefs.visualNovel != active.visualNovel ||
        prefs.theme != active.mode || prefs.dynamicColor != active.dynamicColor)
    var editingColors by rememberSaveable { mutableStateOf(false) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var updating by rememberSaveable { mutableStateOf(false) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    val importTheme = rememberLauncherForActivityResult(OpenDocument()) { uri -> uri?.let(model::importChatTheme) }
    val export = rememberLauncherForActivityResult(CreateDocument("application/json")) { uri ->
        val id = exportId
        exportId = null
        if (id != null && uri != null) model.exportChatTheme(uri, id)
    }
    BackHandler { if (!state.busy) { if (editingColors) editingColors = false else close() } }
    if (editingColors) {
        ThemePaletteScreen(appearance.palette, !state.busy, { editingColors = false }) {
            model.setChatAppearance(appearance.copy(palette = it))
            editingColors = false
        }
        return
    }

    Form {
        TextButton(close, enabled = !state.busy) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
            Spacer(Modifier.width(8.dp))
            Text("返回设置")
        }
        FormHeading("聊天主题")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ editingColors = true }, Modifier.weight(1f), enabled = !state.busy) { Text("编辑配色") }
            OutlinedButton({ importTheme.launch(arrayOf("application/json", "text/plain")) }, Modifier.weight(1f),
                enabled = !state.busy) { Text("导入主题") }
        }
        Button({ creating = true }, Modifier.fillMaxWidth(), enabled = !state.busy) { Text("将当前外观保存为新主题") }
        if (modified) OutlinedButton({ updating = true }, Modifier.fillMaxWidth(), enabled = !state.busy) {
            Text("更新 ${active.name}")
        }
        for (theme in themes.items) key(theme.id) {
            var menu by remember { mutableStateOf(false) }
            val selected = theme.id == active?.id
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(theme.name, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TextButton({ model.applyChatTheme(theme.id) }, enabled = !state.busy && (!selected || modified)) {
                        Text(if (selected && !modified) "使用中" else "应用")
                    }
                    Box {
                        ActionIcon(Icons.Outlined.MoreVert, "管理 ${theme.name}", !state.busy) { menu = true }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("导出 JSON") }, onClick = {
                                menu = false
                                exportId = theme.id
                                export.launch("${safeFilename(theme.name)}.json")
                            })
                            DropdownMenuItem(text = { Text("删除") }, onClick = { menu = false; deleteId = theme.id })
                        }
                    }
                }
            }
        }
        if (availablePresets.isNotEmpty()) {
            FormHeading("原版预设")
            for (preset in availablePresets) {
                OutlinedButton({ model.applyChatTheme(preset.id) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(preset.name, Modifier.weight(1f))
                    Text("应用")
                }
            }
        }
    }
    if (creating) TextDialog("保存聊天主题", "名称", "", { creating = false }) {
        creating = false
        model.saveChatTheme(it)
    }
    if (updating && active != null) Confirm("更新主题？", "将当前外观保存到「${active.name}」。", { updating = false }) {
        updating = false
        model.updateChatTheme(active.id)
    }
    themes.items.firstOrNull { it.id == deleteId }?.let { theme ->
        Confirm("删除主题？", "将从本机主题列表移除「${theme.name}」。", { deleteId = null }) {
            deleteId = null
            model.deleteChatTheme(theme.id)
        }
    }
}

@Composable
private fun ThemePaletteScreen(initial: ThemePalette, enabled: Boolean, close: () -> Unit, save: (ThemePalette) -> Unit) {
    var draftJson by rememberSaveable { mutableStateOf(initial.json().toString()) }
    val draft = remember(draftJson) { ThemePalette.from(JSONObject(draftJson)) }
    var slot by rememberSaveable { mutableStateOf<ThemeColor?>(null) }
    val scheme = MaterialTheme.colorScheme
    fun update(changes: ThemePalette) { draftJson = changes.json().toString() }
    fun default(role: ThemeColor): Color = when (role) {
        ThemeColor.USER -> scheme.primaryContainer
        ThemeColor.QUOTE, ThemeColor.UNDERLINE -> scheme.primary
        ThemeColor.SHADOW -> Color.Black
        ThemeColor.CHAT, ThemeColor.PANEL -> scheme.surface
        ThemeColor.BORDER -> scheme.outline
        ThemeColor.ASSISTANT -> scheme.surfaceContainer
        ThemeColor.TEXT, ThemeColor.ITALICS -> scheme.onSurface
    }
    Form {
        TextButton(close, enabled = enabled) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
            Spacer(Modifier.width(8.dp))
            Text("返回主题")
        }
        FormHeading("主题配色")
        for (role in ThemeColor.entries) {
            OutlinedButton({ slot = role }, Modifier.fillMaxWidth(), enabled = enabled) {
                ThemeSwatch(draft.color(role) ?: default(role))
                Text(role.label, Modifier.weight(1f).padding(start = 10.dp))
                Text(draft.colors[role]?.let(::colorHex) ?: "默认", style = MaterialTheme.typography.labelMedium)
            }
        }
        GroupToggle("显示文字阴影", !draft.noShadows, enabled) { update(draft.copy(noShadows = !it)) }
        if (!draft.noShadows) {
            Text("阴影强度 · ${draft.shadowWidth}")
            Slider(draft.shadowWidth.toFloat(), { update(draft.copy(shadowWidth = it.roundToInt())) },
                valueRange = 0f..5f, steps = 4, enabled = enabled)
        }
        Button({ save(draft) }, Modifier.fillMaxWidth(), enabled = enabled) { Text("应用配色") }
        TextButton({ update(ThemePalette()) }, enabled = enabled && draft != ThemePalette()) { Text("重置配色") }
    }
    slot?.let { role ->
        ThemeColorDialog(role, draft.colors[role] ?: default(role).toArgb(), { slot = null },
            reset = {
                update(draft.copy(colors = draft.colors - role))
                slot = null
            },
            apply = {
                update(draft.copy(colors = draft.colors + (role to it)))
                slot = null
            })
    }
}

@Composable
private fun ThemeSwatch(color: Color) {
    val shape = RoundedCornerShape(6.dp)
    Box(Modifier.size(22.dp).border(1.dp, MaterialTheme.colorScheme.outline, shape).background(color, shape))
}

private fun colorHex(value: Int): String =
    "#" + ((value shl 8) or (value ushr 24)).toUInt().toString(16).padStart(8, '0').uppercase()

@Composable
private fun ThemeColorDialog(role: ThemeColor, initial: Int, dismiss: () -> Unit, reset: () -> Unit, apply: (Int) -> Unit) {
    var value by rememberSaveable(role) { mutableStateOf(colorHex(initial)) }
    val parsed = parseThemeColor(value)
    val sample = parsed ?: initial
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(role.label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ThemeSwatch(Color(sample))
                    OutlinedTextField(value, { value = it.take(80) }, label = { Text("颜色值") }, singleLine = true,
                        isError = parsed == null, supportingText = { Text("#RRGGBB、#RRGGBBAA 或 rgba") })
                }
                for ((shift, label) in listOf(16 to "红", 8 to "绿", 0 to "蓝", 24 to "不透明度")) {
                    val channel = (sample ushr shift) and 255
                    Text("$label · $channel", style = MaterialTheme.typography.labelMedium)
                    Slider(channel.toFloat(), {
                        val next = (sample and (255 shl shift).inv()) or (it.roundToInt() shl shift)
                        value = colorHex(next)
                    }, valueRange = 0f..255f)
                }
                TextButton(reset) { Text("使用默认颜色") }
            }
        },
        dismissButton = { TextButton(dismiss) { Text("取消") } },
        confirmButton = { Button({ parsed?.let(apply) }, enabled = parsed != null) { Text("确定") } },
    )
}
