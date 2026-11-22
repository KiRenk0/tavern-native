package app.tavern.nativeclient

import android.annotation.SuppressLint
import android.content.ClipData
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import app.tavern.nativeclient.chat.StreamingFadeText
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream

@Composable
internal fun RichMessageContent(
    text: String,
    appearance: ChatAppearance,
    modifier: Modifier = Modifier,
    textColor: Color = LocalContentColor.current,
    cardScriptsEnabled: Boolean = false,
    mvuStateJson: String = "{}",
    onRunScript: ((String) -> Unit)? = null,
    streamFadeIn: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val blocks = remember(text, appearance.renderHtmlCards) {
        parseRichBlocks(text, appearance.renderHtmlCards)
    }
    val baseSize = appearance.fontSizeSp.sp
    val baseLineHeight = (appearance.fontSizeSp * appearance.lineHeightMultiplier).sp
    val palette = appearance.palette
    val quoteColor = palette.color(ThemeColor.QUOTE) ?: scheme.primary
    val codeBg = scheme.surfaceContainerHighest.copy(alpha = 0.72f)
    val linkColor = scheme.primary
    val shadow = palette.shadowWidth.takeIf { it > 0 && !palette.noShadows }?.let {
        Shadow(palette.color(ThemeColor.SHADOW) ?: Color.Black, blurRadius = it.toFloat())
    }
    val bodyStyle = TextStyle(fontSize = baseSize, lineHeight = baseLineHeight, color = textColor, shadow = shadow)

    fun inline(raw: String) = buildRichInline(
        text = raw,
        highlightQuotes = appearance.highlightQuotes,
        quoteColor = quoteColor,
        codeBg = codeBg,
        linkColor = linkColor,
        italicsColor = palette.color(ThemeColor.ITALICS) ?: Color.Unspecified,
        underlineColor = palette.color(ThemeColor.UNDERLINE) ?: Color.Unspecified,
    )

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is RichBlock.Heading -> {
                    val scale = when (block.level) {
                        1 -> 1.38f
                        2 -> 1.24f
                        3 -> 1.14f
                        else -> 1.06f
                    }
                    val size = (appearance.fontSizeSp * scale).sp
                    SelectionContainer {
                        StreamingFadeText(
                            text = remember(block.text, appearance, quoteColor, codeBg, linkColor) { inline(block.text) },
                            style = bodyStyle.copy(
                                fontSize = size,
                                lineHeight = (appearance.fontSizeSp * scale * 1.25f).sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            enabled = streamFadeIn,
                        )
                    }
                }
                is RichBlock.CodeBlock -> CodeBlockCard(block)
                is RichBlock.BlockQuote -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            Modifier.width(3.dp).fillMaxHeight()
                                .clip(RoundedCornerShape(2.dp))
                                .background(scheme.primary.copy(alpha = 0.65f)),
                        )
                        val body = block.lines.joinToString("\n")
                        SelectionContainer {
                            StreamingFadeText(
                                text = remember(body, appearance, quoteColor, codeBg, linkColor) { inline(body) },
                                style = bodyStyle.copy(color = textColor.copy(alpha = 0.85f)),
                                enabled = streamFadeIn,
                            )
                        }
                    }
                }
                is RichBlock.BulletList -> {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        block.items.forEach { item ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("•", style = TextStyle(fontSize = baseSize, lineHeight = baseLineHeight, color = scheme.primary))
                                SelectionContainer(Modifier.weight(1f)) {
                                    StreamingFadeText(
                                        text = remember(item, appearance, quoteColor, codeBg, linkColor) { inline(item) },
                                        style = bodyStyle,
                                        enabled = streamFadeIn,
                                    )
                                }
                            }
                        }
                    }
                }
                is RichBlock.OrderedList -> {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        block.items.forEach { (num, item) ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    "$num.",
                                    style = TextStyle(
                                        fontSize = baseSize,
                                        lineHeight = baseLineHeight,
                                        fontWeight = FontWeight.Medium,
                                        color = scheme.primary,
                                    ),
                                )
                                SelectionContainer(Modifier.weight(1f)) {
                                    StreamingFadeText(
                                        text = remember(item, appearance, quoteColor, codeBg, linkColor) { inline(item) },
                                        style = bodyStyle,
                                        enabled = streamFadeIn,
                                    )
                                }
                            }
                        }
                    }
                }
                RichBlock.HorizontalRule -> HorizontalDivider(
                    modifier = Modifier.padding(vertical = 2.dp),
                    color = scheme.outlineVariant,
                )
                is RichBlock.MarkdownTable -> MarkdownTableView(block, appearance, textColor)
                is RichBlock.DetailsBlock -> DetailsBlockCard(
                    block = block,
                    appearance = appearance,
                    textColor = textColor,
                    cardScriptsEnabled = cardScriptsEnabled,
                    mvuStateJson = mvuStateJson,
                    onRunScript = onRunScript,
                )
                is RichBlock.HtmlCardBlock -> {
                    if (cardScriptsEnabled && block.rawHtml.contains("<script", ignoreCase = true)) {
                        app.tavern.nativeclient.script.CardScriptWebView(
                            rawHtml = block.rawHtml,
                            label = block.label,
                            mvuStateJson = mvuStateJson,
                            onRunScript = onRunScript,
                        )
                    } else {
                        HtmlStatusCard(block)
                    }
                }
                is RichBlock.Paragraph -> {
                    SelectionContainer {
                        StreamingFadeText(
                            text = remember(block.text, appearance, quoteColor, codeBg, linkColor) { inline(block.text) },
                            style = bodyStyle,
                            enabled = streamFadeIn,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeBlockCard(block: RichBlock.CodeBlock) {
    val scheme = MaterialTheme.colorScheme
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = scheme.surfaceContainerHighest,
        border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = block.language.ifBlank { "code" },
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("code", block.code)))
                            copied = true
                        }
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                        contentDescription = if (copied) "已复制" else "复制代码",
                        modifier = Modifier.size(16.dp),
                        tint = if (copied) scheme.primary else scheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp)) {
                SelectionContainer {
                    Text(
                        text = block.code,
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = scheme.onSurface,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkdownTableView(block: RichBlock.MarkdownTable, appearance: ChatAppearance, textColor: Color) {
    val scheme = MaterialTheme.colorScheme
    val columns = maxOf(block.headers.size, block.rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)
    val palette = appearance.palette
    val quoteColor = palette.color(ThemeColor.QUOTE) ?: scheme.primary
    val codeBg = scheme.surfaceContainerHighest.copy(alpha = 0.72f)
    val linkColor = scheme.primary
    fun inline(cell: String) = buildRichInline(cell, appearance.highlightQuotes, quoteColor, codeBg, linkColor,
        palette.color(ThemeColor.ITALICS) ?: Color.Unspecified, palette.color(ThemeColor.UNDERLINE) ?: Color.Unspecified)
    Surface(
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, scheme.outlineVariant),
        color = scheme.surfaceContainerLow,
    ) {
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Column {
                Row(Modifier.background(scheme.surfaceContainerHigh)) {
                    for (col in 0 until columns) {
                        val cell = block.headers.getOrNull(col).orEmpty()
                        Box(Modifier.widthIn(min = 84.dp, max = 220.dp).padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Text(
                                text = inline(cell),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = textColor,
                            )
                        }
                    }
                }
                block.rows.forEachIndexed { rowIndex, row ->
                    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.6f))
                    Row(
                        Modifier.background(
                            if (rowIndex % 2 == 1) scheme.surfaceContainer.copy(alpha = 0.5f) else Color.Transparent,
                        ),
                    ) {
                        for (col in 0 until columns) {
                            val cell = row.getOrNull(col).orEmpty()
                            Box(Modifier.widthIn(min = 84.dp, max = 220.dp).padding(horizontal = 10.dp, vertical = 6.dp)) {
                                SelectionContainer {
                                    Text(
                                        text = inline(cell),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = textColor,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailsBlockCard(
    block: RichBlock.DetailsBlock,
    appearance: ChatAppearance,
    textColor: Color,
    cardScriptsEnabled: Boolean = false,
    mvuStateJson: String = "{}",
    onRunScript: ((String) -> Unit)? = null,
) {
    var expanded by rememberSaveable(block.summary, block.openByDefault) { mutableStateOf(block.openByDefault) }
    val scheme = MaterialTheme.colorScheme
    OutlinedCard(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = scheme.surfaceContainerLow),
        border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Surface(onClick = { expanded = !expanded }, color = Color.Transparent) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (expanded) "收起" else "展开",
                        modifier = Modifier.size(18.dp),
                        tint = scheme.primary,
                    )
                    Text(
                        text = block.summary,
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (expanded && block.body.isNotBlank()) {
                HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.6f))
                RichMessageContent(
                    text = block.body,
                    appearance = appearance,
                    modifier = Modifier.padding(12.dp),
                    textColor = textColor,
                    cardScriptsEnabled = cardScriptsEnabled,
                    mvuStateJson = mvuStateJson,
                    onRunScript = onRunScript,
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HtmlStatusCard(block: RichBlock.HtmlCardBlock) {
    val scheme = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme() || scheme.surface.luminanceCompat() < 0.4f
    var collapsed by rememberSaveable(block.label) { mutableStateOf(false) }
    var showSource by rememberSaveable(block.label) { mutableStateOf(false) }
    val wrappedHtml = remember(block.rawHtml, dark, scheme) {
        sanitizeCardHtml(
            rawHtml = block.rawHtml,
            darkTheme = dark,
            bgHex = scheme.surface.toHexCss(),
            surfaceHex = scheme.surfaceContainerHigh.toHexCss(),
            fgHex = scheme.onSurface.toHexCss(),
            mutedHex = scheme.onSurfaceVariant.toHexCss(),
            accentHex = scheme.primary.toHexCss(),
            borderHex = scheme.outlineVariant.toHexCss(),
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
                Text(
                    text = block.label,
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
                                text = block.rawHtml,
                                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp),
                            )
                        }
                    }
                } else {
                    AndroidView(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp, max = 520.dp),
                        factory = { context ->
                            WebView(context).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                )
                                setBackgroundColor(0)
                                isVerticalScrollBarEnabled = false
                                isHorizontalScrollBarEnabled = true
                                settings.apply {
                                    javaScriptEnabled = false
                                    allowFileAccess = false
                                    allowContentAccess = false
                                    @Suppress("DEPRECATION")
                                    allowFileAccessFromFileURLs = false
                                    @Suppress("DEPRECATION")
                                    allowUniversalAccessFromFileURLs = false
                                    blockNetworkLoads = true
                                    blockNetworkImage = true
                                    loadsImagesAutomatically = false
                                    setSupportZoom(false)
                                    builtInZoomControls = false
                                    displayZoomControls = false
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true

                                    override fun shouldInterceptRequest(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                    ): WebResourceResponse = WebResourceResponse(
                                        "text/plain",
                                        "utf-8",
                                        ByteArrayInputStream(ByteArray(0)),
                                    )
                                }
                            }
                        },
                        update = { view ->
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

private fun Color.toHexCss(): String = String.format("#%06X", toArgb() and 0xFFFFFF)

private fun Color.luminanceCompat(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
