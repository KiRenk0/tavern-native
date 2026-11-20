package app.tavern.nativeclient

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal object ChatMediaArchive {
    private const val TRANSCRIPT = "chat.jsonl"
    private const val TOTAL_LIMIT = 256L * 1024 * 1024
    private const val TRANSCRIPT_LIMIT = 24 * 1024 * 1024
    private val signature = byteArrayOf(0x50, 0x4b, 0x03, 0x04)

    fun isArchive(header: ByteArray) = header.contentEquals(signature)

    private fun images(chat: Chat) = (chat.messages.flatMap(Message::imageAssets) + listOfNotNull(chat.options.background)).distinctBy(ChatImage::filename)
        .associateBy { "images/${it.filename}" }

    private fun documents(chat: Chat) = chat.messages.flatMap(Message::documents).distinctBy(ChatDocument::filename)
        .associateBy { "documents/${it.filename}" }

    fun write(output: OutputStream, transcript: ByteArray, chat: Chat,
              readDocument: (ChatDocument) -> ByteArray? = { null }, read: (ChatImage) -> ByteArray?) {
        require(transcript.size <= TRANSCRIPT_LIMIT) { "聊天文本超过归档大小限制" }
        var total = 0L
        ZipOutputStream(output).use { zip ->
            fun add(name: String, bytes: ByteArray, maximum: Int) {
                require(bytes.size <= maximum && total + bytes.size <= TOTAL_LIMIT) { "聊天归档超过大小限制" }
                total += bytes.size
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            add(TRANSCRIPT, transcript, TRANSCRIPT_LIMIT)
            for ((name, image) in images(chat)) {
                val data = requireNotNull(read(image)) { "归档缺少图片 ${image.name}" }
                add(name, data, MAX_STORED_IMAGE)
            }
            for ((name, document) in documents(chat)) {
                val data = requireNotNull(readDocument(document)) { "归档缺少文档 ${document.name}" }
                add(name, data, MAX_STORED_CHAT_DOCUMENT)
            }
        }
    }

    fun read(input: InputStream, parse: (ByteArray) -> Chat,
             restoreDocument: (ChatDocument, ByteArray) -> Unit = { _, _ -> error("缺少文档写入目标") },
             restore: (ChatImage, ByteArray) -> Unit): Chat =
        ZipInputStream(input).use { zip ->
            require(zip.nextEntry?.name == TRANSCRIPT) { "无法读取聊天 ZIP 归档" }
            val transcript = zip.readLimited(TRANSCRIPT_LIMIT)
            var total = transcript.size.toLong()
            val chat = parse(transcript)
            val remainingImages = images(chat).toMutableMap()
            val remainingDocuments = documents(chat).toMutableMap()
            while (true) {
                val entry = zip.nextEntry ?: break
                val image = remainingImages.remove(entry.name)
                val document = remainingDocuments.remove(entry.name)
                require(image != null || document != null) { "聊天归档包含无效的附件条目" }
                val bytes = zip.readLimited(if (image != null) MAX_STORED_IMAGE else MAX_STORED_CHAT_DOCUMENT)
                total += bytes.size
                require(total <= TOTAL_LIMIT) { "聊天归档超过 256 MB" }
                if (image != null) restore(image, bytes) else restoreDocument(requireNotNull(document), bytes)
            }
            require(remainingImages.isEmpty() && remainingDocuments.isEmpty()) { "聊天归档不含完整的附件文件" }
            chat
        }
}
