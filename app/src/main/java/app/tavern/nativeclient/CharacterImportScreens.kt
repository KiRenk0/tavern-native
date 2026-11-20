package app.tavern.nativeclient

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun CharacterLinkDialog(card: Card?, busy: Boolean, close: () -> Unit, submit: (String) -> Boolean) {
    var input by rememberSaveable(card?.id) { mutableStateOf(card?.sourceLink.orEmpty()) }
    val multiple = card == null
    AlertDialog(
        onDismissRequest = { if (!busy) close() },
        title = { Text(if (multiple) "从分享链接导入" else "从链接更新角色设定") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(input, { input = it }, modifier = Modifier.fillMaxWidth(), enabled = !busy,
                    label = { Text(if (multiple) "分享链接或 ID" else "角色分享链接或 ID") },
                    supportingText = {
                        Text(if (multiple) "每行输入一个链接或 ID。Chub 世界书会导入到世界书列表。"
                            else "填写一张角色卡的链接。原来的角色名称、聊天记录和群组归属会用于更新后的角色。")
                    }, minLines = if (multiple) 3 else 1, maxLines = if (multiple) 7 else 3)
            }
        },
        confirmButton = {
            TextButton({ if (submit(input.trim())) close() }, enabled = !busy && input.isNotBlank() &&
                (multiple || input.lineSequence().count { it.isNotBlank() } == 1)) {
                Text(if (multiple) "导入" else "更新")
            }
        },
        dismissButton = { TextButton(close, enabled = !busy) { Text("取消") } },
    )
}

@Composable
internal fun ReplaceCharacterDialog(card: Card, busy: Boolean, close: () -> Unit, file: () -> Unit, link: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!busy) close() },
        title = { Text("更新 ${card.name} 的角色设定") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("替换角色描述、开场白和其他卡片内容。更新后的角色沿用原名称、聊天记录、群组、图库、本机标签和世界书绑定。")
                OutlinedButton(file, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("选择角色卡文件") }
                OutlinedButton(link, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("输入分享链接") }
            }
        },
        confirmButton = { TextButton(close, enabled = !busy) { Text("取消") } },
    )
}
