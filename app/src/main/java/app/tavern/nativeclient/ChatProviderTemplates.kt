package app.tavern.nativeclient

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

internal const val DEFAULT_AZURE_API_VERSION = "2024-10-21"

internal enum class AuthHeaderConvention(val label: String) {
    BEARER("Authorization: Bearer <API_KEY>"),
    AZURE_API_KEY("api-key: <API_KEY>"),
    VERTEX_AUTO("Authorization: Bearer <TOKEN> / x-goog-api-key: <KEY>"),
}

internal data class AzureOpenAiConfig(
    val deployment: String = "",
    val apiVersion: String = DEFAULT_AZURE_API_VERSION,
) {
    fun validated(): AzureOpenAiConfig = copy(
        deployment = deployment.trim(),
        apiVersion = apiVersion.trim().ifBlank { DEFAULT_AZURE_API_VERSION },
    )

    fun json(): JSONObject = jsonOf(
        "deployment" to deployment.trim(),
        "apiVersion" to apiVersion.trim().ifBlank { DEFAULT_AZURE_API_VERSION },
    )

    companion object {
        fun read(json: JSONObject?): AzureOpenAiConfig {
            if (json == null) return AzureOpenAiConfig()
            return AzureOpenAiConfig(
                deployment = json.string("deployment").trim(),
                apiVersion = json.string("apiVersion").trim().ifBlank { DEFAULT_AZURE_API_VERSION },
            )
        }
    }
}

internal enum class ChatProviderPreset(
    val displayName: String,
    val baseUrl: String,
    val protocol: ApiProtocol,
    val defaultModel: String,
    val recommendedModels: List<String>,
    val authHeader: AuthHeaderConvention,
    val description: String,
    val azureDefaultVersion: String = DEFAULT_AZURE_API_VERSION,
) {
    MISTRAL(
        displayName = "Mistral AI",
        baseUrl = "https://api.mistral.ai/v1",
        protocol = ApiProtocol.OPENAI_COMPATIBLE,
        defaultModel = "mistral-large-latest",
        recommendedModels = listOf(
            "mistral-large-latest",
            "mistral-medium-latest",
            "mistral-small-latest",
            "open-mistral-nemo",
            "ministral-8b-latest",
            "codestral-latest",
        ),
        authHeader = AuthHeaderConvention.BEARER,
        description = "Mistral 官方对话接口（支持 Large / Small / Nemo / Codestral）。",
    ),
    COHERE(
        displayName = "Cohere",
        baseUrl = "https://api.cohere.ai/compatibility/v1",
        protocol = ApiProtocol.OPENAI_COMPATIBLE,
        defaultModel = "command-a-03-2025",
        recommendedModels = listOf(
            "command-a-03-2025",
            "command-r-plus-08-2024",
            "command-r-08-2024",
            "command-r7b-12-2024",
        ),
        authHeader = AuthHeaderConvention.BEARER,
        description = "Cohere Command-A / Command-R 系列 OpenAI 兼容对话接口。",
    ),
    VERTEX_AI(
        displayName = "Google Vertex AI",
        baseUrl = "https://aiplatform.googleapis.com/v1/publishers/google",
        protocol = ApiProtocol.VERTEX_AI,
        defaultModel = "gemini-2.5-pro",
        recommendedModels = listOf(
            "gemini-2.5-pro",
            "gemini-2.5-flash",
            "gemini-2.0-flash",
            "gemini-1.5-pro",
        ),
        authHeader = AuthHeaderConvention.VERTEX_AUTO,
        description = "Google Cloud Vertex AI（支持 Express API Key 与区域 OAuth Bearer 端点）。",
    ),
    AZURE_OPENAI(
        displayName = "Azure OpenAI",
        baseUrl = "https://your-resource.openai.azure.com",
        protocol = ApiProtocol.AZURE_OPENAI,
        defaultModel = "gpt-4o",
        recommendedModels = listOf("gpt-4o", "gpt-4o-mini", "gpt-4.1", "o3-mini"),
        authHeader = AuthHeaderConvention.AZURE_API_KEY,
        description = "Microsoft Azure OpenAI 服务（支持自定义部署名 Deployment 与 api-version）。",
    ),
    XAI(
        displayName = "xAI (Grok)",
        baseUrl = "https://api.x.ai/v1",
        protocol = ApiProtocol.OPENAI_COMPATIBLE,
        defaultModel = "grok-3",
        recommendedModels = listOf("grok-3", "grok-3-mini", "grok-2-latest", "grok-2-vision-1212"),
        authHeader = AuthHeaderConvention.BEARER,
        description = "xAI Grok 系列官方对话接口。",
    ),
    GROQ(
        displayName = "Groq",
        baseUrl = "https://api.groq.com/openai/v1",
        protocol = ApiProtocol.OPENAI_COMPATIBLE,
        defaultModel = "llama-3.3-70b-versatile",
        recommendedModels = listOf(
            "llama-3.3-70b-versatile",
            "llama-3.1-8b-instant",
            "deepseek-r1-distill-llama-70b",
            "qwen-qwq-32b",
            "gemma2-9b-it",
        ),
        authHeader = AuthHeaderConvention.BEARER,
        description = "Groq LPU 超高速推理接口。",
    ),
    PERPLEXITY(
        displayName = "Perplexity",
        baseUrl = "https://api.perplexity.ai",
        protocol = ApiProtocol.OPENAI_COMPATIBLE,
        defaultModel = "sonar-pro",
        recommendedModels = listOf("sonar-pro", "sonar", "sonar-reasoning-pro", "sonar-reasoning", "r1-1776"),
        authHeader = AuthHeaderConvention.BEARER,
        description = "Perplexity Sonar 联网搜索与推理对话接口。",
    ),
    MOONSHOT(
        displayName = "Moonshot (Kimi)",
        baseUrl = "https://api.moonshot.cn/v1",
        protocol = ApiProtocol.OPENAI_COMPATIBLE,
        defaultModel = "kimi-latest",
        recommendedModels = listOf("kimi-latest", "moonshot-v1-128k", "moonshot-v1-32k", "moonshot-v1-8k"),
        authHeader = AuthHeaderConvention.BEARER,
        description = "月之暗面 Moonshot / Kimi 长上下文对话接口。",
    ),
    ZHIPU(
        displayName = "智谱 AI (GLM)",
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        protocol = ApiProtocol.OPENAI_COMPATIBLE,
        defaultModel = "glm-4-plus",
        recommendedModels = listOf("glm-4-plus", "glm-4-air", "glm-4-flash", "glm-4-long", "glm-z1-air"),
        authHeader = AuthHeaderConvention.BEARER,
        description = "智谱清言开放平台 GLM-4 / GLM-Z1 系列对话接口。",
    ),
    NANOGPT(
        displayName = "NanoGPT",
        baseUrl = "https://nano-gpt.com/api/v1",
        protocol = ApiProtocol.OPENAI_COMPATIBLE,
        defaultModel = "chatgpt-4o-latest",
        recommendedModels = listOf(
            "chatgpt-4o-latest",
            "claude-3-7-sonnet-20250219",
            "deepseek-r1",
            "deepseek-chat",
        ),
        authHeader = AuthHeaderConvention.BEARER,
        description = "NanoGPT 按量聚合多模型对话接口。",
    );

    val presetId: String get() = name.lowercase(Locale.ROOT)

    fun createProviderDraft(id: String = newId()): Provider = Provider(
        id = id,
        name = displayName,
        endpoint = baseUrl,
        defaultModel = defaultModel,
        models = recommendedModels,
        protocol = protocol,
        streaming = true,
        azureConfig = if (this == AZURE_OPENAI) AzureOpenAiConfig(deployment = defaultModel, apiVersion = azureDefaultVersion) else AzureOpenAiConfig(),
    )

    fun buildAuthHeaders(apiKey: String): Map<String, String> {
        val key = apiKey.trim()
        if (key.isEmpty()) return emptyMap()
        return when (authHeader) {
            AuthHeaderConvention.BEARER -> mapOf("Authorization" to "Bearer $key")
            AuthHeaderConvention.AZURE_API_KEY -> mapOf("api-key" to key)
            AuthHeaderConvention.VERTEX_AUTO -> VertexAiAdapter.buildHeaders(key)
        }
    }

    companion object {
        fun fromId(id: String): ChatProviderPreset? =
            entries.firstOrNull { it.presetId.equals(id.trim(), ignoreCase = true) || it.name.equals(id.trim(), ignoreCase = true) }
    }
}

internal object AzureOpenAiAdapter {
    fun resolveDeployment(provider: Provider, model: String): String =
        provider.azureConfig.deployment.trim().ifBlank { model.trim() }.ifBlank { provider.defaultModel.trim() }

    fun resolveApiVersion(provider: Provider): String =
        provider.azureConfig.apiVersion.trim().ifBlank { DEFAULT_AZURE_API_VERSION }

    fun buildHeaders(provider: Provider): Map<String, String> {
        val key = provider.apiKey.trim()
        return if (key.isBlank()) emptyMap() else mapOf("api-key" to key)
    }

    fun buildChatCompletionsUrl(provider: Provider, model: String): String {
        val base = checkedAddress(provider.endpoint).toString().trimEnd('/')
        val version = URLEncoder.encode(resolveApiVersion(provider), "UTF-8")
        if ("/chat/completions" in base) {
            return "$base?api-version=$version"
        }
        val cleanRoot = base
            .removeSuffix("/openai")
            .substringBefore("/openai/deployments/")
        val deployment = URLEncoder.encode(resolveDeployment(provider, model), "UTF-8")
        return "$cleanRoot/openai/deployments/$deployment/chat/completions?api-version=$version"
    }

    fun buildDeploymentsUrl(provider: Provider): String {
        val base = checkedAddress(provider.endpoint).toString().trimEnd('/')
            .substringBefore("/openai/")
            .removeSuffix("/openai")
        val version = URLEncoder.encode(resolveApiVersion(provider), "UTF-8")
        return "$base/openai/deployments?api-version=$version"
    }

    fun buildModelsUrl(provider: Provider): String {
        val base = checkedAddress(provider.endpoint).toString().trimEnd('/')
            .substringBefore("/openai/")
            .removeSuffix("/openai")
        val version = URLEncoder.encode(resolveApiVersion(provider), "UTF-8")
        return "$base/openai/models?api-version=$version"
    }

    suspend fun discoverModels(provider: Provider): DiscoveredModels {
        val headers = buildHeaders(provider)
        val redact: (String) -> String = { it.redactProviderSecrets(provider) }
        val configured = provider.azureConfig.deployment.trim().takeIf(String::isNotBlank)
        return try {
            request(buildDeploymentsUrl(provider), headers = headers, readTimeout = 15_000, redactError = redact) { input, _ ->
                val rows = JSONObject(input.readLimited().decodeToString()).optJSONArray("data")?.objects().orEmpty()
                val ids = linkedSetOf<String>()
                configured?.let { ids += it }
                rows.forEach { row ->
                    val id = row.string("id").ifBlank { row.string("model") }.trim()
                    if (id.isNotBlank()) ids += id
                }
                DiscoveredModels(ids.sorted(), emptyMap())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            request(buildModelsUrl(provider), headers = headers, readTimeout = 15_000, redactError = redact) { input, _ ->
                val found = DiscoveredModels.read(JSONObject(input.readLimited().decodeToString()))
                val ids = (listOfNotNull(configured) + found.ids).distinct().sorted()
                found.copy(ids = ids)
            }
        }
    }
}

internal object VertexAiAdapter {
    fun buildHeaders(apiKey: String): Map<String, String> {
        val key = apiKey.trim()
        if (key.isEmpty()) return emptyMap()
        // Vertex AI Express API keys start with AIza or AQ.; GCP OAuth access tokens use Bearer auth.
        return if (key.startsWith("AIza") || key.startsWith("AQ.")) {
            mapOf("x-goog-api-key" to key)
        } else {
            mapOf("Authorization" to "Bearer $key")
        }
    }

    fun isOpenAiCompatEndpoint(endpoint: String): Boolean =
        endpoint.trimEnd('/').endsWith("/openapi", ignoreCase = true)

    fun buildGenerateUrl(endpoint: String, model: String, streaming: Boolean): String {
        val clean = endpoint.trimEnd('/')
        val cleanModel = model.trim().removePrefix("models/")
        val encodedModel = URLEncoder.encode(cleanModel, "UTF-8")
        val suffix = if (streaming) ":streamGenerateContent?alt=sse" else ":generateContent"
        val base = when {
            clean.endsWith("/models") -> clean
            "/publishers/google" in clean -> "$clean/models"
            clean.endsWith("/v1") || clean.endsWith("/v1beta1") -> "$clean/publishers/google/models"
            else -> "$clean/v1/publishers/google/models"
        }
        return "$base/$encodedModel$suffix"
    }

    suspend fun discoverModels(provider: Provider): DiscoveredModels {
        val base = ProviderProtocols.baseAddress(provider)
        val headers = buildHeaders(provider.apiKey)
        val redact: (String) -> String = { it.redactProviderSecrets(provider) }
        if (isOpenAiCompatEndpoint(base)) {
            return request("$base/models", headers = headers, readTimeout = 15_000, redactError = redact) { input, _ ->
                DiscoveredModels.read(JSONObject(input.readLimited().decodeToString()))
            }
        }
        val modelsUrl = when {
            base.endsWith("/models") -> base
            "/publishers/google" in base -> "$base/models"
            else -> "$base/v1/publishers/google/models"
        }
        return try {
            request(modelsUrl, headers = headers, readTimeout = 12_000, redactError = redact) { input, _ ->
                val found = DiscoveredModels.read(JSONObject(input.readLimited().decodeToString()), googleAliases = true)
                if (found.ids.isNotEmpty()) found else DiscoveredModels(ChatProviderPreset.VERTEX_AI.recommendedModels, emptyMap())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            DiscoveredModels(ChatProviderPreset.VERTEX_AI.recommendedModels, emptyMap())
        }
    }

    suspend fun complete(
        provider: Provider,
        payload: JSONObject,
        onReasoning: (String) -> Unit,
        token: (String) -> Unit,
        acceptTools: Boolean,
        customStops: List<String>,
    ): CompletionAccumulator {
        val base = ProviderProtocols.baseAddress(provider)
        val headers = buildHeaders(provider.apiKey)
        val streaming = payload.optBoolean("stream", true)
        val modelName = payload.string("model").ifBlank { provider.defaultModel }.removePrefix("models/")
        val body = ProviderProtocols.buildGeminiNativePayload(provider, payload, cachedContentName = null).first
        val url = buildGenerateUrl(base, modelName, streaming)
        return ProviderProtocols.executeGeminiUrlRequest(
            url = url,
            headers = headers,
            keyForRedaction = provider.apiKey.trim(),
            body = body,
            onReasoning = onReasoning,
            token = token,
            acceptTools = acceptTools,
            customStops = customStops,
        )
    }
}

@Composable
internal fun ProviderPresetPickerDialog(
    onDismiss: () -> Unit,
    onSelectProvider: (Provider) -> Unit,
) {
    var showTextBackends by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从预设模板添加提供商") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !showTextBackends,
                        onClick = { showTextBackends = false },
                        label = { Text("聊天补全 (${ChatProviderPreset.entries.size})") },
                    )
                    FilterChip(
                        selected = showTextBackends,
                        onClick = { showTextBackends = true },
                        label = { Text("文本补全 (${AdditionalTextBackends.allSpecs.size})") },
                    )
                }
                Field("搜索提供商或模型", filter, { filter = it }, placeholder = "例如：Mistral、Azure、Tabby、vLLM")
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!showTextBackends) {
                        val items = ChatProviderPreset.entries.filter { preset ->
                            filter.isBlank() ||
                                preset.displayName.contains(filter, ignoreCase = true) ||
                                preset.defaultModel.contains(filter, ignoreCase = true) ||
                                preset.description.contains(filter, ignoreCase = true)
                        }
                        items(items, key = { it.name }) { preset ->
                            Card(
                                onClick = { onSelectProvider(preset.createProviderDraft()) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(preset.displayName, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        preset.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        "${preset.defaultModel} · ${preset.authHeader.label}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    } else {
                        val items = AdditionalTextBackends.allSpecs.filter { spec ->
                            filter.isBlank() ||
                                spec.displayName.contains(filter, ignoreCase = true) ||
                                spec.defaultModel.contains(filter, ignoreCase = true) ||
                                spec.description.contains(filter, ignoreCase = true)
                        }
                        items(items, key = { it.protocol.name }) { spec ->
                            Card(
                                onClick = { onSelectProvider(AdditionalTextBackends.createProviderDraft(spec)) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(spec.displayName, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        spec.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        spec.defaultEndpoint,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("取消") } },
    )
}
