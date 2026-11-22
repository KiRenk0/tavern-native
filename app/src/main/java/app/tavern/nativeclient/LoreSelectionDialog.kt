package app.tavern.nativeclient

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun LoreSelectionDialog(
    title: String,
    options: List<Pair<String, String>>,
    selected: List<String>,
    dismiss: () -> Unit,
    save: (List<String>) -> Unit,
) {
    var selection by rememberSaveable { mutableStateOf(selected) }
    var query by rememberSaveable { mutableStateOf("") }
    val known = remember(options) { options.mapTo(hashSetOf()) { it.first } }
    val choices = remember(options, selected, query) {
        (options + selected.filterNot(known::contains).map { it to "未找到 · $it" })
            .filter { it.second.contains(query.trim(), ignoreCase = true) }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
        Column {
            if (options.size > 8) Field("搜索", query, { query = it })
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                if (choices.isEmpty()) item { Text("没有可选择的项目", Modifier.padding(vertical = 12.dp)) }
                items(choices, key = { it.first }) { (key, label) ->
                    val checked = key in selection
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .toggleable(checked, role = Role.Checkbox) { on ->
                            selection = if (on) selection + key else selection - key
                        }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked, onCheckedChange = null)
                        Text(label, Modifier.padding(start = 12.dp))
                    }
                }
            }
        }
    }, confirmButton = { TextButton({ save(selection) }) { Text("应用") } },
        dismissButton = { TextButton(dismiss) { Text("取消") } })
}
