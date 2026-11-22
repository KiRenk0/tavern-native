package app.tavern.nativeclient

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow

@Composable
internal fun ReplySourcesSection(sources: List<ReplySource>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var openFailed by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "收起联网来源" else "联网来源 · ${sources.size}")
    }
    if (!expanded) return
    for (source in sources) {
        TextButton(onClick = {
            openFailed = runCatching { uriHandler.openUri(source.url) }.isFailure
        }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                Text(source.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (source.domain != source.title) {
                    Text(source.domain, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    if (openFailed) Text("无法打开网页", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}
