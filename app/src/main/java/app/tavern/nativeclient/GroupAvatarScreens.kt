package app.tavern.nativeclient

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

@Composable
internal fun GroupPortrait(group: TavernGroup, cards: List<Card>, portrait: (Card) -> File, size: Dp) {
    val custom by produceState<Bitmap?>(null, group.avatar) {
        value = null
        value = withContext(Dispatchers.IO) { runCatching { group.avatar?.decode() }.getOrNull() }
    }
    val members = remember(group.memberIds, cards) { group.members(cards).filter(Card::hasAvatar).take(4) }
    val image = custom
    Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center) {
        if (image != null) {
            Image(image.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else when (members.size) {
            0 -> Icon(Icons.Outlined.Groups, null, Modifier.size(size * 0.52f))
            1 -> GroupPortraitTile(portrait(members.first()), Modifier.fillMaxSize())
            2 -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                members.forEach { member ->
                    GroupPortraitTile(portrait(member), Modifier.weight(1f).fillMaxHeight())
                }
            }
            3 -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                GroupPortraitTile(portrait(members.first()), Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    for (member in members.drop(1)) GroupPortraitTile(portrait(member), Modifier.weight(1f).fillMaxWidth())
                }
            }
            else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                members.chunked(2).forEach { row ->
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                        row.forEach { member ->
                            GroupPortraitTile(portrait(member), Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupPortraitTile(file: File, modifier: Modifier) {
    val image = rememberImagePreview(file, 160)
    if (image != null) Image(image.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest))
}

@Composable
internal fun GroupAvatarCropDialog(uri: Uri, dismiss: () -> Unit, accept: (GroupAvatar) -> Unit) {
    val resolver = LocalContext.current.contentResolver
    AvatarCropDialog(uri, "裁剪群组头像", {
        requireNotNull(resolver.openInputStream(uri)) { "无法打开所选图片" }.use { it.readLimited(MAX_IMAGE_SOURCE) }
    }, dismiss, accept)
}

@Composable
internal fun AvatarCropDialog(source: Any, title: String, load: () -> ByteArray, dismiss: () -> Unit, accept: (GroupAvatar) -> Unit) {
    val scope = rememberCoroutineScope()
    var crop by remember(source) { mutableStateOf(GroupAvatarCrop()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val loading by produceState<Result<Bitmap>?>(null, source) {
        value = withContext(Dispatchers.IO) {
            runCatching { decodeBoundedImage(load(), 1024) }
        }
    }
    val bitmap = loading?.getOrNull()
    AlertDialog(
        onDismissRequest = { if (!saving) dismiss() },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (bitmap != null) {
                    val image = remember(bitmap) { bitmap.asImageBitmap() }
                    Canvas(
                        Modifier.widthIn(max = 268.dp).fillMaxWidth().aspectRatio(1f)
                            .clip(CircleShape).background(Color.White).pointerInput(bitmap) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    val edge = size.width.toFloat().coerceAtLeast(1f)
                                    crop = crop.move(bitmap.width, bitmap.height, pan.x / edge, pan.y / edge, zoom)
                                }
                            }
                    ) {
                        val (left, top, side) = crop.square(bitmap.width, bitmap.height)
                        drawImage(image, srcOffset = IntOffset(left, top), srcSize = IntSize(side, side),
                            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Medium)
                    }
                    Text("拖动图片调整位置", style = MaterialTheme.typography.bodySmall)
                    Slider(crop.zoom, { crop = crop.move(bitmap.width, bitmap.height, 0f, 0f, it / crop.zoom) },
                        modifier = Modifier.semantics { contentDescription = "缩放头像" },
                        enabled = !saving, valueRange = 1f..4f)
                    TextButton({ crop = GroupAvatarCrop() }, enabled = !saving) { Text("重置裁剪") }
                } else if (loading == null) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                } else {
                    Text(loading?.exceptionOrNull()?.localizedMessage ?: "无法读取头像")
                }
                if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton({
                val chosen = crop
                saving = true
                scope.launch {
                    val result = withContext(Dispatchers.Default) { runCatching { GroupAvatar.create(requireNotNull(bitmap), chosen) } }
                    saving = false
                    result.fold(accept) { error = it.localizedMessage ?: "无法保存头像" }
                }
            }, enabled = bitmap != null && !saving) { Text("使用头像") }
        },
        dismissButton = { TextButton(dismiss, enabled = !saving) { Text("取消") } },
    )
}
