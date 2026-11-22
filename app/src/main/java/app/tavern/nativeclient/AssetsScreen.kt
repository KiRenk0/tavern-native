package app.tavern.nativeclient

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun AssetsScreen(
    state: TavernState,
    model: TavernViewModel,
    close: () -> Unit,
) {
    BackHandler(onBack = close)
    val assetsConfig = state.prefs.extensions.assets
    var manifestUrl by rememberSaveable(assetsConfig.manifestUrl) { mutableStateOf(assetsConfig.manifestUrl) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf<AssetCategory?>(null) }
    var items by remember { mutableStateOf(builtInOfficialAssets) }
    var loadingManifest by remember { mutableStateOf(false) }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    val filtered = remember(items, query, selectedCategory, refreshTick) {
        model.assetsManager.filterAssets(items, query, selectedCategory)
    }

    Form {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionIcon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", !state.busy, close)
            Text("官方素材下载器", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            ActionIcon(Icons.Outlined.Refresh, "刷新远程清单", !loadingManifest) {
                loadingManifest = true
                scope.launch {
                    val result = runCatching { model.assetsManager.fetchManifest(manifestUrl) }
                    result.onSuccess { remote ->
                        if (remote.isNotEmpty()) {
                            items = remote
                            model.notice("已加载 ${remote.size} 项远程官方素材")
                        } else {
                            model.notice("远程清单为空，已保留内置目录")
                        }
                    }.onFailure { err ->
                        model.notice("加载远程清单失败：${err.message ?: "网络错误"}")
                    }
                    loadingManifest = false
                }
            }
        }

        Field(
            label = "官方素材清单 URL（仅允许受信任 HTTPS 主机）",
            value = manifestUrl,
            change = { manifestUrl = it },
            placeholder = DEFAULT_ASSETS_MANIFEST_URL,
            enabled = !loadingManifest,
        )
        Field("搜索素材名称、作者或标签", query, { query = it }, placeholder = "例如：theme, tavern, Seraphina")

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = selectedCategory == null,
                onClick = { selectedCategory = null },
                label = { Text("全部") },
            )
            AssetCategory.entries.forEach { cat ->
                FilterChip(
                    selected = selectedCategory == cat,
                    onClick = { selectedCategory = if (selectedCategory == cat) null else cat },
                    label = { Text(cat.label) },
                )
            }
        }

        if (loadingManifest) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        if (filtered.isEmpty()) {
            Text("没有匹配的素材条目。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            filtered.forEach { item ->
                val installed = remember(item.id, refreshTick) { model.assetsManager.isInstalled(item) }
                val busyItem = downloadingId == item.id
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${item.category.label} · ${item.author} · ${item.filename}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (installed) {
                                Icon(Icons.Outlined.CheckCircle, "已下载", tint = MaterialTheme.colorScheme.primary)
                                ActionIcon(Icons.Outlined.Delete, "删除本地素材") {
                                    model.assetsManager.deleteAsset(item)
                                    val nextIds = state.prefs.extensions.assets.installedAssetIds - item.id
                                    model.saveExtensions(
                                        state.prefs.extensions.copy(
                                            assets = state.prefs.extensions.assets.copy(
                                                manifestUrl = manifestUrl.trim().ifBlank { DEFAULT_ASSETS_MANIFEST_URL },
                                                installedAssetIds = nextIds,
                                            ),
                                        ),
                                    )
                                    refreshTick++
                                }
                            } else {
                                FilledTonalButton(
                                    onClick = {
                                        downloadingId = item.id
                                        scope.launch {
                                            runCatching { model.assetsManager.downloadAsset(item) }
                                                .onSuccess { file ->
                                                    val nextIds = state.prefs.extensions.assets.installedAssetIds + item.id
                                                    model.saveExtensions(
                                                        state.prefs.extensions.copy(
                                                            assets = state.prefs.extensions.assets.copy(
                                                                manifestUrl = manifestUrl.trim().ifBlank { DEFAULT_ASSETS_MANIFEST_URL },
                                                                installedAssetIds = nextIds,
                                                            ),
                                                        ),
                                                    )
                                                    refreshTick++
                                                    model.notice("已下载素材：${file.name}")
                                                }
                                                .onFailure { err ->
                                                    model.notice("下载失败：${err.message ?: "未知错误"}")
                                                }
                                            downloadingId = null
                                        }
                                    },
                                    enabled = downloadingId == null,
                                ) {
                                    Icon(Icons.Outlined.CloudDownload, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (busyItem) "下载中…" else "下载")
                                }
                            }
                        }
                        if (item.description.isNotBlank()) {
                            Text(item.description, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
