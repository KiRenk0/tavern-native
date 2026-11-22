package app.tavern.nativeclient

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal enum class AutocompleteKind(val label: String) {
    COMMAND("命令"),
    QUICK_REPLY("快捷回复"),
    MACRO("宏"),
    VARIABLE("变量"),
}

internal data class AutocompleteItem(
    val insertText: String,
    val label: String,
    val detail: String,
    val kind: AutocompleteKind,
    val cursorOffsetFromEnd: Int = 0,
)

internal data class AutocompleteQuery(
    val replaceStart: Int,
    val replaceEnd: Int,
    val prefix: String,
    val trigger: String, // "/" or "{{"
)

internal val stScriptCatalog: List<AutocompleteItem> = listOf(
    AutocompleteItem("/send ", "/send", "以用户身份发送消息", AutocompleteKind.COMMAND),
    AutocompleteItem("/sendas name=", "/sendas", "以指定角色名发送消息", AutocompleteKind.COMMAND),
    AutocompleteItem("/sys ", "/sys", "插入系统旁白消息", AutocompleteKind.COMMAND),
    AutocompleteItem("/trigger", "/trigger", "触发模型生成回复", AutocompleteKind.COMMAND),
    AutocompleteItem("/gen ", "/gen", "静默生成文本到管道", AutocompleteKind.COMMAND),
    AutocompleteItem("/echo ", "/echo", "弹出提示消息", AutocompleteKind.COMMAND),
    AutocompleteItem("/setvar key=", "/setvar", "设置当前对话局部变量", AutocompleteKind.COMMAND),
    AutocompleteItem("/getvar ", "/getvar", "读取当前对话局部变量", AutocompleteKind.COMMAND),
    AutocompleteItem("/setglobalvar key=", "/setglobalvar", "设置全局变量", AutocompleteKind.COMMAND),
    AutocompleteItem("/getglobalvar ", "/getglobalvar", "读取全局变量", AutocompleteKind.COMMAND),
    AutocompleteItem("/let ", "/let", "声明脚本块级变量", AutocompleteKind.COMMAND),
    AutocompleteItem("/if left= rule=eq right= {:  :}", "/if", "条件分支执行闭包", AutocompleteKind.COMMAND, cursorOffsetFromEnd = 3),
    AutocompleteItem("/while left= rule=lt right= {:  :}", "/while", "循环执行闭包", AutocompleteKind.COMMAND, cursorOffsetFromEnd = 3),
    AutocompleteItem("/times  {:  :}", "/times", "按次数重复执行闭包", AutocompleteKind.COMMAND, cursorOffsetFromEnd = 8),
    AutocompleteItem("/run ", "/run", "运行命名快捷回复", AutocompleteKind.COMMAND),
    AutocompleteItem("/input ", "/input", "弹出输入对话框", AutocompleteKind.COMMAND),
    AutocompleteItem("/buttons labels=", "/buttons", "弹出按钮选择框", AutocompleteKind.COMMAND),
    AutocompleteItem("/messages ", "/messages", "按范围读取可见消息", AutocompleteKind.COMMAND),
    AutocompleteItem("/getchatname", "/getchatname", "读取当前聊天名称", AutocompleteKind.COMMAND),
    AutocompleteItem("/renamechat ", "/renamechat", "重命名当前聊天", AutocompleteKind.COMMAND),
    AutocompleteItem("/closechat", "/closechat", "关闭当前聊天", AutocompleteKind.COMMAND),
    AutocompleteItem("/chat-reload", "/chat-reload", "重读已保存的当前聊天", AutocompleteKind.COMMAND),
    AutocompleteItem("/chat-jump ", "/chat-jump", "滚动到指定消息（从 0 开始）", AutocompleteKind.COMMAND),
    AutocompleteItem("/hide ", "/hide", "从模型上下文隐藏消息", AutocompleteKind.COMMAND),
    AutocompleteItem("/unhide ", "/unhide", "恢复消息到模型上下文", AutocompleteKind.COMMAND),
    AutocompleteItem("/message-name at=-1 ", "/message-name", "读取或修改消息发言者", AutocompleteKind.COMMAND),
    AutocompleteItem("/message-role at=-1 ", "/message-role", "读取或修改消息身份", AutocompleteKind.COMMAND),
    AutocompleteItem("/addswipe ", "/addswipe", "添加备选回复", AutocompleteKind.COMMAND),
    AutocompleteItem("/swipe direction=right", "/swipe", "切换备选回复", AutocompleteKind.COMMAND),
    AutocompleteItem("/delswipe ", "/delswipe", "删除备选回复", AutocompleteKind.COMMAND),
    AutocompleteItem("/branch-create ", "/branch-create", "从指定消息创建分支并打开", AutocompleteKind.COMMAND),
    AutocompleteItem("/checkpoint-create mesId=", "/checkpoint-create", "从消息保存检查点", AutocompleteKind.COMMAND),
    AutocompleteItem("/checkpoint-get ", "/checkpoint-get", "读取消息链接的检查点名称", AutocompleteKind.COMMAND),
    AutocompleteItem("/checkpoint-go ", "/checkpoint-go", "打开消息链接的检查点", AutocompleteKind.COMMAND),
    AutocompleteItem("/checkpoint-parent", "/checkpoint-parent", "读取父对话名称", AutocompleteKind.COMMAND),
    AutocompleteItem("/checkpoint-exit", "/checkpoint-exit", "返回父对话", AutocompleteKind.COMMAND),
    AutocompleteItem("/checkpoint-list", "/checkpoint-list", "列出当前聊天的检查点", AutocompleteKind.COMMAND),
    AutocompleteItem("/cut ", "/cut", "剪切消息到管道", AutocompleteKind.COMMAND),
    AutocompleteItem("/del ", "/del", "删除消息或批量选择", AutocompleteKind.COMMAND),
    AutocompleteItem("/regex name=", "/regex", "运行指定正则规则", AutocompleteKind.COMMAND),
    AutocompleteItem("/replace pattern= replacer= ", "/replace", "替换文本，可选 mode=regex", AutocompleteKind.COMMAND),
    AutocompleteItem("/test pattern= ", "/test", "测试正则是否匹配", AutocompleteKind.COMMAND),
    AutocompleteItem("/match pattern= ", "/match", "获取正则匹配及捕获组", AutocompleteKind.COMMAND),
    AutocompleteItem("/array-wrap ", "/array-wrap", "将值包装成 JSON 数组", AutocompleteKind.COMMAND),
    AutocompleteItem("/array-unwrap ", "/array-unwrap", "取出 JSON 数组首项", AutocompleteKind.COMMAND),
    AutocompleteItem("/speak ", "/speak", "语音朗读指定文本", AutocompleteKind.COMMAND),
    AutocompleteItem("/translate target=zh-CN ", "/translate", "翻译文本", AutocompleteKind.COMMAND),
    AutocompleteItem("/summarize", "/summarize", "更新当前对话摘要", AutocompleteKind.COMMAND),
    AutocompleteItem("/sd ", "/sd", "按提示词生成图片", AutocompleteKind.COMMAND),
    AutocompleteItem("/icw ", "/icw", "切换 ComfyUI 工作流", AutocompleteKind.COMMAND),
    AutocompleteItem("/show-gallery", "/show-gallery", "打开角色/群组图库", AutocompleteKind.COMMAND),
    AutocompleteItem("/caption mesId=-1 index=0 quiet=true", "/caption", "获取消息图片描述", AutocompleteKind.COMMAND),
    AutocompleteItem("/expression-set ", "/expression-set", "切换角色表情", AutocompleteKind.COMMAND),
    AutocompleteItem("/expression-list", "/expression-list", "列出可用表情", AutocompleteKind.COMMAND),
    AutocompleteItem("/db-list", "/db-list", "列出资料库条目", AutocompleteKind.COMMAND),
    AutocompleteItem("/db-get ", "/db-get", "读取资料库条目", AutocompleteKind.COMMAND),
    AutocompleteItem("/db-add name=", "/db-add", "向资料库添加条目", AutocompleteKind.COMMAND),
    AutocompleteItem("/tools-register name=", "/tools-register", "注册自定义函数工具", AutocompleteKind.COMMAND),
    AutocompleteItem("/tools-list", "/tools-list", "列出已注册工具", AutocompleteKind.COMMAND),
    AutocompleteItem("/tools-invoke ", "/tools-invoke", "手动调用工具", AutocompleteKind.COMMAND),
    AutocompleteItem("/model ", "/model", "读取或切换对话模型", AutocompleteKind.COMMAND),
    AutocompleteItem("/profile ", "/profile", "读取或切换连接配置档案", AutocompleteKind.COMMAND),
    AutocompleteItem("/preset ", "/preset", "读取或切换提示词预设", AutocompleteKind.COMMAND),
    AutocompleteItem("/bg ", "/bg", "读取、切换或清除背景图", AutocompleteKind.COMMAND),
    AutocompleteItem("/flat", "/flat", "切换为平铺模式", AutocompleteKind.COMMAND),
    AutocompleteItem("/bubble", "/bubble", "切换为气泡模式", AutocompleteKind.COMMAND),
    AutocompleteItem("/story", "/story", "切换为文档小说模式", AutocompleteKind.COMMAND),
    AutocompleteItem("/char-get ", "/char-get", "读取角色卡字段（description/scenario等）", AutocompleteKind.COMMAND),
    AutocompleteItem("/listvar", "/listvar", "列出当前对话/全局变量 JSON", AutocompleteKind.COMMAND),
    AutocompleteItem("/flushvar", "/flushvar", "清空当前对话变量或指定变量", AutocompleteKind.COMMAND),
    AutocompleteItem("/flushglobalvar", "/flushglobalvar", "清空全局变量或指定变量", AutocompleteKind.COMMAND),
    AutocompleteItem("/wi-get-book", "/wi-get-book", "读取当前/指定世界书 JSON", AutocompleteKind.COMMAND),
    AutocompleteItem("/lockbg", "/lockbg", "把当前背景锁定到对话", AutocompleteKind.COMMAND),
    AutocompleteItem("/unlockbg", "/unlockbg", "解除对话背景锁定", AutocompleteKind.COMMAND),
    AutocompleteItem("/wi-list-entries", "/wi-list-entries", "列出世界书条目", AutocompleteKind.COMMAND),
    AutocompleteItem("/wi-get-entry uid=", "/wi-get-entry", "读取指定世界书条目字段", AutocompleteKind.COMMAND),
    AutocompleteItem("/wi-set-entry uid= field=content value=", "/wi-set-entry", "修改世界书条目并持久化", AutocompleteKind.COMMAND),
    AutocompleteItem("/wi-create-entry key= comment= ", "/wi-create-entry", "新建世界书条目", AutocompleteKind.COMMAND),
    AutocompleteItem("/wi-delete-entry uid=", "/wi-delete-entry", "删除世界书条目", AutocompleteKind.COMMAND),
    AutocompleteItem("/world state=toggle ", "/world", "启用、停用或切换全局世界书", AutocompleteKind.COMMAND),
    AutocompleteItem("/getglobalbooks", "/getglobalbooks", "读取全局启用的世界书", AutocompleteKind.COMMAND),
    AutocompleteItem("/getchatbook create=false", "/getchatbook", "读取或创建聊天关联世界书", AutocompleteKind.COMMAND),
    AutocompleteItem("/getpersonabook", "/getpersonabook", "读取或创建当前人设的世界书", AutocompleteKind.COMMAND),
    AutocompleteItem("/getcharbook type=all ", "/getcharbook", "读取角色主世界书及附加世界书", AutocompleteKind.COMMAND),
    AutocompleteItem("/findentry file= field=key ", "/findentry", "按字段查找世界书条目编号", AutocompleteKind.COMMAND),
    AutocompleteItem("/getentryfield file= field=content ", "/getentryfield", "读取条目字段", AutocompleteKind.COMMAND),
    AutocompleteItem("/setentryfield file= uid= field=content ", "/setentryfield", "保存条目字段", AutocompleteKind.COMMAND),
    AutocompleteItem("/createentry file= key= ", "/createentry", "新建条目并返回编号", AutocompleteKind.COMMAND),
    AutocompleteItem("/re-replace find= replace= ", "/re-replace", "对文本执行正则替换", AutocompleteKind.COMMAND),
    AutocompleteItem("/trimtokens limit= ", "/trimtokens", "按 Token 上限裁剪文本", AutocompleteKind.COMMAND),
    AutocompleteItem("/reroll-pick", "/reroll-pick", "重新抽取 {{pick}} 结果", AutocompleteKind.COMMAND),
    AutocompleteItem("/help", "/help", "查看命令帮助", AutocompleteKind.COMMAND),
)

internal val macroCatalog: List<AutocompleteItem> = listOf(
    AutocompleteItem("{{char}}", "{{char}}", "当前角色名称", AutocompleteKind.MACRO),
    AutocompleteItem("{{user}}", "{{user}}", "当前用户人设名称", AutocompleteKind.MACRO),
    AutocompleteItem("{{persona}}", "{{persona}}", "当前人设描述", AutocompleteKind.MACRO),
    AutocompleteItem("{{model}}", "{{model}}", "当前选择的模型 ID", AutocompleteKind.MACRO),
    AutocompleteItem("{{lastMessage}}", "{{lastMessage}}", "最后一条可见消息", AutocompleteKind.MACRO),
    AutocompleteItem("{{lastUserMessage}}", "{{lastUserMessage}}", "最后一条用户消息", AutocompleteKind.MACRO),
    AutocompleteItem("{{lastCharMessage}}", "{{lastCharMessage}}", "最后一条角色消息", AutocompleteKind.MACRO),
    AutocompleteItem("{{summary}}", "{{summary}}", "当前对话摘要", AutocompleteKind.MACRO),
    AutocompleteItem("{{pipe}}", "{{pipe}}", "上一条 STscript 管道结果", AutocompleteKind.MACRO),
    AutocompleteItem("{{stat_data}}", "{{stat_data}}", "MVU 当前完整状态变量 JSON", AutocompleteKind.MACRO),
    AutocompleteItem("{{get_message_variable::}}", "{{get_message_variable::…}}", "读取 MVU 状态路径变量", AutocompleteKind.MACRO, cursorOffsetFromEnd = 2),
    AutocompleteItem("{{time}}", "{{time}}", "当前时间", AutocompleteKind.MACRO),
    AutocompleteItem("{{date}}", "{{date}}", "当前日期", AutocompleteKind.MACRO),
    AutocompleteItem("{{weekday}}", "{{weekday}}", "星期几", AutocompleteKind.MACRO),
    AutocompleteItem("{{random::甲::乙}}", "{{random::…}}", "每次随机选择一项", AutocompleteKind.MACRO, cursorOffsetFromEnd = 2),
    AutocompleteItem("{{pick::甲::乙}}", "{{pick::…}}", "本对话固定抽选一项", AutocompleteKind.MACRO, cursorOffsetFromEnd = 2),
    AutocompleteItem("{{roll::1d20}}", "{{roll::…}}", "掷骰公式", AutocompleteKind.MACRO, cursorOffsetFromEnd = 2),
    AutocompleteItem("{{getvar::}}", "{{getvar::…}}", "读取局部变量", AutocompleteKind.MACRO, cursorOffsetFromEnd = 2),
    AutocompleteItem("{{setvar::键::值}}", "{{setvar::…}}", "写入局部变量", AutocompleteKind.MACRO, cursorOffsetFromEnd = 2),
    AutocompleteItem("{{incvar::}}", "{{incvar::…}}", "局部变量自增 1", AutocompleteKind.MACRO, cursorOffsetFromEnd = 2),
    AutocompleteItem("{{getglobalvar::}}", "{{getglobalvar::…}}", "读取全局变量", AutocompleteKind.MACRO, cursorOffsetFromEnd = 2),
    AutocompleteItem("{{if .flag}}…{{else}}…{{/if}}", "{{if …}}", "条件宏模板", AutocompleteKind.MACRO, cursorOffsetFromEnd = 17),
    AutocompleteItem("{{outlet::}}", "{{outlet::…}}", "世界书锚点占位", AutocompleteKind.MACRO, cursorOffsetFromEnd = 2),
)

internal fun detectAutocomplete(text: String, cursor: Int): AutocompleteQuery? {
    val pos = cursor.coerceIn(0, text.length)
    val lineStart = text.lastIndexOf('\n', (pos - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
    val segment = text.substring(lineStart, pos)

    // Check {{ macro trigger first (works anywhere in a line)
    val macroOpen = segment.lastIndexOf("{{")
    if (macroOpen >= 0) {
        val macroClose = segment.lastIndexOf("}}")
        if (macroClose < macroOpen) {
            val queryText = segment.substring(macroOpen)
            if ('\n' !in queryText) {
                return AutocompleteQuery(
                    replaceStart = lineStart + macroOpen,
                    replaceEnd = pos,
                    prefix = queryText,
                    trigger = "{{",
                )
            }
        }
    }

    // Check / command trigger at line start or after pipe |
    val pipeIndex = segment.lastIndexOf('|')
    val afterDelimiter = if (pipeIndex >= 0) pipeIndex + 1 else 0
    var tokenStart = afterDelimiter
    while (tokenStart < segment.length && segment[tokenStart].isWhitespace()) tokenStart++
    if (tokenStart < segment.length && segment[tokenStart] == '/') {
        val token = segment.substring(tokenStart)
        if (!token.any(Char::isWhitespace)) {
            return AutocompleteQuery(
                replaceStart = lineStart + tokenStart,
                replaceEnd = pos,
                prefix = token,
                trigger = "/",
            )
        }
    }
    return null
}

internal fun suggestAutocomplete(
    query: AutocompleteQuery,
    quickReplies: List<QuickReply> = emptyList(),
    localVars: Map<String, String> = emptyMap(),
    globalVars: Map<String, String> = emptyMap(),
): List<AutocompleteItem> {
    val needle = query.prefix.lowercase()
    val pool = when (query.trigger) {
        "{{" -> buildList {
            addAll(macroCatalog)
            localVars.keys.sorted().forEach { key ->
                add(AutocompleteItem("{{var::$key}}", "{{var::$key}}", "局部变量 · ${localVars[key].orEmpty().take(20)}", AutocompleteKind.VARIABLE))
            }
            globalVars.keys.sorted().forEach { key ->
                add(AutocompleteItem("{{getglobalvar::$key}}", "{{getglobalvar::$key}}", "全局变量 · ${globalVars[key].orEmpty().take(20)}", AutocompleteKind.VARIABLE))
            }
        }
        "/" -> buildList {
            addAll(stScriptCatalog)
            quickReplies.filter { it.label.isNotBlank() }.forEach { qr ->
                val cmd = "/run ${qr.label}"
                add(AutocompleteItem(cmd, cmd, "快捷回复 · ${qr.message.take(24)}", AutocompleteKind.QUICK_REPLY))
            }
        }
        else -> emptyList()
    }
    val prefixMatches = pool.filter { it.label.lowercase().startsWith(needle) }
    val innerNeedle = needle.removePrefix(query.trigger)
    val containsMatches = if (innerNeedle.isBlank()) emptyList() else pool.filter {
        it !in prefixMatches && (it.label.lowercase().contains(innerNeedle) || it.detail.lowercase().contains(innerNeedle))
    }
    return (prefixMatches + containsMatches).take(16)
}

internal fun applyAutocomplete(text: String, query: AutocompleteQuery, item: AutocompleteItem): Pair<String, Int> {
    val start = query.replaceStart.coerceIn(0, text.length)
    val end = query.replaceEnd.coerceIn(start, text.length)
    val next = text.substring(0, start) + item.insertText + text.substring(end)
    val rawCursor = start + item.insertText.length - item.cursorOffsetFromEnd
    return next to rawCursor.coerceIn(start, next.length)
}

@Composable
internal fun AutocompleteStrip(items: List<AutocompleteItem>, onSelect: (AutocompleteItem) -> Unit) {
    if (items.isEmpty()) return
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { "${it.kind.name}:${it.label}:${it.insertText}" }) { item ->
            ElevatedSuggestionChip(
                onClick = { onSelect(item) },
                label = {
                    Column(Modifier.widthIn(max = 200.dp)) {
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${item.kind.label} · ${item.detail}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
            )
        }
    }
}
