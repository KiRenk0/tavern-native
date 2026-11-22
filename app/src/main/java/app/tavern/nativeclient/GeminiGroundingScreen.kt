package app.tavern.nativeclient

import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
internal fun GoogleGroundedReplyCard(entry: OpenGoogleReply, appearance: ChatAppearance) {
    val handler = LocalUriHandler.current
    var openFailed by remember { mutableStateOf(false) }
    fun open(url: String) {
        openFailed = runCatching { handler.openUri(url) }.isFailure
    }
    val reply = entry.reply
    Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${entry.speaker} · Google 联网回复", style = MaterialTheme.typography.labelLarge)
            RichMessageContent(reply.text, appearance.copy(renderHtmlCards = false), Modifier.fillMaxWidth())
            if (reply.suggestionHtml != null) {
                GoogleSearchSuggestions(reply.suggestionHtml, ::open)
            } else {
                for (query in reply.searchQueries) {
                    TextButton(onClick = { open("https://www.google.com/search?q=${Uri.encode(query)}") }) {
                        Text(query)
                    }
                }
            }
            if (reply.sources.isNotEmpty()) ReplySourcesSection(reply.sources)
            entry.interruption?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (openFailed) Text("无法打开网页", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun GoogleSearchSuggestions(html: String, open: (String) -> Unit) {
    val currentOpen by rememberUpdatedState(open)
    val document = remember(html) {
        """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline'; font-src data:; base-uri 'none'; form-action 'none'">
        </head><body style="margin:0">$html</body></html>
        """.trimIndent()
    }
    AndroidView(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                settings.apply {
                    javaScriptEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                    blockNetworkLoads = true
                    cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                    setSupportMultipleWindows(false)
                }
                importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url.toString()
                        if (request.isForMainFrame && request.hasGesture() && isGoogleSearchSuggestion(url)) currentOpen(url)
                        return true
                    }
                }
            }
        },
        update = { view ->
            if (view.tag != document) {
                view.tag = document
                view.loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
            }
        },
        onRelease = { view -> view.destroy() },
    )
}
