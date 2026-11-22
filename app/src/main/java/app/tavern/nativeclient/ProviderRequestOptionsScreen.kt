package app.tavern.nativeclient

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun ProviderRequestOptionsScreen(value: ProviderRequestOptions, close: () -> Unit, save: (ProviderRequestOptions) -> Unit) {
    var draft by remember { mutableStateOf(value) }
    var error by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val import = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                importing = true
                error = null
                try {
                    draft = withContext(Dispatchers.IO) {
                        val bytes = requireNotNull(resolver.openInputStream(uri)) { "无法打开设置文件" }.use { it.readLimited(1024 * 1024) }
                        readOriginalProviderRequestOptions(bytes)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e.localizedMessage ?: "无法导入请求设置"
                } finally {
                    importing = false
                }
            }
        }
    }
    BackHandler { close() }
    Form {
        TextButton(close) { Text("返回提供商设置") }
        FormHeading("附加请求参数", "支持 YAML 和 JSON。先添加或覆盖请求体字段，再移除指定字段。")
        SelectField("提示词后处理", draft.processing, PromptPostProcessing.entries, PromptPostProcessing::label, !importing) {
            draft = draft.copy(processing = it)
        }
        Field("补充请求体", draft.includeBody, { draft = draft.copy(includeBody = it); error = null },
            placeholder = "top_k: 20\nrepetition_penalty: 1.1", lines = 4, enabled = !importing)
        Field("移除请求体参数", draft.excludeBody, { draft = draft.copy(excludeBody = it); error = null },
            placeholder = "- frequency_penalty\n- presence_penalty", lines = 3, enabled = !importing)
        Field("补充请求头", draft.includeHeaders, { draft = draft.copy(includeHeaders = it); error = null },
            placeholder = "X-Project: example", lines = 4, enabled = !importing)
        if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
        OutlinedButton({ import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
            enabled = !importing, modifier = Modifier.fillMaxWidth()) { Text("导入原版附加参数 JSON") }
        Button({
            runCatching { draft.compile() }.fold(
                { save(draft) },
                { error = it.localizedMessage ?: "请求设置有误" },
            )
        }, enabled = !importing, modifier = Modifier.fillMaxWidth()) { Text("应用附加参数") }
    }
}

internal fun readOriginalProviderRequestOptions(bytes: ByteArray): ProviderRequestOptions {
    val root = boundedJsonObject(bytes.decodeToString().trimStart('\uFEFF'), "无法读取设置 JSON")
    val options = root.optJSONObject("oai_settings") ?: root
    require(listOf("custom_include_body", "custom_exclude_body", "custom_include_headers", "custom_prompt_post_processing").any(options::has)) {
        "文件中没有 SillyTavern 自定义请求字段"
    }
    return ProviderRequestOptions.fromOriginal(options).also { it.compile() }
}
