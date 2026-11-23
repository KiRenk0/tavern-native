package app.tavern.nativeclient

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal const val DEFAULT_KEY_COOLDOWN_SECONDS = 60
internal const val MAX_KEY_COOLDOWN_SECONDS = 86_400
internal const val MAX_MULTI_KEYS = 32

internal enum class KeyRotationPolicy(val wire: String, val label: String) {
    FAILOVER_ON_ERROR("failover", "故障自动切换（401/403/429/配额）"),
    ROUND_ROBIN("round_robin", "轮询切换（Round-Robin）");

    companion object {
        fun from(wire: String): KeyRotationPolicy =
            entries.firstOrNull { it.wire.equals(wire.trim(), ignoreCase = true) || it.name.equals(wire.trim(), ignoreCase = true) }
                ?: FAILOVER_ON_ERROR
    }
}

internal data class ApiKeyEntry(
    val id: String = newId(),
    val label: String = "",
    val secret: String = "",
    val enabled: Boolean = true,
) {
    fun masked(): String {
        val clean = secret.trim()
        if (clean.isEmpty()) return "（空）"
        if (clean.length <= 6) return "••••••"
        val prefix = if (clean.length >= 12) clean.take(4) else clean.take(2)
        val suffix = clean.takeLast(4)
        return "$prefix••••••$suffix"
    }

    fun storageJson(seal: (String) -> String): JSONObject = jsonOf(
        "id" to id,
        "label" to label.trim().take(64),
        "secret" to seal(secret.trim()),
        "enabled" to enabled,
    )

    companion object {
        fun fromJson(json: JSONObject, unseal: (String) -> String): ApiKeyEntry? {
            val secret = unseal(json.string("secret")).trim()
            if (secret.isEmpty()) return null
            val id = json.string("id").takeIf { it.isNotBlank() && it.length <= 128 } ?: newId()
            return ApiKeyEntry(
                id = id,
                label = json.string("label").trim().take(64),
                secret = secret,
                enabled = json.optBoolean("enabled", true),
            )
        }
    }
}

internal data class MultiKeyConfig(
    val keys: List<ApiKeyEntry> = emptyList(),
    val policy: KeyRotationPolicy = KeyRotationPolicy.FAILOVER_ON_ERROR,
    val cooldownSeconds: Int = DEFAULT_KEY_COOLDOWN_SECONDS,
) {
    val isEmpty: Boolean get() = keys.isEmpty()
    val enabledKeys: List<ApiKeyEntry> get() = keys.filter { it.enabled && it.secret.isNotBlank() }

    fun validated(): MultiKeyConfig {
        val cleaned = keys.mapNotNull { entry ->
            val trimmed = entry.secret.trim()
            if (trimmed.isEmpty()) null else entry.copy(
                id = entry.id.ifBlank { newId() },
                label = entry.label.trim().take(64),
                secret = trimmed,
            )
        }.distinctBy(ApiKeyEntry::id).take(MAX_MULTI_KEYS)
        return copy(
            keys = cleaned,
            cooldownSeconds = cooldownSeconds.coerceIn(5, MAX_KEY_COOLDOWN_SECONDS),
        )
    }

    fun storageJson(seal: (String) -> String): JSONObject = jsonOf(
        "policy" to policy.wire,
        "cooldownSeconds" to cooldownSeconds.coerceIn(5, MAX_KEY_COOLDOWN_SECONDS),
        "keys" to JSONArray(keys.map { it.storageJson(seal) }),
    )

    companion object {
        fun fromJson(json: JSONObject?, unseal: (String) -> String): MultiKeyConfig {
            if (json == null) return MultiKeyConfig()
            val list = json.optJSONArray("keys")?.objects().orEmpty()
                .mapNotNull { ApiKeyEntry.fromJson(it, unseal) }
                .distinctBy(ApiKeyEntry::id)
                .take(MAX_MULTI_KEYS)
            return MultiKeyConfig(
                keys = list,
                policy = KeyRotationPolicy.from(json.string("policy")),
                cooldownSeconds = json.optInt("cooldownSeconds", DEFAULT_KEY_COOLDOWN_SECONDS)
                    .coerceIn(5, MAX_KEY_COOLDOWN_SECONDS),
            )
        }
    }
}

internal data class KeyHealthState(
    val keyId: String,
    val cooldownUntilMs: Long = 0L,
    val lastStatus: Int? = null,
    val lastError: String = "",
    val consecutiveFailures: Int = 0,
    val totalRequests: Int = 0,
    val totalFailures: Int = 0,
) {
    fun isInCooldown(nowMs: Long): Boolean = cooldownUntilMs > nowMs
    fun remainingCooldownSeconds(nowMs: Long): Long = ((cooldownUntilMs - nowMs).coerceAtLeast(0L) + 999L) / 1000L
}

internal fun Provider.effectiveApiKeys(): List<ApiKeyEntry> {
    val configured = multiKeys.enabledKeys
    if (configured.isNotEmpty()) return configured
    val single = apiKey.trim()
    return if (single.isNotEmpty()) listOf(ApiKeyEntry(id = "primary", label = "主 Key", secret = single)) else emptyList()
}

internal val Provider.hasAnyApiKey: Boolean get() = apiKey.isNotBlank() || multiKeys.enabledKeys.isNotEmpty()

internal fun Provider.allSecretsForRedaction(): List<String> = buildList {
    val single = apiKey.trim()
    if (single.isNotEmpty()) add(single)
    multiKeys.keys.forEach { entry ->
        val s = entry.secret.trim()
        if (s.isNotEmpty()) add(s)
    }
}.distinct()

internal fun String.redactProviderSecrets(provider: Provider): String {
    var result = this
    for (secret in provider.allSecretsForRedaction()) {
        result = result.redactValue(secret)
    }
    return result
}

internal class MultiKeyRotator(
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private class ProviderRotationState {
        var cursor: Int = 0
        val health = linkedMapOf<String, KeyHealthState>()
    }

    private val states = ConcurrentHashMap<String, ProviderRotationState>()

    private val quotaErrorPattern = Regex(
        """quota|rate[\s_-]?limit|too many requests|insufficient[_\s]?(?:quota|credits?|balance)|billing|credit|exceeded|invalid[_\s]?api[_\s]?key|unauthorized|forbidden|authentication|api[_\s]?key.*(?:invalid|expired|revoked|disabled)""",
        RegexOption.IGNORE_CASE,
    )

    fun clearAll() = states.clear()

    fun resetHealth(providerId: String) {
        states.remove(providerId)
    }

    fun healthFor(providerId: String, keyId: String): KeyHealthState? {
        val state = states[providerId] ?: return null
        return synchronized(state) { state.health[keyId] }
    }

    fun snapshotHealth(providerId: String): Map<String, KeyHealthState> {
        val state = states[providerId] ?: return emptyMap()
        return synchronized(state) { HashMap(state.health) }
    }

    fun isRotatableError(error: Throwable): Boolean = when (error) {
        is HttpRequestError -> when (error.status) {
            401, 402, 403, 429 -> true
            400, 500, 502, 503 -> quotaErrorPattern.containsMatchIn(error.detail)
            else -> false
        }
        else -> {
            val msg = error.message.orEmpty()
            msg.isNotBlank() && quotaErrorPattern.containsMatchIn(msg)
        }
    }

    fun selectKey(provider: Provider, excludeKeyIds: Set<String> = emptySet()): ApiKeyEntry? {
        val candidates = provider.effectiveApiKeys().filterNot { it.id in excludeKeyIds }
        if (candidates.isEmpty()) return null
        if (candidates.size == 1 && excludeKeyIds.isEmpty() && provider.multiKeys.keys.size <= 1) {
            return candidates.first()
        }
        val state = states.computeIfAbsent(provider.id) { ProviderRotationState() }
        val now = clockMs()
        return synchronized(state) {
            val healthy = candidates.filter { entry ->
                val h = state.health[entry.id]
                h == null || !h.isInCooldown(now)
            }
            val pool = healthy.ifEmpty {
                // All remaining keys are in cooldown; choose the one whose cooldown expires soonest.
                listOf(candidates.minBy { entry -> state.health[entry.id]?.cooldownUntilMs ?: 0L })
            }
            when (provider.multiKeys.policy) {
                KeyRotationPolicy.FAILOVER_ON_ERROR -> pool.first()
                KeyRotationPolicy.ROUND_ROBIN -> {
                    val idx = Math.floorMod(state.cursor, pool.size)
                    state.cursor = (state.cursor + 1) and 0x7FFF_FFFF
                    pool[idx]
                }
            }
        }
    }

    fun reportSuccess(providerId: String, keyId: String) {
        val state = states.computeIfAbsent(providerId) { ProviderRotationState() }
        synchronized(state) {
            val current = state.health[keyId] ?: KeyHealthState(keyId)
            state.health[keyId] = current.copy(
                cooldownUntilMs = 0L,
                lastStatus = 200,
                lastError = "",
                consecutiveFailures = 0,
                totalRequests = current.totalRequests + 1,
            )
        }
    }

    fun reportFailure(
        providerId: String,
        keyId: String,
        status: Int?,
        detail: String,
        cooldownSeconds: Int = DEFAULT_KEY_COOLDOWN_SECONDS,
    ) {
        val state = states.computeIfAbsent(providerId) { ProviderRotationState() }
        val now = clockMs()
        val durationMs = cooldownSeconds.coerceIn(5, MAX_KEY_COOLDOWN_SECONDS).toLong() * 1000L
        synchronized(state) {
            val current = state.health[keyId] ?: KeyHealthState(keyId)
            val failures = current.consecutiveFailures + 1
            // Apply bounded backoff for repeated consecutive failures on the same key.
            val multiplier = when (status) {
                401, 403 -> minOf(failures, 4)
                else -> minOf(failures, 3)
            }
            state.health[keyId] = current.copy(
                cooldownUntilMs = now + durationMs * multiplier,
                lastStatus = status,
                lastError = detail.take(160),
                consecutiveFailures = failures,
                totalRequests = current.totalRequests + 1,
                totalFailures = current.totalFailures + 1,
            )
        }
    }

    suspend fun <T> executeWithRotation(
        provider: Provider,
        block: suspend (activeProvider: Provider, activeKey: ApiKeyEntry?) -> T,
    ): T {
        val effective = provider.effectiveApiKeys()
        if (effective.size <= 1) {
            val single = effective.firstOrNull()
            val active = if (single != null && provider.apiKey.trim() != single.secret) {
                provider.copy(apiKey = single.secret)
            } else provider
            return try {
                val res = block(active, single)
                if (single != null) reportSuccess(provider.id, single.id)
                res
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (single != null && isRotatableError(e)) {
                    val status = (e as? HttpRequestError)?.status
                    val detail = (e as? HttpRequestError)?.detail ?: e.message.orEmpty()
                    reportFailure(provider.id, single.id, status, detail.redactProviderSecrets(provider), provider.multiKeys.cooldownSeconds)
                }
                throw e
            }
        }

        val tried = linkedSetOf<String>()
        var lastError: Exception? = null
        repeat(effective.size) {
            val chosen = selectKey(provider, excludeKeyIds = tried) ?: return@repeat
            tried += chosen.id
            val active = provider.copy(apiKey = chosen.secret)
            try {
                val result = block(active, chosen)
                reportSuccess(provider.id, chosen.id)
                return result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (!isRotatableError(e)) throw e
                val status = (e as? HttpRequestError)?.status
                val detail = ((e as? HttpRequestError)?.detail ?: e.message.orEmpty()).redactProviderSecrets(provider)
                reportFailure(provider.id, chosen.id, status, detail, provider.multiKeys.cooldownSeconds)
            }
        }
        throw lastError ?: IllegalStateException("没有可用的 API Key")
    }

    suspend fun testKey(provider: Provider, entry: ApiKeyEntry): Result<String> {
        val secret = entry.secret.trim()
        if (secret.isEmpty()) return Result.failure(IllegalArgumentException("API Key 不能为空"))
        val testProvider = provider.copy(apiKey = secret, multiKeys = MultiKeyConfig())
        return try {
            val discovered = ProviderProtocols.discoverModels(testProvider)
            reportSuccess(provider.id, entry.id)
            val msg = if (discovered.ids.isEmpty()) "连接成功（未返回模型列表）" else "有效 · 读取到 ${discovered.ids.size} 个模型"
            Result.success(msg)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val status = (e as? HttpRequestError)?.status
            val detail = (e.localizedMessage ?: "连接失败").redactValue(secret)
            if (isRotatableError(e)) {
                reportFailure(provider.id, entry.id, status, detail, provider.multiKeys.cooldownSeconds)
            }
            Result.failure(IllegalStateException(detail, e))
        }
    }
}

internal val MultiKeyManager = MultiKeyRotator()

@Composable
internal fun MultiKeyManagerDialog(
    provider: Provider,
    initialConfig: MultiKeyConfig,
    primaryApiKey: String,
    onDismiss: () -> Unit,
    onSave: (MultiKeyConfig) -> Unit,
) {
    val seedKeys = remember(initialConfig, primaryApiKey) {
        if (initialConfig.keys.isNotEmpty()) initialConfig.keys
        else primaryApiKey.trim().takeIf(String::isNotBlank)?.let {
            listOf(ApiKeyEntry(label = "主 Key", secret = it, enabled = true))
        }.orEmpty()
    }
    var keys by remember { mutableStateOf(seedKeys) }
    var policy by remember { mutableStateOf(initialConfig.policy) }
    var cooldownText by remember { mutableStateOf(initialConfig.cooldownSeconds.toString()) }
    var newLabel by remember { mutableStateOf("") }
    var newSecret by remember { mutableStateOf("") }
    var healthVersion by remember { mutableStateOf(0) }
    val revealedIds = remember { mutableStateMapOf<String, Boolean>() }
    val testingIds = remember { mutableStateMapOf<String, Boolean>() }
    val testResults = remember { mutableStateMapOf<String, Result<String>>() }
    val scope = rememberCoroutineScope()
    val healthSnapshot = remember(provider.id, healthVersion) { MultiKeyManager.snapshotHealth(provider.id) }
    val now = System.currentTimeMillis()
    val cooldownSeconds = cooldownText.trim().toIntOrNull()?.takeIf { it in 5..MAX_KEY_COOLDOWN_SECONDS }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Key, null, tint = MaterialTheme.colorScheme.primary)
                Text("多密钥轮换管理")
            }
        },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "所有 API Key 均通过 Android Keystore 加密存储。遇到 HTTP 401/403/429 或配额不足时将自动切换至下一个健康 Key。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SelectField("轮换策略", policy, KeyRotationPolicy.entries, KeyRotationPolicy::label) { policy = it }
                Field(
                    "故障冷却时间（秒）",
                    cooldownText,
                    { cooldownText = it },
                    placeholder = "$DEFAULT_KEY_COOLDOWN_SECONDS",
                    type = KeyboardType.Number,
                )
                if (healthSnapshot.values.any { it.isInCooldown(now) }) {
                    OutlinedButton(
                        onClick = {
                            MultiKeyManager.resetHealth(provider.id)
                            healthVersion++
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("重置所有冷却状态")
                    }
                }
                HorizontalDivider()
                Text("已配置密钥（${keys.size} / $MAX_MULTI_KEYS）", style = MaterialTheme.typography.titleSmall)
                if (keys.isEmpty()) {
                    Text(
                        "尚未添加多密钥，请在下方添加。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                keys.forEachIndexed { index, entry ->
                    val revealed = revealedIds[entry.id] == true
                    val testing = testingIds[entry.id] == true
                    val testResult = testResults[entry.id]
                    val health = healthSnapshot[entry.id]
                    val inCooldown = health?.isInCooldown(now) == true
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        entry.label.ifBlank { "Key #${index + 1}" },
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        if (revealed) entry.secret else entry.masked(),
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Switch(
                                    checked = entry.enabled,
                                    onCheckedChange = { checked ->
                                        keys = keys.mapIndexed { i, item -> if (i == index) item.copy(enabled = checked) else item }
                                    },
                                )
                            }
                            if (inCooldown) {
                                val rem = health!!.remainingCooldownSeconds(now)
                                val code = health.lastStatus?.let { "HTTP $it · " }.orEmpty()
                                Text(
                                    "冷却中（剩余 ${rem}s · ${code}${health.lastError.ifBlank { "临时不可用" }}）",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            if (testResult != null) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Icon(
                                        if (testResult.isSuccess) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                                        null,
                                        modifier = Modifier.size(16.dp),
                                        tint = if (testResult.isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    )
                                    Text(
                                        testResult.fold({ it }, { it.localizedMessage ?: "测试失败" }),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (testResult.isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                                ActionIcon(
                                    if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    if (revealed) "隐藏密钥" else "显示密钥",
                                ) { revealedIds[entry.id] = !revealed }
                                ActionIcon(Icons.Outlined.ArrowUpward, "上移", enabled = index > 0) {
                                    val mutable = keys.toMutableList()
                                    val item = mutable.removeAt(index)
                                    mutable.add(index - 1, item)
                                    keys = mutable
                                }
                                ActionIcon(Icons.Outlined.ArrowDownward, "下移", enabled = index < keys.lastIndex) {
                                    val mutable = keys.toMutableList()
                                    val item = mutable.removeAt(index)
                                    mutable.add(index + 1, item)
                                    keys = mutable
                                }
                                if (testing) {
                                    CircularProgressIndicator(Modifier.size(20.dp).padding(2.dp), strokeWidth = 2.dp)
                                } else {
                                    ActionIcon(Icons.Outlined.PlayArrow, "测试密钥", enabled = provider.endpoint.isNotBlank()) {
                                        testingIds[entry.id] = true
                                        scope.launch {
                                            try {
                                                testResults[entry.id] = MultiKeyManager.testKey(provider, entry)
                                                healthVersion++
                                            } finally {
                                                testingIds[entry.id] = false
                                            }
                                        }
                                    }
                                }
                                ActionIcon(Icons.Outlined.Delete, "移除密钥") {
                                    keys = keys.filterNot { it.id == entry.id }
                                    revealedIds.remove(entry.id)
                                    testResults.remove(entry.id)
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
                Text("添加新密钥", style = MaterialTheme.typography.titleSmall)
                Field("标签（可选）", newLabel, { newLabel = it }, placeholder = "例如：主账号 / 备用 Key 2")
                Field("API Key", newSecret, { newSecret = it }, secret = true, placeholder = "粘贴 API Key")
                OutlinedButton(
                    onClick = {
                        val trimmed = newSecret.trim()
                        if (trimmed.isNotEmpty() && keys.size < MAX_MULTI_KEYS) {
                            keys = keys + ApiKeyEntry(
                                id = newId(),
                                label = newLabel.trim().ifBlank { "Key #${keys.size + 1}" },
                                secret = trimmed,
                                enabled = true,
                            )
                            newLabel = ""
                            newSecret = ""
                        }
                    },
                    enabled = newSecret.isNotBlank() && keys.size < MAX_MULTI_KEYS,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("添加到密钥池")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val config = MultiKeyConfig(
                        keys = keys,
                        policy = policy,
                        cooldownSeconds = cooldownSeconds ?: DEFAULT_KEY_COOLDOWN_SECONDS,
                    ).validated()
                    onSave(config)
                },
                enabled = cooldownSeconds != null,
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}
