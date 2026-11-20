package app.tavern.nativeclient.backup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.tavern.nativeclient.Field
import app.tavern.nativeclient.Form
import app.tavern.nativeclient.FormHeading
import app.tavern.nativeclient.GroupToggle
import app.tavern.nativeclient.SelectField
import app.tavern.nativeclient.TavernState
import app.tavern.nativeclient.TavernViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun SyncSettingsScreen(
    state: TavernState,
    model: TavernViewModel,
    close: () -> Unit,
) {
    val saved = state.prefs.sync
    var webDavUrl by rememberSaveable(saved.webDavUrl) { mutableStateOf(saved.webDavUrl) }
    var username by rememberSaveable(saved.webDavUsername) { mutableStateOf(saved.webDavUsername) }
    var password by remember(saved.webDavPassword) { mutableStateOf(saved.webDavPassword) }
    var syncCards by rememberSaveable(saved.syncCards) { mutableStateOf(saved.syncCards) }
    var syncChats by rememberSaveable(saved.syncChats) { mutableStateOf(saved.syncChats) }
    var syncWorlds by rememberSaveable(saved.syncWorlds) { mutableStateOf(saved.syncWorlds) }
    var syncPresets by rememberSaveable(saved.syncPresets) { mutableStateOf(saved.syncPresets) }
    var policy by rememberSaveable(saved.conflictPolicy) { mutableStateOf(saved.conflictPolicy) }

    fun draft() = saved.copy(
        webDavUrl = webDavUrl.trim(),
        webDavUsername = username.trim(),
        webDavPassword = password,
        syncCards = syncCards,
        syncChats = syncChats,
        syncWorlds = syncWorlds,
        syncPresets = syncPresets,
        conflictPolicy = policy,
    )

    BackHandler { if (!state.busy) close() }

    Form {
        TextButton(close, enabled = !state.busy) { Text("返回设置") }
        FormHeading(
            "WebDAV 云同步与跨设备联动",
            "支持坚果云、Nextcloud、AList、群晖/NAS 等标准 WebDAV 目录，按内容哈希增量上传与下载角色卡、聊天记录、世界书与提示词预设。",
        )

        if (saved.lastSyncAt > 0L || saved.lastSyncSummary.isNotBlank()) {
            val formattedTime = remember(saved.lastSyncAt) {
                if (saved.lastSyncAt > 0L) {
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(saved.lastSyncAt))
                } else ""
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("最近同步状态", style = MaterialTheme.typography.titleSmall)
                    if (formattedTime.isNotBlank()) {
                        Text("时间：$formattedTime", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (saved.lastSyncSummary.isNotBlank()) {
                        Text(saved.lastSyncSummary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Field(
            "WebDAV 目录地址",
            webDavUrl,
            { webDavUrl = it },
            placeholder = "https://dav.jianguoyun.com/dav/TavernNative/",
            enabled = !state.busy,
            type = KeyboardType.Uri,
        )
        Field(
            "用户名",
            username,
            { username = it },
            placeholder = "邮箱或账号名",
            enabled = !state.busy,
        )
        Field(
            "密码 / 应用专用密码",
            password,
            { password = it },
            placeholder = "本地使用 Android Keystore AES-GCM 加密保存",
            secret = true,
            enabled = !state.busy,
        )

        SelectField(
            "双方均修改时的冲突策略",
            policy,
            SyncConflictPolicy.entries,
            SyncConflictPolicy::label,
            !state.busy,
        ) { policy = it }

        HorizontalDivider()
        FormHeading("同步数据类型")
        GroupToggle("角色卡 (.png)", syncCards, !state.busy) { syncCards = it }
        GroupToggle("单聊对话记录 (.jsonl)", syncChats, !state.busy) { syncChats = it }
        GroupToggle("世界书 (.json)", syncWorlds, !state.busy) { syncWorlds = it }
        GroupToggle("提示词预设 (.json)", syncPresets, !state.busy) { syncPresets = it }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { model.testWebDavConnection(draft()) },
                enabled = !state.busy && draft().configured,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Outlined.VerifiedUser, null)
                Spacer(Modifier.width(6.dp))
                Text("测试连接")
            }
            OutlinedButton(
                onClick = { model.saveSyncSettings(draft()) {} },
                enabled = !state.busy,
                modifier = Modifier.weight(1f),
            ) {
                Text("保存配置")
            }
        }

        Button(
            onClick = { model.runWebDavSync(draft()) },
            enabled = !state.busy && draft().configured,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.CloudSync, null)
            Spacer(Modifier.width(8.dp))
            Text("立即双向增量同步")
        }

        if (state.prefs.server.isNotBlank()) {
            HorizontalDivider()
            FormHeading(
                "局域网 SillyTavern 快捷同步",
                "检测到已保存桌面端地址：${state.prefs.server}。如需修改地址或凭据，请在首页 Server 页编辑。",
            )
            OutlinedButton(
                onClick = { model.syncLanServer(state.prefs.server, state.prefs.handle, "", "", "", includeChats = true) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Sync, null)
                Spacer(Modifier.width(8.dp))
                Text("与上次连接的桌面端双向同步聊天")
            }
        }
    }
}
