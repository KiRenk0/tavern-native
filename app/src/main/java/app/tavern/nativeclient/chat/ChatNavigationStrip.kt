package app.tavern.nativeclient.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tavern.nativeclient.Card
import app.tavern.nativeclient.Chat
import app.tavern.nativeclient.GroupPortrait
import app.tavern.nativeclient.LibraryItem
import app.tavern.nativeclient.Portrait
import app.tavern.nativeclient.TavernState
import app.tavern.nativeclient.TavernViewModel
import app.tavern.nativeclient.isTemporary

@Composable
internal fun FavoriteChatStrip(chat: Chat, state: TavernState, model: TavernViewModel, enabled: Boolean, open: (LibraryItem) -> Unit) {
    val library = state.prefs.library
    val favorites = remember(library, state.cards, state.groups) {
        library.entities(state.cards, state.groups).filter(LibraryItem::favorite)
    }
    if (favorites.isEmpty()) return
    LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(favorites, key = LibraryItem::id) { entry ->
            val selected = !chat.isTemporary && (entry.card?.id?.let { it == chat.cardId } == true ||
                entry.group?.id?.let { it == chat.groupId } == true)
            FilterChip(selected = selected, onClick = { if (!selected) open(entry) }, enabled = enabled,
                label = { Text(entry.name, Modifier.widthIn(max = 160.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) }, leadingIcon = {
                    entry.card?.let { Portrait(it, model.portrait(it), 22.dp, round = true) }
                    entry.group?.let { GroupPortrait(it, state.cards, model::portrait, 22.dp) }
                })
        }
    }
}

@Composable
internal fun GroupReplyQueue(speakers: List<Card>) {
    if (speakers.isEmpty()) return
    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("发言队列", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyRow(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(speakers, key = { _, card -> card.id }) { index, card ->
                val scheme = MaterialTheme.colorScheme
                Surface(shape = RoundedCornerShape(8.dp), color = if (index == 0) scheme.secondaryContainer else scheme.surfaceContainer,
                    contentColor = if (index == 0) scheme.onSecondaryContainer else scheme.onSurface) {
                    Text(if (index == 0) "发言中 · ${card.name}" else "${index + 1}. ${card.name}",
                        Modifier.widthIn(max = 180.dp).padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
