package app.tavern.nativeclient

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun ConversationLayout(chat: Chat, state: TavernState, model: TavernViewModel, showStage: Boolean,
                                speakerId: String?, modifier: Modifier, transcript: @Composable () -> Unit) {
    if (!state.prefs.visualNovel) {
        Box(modifier.fillMaxWidth()) { transcript() }
        return
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val wide = maxWidth >= 600.dp
        val background = state.prefs.backgrounds.effective(chat)
        val hasSpace = maxHeight >= if (wide) 120.dp else 192.dp
        val cast = if (showStage && hasSpace) {
            remember(chat, state.cards, state.group, state.cardId) { state.visualNovelCast(chat) }
        } else null
        if (cast == null || (cast.subjects.isEmpty() && background == null)) {
            background?.let { ConversationBackground(it, model.backgroundFile(it), Modifier.matchParentSize(), state.prefs.backgrounds.fit) }
            transcript()
        } else if (wide) {
            Row(Modifier.fillMaxSize()) {
                VisualNovelStage(chat, state, model, cast, speakerId, Modifier.weight(0.46f).fillMaxHeight())
                Box(Modifier.weight(0.54f).fillMaxHeight()) { transcript() }
            }
        } else {
            val stageHeight = (maxHeight * 0.51f).coerceAtMost(400.dp)
            Column(Modifier.fillMaxSize()) {
                VisualNovelStage(chat, state, model, cast, speakerId, Modifier.fillMaxWidth().height(stageHeight))
                Box(Modifier.weight(1f).fillMaxWidth()) { transcript() }
            }
        }
    }
}

@Composable
private fun VisualNovelStage(chat: Chat, state: TavernState, model: TavernViewModel, cast: VisualNovelCast, speakerId: String?,
                             modifier: Modifier) {
    val active = cast.focusedSpeaker(speakerId)
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(modifier.background(colors.surfaceContainerHighest)) {
        state.prefs.backgrounds.effective(chat)?.let { image ->
            ConversationBackground(image, model.backgroundFile(image), Modifier.matchParentSize(), state.prefs.backgrounds.fit, opacity = 1f)
        }
        val count = cast.subjects.size
        if (count == 0) return@BoxWithConstraints
        val list = rememberLazyListState()
        val spacing = 8.dp
        val available = (maxWidth - 24.dp - spacing * (count - 1)) / count
        val actorWidth = available.coerceIn(94.dp, 320.dp)
        val activeIndex = cast.subjects.indexOfFirst { it.card.id == active }
        LaunchedEffect(activeIndex, actorWidth, count) {
            if (activeIndex >= 0 && list.layoutInfo.visibleItemsInfo.none { item ->
                    item.index == activeIndex && item.offset >= list.layoutInfo.viewportStartOffset &&
                        item.offset + item.size <= list.layoutInfo.viewportEndOffset
                }) list.scrollToItem(activeIndex)
        }
        LazyRow(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(spacing, Alignment.CenterHorizontally)) {
            items(cast.subjects, key = { it.card.id }) { subject ->
                VisualNovelActor(subject, state, chat, model, active == subject.card.id,
                    active == null || active == subject.card.id, Modifier.width(actorWidth).fillParentMaxHeight())
            }
        }
    }
}

@Composable
private fun VisualNovelActor(subject: ExpressionSubject, state: TavernState, chat: Chat, model: TavernViewModel,
                             active: Boolean, lit: Boolean, modifier: Modifier) {
    val actor = subject.card
    val frame = rememberExpressionFrame(state, chat, subject, model, forScene = true)
    val sprite = frame?.image
    val emoji = frame?.emoji
    val colors = MaterialTheme.colorScheme
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth().alpha(if (lit) 1f else 0.68f), contentAlignment = Alignment.BottomCenter) {
            when {
                sprite != null -> ChatImageContent(sprite, model.imageFile(sprite), 480, Modifier.fillMaxSize(),
                    decorative = true, alignment = Alignment.BottomCenter)
                emoji != null -> Text(emoji, fontSize = 54.sp, modifier = Modifier.align(Alignment.Center))
                else -> {
                    val bitmap = if (actor.hasAvatar) rememberImagePreview(model.portrait(actor), 360) else null
                    if (bitmap != null) {
                        val preview = remember(bitmap) { bitmap.asImageBitmap() }
                        Image(preview, null, Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                            alignment = Alignment.BottomCenter, contentScale = ContentScale.Fit)
                    } else {
                        Icon(Icons.Outlined.Person, null, Modifier.size(50.dp).align(Alignment.Center),
                            tint = colors.onSurfaceVariant)
                    }
                }
            }
        }
        Surface(shape = RoundedCornerShape(20.dp),
            color = if (active) colors.primaryContainer else colors.surfaceContainer,
            contentColor = if (active) colors.onPrimaryContainer else colors.onSurface) {
            Text(actor.name, modifier = Modifier.semantics {
                if (active) stateDescription = "当前发言角色"
            }.padding(horizontal = 10.dp, vertical = 3.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium)
        }
    }
}
