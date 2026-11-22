package app.tavern.nativeclient.script

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.tavern.nativeclient.LoreEntry
import app.tavern.nativeclient.Message
import app.tavern.nativeclient.TavernCoreEvent
import app.tavern.nativeclient.TavernEventBus
import app.tavern.nativeclient.WorldBook
import app.tavern.nativeclient.jsonOf
import app.tavern.nativeclient.newId
import app.tavern.nativeclient.objects
import app.tavern.nativeclient.string
import app.tavern.nativeclient.strings
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.lang.ref.WeakReference
import java.net.URI
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

internal data class CardSandboxPolicy(
    val allowScripts: Boolean = false,
    val allowNetwork: Boolean = false,
    val allowedDomains: List<String> = emptyList(),
    val allowVariableWrite: Boolean = true,
    val allowMessageWrite: Boolean = false,
    val allowWorldbookWrite: Boolean = false,
    val allowGeneration: Boolean = false,
    val allowCustomCss: Boolean = true,
) {
    val normalizedDomains: List<String>
        get() = allowedDomains
            .mapNotNull(::normalizeDomainEntry)
            .distinct()

    fun isUrlAllowed(rawUrl: String?): Boolean {
        val trimmed = rawUrl?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        val lower = trimmed.lowercase(Locale.ROOT)
        if (lower.startsWith("data:image/")) return true
        if (lower.startsWith("javascript:") || lower.startsWith("vbscript:") ||
            lower.startsWith("file:") || lower.startsWith("content:") ||
            lower.startsWith("data:")
        ) {
            return false
        }
        if (!allowNetwork) return false
        val domains = normalizedDomains
        if (domains.isEmpty()) return false
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        if (scheme != "https" && scheme != "http") return false
        if (uri.rawUserInfo != null) return false
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return false
        if (host.isEmpty()) return false
        return domains.any { allowed ->
            if (allowed.startsWith("*.")) {
                val suffix = allowed.removePrefix("*.")
                host == suffix || host.endsWith(".$suffix")
            } else {
                host == allowed || host.endsWith(".$allowed")
            }
        }
    }

    fun buildCspHeader(scriptsEnabled: Boolean): String {
        val scriptSrc = if (scriptsEnabled) "'unsafe-inline'" else "'none'"
        val domains = if (allowNetwork) normalizedDomains else emptyList()
        if (domains.isEmpty()) {
            return "default-src 'none'; style-src 'unsafe-inline'; script-src $scriptSrc; img-src data:; connect-src 'none'; font-src data:;"
        }
        val origins = domains.flatMap { d ->
            val clean = d.removePrefix("*.")
            listOf("https://$clean", "https://*.$clean")
        }.distinct().joinToString(" ")
        return "default-src 'none'; style-src 'unsafe-inline' $origins; script-src $scriptSrc; img-src data: $origins; connect-src $origins; font-src data: $origins;"
    }

    fun json(): JSONObject = jsonOf(
        "allowScripts" to allowScripts,
        "allowNetwork" to allowNetwork,
        "allowedDomains" to JSONArray(normalizedDomains),
        "allowVariableWrite" to allowVariableWrite,
        "allowMessageWrite" to allowMessageWrite,
        "allowWorldbookWrite" to allowWorldbookWrite,
        "allowGeneration" to allowGeneration,
        "allowCustomCss" to allowCustomCss,
    )

    companion object {
        private val domainRegex = Regex("""^(?:\*\.)?[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$""")

        fun normalizeDomainEntry(raw: String): String? {
            var candidate = raw.trim().lowercase(Locale.ROOT)
            if (candidate.isEmpty()) return null
            if ("://" in candidate) {
                candidate = runCatching { URI(candidate).host?.lowercase(Locale.ROOT) }.getOrNull().orEmpty()
            }
            candidate = candidate.substringBefore('/').substringBefore(':').trimEnd('.')
            return candidate.takeIf { domainRegex.matches(it) }
        }

        fun from(j: JSONObject?): CardSandboxPolicy {
            if (j == null) return CardSandboxPolicy()
            return CardSandboxPolicy(
                allowScripts = j.optBoolean("allowScripts", false),
                allowNetwork = j.optBoolean("allowNetwork", false),
                allowedDomains = j.optJSONArray("allowedDomains").strings().mapNotNull(::normalizeDomainEntry),
                allowVariableWrite = j.optBoolean("allowVariableWrite", true),
                allowMessageWrite = j.optBoolean("allowMessageWrite", false),
                allowWorldbookWrite = j.optBoolean("allowWorldbookWrite", false),
                allowGeneration = j.optBoolean("allowGeneration", false),
                allowCustomCss = j.optBoolean("allowCustomCss", true),
            )
        }
    }
}

/**
 * ST 1.19.1 <style> security hardening + strict container scoping inside `#tavern-card-root`.
 */
internal object CardCssScoper {
    const val SCOPE_SELECTOR = "#tavern-card-root"
    private const val MAX_CSS_LENGTH = 64 * 1024

    private val cssCommentRegex = Regex("""/\*[\s\S]*?\*/""")
    private val dangerousAtRuleRegex = Regex("""(?is)@(?:import|charset|namespace)\b[^;{}]*(?:;|\{[\s\S]*?\})?""")
    private val expressionRegex = Regex("""(?i)\b(?:expression|behavior|-moz-binding)\s*(?:\([^)]*\)|:[^;{}]+)""")
    private val positionEscapeRegex = Regex("""(?i)\bposition\s*:\s*(?:fixed|sticky)\b(?:\s*!important)?""")
    private val cssUrlRegex = Regex("""(?is)url\(\s*(?:"([^"]*)"|'([^']*)'|([^)\s]+))\s*\)""")
    private val styleBlockRegex = Regex("""(?is)<style\b[^>]*>([\s\S]*?)</style\s*>""")
    private val inlineStyleAttrRegex = Regex("""(?is)\bstyle\s*=\s*(?:"([^"]*)"|'([^']*)')""")

    fun sanitizeAndScopeCss(rawCss: String, policy: CardSandboxPolicy = CardSandboxPolicy()): String {
        if (!policy.allowCustomCss) return ""
        val trimmed = rawCss.take(MAX_CSS_LENGTH)
        val noComments = cssCommentRegex.replace(trimmed, "")
            .replace(dangerousAtRuleRegex, "")
            .replace(expressionRegex, "")
            .replace(positionEscapeRegex, "position: relative")
        val safeUrls = cssUrlRegex.replace(noComments) { match ->
            val rawTarget = (match.groupValues[1].ifEmpty { match.groupValues[2].ifEmpty { match.groupValues[3] } }).trim()
            if (policy.isUrlAllowed(rawTarget)) "url(\"$rawTarget\")" else "none"
        }
        return scopeStylesheet(safeUrls)
    }

    fun sanitizeStyleBlocksInHtml(rawHtml: String, policy: CardSandboxPolicy = CardSandboxPolicy()): String {
        val scopedStyles = styleBlockRegex.replace(rawHtml) { match ->
            if (!policy.allowCustomCss) ""
            else {
                val safe = sanitizeAndScopeCss(match.groupValues[1], policy)
                if (safe.isBlank()) "" else "<style>$safe</style>"
            }
        }
        return inlineStyleAttrRegex.replace(scopedStyles) { match ->
            if (!policy.allowCustomCss) ""
            else {
                val decl = (match.groupValues[1].ifEmpty { match.groupValues[2] })
                    .replace(cssCommentRegex, "")
                    .replace(expressionRegex, "")
                    .replace(positionEscapeRegex, "position: relative")
                val safeDecl = cssUrlRegex.replace(decl) { u ->
                    val rawTarget = (u.groupValues[1].ifEmpty { u.groupValues[2].ifEmpty { u.groupValues[3] } }).trim()
                    if (policy.isUrlAllowed(rawTarget)) "url('$rawTarget')" else "none"
                }
                "style=\"${safeDecl.replace("\"", "&quot;")}\""
            }
        }
    }

    private fun scopeStylesheet(css: String): String {
        val out = StringBuilder(css.length + 64)
        var i = 0
        val n = css.length
        while (i < n) {
            while (i < n && css[i].isWhitespace()) i++
            if (i >= n) break
            if (css[i] == '@') {
                val openBrace = css.indexOf('{', i)
                val semi = css.indexOf(';', i)
                if (semi != -1 && (openBrace == -1 || semi < openBrace)) {
                    i = semi + 1
                    continue
                }
                if (openBrace == -1) break
                val header = css.substring(i, openBrace).trim()
                val closeBrace = findMatchingBrace(css, openBrace)
                if (closeBrace < 0) break
                val body = css.substring(openBrace + 1, closeBrace)
                val lowerHeader = header.lowercase(Locale.ROOT)
                when {
                    lowerHeader.startsWith("@keyframes") || lowerHeader.startsWith("@-webkit-keyframes") ||
                        lowerHeader.startsWith("@font-face") -> {
                        out.append(header).append(" {").append(body).append("}\n")
                    }
                    lowerHeader.startsWith("@media") || lowerHeader.startsWith("@supports") ||
                        lowerHeader.startsWith("@container") -> {
                        out.append(header).append(" {\n").append(scopeStylesheet(body)).append("}\n")
                    }
                    else -> Unit // Drop unsupported/unsafe at-rules
                }
                i = closeBrace + 1
            } else {
                val openBrace = css.indexOf('{', i)
                if (openBrace < 0) break
                val rawSelectors = css.substring(i, openBrace).trim()
                val closeBrace = findMatchingBrace(css, openBrace)
                if (closeBrace < 0) break
                val declarations = css.substring(openBrace + 1, closeBrace).trim()
                if (rawSelectors.isNotEmpty() && declarations.isNotEmpty()) {
                    val scopedSelectors = rawSelectors.split(',')
                        .mapNotNull { sel -> scopeSingleSelector(sel.trim()) }
                        .joinToString(", ")
                    if (scopedSelectors.isNotEmpty()) {
                        out.append(scopedSelectors).append(" { ").append(declarations).append(" }\n")
                    }
                }
                i = closeBrace + 1
            }
        }
        return out.toString().trim()
    }

    private fun scopeSingleSelector(selector: String): String? {
        if (selector.isEmpty()) return null
        if (selector.startsWith(SCOPE_SELECTOR)) return selector
        val lower = selector.lowercase(Locale.ROOT)
        return when {
            lower == "html" || lower == "body" || lower == ":root" || lower == "html, body" -> SCOPE_SELECTOR
            lower.startsWith("body ") -> "$SCOPE_SELECTOR ${selector.substring(5).trim()}"
            lower.startsWith("html ") -> "$SCOPE_SELECTOR ${selector.substring(5).trim()}"
            lower.startsWith(":root ") -> "$SCOPE_SELECTOR ${selector.substring(6).trim()}"
            else -> "$SCOPE_SELECTOR $selector"
        }
    }

    private fun findMatchingBrace(text: String, openIndex: Int): Int {
        var depth = 0
        var quote: Char? = null
        var i = openIndex
        while (i < text.length) {
            val ch = text[i]
            if (quote != null) {
                if (ch == '\\') { i += 2; continue }
                if (ch == quote) quote = null
            } else {
                when (ch) {
                    '\'', '"' -> quote = ch
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            i++
        }
        return -1
    }
}

internal object CardSandboxHtml {
    private val dangerousContainerStrip = Regex(
        """(?is)<(?:iframe|object|embed|applet|form|meta|base|link)\b[^>]*>[\s\S]*?</(?:iframe|object|embed|applet|form)\s*>|<(?:iframe|object|embed|applet|form|meta|base|link)\b[^>]*/?>""",
    )
    private val scriptTagStrip = Regex("""(?is)<script\b[^>]*>[\s\S]*?</script\s*>|<script\b[^>]*>""")
    private val eventHandlerStrip = Regex("""(?is)\s+on[a-z0-9_-]+\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+)""")
    private val urlAttrRegex = Regex("""(?is)\b(src|href|action|poster|xlink:href)\s*=\s*(?:"([^"]*)"|'([^']*)')""")

    fun sanitizeStaticCardHtml(
        rawHtml: String,
        darkTheme: Boolean,
        bgHex: String,
        surfaceHex: String,
        fgHex: String,
        mutedHex: String,
        accentHex: String,
        borderHex: String,
        policy: CardSandboxPolicy = CardSandboxPolicy(allowScripts = false),
    ): String {
        val noScripts = rawHtml
            .replace(scriptTagStrip, "")
            .replace(dangerousContainerStrip, "")
            .replace(eventHandlerStrip, "")
        val safeUrls = sanitizeUrlAttributes(noScripts, policy)
        val scopedHtml = CardCssScoper.sanitizeStyleBlocksInHtml(safeUrls, policy)
        val csp = policy.buildCspHeader(scriptsEnabled = false)

        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="utf-8">
            <meta http-equiv="Content-Security-Policy" content="$csp">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            <style>
            :root {
                --bg: $bgHex;
                --surface: $surfaceHex;
                --fg: $fgHex;
                --fg-muted: $mutedHex;
                --accent: $accentHex;
                --border: $borderHex;
                color-scheme: ${if (darkTheme) "dark" else "light"};
            }
            * { box-sizing: border-box; max-width: 100%; }
            html, body {
                margin: 0; padding: 0;
                background: transparent;
                color: var(--fg);
                font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                font-size: 14px;
                line-height: 1.45;
                overflow-wrap: anywhere;
                word-break: break-word;
            }
            #tavern-card-root { padding: 8px 10px; position: relative; isolation: isolate; }
            #tavern-card-root table { width: 100%; border-collapse: collapse; margin: 4px 0; }
            #tavern-card-root th, #tavern-card-root td { border: 1px solid var(--border); padding: 4px 6px; text-align: left; }
            #tavern-card-root th { background: var(--surface); font-weight: 600; }
            #tavern-card-root progress, #tavern-card-root meter { width: 100%; accent-color: var(--accent); }
            #tavern-card-root hr { border: 0; border-top: 1px solid var(--border); margin: 6px 0; }
            </style>
            </head>
            <body>
            <div id="tavern-card-root">$scopedHtml</div>
            </body>
            </html>
        """.trimIndent()
    }

    fun sanitizeCardScriptHtml(
        rawHtml: String,
        darkTheme: Boolean,
        bgHex: String,
        surfaceHex: String,
        fgHex: String,
        mutedHex: String,
        accentHex: String,
        borderHex: String,
        policy: CardSandboxPolicy = CardSandboxPolicy(allowScripts = true),
    ): String {
        val noContainers = rawHtml.replace(dangerousContainerStrip, "")
        val safeUrls = sanitizeUrlAttributes(noContainers, policy)
        val scopedHtml = CardCssScoper.sanitizeStyleBlocksInHtml(safeUrls, policy)
        val csp = policy.buildCspHeader(scriptsEnabled = true)
        val eventTypesJson = TavernCoreEvent.eventTypesJson().toString()

        val bridgeBootstrap = """
            <script>
            (function() {
                function safeParse(raw, fallback) {
                    try { return JSON.parse(raw); } catch (e) { return fallback; }
                }
                function toJson(val) {
                    if (val === undefined || val === null) return "";
                    return typeof val === "string" ? val : JSON.stringify(val);
                }
                var listeners = Object.create(null);
                var listenerSeq = 0;
                window.__tavernDispatchEvent = function(listenerId, eventName, payloadJson) {
                    var entry = listeners[listenerId];
                    if (!entry) return;
                    var payload = safeParse(payloadJson, {});
                    if (entry.once) { delete listeners[listenerId]; }
                    try { entry.fn(payload, eventName); } catch (e) { console.error(e); }
                };
                var helper = {
                    getVariables: function(option) {
                        if (window.TavernNativeBridge) {
                            return safeParse(window.TavernNativeBridge.getVariables(toJson(option)), {});
                        }
                        return {};
                    },
                    getVariable: function(path) {
                        if (window.TavernNativeBridge) {
                            return window.TavernNativeBridge.getVariable(String(path || ""));
                        }
                        return "";
                    },
                    replaceVariables: function(vars, option) {
                        if (window.TavernNativeBridge) {
                            return safeParse(window.TavernNativeBridge.replaceVariables(toJson(vars), toJson(option)), {});
                        }
                        return {};
                    },
                    insertOrAssignVariables: function(vars, option) {
                        if (window.TavernNativeBridge) {
                            return safeParse(window.TavernNativeBridge.insertOrAssignVariables(toJson(vars), toJson(option)), {});
                        }
                        return {};
                    },
                    deleteVariable: function(path, option) {
                        if (window.TavernNativeBridge) {
                            return safeParse(window.TavernNativeBridge.deleteVariable(String(path || ""), toJson(option)), {});
                        }
                        return {};
                    },
                    getChatMessages: function(range, option) {
                        if (window.TavernNativeBridge) {
                            return safeParse(window.TavernNativeBridge.getChatMessages(range == null ? "" : String(range), toJson(option)), []);
                        }
                        return [];
                    },
                    setChatMessages: function(messages, option) {
                        if (window.TavernNativeBridge) {
                            return safeParse(window.TavernNativeBridge.setChatMessages(toJson(messages), toJson(option)), { ok: false });
                        }
                        return { ok: false };
                    },
                    createChatMessages: function(messages, option) {
                        if (window.TavernNativeBridge) {
                            return safeParse(window.TavernNativeBridge.createChatMessages(toJson(messages), toJson(option)), { ok: false });
                        }
                        return { ok: false };
                    },
                    deleteChatMessages: function(ids, option) {
                        if (window.TavernNativeBridge) {
                            return safeParse(window.TavernNativeBridge.deleteChatMessages(toJson(ids), toJson(option)), { ok: false });
                        }
                        return { ok: false };
                    },
                    getWorldbookNames: function() {
                        if (window.TavernNativeBridge) {
                            return safeParse(window.TavernNativeBridge.getWorldbookNames(), []);
                        }
                        return [];
                    },
                    getWorldbook: function(name) {
                        if (window.TavernNativeBridge) {
                            return safeParse(window.TavernNativeBridge.getWorldbook(String(name || "")), []);
                        }
                        return [];
                    },
                    createWorldbookEntries: function(name, entries) {
                        if (window.TavernNativeBridge) {
                            return safeParse(window.TavernNativeBridge.createWorldbookEntries(String(name || ""), toJson(entries)), { ok: false });
                        }
                        return { ok: false };
                    },
                    updateWorldbookWith: function(name, entries) {
                        if (window.TavernNativeBridge) {
                            return safeParse(window.TavernNativeBridge.updateWorldbookWith(String(name || ""), toJson(entries)), { ok: false });
                        }
                        return { ok: false };
                    },
                    deleteWorldbookEntries: function(name, uids) {
                        if (window.TavernNativeBridge) {
                            return safeParse(window.TavernNativeBridge.deleteWorldbookEntries(String(name || ""), toJson(uids)), { ok: false });
                        }
                        return { ok: false };
                    },
                    generate: function(config) {
                        if (window.TavernNativeBridge) {
                            return Promise.resolve(safeParse(window.TavernNativeBridge.generate(toJson(config)), { ok: false }));
                        }
                        return Promise.resolve({ ok: false });
                    },
                    generateRaw: function(config) {
                        if (window.TavernNativeBridge) {
                            return Promise.resolve(safeParse(window.TavernNativeBridge.generateRaw(toJson(config)), { ok: false }));
                        }
                        return Promise.resolve({ ok: false });
                    },
                    eventOn: function(eventName, fn) {
                        if (typeof fn !== "function") return "";
                        var id = "ev_" + (++listenerSeq);
                        listeners[id] = { eventName: String(eventName), fn: fn, once: false };
                        if (window.TavernNativeBridge) {
                            window.TavernNativeBridge.eventOn(String(eventName), id);
                        }
                        return id;
                    },
                    eventOnce: function(eventName, fn) {
                        if (typeof fn !== "function") return "";
                        var id = "ev_" + (++listenerSeq);
                        listeners[id] = { eventName: String(eventName), fn: fn, once: true };
                        if (window.TavernNativeBridge) {
                            window.TavernNativeBridge.eventOnce(String(eventName), id);
                        }
                        return id;
                    },
                    eventEmit: function(eventName, payload) {
                        if (window.TavernNativeBridge) {
                            return safeParse(window.TavernNativeBridge.eventEmit(String(eventName), toJson(payload || {})), { ok: false });
                        }
                        return { ok: false };
                    },
                    eventRemoveListener: function(eventName, listenerIdOrFn) {
                        var targetId = null;
                        if (typeof listenerIdOrFn === "string") {
                            targetId = listenerIdOrFn;
                        } else if (typeof listenerIdOrFn === "function") {
                            for (var k in listeners) {
                                if (listeners[k].fn === listenerIdOrFn && (!eventName || listeners[k].eventName === String(eventName))) {
                                    targetId = k;
                                    break;
                                }
                            }
                        }
                        if (!targetId) return false;
                        delete listeners[targetId];
                        if (window.TavernNativeBridge) {
                            return window.TavernNativeBridge.eventRemoveListener(String(eventName || ""), targetId);
                        }
                        return true;
                    },
                    triggerSlash: function(cmd) {
                        if (window.TavernNativeBridge && cmd) {
                            window.TavernNativeBridge.triggerSlash(String(cmd));
                        }
                    },
                    event_types: $eventTypesJson
                };
                window.TavernHelper = helper;
                window.eventOn = helper.eventOn;
                window.eventOnce = helper.eventOnce;
                window.eventEmit = helper.eventEmit;
                window.eventRemoveListener = helper.eventRemoveListener;
                window.SillyTavern = window.SillyTavern || {
                    getContext: function() {
                        return {
                            variables: helper.getVariables(),
                            stat_data: helper.getVariables(),
                            event_types: helper.event_types
                        };
                    }
                };
            })();
            </script>
        """.trimIndent()

        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="utf-8">
            <meta http-equiv="Content-Security-Policy" content="$csp">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            <style>
            :root {
                --bg: $bgHex;
                --surface: $surfaceHex;
                --fg: $fgHex;
                --fg-muted: $mutedHex;
                --accent: $accentHex;
                --border: $borderHex;
                color-scheme: ${if (darkTheme) "dark" else "light"};
            }
            html, body {
                margin: 0;
                padding: 0;
                background: transparent;
                color: var(--fg);
                font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                font-size: 14px;
                line-height: 1.5;
                overflow-x: auto;
            }
            #tavern-card-root { padding: 8px; position: relative; isolation: isolate; }
            </style>
            $bridgeBootstrap
            </head>
            <body>
            <div id="tavern-card-root">$scopedHtml</div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun sanitizeUrlAttributes(html: String, policy: CardSandboxPolicy): String =
        urlAttrRegex.replace(html) { match ->
            val attr = match.groupValues[1]
            val target = (match.groupValues[2].ifEmpty { match.groupValues[3] }).trim()
            if (target.startsWith("#") || policy.isUrlAllowed(target)) {
                match.value
            } else {
                "$attr=\"#blocked\""
            }
        }
}

internal interface TavernHelperHost {
    fun getVariables(scope: String, messageId: Int?): JSONObject
    fun replaceVariables(variables: JSONObject, scope: String, messageId: Int?): JSONObject
    fun insertOrAssignVariables(patch: JSONObject, scope: String, messageId: Int?): JSONObject
    fun deleteVariable(path: String, scope: String, messageId: Int?): JSONObject

    fun getChatMessages(range: String?): JSONArray
    fun setChatMessages(updates: JSONArray): Int
    fun createChatMessages(items: JSONArray): Int
    fun deleteChatMessages(ids: List<Int>): Int

    fun getWorldbookNames(): List<String>
    fun getWorldbook(name: String): JSONArray
    fun createWorldbookEntries(name: String, entries: JSONArray): List<Int>
    fun updateWorldbookWith(name: String, entries: JSONArray): Int
    fun deleteWorldbookEntries(name: String, uids: List<Int>): Int

    fun generate(config: JSONObject): JSONObject
    fun generateRaw(config: JSONObject): JSONObject

    fun triggerSlash(command: String)
    val eventBus: TavernEventBus
}

/**
 * Pure Kotlin in-memory implementation of [TavernHelperHost] used both as a default fallback
 * and for thorough deterministic unit testing of the TavernHelper bridge.
 */
internal class InMemoryTavernHelperHost(
    initialChatVariables: JSONObject = JSONObject(),
    initialGlobalVariables: JSONObject = JSONObject(),
    initialMessages: List<Message> = emptyList(),
    initialBooks: List<WorldBook> = emptyList(),
    override val eventBus: TavernEventBus = TavernEventBus(),
    private val onSlash: (String) -> Unit = {},
    private val onGenerate: (JSONObject, Boolean) -> JSONObject = { cfg, raw ->
        jsonOf("ok" to true, "request_id" to newId(), "raw" to raw, "echo" to cfg.string("user_input"))
    },
) : TavernHelperHost {
    private var chatVars = MvuEngine.deepCopyJsonObject(initialChatVariables)
    private var globalVars = MvuEngine.deepCopyJsonObject(initialGlobalVariables)
    private val messageVars = linkedMapOf<Int, JSONObject>()
    val messages: MutableList<Message> = initialMessages.toMutableList()
    val books: MutableList<WorldBook> = initialBooks.toMutableList()

    private fun storeFor(scope: String, messageId: Int?): JSONObject = when (scope.lowercase(Locale.ROOT)) {
        "global" -> globalVars
        "message" -> messageVars.getOrPut(messageId ?: (messages.lastIndex.coerceAtLeast(0))) { JSONObject() }
        else -> chatVars
    }

    private fun setStore(scope: String, messageId: Int?, value: JSONObject) {
        val copy = MvuEngine.deepCopyJsonObject(value)
        when (scope.lowercase(Locale.ROOT)) {
            "global" -> globalVars = copy
            "message" -> messageVars[messageId ?: (messages.lastIndex.coerceAtLeast(0))] = copy
            else -> chatVars = copy
        }
    }

    override fun getVariables(scope: String, messageId: Int?): JSONObject =
        MvuEngine.deepCopyJsonObject(storeFor(scope, messageId))

    override fun replaceVariables(variables: JSONObject, scope: String, messageId: Int?): JSONObject {
        setStore(scope, messageId, variables)
        return getVariables(scope, messageId)
    }

    override fun insertOrAssignVariables(patch: JSONObject, scope: String, messageId: Int?): JSONObject {
        val target = storeFor(scope, messageId)
        MvuEngine.deepMergeInto(target, patch)
        return MvuEngine.deepCopyJsonObject(target)
    }

    override fun deleteVariable(path: String, scope: String, messageId: Int?): JSONObject {
        val target = storeFor(scope, messageId)
        val parts = MvuEngine.normalizePath(path).split('.').filter(String::isNotEmpty)
        if (parts.isEmpty()) return MvuEngine.deepCopyJsonObject(target)
        var cursor: JSONObject = target
        for (i in 0 until parts.lastIndex) {
            cursor = cursor.optJSONObject(parts[i]) ?: return MvuEngine.deepCopyJsonObject(target)
        }
        cursor.remove(parts.last())
        return MvuEngine.deepCopyJsonObject(target)
    }

    override fun getChatMessages(range: String?): JSONArray {
        val indices = resolveMessageRange(range, messages.size)
        return JSONArray().apply {
            indices.forEach { idx ->
                val msg = messages[idx]
                put(
                    jsonOf(
                        "message_id" to idx,
                        "name" to msg.name,
                        "role" to msg.role,
                        "is_user" to (msg.role == "user"),
                        "is_system" to (msg.role == "system" || msg.hidden),
                        "message" to msg.text,
                        "swipes" to JSONArray(msg.variants.ifEmpty { listOf(msg.text) }),
                        "swipe_id" to msg.index,
                    ),
                )
            }
        }
    }

    override fun setChatMessages(updates: JSONArray): Int {
        var count = 0
        updates.objects().forEach { obj ->
            val id = obj.optInt("message_id", -1)
            if (id in messages.indices) {
                val cur = messages[id]
                val nextText = if (obj.has("message")) obj.string("message") else cur.text
                val nextName = if (obj.has("name")) obj.string("name") else cur.name
                val nextRole = if (obj.has("role")) obj.string("role") else cur.role
                messages[id] = cur.copy(text = nextText, name = nextName, role = nextRole)
                count++
                eventBus.emit(TavernCoreEvent.MESSAGE_EDITED, jsonOf("message_id" to id), source = "tavern_helper")
            }
        }
        return count
    }

    override fun createChatMessages(items: JSONArray): Int {
        var count = 0
        items.objects().forEach { obj ->
            val role = obj.string("role").ifBlank { if (obj.optBoolean("is_user")) "user" else "assistant" }
            val name = obj.string("name").ifBlank { if (role == "user") "User" else "Assistant" }
            val text = obj.string("message")
            val insertAt = obj.optInt("insert_at", messages.size).coerceIn(0, messages.size)
            messages.add(insertAt, Message(role = role, name = name, text = text))
            count++
            val event = if (role == "user") TavernCoreEvent.MESSAGE_SENT else TavernCoreEvent.MESSAGE_RECEIVED
            eventBus.emit(event, jsonOf("message_id" to insertAt), source = "tavern_helper")
        }
        return count
    }

    override fun deleteChatMessages(ids: List<Int>): Int {
        val valid = ids.filter { it in messages.indices }.distinct().sortedDescending()
        valid.forEach { idx ->
            messages.removeAt(idx)
            eventBus.emit(TavernCoreEvent.MESSAGE_DELETED, jsonOf("message_id" to idx), source = "tavern_helper")
        }
        return valid.size
    }

    override fun getWorldbookNames(): List<String> = books.map(WorldBook::name)

    override fun getWorldbook(name: String): JSONArray {
        val book = books.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) || it.id == name.trim() }
            ?: return JSONArray()
        return JSONArray(book.entries.map(LoreEntry::json))
    }

    override fun createWorldbookEntries(name: String, entries: JSONArray): List<Int> {
        val idx = books.indexOfFirst { it.name.equals(name.trim(), ignoreCase = true) || it.id == name.trim() }
        if (idx < 0) return emptyList()
        val book = books[idx]
        val existing = book.entries.toMutableList()
        var nextId = (existing.maxOfOrNull(LoreEntry::id) ?: -1) + 1
        val createdIds = mutableListOf<Int>()
        entries.objects().forEach { obj ->
            val uid = nextId++
            val entry = LoreEntry.from(uid, obj)
            existing += entry
            createdIds += uid
        }
        books[idx] = book.withEntries(existing)
        eventBus.emit(TavernCoreEvent.WORLDINFO_UPDATED, jsonOf("worldbook" to book.name), source = "tavern_helper")
        return createdIds
    }

    override fun updateWorldbookWith(name: String, entries: JSONArray): Int {
        val idx = books.indexOfFirst { it.name.equals(name.trim(), ignoreCase = true) || it.id == name.trim() }
        if (idx < 0) return 0
        val book = books[idx]
        val map = book.entries.associateBy(LoreEntry::id).toMutableMap()
        var updated = 0
        entries.objects().forEach { obj ->
            val uid = obj.optInt("uid", obj.optInt("id", -1))
            if (uid >= 0 && uid in map) {
                val base = map.getValue(uid).json()
                obj.keys().forEach { key -> base.put(key, obj.opt(key)) }
                map[uid] = LoreEntry.from(uid, base)
                updated++
            }
        }
        if (updated > 0) {
            books[idx] = book.withEntries(map.values.sortedBy(LoreEntry::id))
            eventBus.emit(TavernCoreEvent.WORLDINFO_UPDATED, jsonOf("worldbook" to book.name), source = "tavern_helper")
        }
        return updated
    }

    override fun deleteWorldbookEntries(name: String, uids: List<Int>): Int {
        val idx = books.indexOfFirst { it.name.equals(name.trim(), ignoreCase = true) || it.id == name.trim() }
        if (idx < 0) return 0
        val book = books[idx]
        val toDelete = uids.toSet()
        val kept = book.entries.filterNot { it.id in toDelete }
        val removed = book.entries.size - kept.size
        if (removed > 0) {
            books[idx] = book.withEntries(kept)
            eventBus.emit(TavernCoreEvent.WORLDINFO_UPDATED, jsonOf("worldbook" to book.name), source = "tavern_helper")
        }
        return removed
    }

    override fun generate(config: JSONObject): JSONObject {
        eventBus.emit(TavernCoreEvent.GENERATION_STARTED, jsonOf("raw" to false), source = "tavern_helper")
        val res = onGenerate(config, false)
        eventBus.emit(TavernCoreEvent.GENERATION_ENDED, jsonOf("raw" to false), source = "tavern_helper")
        return res
    }

    override fun generateRaw(config: JSONObject): JSONObject {
        eventBus.emit(TavernCoreEvent.GENERATION_STARTED, jsonOf("raw" to true), source = "tavern_helper")
        val res = onGenerate(config, true)
        eventBus.emit(TavernCoreEvent.GENERATION_ENDED, jsonOf("raw" to true), source = "tavern_helper")
        return res
    }

    override fun triggerSlash(command: String) = onSlash(command)

    companion object {
        fun resolveMessageRange(range: String?, size: Int): List<Int> {
            if (size <= 0) return emptyList()
            val cleaned = range?.trim().orEmpty()
            if (cleaned.isEmpty() || cleaned.equals("all", ignoreCase = true)) {
                return (0 until size).toList()
            }
            fun resolveIndex(raw: Int): Int = if (raw < 0) size + raw else raw
            val dash = cleaned.indexOf('-', startIndex = 1)
            if (dash > 0) {
                val startRaw = cleaned.substring(0, dash).trim().toIntOrNull() ?: return emptyList()
                val endRaw = cleaned.substring(dash + 1).trim().toIntOrNull() ?: return emptyList()
                val start = resolveIndex(startRaw).coerceIn(0, size - 1)
                val end = resolveIndex(endRaw).coerceIn(0, size - 1)
                return if (start <= end) (start..end).toList() else emptyList()
            }
            val single = cleaned.toIntOrNull() ?: return emptyList()
            val resolved = resolveIndex(single)
            return if (resolved in 0 until size) listOf(resolved) else emptyList()
        }
    }
}

internal class TavernHelperBridge(
    private val host: TavernHelperHost,
    private val policy: CardSandboxPolicy = CardSandboxPolicy(allowScripts = true),
    private val dispatchEventToJs: (listenerId: String, eventName: String, payloadJson: String) -> Unit = { _, _, _ -> },
    private val postToMain: (() -> Unit) -> Unit = defaultMainDispatcher(),
) {
    private val registeredListeners = CopyOnWriteArraySet<String>()

    @Volatile
    private var lastSlashAt = 0L

    constructor(
        readVariables: () -> String,
        onSlashCommand: (String) -> Unit,
        policy: CardSandboxPolicy = CardSandboxPolicy(allowScripts = true),
        eventBus: TavernEventBus = TavernEventBus(),
    ) : this(
        host = object : TavernHelperHost by InMemoryTavernHelperHost(eventBus = eventBus, onSlash = onSlashCommand) {
            override fun getVariables(scope: String, messageId: Int?): JSONObject =
                runCatching { JSONObject(readVariables()) }.getOrElse { JSONObject() }
        },
        policy = policy,
    )

    private fun parseOption(optionJson: String?): Pair<String, Int?> {
        if (optionJson.isNullOrBlank()) return "chat" to null
        val obj = runCatching { JSONObject(optionJson) }.getOrNull() ?: return "chat" to null
        val scope = obj.string("type").ifBlank { "chat" }
        val msgId = if (obj.has("message_id") && !obj.isNull("message_id")) obj.optInt("message_id") else null
        return scope to msgId
    }

    @JavascriptInterface
    fun getVariables(optionJson: String? = null): String = runCatching {
        val (scope, msgId) = parseOption(optionJson)
        host.getVariables(scope, msgId).toString()
    }.getOrDefault("{}")

    @JavascriptInterface
    fun getVariable(path: String?): String {
        val rawPath = path?.trim().orEmpty()
        if (rawPath.isEmpty()) return ""
        return runCatching {
            val obj = host.getVariables("chat", null)
            MvuEngine.readPath(obj, rawPath).orEmpty()
        }.getOrDefault("")
    }

    @JavascriptInterface
    fun replaceVariables(variablesJson: String?, optionJson: String? = null): String {
        if (!policy.allowVariableWrite) return jsonOf("ok" to false, "error" to "permission_denied").toString()
        return runCatching {
            val (scope, msgId) = parseOption(optionJson)
            val obj = JSONObject(variablesJson.orEmpty().ifBlank { "{}" })
            host.replaceVariables(obj, scope, msgId).toString()
        }.getOrElse { jsonOf("ok" to false, "error" to (it.message ?: "invalid_json")).toString() }
    }

    @JavascriptInterface
    fun insertOrAssignVariables(variablesJson: String?, optionJson: String? = null): String {
        if (!policy.allowVariableWrite) return jsonOf("ok" to false, "error" to "permission_denied").toString()
        return runCatching {
            val (scope, msgId) = parseOption(optionJson)
            val patch = JSONObject(variablesJson.orEmpty().ifBlank { "{}" })
            host.insertOrAssignVariables(patch, scope, msgId).toString()
        }.getOrElse { jsonOf("ok" to false, "error" to (it.message ?: "invalid_json")).toString() }
    }

    @JavascriptInterface
    fun deleteVariable(variablePath: String?, optionJson: String? = null): String {
        if (!policy.allowVariableWrite) return jsonOf("ok" to false, "error" to "permission_denied").toString()
        val path = variablePath?.trim().orEmpty()
        if (path.isEmpty()) return jsonOf("ok" to false, "error" to "empty_path").toString()
        return runCatching {
            val (scope, msgId) = parseOption(optionJson)
            host.deleteVariable(path, scope, msgId).toString()
        }.getOrElse { jsonOf("ok" to false, "error" to (it.message ?: "delete_failed")).toString() }
    }

    @JavascriptInterface
    fun getChatMessages(range: String? = null, optionJson: String? = null): String = runCatching {
        host.getChatMessages(range).toString()
    }.getOrDefault("[]")

    @JavascriptInterface
    fun setChatMessages(messagesJson: String?, optionJson: String? = null): String {
        if (!policy.allowMessageWrite) return jsonOf("ok" to false, "error" to "permission_denied").toString()
        return runCatching {
            val array = JSONArray(messagesJson.orEmpty().ifBlank { "[]" })
            val updated = host.setChatMessages(array)
            jsonOf("ok" to true, "updated" to updated).toString()
        }.getOrElse { jsonOf("ok" to false, "error" to (it.message ?: "invalid_messages")).toString() }
    }

    @JavascriptInterface
    fun createChatMessages(messagesJson: String?, optionJson: String? = null): String {
        if (!policy.allowMessageWrite) return jsonOf("ok" to false, "error" to "permission_denied").toString()
        return runCatching {
            val array = JSONArray(messagesJson.orEmpty().ifBlank { "[]" })
            val created = host.createChatMessages(array)
            jsonOf("ok" to true, "created" to created).toString()
        }.getOrElse { jsonOf("ok" to false, "error" to (it.message ?: "invalid_messages")).toString() }
    }

    @JavascriptInterface
    fun deleteChatMessages(idsJson: String?, optionJson: String? = null): String {
        if (!policy.allowMessageWrite) return jsonOf("ok" to false, "error" to "permission_denied").toString()
        return runCatching {
            val array = JSONArray(idsJson.orEmpty().ifBlank { "[]" })
            val ids = (0 until array.length()).mapNotNull { i -> array.optInt(i, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } }
            val deleted = host.deleteChatMessages(ids)
            jsonOf("ok" to true, "deleted" to deleted).toString()
        }.getOrElse { jsonOf("ok" to false, "error" to (it.message ?: "invalid_ids")).toString() }
    }

    @JavascriptInterface
    fun getWorldbookNames(): String = runCatching {
        JSONArray(host.getWorldbookNames()).toString()
    }.getOrDefault("[]")

    @JavascriptInterface
    fun getWorldbook(name: String?): String = runCatching {
        host.getWorldbook(name.orEmpty()).toString()
    }.getOrDefault("[]")

    @JavascriptInterface
    fun createWorldbookEntries(name: String?, entriesJson: String?): String {
        if (!policy.allowWorldbookWrite) return jsonOf("ok" to false, "error" to "permission_denied").toString()
        return runCatching {
            val entries = JSONArray(entriesJson.orEmpty().ifBlank { "[]" })
            val uids = host.createWorldbookEntries(name.orEmpty(), entries)
            jsonOf("ok" to true, "uids" to JSONArray(uids)).toString()
        }.getOrElse { jsonOf("ok" to false, "error" to (it.message ?: "worldbook_create_failed")).toString() }
    }

    @JavascriptInterface
    fun updateWorldbookWith(name: String?, entriesJson: String?): String {
        if (!policy.allowWorldbookWrite) return jsonOf("ok" to false, "error" to "permission_denied").toString()
        return runCatching {
            val entries = JSONArray(entriesJson.orEmpty().ifBlank { "[]" })
            val updated = host.updateWorldbookWith(name.orEmpty(), entries)
            jsonOf("ok" to true, "updated" to updated).toString()
        }.getOrElse { jsonOf("ok" to false, "error" to (it.message ?: "worldbook_update_failed")).toString() }
    }

    @JavascriptInterface
    fun deleteWorldbookEntries(name: String?, uidsJson: String?): String {
        if (!policy.allowWorldbookWrite) return jsonOf("ok" to false, "error" to "permission_denied").toString()
        return runCatching {
            val arr = JSONArray(uidsJson.orEmpty().ifBlank { "[]" })
            val uids = (0 until arr.length()).mapNotNull { i -> arr.optInt(i, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } }
            val deleted = host.deleteWorldbookEntries(name.orEmpty(), uids)
            jsonOf("ok" to true, "deleted" to deleted).toString()
        }.getOrElse { jsonOf("ok" to false, "error" to (it.message ?: "worldbook_delete_failed")).toString() }
    }

    @JavascriptInterface
    fun generate(configJson: String? = null): String {
        if (!policy.allowGeneration) return jsonOf("ok" to false, "error" to "permission_denied").toString()
        return runCatching {
            val cfg = if (configJson.isNullOrBlank()) JSONObject() else JSONObject(configJson)
            host.generate(cfg).toString()
        }.getOrElse { jsonOf("ok" to false, "error" to (it.message ?: "generate_failed")).toString() }
    }

    @JavascriptInterface
    fun generateRaw(configJson: String? = null): String {
        if (!policy.allowGeneration) return jsonOf("ok" to false, "error" to "permission_denied").toString()
        return runCatching {
            val cfg = if (configJson.isNullOrBlank()) JSONObject() else JSONObject(configJson)
            host.generateRaw(cfg).toString()
        }.getOrElse { jsonOf("ok" to false, "error" to (it.message ?: "generate_raw_failed")).toString() }
    }

    @JavascriptInterface
    fun eventOn(eventName: String?, listenerId: String?): String {
        val name = eventName?.trim().orEmpty()
        val id = listenerId?.trim().orEmpty().ifEmpty { newId() }
        if (name.isEmpty()) return ""
        val registered = host.eventBus.on(name, id) { ev ->
            postToMain { dispatchEventToJs(id, ev.name, ev.payload.toString()) }
        }
        registeredListeners += registered
        return registered
    }

    @JavascriptInterface
    fun eventOnce(eventName: String?, listenerId: String?): String {
        val name = eventName?.trim().orEmpty()
        val id = listenerId?.trim().orEmpty().ifEmpty { newId() }
        if (name.isEmpty()) return ""
        val registered = host.eventBus.once(name, id) { ev ->
            registeredListeners -= id
            postToMain { dispatchEventToJs(id, ev.name, ev.payload.toString()) }
        }
        registeredListeners += registered
        return registered
    }

    @JavascriptInterface
    fun eventEmit(eventName: String?, payloadJson: String? = null): String {
        val name = eventName?.trim().orEmpty()
        if (name.isEmpty()) return jsonOf("ok" to false, "error" to "empty_event").toString()
        return runCatching {
            val payload = if (payloadJson.isNullOrBlank()) JSONObject() else JSONObject(payloadJson)
            val count = host.eventBus.emit(name, payload, source = "card_sandbox")
            jsonOf("ok" to true, "notified" to count).toString()
        }.getOrElse { jsonOf("ok" to false, "error" to (it.message ?: "emit_failed")).toString() }
    }

    @JavascriptInterface
    fun eventRemoveListener(eventName: String?, listenerId: String?): Boolean {
        val id = listenerId?.trim().orEmpty()
        if (id.isEmpty()) return false
        registeredListeners -= id
        val name = eventName?.trim().orEmpty()
        return if (name.isEmpty()) host.eventBus.removeListener(id)
        else host.eventBus.removeListener(name, id)
    }

    @JavascriptInterface
    fun triggerSlash(command: String?) {
        val trimmed = command?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.length > 4096 || !trimmed.startsWith("/")) return
        val now = System.currentTimeMillis()
        if (now - lastSlashAt < 250L) return
        lastSlashAt = now
        postToMain { host.triggerSlash(trimmed) }
    }

    fun dispose() {
        registeredListeners.forEach { id -> host.eventBus.removeListener(id) }
        registeredListeners.clear()
    }

    companion object {
        private fun defaultMainDispatcher(): (() -> Unit) -> Unit {
            val looper = runCatching { Looper.getMainLooper() }.getOrNull()
            if (looper != null) {
                val handler = Handler(looper)
                return { block -> handler.post(block) }
            }
            return { block -> block() }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun CardScriptWebView(
    rawHtml: String,
    label: String,
    mvuStateJson: String,
    onRunScript: ((String) -> Unit)? = null,
    sandboxPolicy: CardSandboxPolicy = CardSandboxPolicy(allowScripts = true),
    helperHost: TavernHelperHost? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme() || scheme.surface.luminanceCompat() < 0.4f
    var collapsed by rememberSaveable(label) { mutableStateOf(false) }
    var showSource by rememberSaveable(label) { mutableStateOf(false) }
    val latestVarsState = rememberUpdatedState(mvuStateJson)
    val latestRunScriptState = rememberUpdatedState(onRunScript)
    val latestPolicy by rememberUpdatedState(sandboxPolicy)
    var bridgeHolder by remember { mutableStateOf<TavernHelperBridge?>(null) }
    DisposableEffect(Unit) {
        onDispose { bridgeHolder?.dispose() }
    }

    val wrappedHtml = remember(rawHtml, dark, scheme, mvuStateJson, sandboxPolicy) {
        CardSandboxHtml.sanitizeCardScriptHtml(
            rawHtml = rawHtml,
            darkTheme = dark,
            bgHex = scheme.surface.toHexCss(),
            surfaceHex = scheme.surfaceContainerHigh.toHexCss(),
            fgHex = scheme.onSurface.toHexCss(),
            mutedHex = scheme.onSurfaceVariant.toHexCss(),
            accentHex = scheme.primary.toHexCss(),
            borderHex = scheme.outlineVariant.toHexCss(),
            policy = sandboxPolicy,
        )
    }

    OutlinedCard(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = scheme.surfaceContainerLow),
        border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val netLabel = if (sandboxPolicy.allowNetwork && sandboxPolicy.normalizedDomains.isNotEmpty()) {
                    "白名单网络(${sandboxPolicy.normalizedDomains.size})"
                } else {
                    "离线沙盒"
                }
                Text(
                    text = "$label · $netLabel",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { showSource = !showSource }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Outlined.Code,
                        contentDescription = if (showSource) "预览面板" else "查看源码",
                        modifier = Modifier.size(16.dp),
                        tint = if (showSource) scheme.primary else scheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { collapsed = !collapsed }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (collapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                        contentDescription = if (collapsed) "展开面板" else "折叠面板",
                        modifier = Modifier.size(18.dp),
                        tint = scheme.onSurfaceVariant,
                    )
                }
            }
            if (!collapsed) {
                HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
                if (showSource) {
                    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(10.dp)) {
                        SelectionContainer {
                            Text(
                                text = rawHtml,
                                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp),
                            )
                        }
                    }
                } else {
                    AndroidView(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 560.dp),
                        factory = { context ->
                            WebView(context).apply {
                                val viewRef = WeakReference(this)
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                )
                                setBackgroundColor(0)
                                isVerticalScrollBarEnabled = false
                                isHorizontalScrollBarEnabled = true
                                val allowNet = latestPolicy.allowNetwork && latestPolicy.normalizedDomains.isNotEmpty()
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    allowFileAccess = false
                                    allowContentAccess = false
                                    @Suppress("DEPRECATION")
                                    allowFileAccessFromFileURLs = false
                                    @Suppress("DEPRECATION")
                                    allowUniversalAccessFromFileURLs = false
                                    blockNetworkLoads = !allowNet
                                    blockNetworkImage = !allowNet
                                    loadsImagesAutomatically = allowNet
                                    setSupportZoom(false)
                                    builtInZoomControls = false
                                    displayZoomControls = false
                                }
                                val dispatchJs: (String, String, String) -> Unit = { lid, evName, payloadJson ->
                                    val safeId = JSONObject.quote(lid)
                                    val safeName = JSONObject.quote(evName)
                                    val safePayload = JSONObject.quote(payloadJson)
                                    viewRef.get()?.evaluateJavascript(
                                        "window.__tavernDispatchEvent && window.__tavernDispatchEvent($safeId, $safeName, $safePayload);",
                                        null,
                                    )
                                }
                                val nativeBridge = if (helperHost != null) {
                                    TavernHelperBridge(
                                        host = helperHost,
                                        policy = latestPolicy,
                                        dispatchEventToJs = dispatchJs,
                                    )
                                } else {
                                    TavernHelperBridge(
                                        readVariables = { latestVarsState.value },
                                        onSlashCommand = { cmd -> latestRunScriptState.value?.invoke(cmd) },
                                        policy = latestPolicy,
                                    )
                                }
                                bridgeHolder?.dispose()
                                bridgeHolder = nativeBridge
                                addJavascriptInterface(nativeBridge, "TavernNativeBridge")
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true

                                    override fun shouldInterceptRequest(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                    ): WebResourceResponse? {
                                        val url = request?.url?.toString().orEmpty()
                                        if (latestPolicy.isUrlAllowed(url)) return null
                                        return WebResourceResponse(
                                            "text/plain",
                                            "utf-8",
                                            ByteArrayInputStream(ByteArray(0)),
                                        )
                                    }
                                }
                            }
                        },
                        update = { view ->
                            val allowNet = latestPolicy.allowNetwork && latestPolicy.normalizedDomains.isNotEmpty()
                            view.settings.blockNetworkLoads = !allowNet
                            view.settings.blockNetworkImage = !allowNet
                            view.settings.loadsImagesAutomatically = allowNet
                            if (view.tag != wrappedHtml) {
                                view.tag = wrappedHtml
                                view.loadDataWithBaseURL(null, wrappedHtml, "text/html", "utf-8", null)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun MvuStateChip(deltas: List<MvuDelta>, statDataJson: String) {
    if (deltas.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = { expanded = !expanded },
        shape = RoundedCornerShape(10.dp),
        color = scheme.surfaceContainerHighest.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.65f)),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.DataObject, null, modifier = Modifier.size(15.dp), tint = scheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "MVU 状态变更 (${deltas.size} 项)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "收起状态变更" else "展开状态变更",
                    modifier = Modifier.size(16.dp),
                    tint = scheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                deltas.forEach { delta ->
                    val transition = buildString {
                        append(delta.path)
                        append(": ")
                        if (delta.before != null) {
                            append(delta.before)
                            append(" → ")
                        }
                        append(delta.after ?: "已移除")
                    }
                    Text(
                        text = "• $transition",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurface,
                    )
                }
                if (statDataJson.length > 2) {
                    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
                    Text(
                        text = statDataJson,
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp),
                        color = scheme.onSurfaceVariant,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun Color.toHexCss(): String = String.format("#%06X", toArgb() and 0xFFFFFF)

private fun Color.luminanceCompat(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
