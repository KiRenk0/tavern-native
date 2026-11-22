package app.tavern.nativeclient

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun PersonaSettings(state: TavernState, model: TavernViewModel, close: () -> Unit) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var newProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val importJson = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) model.importPersonas(uri) { query = "" }
    }
    val profile = state.prefs.personas.firstOrNull { it.id == editing }
        ?: newProfileId?.takeIf { it == editing }?.let { PersonaProfile(id = it) }
    fun back() {
        if (state.busy) return
        if (editing == null) close() else { editing = null; newProfileId = null }
    }
    BackHandler { back() }
    if (profile != null) key(profile.id) {
        PersonaEditor(profile, state, model, ::back)
    } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 680.dp).fillMaxWidth(),
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                TextButton(::back, enabled = !state.busy) { Text("返回设置") }
                FormHeading("人设管理", "设置默认身份，也可以在对话里临时使用或绑定其他人设。")
                if (state.prefs.personas.size > 1) Field("搜索人设", query, { query = it })
                Button(onClick = { newProfileId = newId(); editing = newProfileId }, enabled = !state.busy) {
                    Icon(Icons.Outlined.PersonAdd, null)
                    Spacer(Modifier.width(8.dp))
                    Text("创建人设")
                }
                OutlinedButton({ importJson.launch(arrayOf("*/*")) }, enabled = !state.busy) {
                    Icon(Icons.Outlined.FileDownload, null)
                    Spacer(Modifier.width(8.dp))
                    Text("导入人设 JSON")
                }
            }
            val values = state.prefs.personas.filter { it.name.contains(query.trim(), true) || it.title.contains(query.trim(), true) }
            items(values, key = PersonaProfile::id) { item ->
                Card(onClick = { editing = item.id }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AvatarBadge(item.name, model.personaAvatar(item), 44.dp)
                        Column(Modifier.weight(1f)) {
                            Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            val info = listOfNotNull("默认".takeIf { item.id == state.prefs.defaultPersonaId },
                                item.title.takeIf(String::isNotBlank)).joinToString(" · ")
                            if (info.isNotBlank()) Text(info, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (item.id != state.prefs.defaultPersonaId) {
                            ActionIcon(Icons.Outlined.StarOutline, "将 ${item.name} 设为默认", !state.busy) {
                                model.setDefaultPersona(item)
                            }
                        }
                        ActionIcon(Icons.Outlined.DeleteOutline, "删除 ${item.name}", !state.busy) { deleteId = item.id }
                    }
                }
            }
        }
    }
    state.prefs.personas.firstOrNull { it.id == deleteId }?.let {
        Confirm("删除人设？", "对话会按其余人设绑定重新选择身份。已有消息的署名会保留。", { deleteId = null }) {
            deleteId = null
            model.removePersona(it)
        }
    }
}

@Composable
private fun PersonaEditor(persona: PersonaProfile, state: TavernState, model: TavernViewModel, back: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(persona.name) }
    var title by rememberSaveable { mutableStateOf(persona.title) }
    var description by rememberSaveable { mutableStateOf(persona.description) }
    var position by rememberSaveable { mutableStateOf(persona.position) }
    var depth by rememberSaveable { mutableStateOf(persona.depth.toString()) }
    var role by rememberSaveable { mutableStateOf(persona.role) }
    var world by rememberSaveable { mutableStateOf(persona.worldId) }
    var photo by rememberSaveable { mutableStateOf<Uri?>(null) }
    var keepAvatar by rememberSaveable { mutableStateOf(true) }
    val context = LocalContext.current
    val pick = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) {
            photo = uri
            keepAvatar = false
        }
    }
    val numberDepth = depth.toIntOrNull()?.takeIf { it >= 0 }
    Form {
        TextButton(back, enabled = !state.busy) { Text("返回人设管理") }
        FormHeading(if (persona.name.isBlank()) "创建人设" else "编辑人设")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AvatarBadge(name, model.personaAvatar(persona).takeIf { keepAvatar }, 64.dp)
            Column(Modifier.weight(1f)) {
                val selectedPhoto = photo
                if (selectedPhoto != null) {
                    Text(context.documentName(selectedPhoto), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { pick.launch(arrayOf("image/*")) }, enabled = !state.busy) { Text("选择头像") }
            }
            if (photo != null || (keepAvatar && persona.avatar != null)) {
                ActionIcon(Icons.Outlined.Close, "移除头像", !state.busy) { photo = null; keepAvatar = false }
            }
        }
        Field("名称", name, { name = it }, enabled = !state.busy)
        Field("标题（可选）", title, { title = it }, enabled = !state.busy)
        Field("人设描述", description, { description = it }, lines = 4, enabled = !state.busy)
        SelectField("描述注入位置", position, PersonaPosition.entries, PersonaPosition::label, !state.busy) { position = it }
        if (position == PersonaPosition.DEPTH) {
            Field("注入深度", depth, { depth = it }, enabled = !state.busy, type = KeyboardType.Number)
            SelectField("注入身份", role, PromptRole.entries, PromptRole::label, !state.busy) { role = it }
        }
        if (state.books.isNotEmpty() || world.isNotBlank()) {
            SelectField("人设的世界书", world, (listOf("") + state.books.map(WorldBook::id) + world).distinct(),
                { id -> state.books.firstOrNull { it.id == id }?.name ?: "未绑定" }, !state.busy) { world = it }
        }
        Button(onClick = {
            model.savePersona(persona.copy(name = name, title = title, description = description, position = position,
                depth = numberDepth ?: persona.depth, role = role, worldId = world,
                avatar = persona.avatar.takeIf { keepAvatar }), photo, back)
        }, enabled = !state.busy && name.isNotBlank() && (position != PersonaPosition.DEPTH || numberDepth != null),
            modifier = Modifier.fillMaxWidth()) { Text("保存人设") }
    }
}

@Composable
internal fun ChatPersonaDialog(state: TavernState, model: TavernViewModel, close: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf(state.persona.id) }
    val personas = state.prefs.personas
    val subject = state.cardId ?: state.groupId
    val owner = if (state.groupId == null) "角色" else "群组"
    AlertDialog(onDismissRequest = close, title = { Text("对话人设") }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectField("使用身份", selected, personas.map(PersonaProfile::id),
                { id -> personas.firstOrNull { it.id == id }?.name.orEmpty() }, !state.busy) { selected = it }
            TextButton(onClick = { model.usePersonaTemporarily(selected); close() }, enabled = !state.busy) {
                Text("临时使用")
            }
            TextButton(onClick = { model.pinPersona(selected); close() }, enabled = !state.busy) {
                Text("绑定此对话")
            }
            if (state.chat?.options?.personaId != null) {
                TextButton(onClick = { model.pinPersona(null); close() }, enabled = !state.busy) { Text("解除对话绑定") }
            }
            if (subject != null) {
                TextButton(onClick = { model.bindSubjectPersona(selected); close() }, enabled = !state.busy) { Text("绑定此$owner") }
                if (state.prefs.personaConnections[subject] != null) {
                    TextButton(onClick = { model.bindSubjectPersona(null); close() }, enabled = !state.busy) {
                        Text("解除${owner}绑定")
                    }
                }
            }
        }
    }, confirmButton = { TextButton(close) { Text("关闭") } })
}

@Composable
internal fun AuthorNoteEditor(note: AuthorNote, title: String, busy: Boolean, close: () -> Unit,
                              save: (AuthorNote) -> Unit) {
    var text by rememberSaveable { mutableStateOf(note.text) }
    var frequency by rememberSaveable { mutableStateOf(note.frequency.toString()) }
    var depth by rememberSaveable { mutableStateOf(note.depth.toString()) }
    var position by rememberSaveable { mutableStateOf(note.position) }
    var role by rememberSaveable { mutableStateOf(note.role) }
    var scan by rememberSaveable { mutableStateOf(note.scan) }
    val numericFrequency = frequency.toIntOrNull()?.takeIf { it >= 0 }
    val numericDepth = depth.toIntOrNull()?.takeIf { it >= 0 }
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text(title) }, text = {
        Column(Modifier.heightIn(max = 424.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("注释", text, { text = it }, lines = 3, enabled = !busy)
            Field("插入频率（0 为关闭）", frequency, { frequency = it }, enabled = !busy, type = KeyboardType.Number)
            SelectField("注入位置", position, NotePosition.entries, NotePosition::label, !busy) { position = it }
            if (position == NotePosition.DEPTH) {
                Field("注入深度", depth, { depth = it }, enabled = !busy, type = KeyboardType.Number)
                SelectField("注入身份", role, PromptRole.entries, PromptRole::label, !busy) { role = it }
            }
            GroupToggle("允许世界书扫描注释", scan, !busy) { scan = it }
        }
    }, dismissButton = { TextButton(close, enabled = !busy) { Text("取消") } }, confirmButton = {
        TextButton(onClick = {
            save(note.copy(text = text, frequency = requireNotNull(numericFrequency), depth = numericDepth ?: note.depth,
                position = position, role = role, scan = scan))
        }, enabled = !busy && numericFrequency != null && (position != NotePosition.DEPTH || numericDepth != null)) {
            Text("保存")
        }
    })
}
