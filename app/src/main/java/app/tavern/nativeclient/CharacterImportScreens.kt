package app.tavern.nativeclient

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

@Composable
internal fun CharacterLinkDialog(
    card: Card?, busy: Boolean, close: () -> Unit, tagImport: CardTagImport = CardTagImport.ALL,
    submit: (String, CardTagImport) -> Boolean,
) {
    var input by rememberSaveable(card?.id) { mutableStateOf(card?.sourceLink.orEmpty()) }
    var tagChoice by rememberSaveable { mutableStateOf(CardTagImport.ALL) }
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
                if (multiple && tagImport == CardTagImport.ASK) CardTagImportField(tagChoice, !busy) { tagChoice = it }
            }
        },
        confirmButton = {
            TextButton({ if (submit(input.trim(), if (tagImport == CardTagImport.ASK) tagChoice else tagImport)) close() },
                enabled = !busy && input.isNotBlank() &&
                (multiple || input.lineSequence().count { it.isNotBlank() } == 1)) {
                Text(if (multiple) "导入" else "更新")
            }
        },
        dismissButton = { TextButton(close, enabled = !busy) { Text("取消") } },
    )
}

@Composable
internal fun CardTagImportDialog(busy: Boolean, close: () -> Unit, submit: (CardTagImport) -> Unit) {
    var mode by rememberSaveable { mutableStateOf(CardTagImport.ALL) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("导入角色卡标签") },
        text = { CardTagImportField(mode, !busy) { mode = it } },
        confirmButton = { TextButton({ submit(mode) }, enabled = !busy) { Text("导入") } },
        dismissButton = { TextButton(close) { Text("取消") } },
    )
}

@Composable
private fun CardTagImportField(mode: CardTagImport, enabled: Boolean, change: (CardTagImport) -> Unit) {
    SelectField("添加到本机标签", mode, CardTagImport.entries.filterNot { it == CardTagImport.ASK },
        CardTagImport::label, enabled, change)
}

@Composable
internal fun CardLoreImportDialog(cards: List<Card>, busy: Boolean, close: () -> Unit, submit: (List<String>) -> Unit) {
    val ids = cards.map(Card::id)
    var selected by rememberSaveable(ids) { mutableStateOf(ids.toTypedArray()) }
    AlertDialog(
        onDismissRequest = { if (!busy) close() },
        title = { Text("提取角色卡世界书") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("为选中的角色提取内嵌条目，并将新世界书关联到角色。已有同名世界书会保留。")
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(cards, key = Card::id) { card ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(card.id in selected, enabled = !busy, onCheckedChange = { checked ->
                                selected = if (checked) selected + card.id else selected.filterNot { it == card.id }.toTypedArray()
                            })
                            Text(card.name)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ submit(selected.toList()) }, enabled = !busy && selected.isNotEmpty()) { Text("提取并关联") } },
        dismissButton = { TextButton(close, enabled = !busy) { Text("稍后") } },
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
