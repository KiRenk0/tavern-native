package app.tavern.nativeclient

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun ModelFormattingScreen(
    provider: Provider, model: TavernViewModel, busy: Boolean, back: () -> Unit, apply: (ModelFormatting) -> Unit,
) {
    var formatting by remember { mutableStateOf(provider.modelFormatting) }
    var selectedRule by remember { mutableStateOf<ModelFormatRule?>(null) }
    var modelId by rememberSaveable { mutableStateOf(provider.defaultModel) }
    val rule = selectedRule
    BackHandler { if (!busy) { if (rule != null) selectedRule = null else back() } }
    if (rule != null) {
        key(rule.id) {
            ModelFormatRuleEditor(rule, { selectedRule = null }, onDelete = if (formatting.rules.any { it.id == rule.id }) ({
                formatting = formatting.copy(rules = formatting.rules.filterNot { it.id == rule.id })
                selectedRule = null
            }) else null) { edited ->
                require(formatting.rules.none { it.id != edited.id && it.match == edited.match && it.target == edited.target }) {
                    "该匹配条件已存在"
                }
                formatting = formatting.copy(rules = formatting.rules.filterNot { it.id == edited.id } + edited)
                selectedRule = null
            }
        }
        return
    }
    Form {
        TextButton(back, enabled = !busy) { Text("返回提供商") }
        FormHeading("按模型切换模板", "按模型 ID、正则或服务端模板哈希匹配。切换模型后会在生成时应用。")
        if (provider.protocol == ApiProtocol.KOBOLD_TEXT) {
            GroupToggle("根据服务端元数据选择指令模板", formatting.deriveInstruct, !busy) {
                formatting = formatting.copy(deriveInstruct = it)
            }
            GroupToggle("根据服务端元数据选择上下文模板", formatting.deriveContext, !busy) {
                formatting = formatting.copy(deriveContext = it)
            }
            GroupToggle("使用服务报告的上下文长度", formatting.deriveContextSize, !busy) {
                formatting = formatting.copy(deriveContextSize = it)
            }
            Field("要检查的模型 ID", modelId, { modelId = it.take(512) }, enabled = !busy)
            OutlinedButton({
                val id = modelId.trim()
                model.loadFormattingMetadata(provider, id) { info -> formatting = formatting.withBackend(id, info) }
            }, enabled = !busy && provider.endpoint.isNotBlank() && modelId.isNotBlank()) { Text("读取模型属性") }
            formatting.backend[modelId.trim()]?.let { result ->
                if (result.hash.isNotEmpty()) Text("模板哈希\n${result.hash}", style = MaterialTheme.typography.bodySmall)
                result.contextTokens?.let { Text("服务报告的上下文长度 · $it Token") }
                if (result.suggested.context != null || result.suggested.instruct != null) {
                    Text("识别到的模板 · ${result.suggested.description}")
                }
                if (result.hash.isNotEmpty()) {
                    val existing = formatting.rules.firstOrNull { it.match == ModelMatch.TEMPLATE_HASH && it.target == result.hash }
                    TextButton({
                        selectedRule = existing ?: ModelFormatRule(match = ModelMatch.TEMPLATE_HASH, target = result.hash,
                            templates = if (result.suggested != ModelTemplatePair()) result.suggested
                            else ModelTemplatePair(provider.instruct, provider.textContext))
                    }, enabled = !busy && (existing != null || formatting.rules.size < MAX_MODEL_FORMAT_RULES)) {
                        Text("编辑此哈希的模板绑定")
                    }
                }
            }
            HorizontalDivider()
        }
        formatting.rules.forEach { entry ->
            OutlinedButton({ selectedRule = entry }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("${entry.match.label} · ${entry.target}\n${entry.templates.description}")
            }
        }
        OutlinedButton({
            val id = modelId.trim()
            selectedRule = ModelFormatRule(target = id, templates = formatting.choose(id,
                ModelTemplatePair(provider.instruct, provider.textContext)))
        }, enabled = !busy && formatting.rules.size < MAX_MODEL_FORMAT_RULES) { Text("添加匹配规则") }
        Button({ apply(formatting) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("应用到提供商") }
    }
}

@Composable
private fun ModelFormatRuleEditor(
    initial: ModelFormatRule, back: () -> Unit, onDelete: (() -> Unit)?, apply: (ModelFormatRule) -> Unit,
) {
    var match by rememberSaveable { mutableStateOf(initial.match) }
    var target by rememberSaveable { mutableStateOf(initial.target) }
    var templates by remember { mutableStateOf(initial.templates) }
    var section by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var delete by remember { mutableStateOf(false) }
    when (section) {
        "instruct" -> {
            InstructTemplateScreen(templates.instruct, { section = "" }) {
                templates = templates.copy(instruct = it)
                if (match == ModelMatch.REGEX && target.isBlank() && it != null) target = it.activationRegex.take(512)
                section = ""
            }
            return
        }
        "context" -> {
            TextContextTemplateScreen(templates.context, { section = "" }) { context, instruct ->
                templates = templates.copy(context = context, instruct = instruct ?: templates.instruct)
                section = ""
            }
            return
        }
    }
    BackHandler(onBack = back)
    Form {
        TextButton(back) { Text("返回模型模板") }
        FormHeading("模型模板规则")
        SelectField("匹配方式", match, ModelMatch.entries, ModelMatch::label) { next ->
            if (match != next) target = if (next == ModelMatch.REGEX) templates.instruct?.activationRegex.orEmpty().take(512) else ""
            match = next
            error = null
        }
        Field(match.label, target, { target = it.take(512); error = null },
            placeholder = if (match == ModelMatch.REGEX) "/qwen|chatml/i" else null)
        OutlinedButton({ section = "context" }, modifier = Modifier.fillMaxWidth()) {
            Text("上下文模板 · ${templates.context?.name ?: "未启用"}")
        }
        OutlinedButton({ section = "instruct" }, modifier = Modifier.fillMaxWidth()) {
            Text("指令模板 · ${templates.instruct?.name ?: "未启用"}")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({
                runCatching { apply(initial.copy(match = match, target = target, templates = templates).valid()) }
                    .onFailure { error = it.message }
            }, enabled = target.isNotBlank()) { Text("保存规则") }
            if (onDelete != null) TextButton({ delete = true }) { Text("删除规则") }
        }
    }
    if (delete && onDelete != null) Confirm("删除模型模板规则？", "此模型会使用下一条匹配规则或提供商默认模板。",
        { delete = false }, onDelete)
}
