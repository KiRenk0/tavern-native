package app.tavern.nativeclient

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun ConnectionProfilesScreen(state: TavernState, model: TavernViewModel, close: () -> Unit) {
    val prefs = state.prefs
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ConnectionProfile?>(null) }
    val active = prefs.connectionProfiles.firstOrNull { it.id == editingId }
    val open = active ?: editingId?.takeIf { creating }?.let { id ->
        val defaultConn = prefs.connectionFor()
        remember(id) {
            ConnectionProfile(
                id = id,
                name = "",
                providerId = defaultConn?.provider?.id ?: prefs.providers.firstOrNull { it.ready }?.id.orEmpty(),
                model = defaultConn?.model ?: prefs.providers.firstOrNull { it.ready }?.defaultModel.orEmpty(),
            )
        }
    }

    fun back() {
        if (state.busy) return
        if (open != null) {
            editingId = null
            creating = false
        } else close()
    }
    BackHandler { back() }

    if (open != null) {
        key(open.id) {
            ConnectionProfileEditor(
                profile = open,
                state = state,
                back = ::back,
                delete = active?.let { { deleting = it } },
                save = { updated ->
                    model.saveConnectionProfile(updated) {
                        editingId = null
                        creating = false
                    }
                },
            )
        }
    } else {
        Form {
            TextButton(::back, enabled = !state.busy) { Text("返回设置") }
            FormHeading("连接配置档案", "一键绑定提供商、模型、提示词预设和采样参数，可在单聊和群聊中秒切。")
            val readyProviders = prefs.providers.filter { it.ready }
            Button(
                onClick = { creating = true; editingId = newId() },
                enabled = !state.busy && readyProviders.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("新建配置档案")
            }
            if (readyProviders.isEmpty()) {
                Text(
                    "请先至少配置一个可用的模型提供商。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            prefs.connectionProfiles.forEach { profile ->
                val providerName = prefs.providers.firstOrNull { it.id == profile.providerId }?.name ?: "未知提供商"
                val presetName = profile.presetId?.let { pid ->
                    state.presets.firstOrNull { it.id == pid }?.name
                }
                val isGlobal = prefs.activeProfileId == profile.id
                Card(
                    onClick = { creating = false; editingId = profile.id },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Tune,
                            null,
                            tint = if (isGlobal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(profile.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                buildString {
                                    append(providerName).append(" · ").append(profile.model)
                                    if (!presetName.isNullOrBlank()) append(" · 预设: ").append(presetName)
                                    if (isGlobal) append("（全局默认）")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        ActionIcon(
                            if (isGlobal) Icons.Outlined.Star else Icons.Outlined.StarOutline,
                            if (isGlobal) "取消全局默认" else "设为全局默认",
                            !state.busy,
                        ) {
                            model.setActiveProfile(if (isGlobal) null else profile.id)
                        }
                    }
                }
            }
        }
    }

    deleting?.let { target ->
        Confirm("删除配置档案？", "将移除「${target.name}」，绑定此档案的对话将恢复默认模型配置。", { deleting = null }) {
            model.deleteConnectionProfile(target.id) {
                deleting = null
                editingId = null
                creating = false
            }
        }
    }
}

@Composable
private fun ConnectionProfileEditor(
    profile: ConnectionProfile,
    state: TavernState,
    back: () -> Unit,
    delete: (() -> Unit)?,
    save: (ConnectionProfile) -> Unit,
) {
    val providers = state.prefs.providers.filter { it.ready }
    var name by rememberSaveable { mutableStateOf(profile.name) }
    var providerId by rememberSaveable { mutableStateOf(profile.providerId) }
    val selectedProvider = providers.firstOrNull { it.id == providerId } ?: providers.firstOrNull()
    var modelName by rememberSaveable { mutableStateOf(profile.model.ifBlank { selectedProvider?.defaultModel.orEmpty() }) }
    var presetId by rememberSaveable { mutableStateOf(profile.presetId) }
    var temperature by rememberSaveable { mutableStateOf(profile.temperature) }
    var maxTokens by rememberSaveable { mutableStateOf(profile.maxTokens) }
    var topP by rememberSaveable { mutableStateOf(profile.topP) }
    var reasoningOverride by rememberSaveable { mutableStateOf(profile.includeReasoning) }

    val modelChoices = selectedProvider?.choices.orEmpty()
    Form {
        TextButton(back, enabled = !state.busy) { Text("返回档案列表") }
        FormHeading(if (delete == null) "新建配置档案" else "编辑配置档案")
        Field("档案名称", name, { name = it }, enabled = !state.busy, placeholder = "例如：Claude RP 精修 / DeepSeek 日常")
        if (selectedProvider != null) {
            SelectField(
                label = "模型提供商",
                value = selectedProvider,
                options = providers,
                name = Provider::name,
                enabled = !state.busy,
            ) { p ->
                providerId = p.id
                if (modelName !in p.choices) modelName = p.defaultModel
            }
        }
        if (modelChoices.size > 1) {
            SelectField(
                label = "快捷选择已有模型",
                value = modelChoices.firstOrNull { it == modelName },
                options = modelChoices,
                name = { it ?: "选择模型" },
                enabled = !state.busy,
            ) { picked -> if (picked != null) modelName = picked }
        }
        Field("模型 ID", modelName, { modelName = it }, enabled = !state.busy)
        SelectField(
            label = "绑定提示词预设（可选）",
            value = presetId,
            options = listOf<String?>(null) + state.presets.map(PromptPreset::id),
            name = { id -> state.presets.firstOrNull { it.id == id }?.name ?: "不覆盖对话/默认预设" },
            enabled = !state.busy,
        ) { presetId = it }
        Field("温度覆盖（可选，0–2）", temperature, { temperature = it }, enabled = !state.busy, type = KeyboardType.Decimal)
        Field("Top P 覆盖（可选，0–1）", topP, { topP = it }, enabled = !state.busy, type = KeyboardType.Decimal)
        Field("最大输出 Token 覆盖（可选）", maxTokens, { maxTokens = it }, enabled = !state.busy, type = KeyboardType.Number)
        SelectField(
            label = "随历史附带推理",
            value = reasoningOverride,
            options = listOf(null, true, false),
            name = {
                when (it) {
                    true -> "强制开启"
                    false -> "强制关闭"
                    null -> "跟随全局设置"
                }
            },
            enabled = !state.busy,
        ) { reasoningOverride = it }
        Button(
            onClick = {
                save(
                    profile.copy(
                        name = name,
                        providerId = selectedProvider?.id ?: providerId,
                        model = modelName,
                        presetId = presetId,
                        temperature = temperature,
                        maxTokens = maxTokens,
                        topP = topP,
                        includeReasoning = reasoningOverride,
                    ),
                )
            },
            enabled = !state.busy && name.isNotBlank() && (selectedProvider != null || providerId.isNotBlank()) && modelName.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("保存档案") }
        if (delete != null) {
            TextButton(delete, enabled = !state.busy) { Text("删除配置档案") }
        }
    }
}
