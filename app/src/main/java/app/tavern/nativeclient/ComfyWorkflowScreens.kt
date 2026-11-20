package app.tavern.nativeclient

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun ComfyWorkflowDialog(state: TavernState, model: TavernViewModel,
                                 close: () -> Unit, select: ((String) -> Unit)? = null) {
    var editor by remember { mutableStateOf<ComfyWorkflow?>(null) }
    var exportTarget by remember { mutableStateOf<ComfyWorkflow?>(null) }
    var removal by remember { mutableStateOf<ComfyWorkflow?>(null) }
    val importWorkflow = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        uri?.let { model.readComfyWorkflow(it) { workflow -> editor = workflow } }
    }
    val export = rememberLauncherForActivityResult(CreateDocument("application/json")) { uri ->
        if (uri != null) exportTarget?.let { model.exportComfyWorkflow(uri, it) }
        exportTarget = null
    }
    fun copy(workflow: ComfyWorkflow) = workflow.copy(id = newId(), name = "${workflow.name} 副本")
    val editing = editor
    if (editing != null) {
        key(editing.id) {
            ComfyWorkflowEditor(editing, state, model, close = { editor = null }) {
                editor = null
                select?.invoke(it.id)
            }
        }
        return
    }
    val deleting = removal
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { if (!state.busy) removal = null },
            title = { Text("删除工作流") },
            text = { Text("删除「${deleting.name}」后，使用它生成的图片仍可查看。") },
            dismissButton = { TextButton({ removal = null }, enabled = !state.busy) { Text("取消") } },
            confirmButton = {
                Button({ model.deleteComfyWorkflow(deleting) { removal = null } }, enabled = !state.busy) { Text("删除") }
            },
        )
        return
    }
    AlertDialog(
        onDismissRequest = { if (!state.busy) close() },
        title = { Text("ComfyUI 工作流") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("可导入 ComfyUI 的 API 格式 JSON，也可复制内置工作流后编辑。",
                    style = MaterialTheme.typography.bodyMedium)
                TextButton({ importWorkflow.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !state.busy) {
                    Icon(Icons.Outlined.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text("导入工作流")
                }
                state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(state.comfyWorkflows, key = ComfyWorkflow::id) { workflow ->
                        var menu by remember { mutableStateOf(false) }
                        ListItem(
                            headlineContent = { Text(workflow.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = if (workflow.id == AVATAR_COMFY_WORKFLOW) ({
                                Text("使用角色头像，需要 comfyui-tooling-nodes")
                            }) else null,
                            modifier = Modifier.clickable(enabled = !state.busy) {
                                if (select == null) editor = if (workflow.isBuiltIn) copy(workflow) else workflow
                                else select(workflow.id)
                            },
                            trailingContent = {
                                Box {
                                    IconButton({ menu = true }, enabled = !state.busy) {
                                        Icon(Icons.Outlined.MoreVert, "管理 ${workflow.name}")
                                    }
                                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                        if (!workflow.isBuiltIn) DropdownMenuItem(
                                            text = { Text("编辑或重命名") },
                                            onClick = { menu = false; editor = workflow },
                                        )
                                        DropdownMenuItem(text = { Text("复制后编辑") }, onClick = {
                                            menu = false; editor = copy(workflow)
                                        })
                                        DropdownMenuItem(text = { Text("导出 API JSON") }, onClick = {
                                            menu = false
                                            exportTarget = workflow
                                            export.launch("${safeFilename(workflow.name)}.json")
                                        }, leadingIcon = { Icon(Icons.Outlined.FileUpload, null) })
                                        if (!workflow.isBuiltIn) DropdownMenuItem(text = { Text("删除") }, onClick = {
                                            menu = false; removal = workflow
                                        }, leadingIcon = { Icon(Icons.Outlined.Delete, null) })
                                    }
                                }
                            },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(close, enabled = !state.busy) { Text("完成") } },
    )
}

@Composable
private fun ComfyWorkflowEditor(workflow: ComfyWorkflow, state: TavernState, model: TavernViewModel,
                                close: () -> Unit, saved: (ComfyWorkflow) -> Unit) {
    var name by remember { mutableStateOf(workflow.name) }
    var source by remember { mutableStateOf(workflow.source) }
    var replacements by remember { mutableStateOf(workflow.replacements.toList()) }
    val validation by produceState<Result<Set<String>>?>(null, source) {
        value = null
        delay(200)
        value = withContext(Dispatchers.Default) { runCatching { parseComfyWorkflow(source).keys } }
    }
    val duplicateName = state.comfyWorkflows.any { it.id != workflow.id && it.name.equals(name.trim(), true) }
    val matchingKeys = replacements.map { it.first.trim() }
    val duplicateKey = matchingKeys.size != matchingKeys.distinct().size
    AlertDialog(
        onDismissRequest = { if (!state.busy) close() },
        title = { Text("编辑 ComfyUI 工作流") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("工作流名称") }, singleLine = true,
                    isError = duplicateName, supportingText = if (duplicateName) ({ Text("已有同名工作流") }) else null)
                OutlinedTextField(source, { source = it }, Modifier.fillMaxWidth(), label = { Text("API 格式 JSON") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), minLines = 7, maxLines = 13)
                validation?.exceptionOrNull()?.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                validation?.getOrNull()?.takeIf(Set<String>::isNotEmpty)?.let { keys ->
                    Text(keys.sorted().joinToString("  ") { "%$it%" }, style = MaterialTheme.typography.bodySmall)
                }
                replacements.forEachIndexed { index, (key, value) ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(key, { changed ->
                                replacements = replacements.toMutableList().apply { this[index] = changed to value }
                            }, Modifier.weight(1f), label = { Text("占位符名称") }, singleLine = true)
                            IconButton({ replacements = replacements.filterIndexed { row, _ -> row != index } }) {
                                Icon(Icons.Outlined.Close, "移除占位符")
                            }
                        }
                        OutlinedTextField(value, { changed ->
                            replacements = replacements.toMutableList().apply { this[index] = key to changed }
                        }, Modifier.fillMaxWidth(), label = { Text("替换值") }, maxLines = 3)
                    }
                }
                if (duplicateKey) Text("请使用不同的占位符名称", color = MaterialTheme.colorScheme.error)
                TextButton({ replacements = replacements + ("" to "") }) { Text("添加自定义占位符") }
                state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        dismissButton = { TextButton(close, enabled = !state.busy) { Text("取消") } },
        confirmButton = {
            Button(
                onClick = { model.saveComfyWorkflow(workflow.copy(name = name, source = source,
                    replacements = replacements.associate { it.first.trim() to it.second }), saved) },
                enabled = !state.busy && validation?.isSuccess == true && name.isNotBlank() && !duplicateName &&
                    !duplicateKey && matchingKeys.none(String::isBlank),
            ) { Text("保存") }
        },
    )
}
