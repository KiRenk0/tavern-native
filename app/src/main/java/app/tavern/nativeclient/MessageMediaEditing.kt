package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject

internal fun Message.withoutDisplayedMedia(filename: String? = null): Message {
    val removedImages = displayImages.filter { filename == null || it.filename == filename }
    val removedVideos = videos.filter { filename == null || it.filename == filename }
    if (removedImages.isEmpty() && removedVideos.isEmpty()) return this

    val imageIds = removedImages.mapTo(hashSetOf(), ChatImage::id)
    val nextReply = replyMedia?.available { it.id !in imageIds }
    val remainingDrawing = drawing?.takeIf { filename != null }?.let { series ->
        val remaining = series.images.withIndex().filter { it.value.id !in imageIds }
        series.copy(images = remaining.map { it.value },
            selected = remaining.indexOfLast { it.index <= series.selected }.coerceAtLeast(0))
            .takeIf { it.images.isNotEmpty() }
    }
    val result = copy(
        raw = withoutOriginalMedia(removedImages.isNotEmpty(), removedVideos.isNotEmpty()),
        images = images.filterNot { it.id in imageIds },
        videos = videos - removedVideos.toSet(),
        drawing = remainingDrawing,
        replyMedia = nextReply,
        variantReplyMedia = if (nextReply == replyMedia) variantReplyMedia
            else List(variants.size) { if (it == index) nextReply else replyMediaAt(it) },
    )
    val retained = result.imageAssets().mapTo(hashSetOf(), ChatImage::id)
    return result.copy(mediaIndex = result.selectedMediaIndex, imageCaptions = imageCaptions.filterKeys(retained::contains))
}

private fun Message.withoutOriginalMedia(imagesRemoved: Boolean, videosRemoved: Boolean): String? {
    val row = raw?.let(::JSONObject) ?: return null
    val extra = row.optJSONObject("extra") ?: return raw
    val media = extra.optJSONArray("media")
    if (media != null) {
        val remaining = media.objects().filterNot {
            val type = it.string("type")
            imagesRemoved && type in listOf("", "image") || videosRemoved && type.equals("video", true)
        }
        if (remaining.isEmpty()) extra.remove("media") else extra.put("media", JSONArray(remaining))
    }
    if (imagesRemoved && extra.has("image")) {
        listOf("image", "inline_image", "title", "append_title", "captioned").forEach(extra::remove)
    }
    return row.toString()
}
