package app.tavern.nativeclient

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.util.Base64

internal data class ComfyInputs(
    val workflow: ComfyWorkflow,
    val userAvatar: () -> ByteArray? = { null },
    val characterAvatar: () -> ByteArray? = { null },
    val expandCustom: (String) -> String = { it },
)

internal data class ComfyCatalog(
    val checkpoints: List<String>, val vaes: List<String>, val samplers: List<String>, val schedulers: List<String>,
)

internal class ComfyService(private val pollDelay: Long = 700, private val generationTimeout: Long = 600_000) {
    suspend fun catalog(provider: Provider): ComfyCatalog {
        val info = json("${root(provider)}/object_info", headers = auth(provider, false))
        fun options(node: String, input: String): List<String> =
            info.optJSONObject(node)?.optJSONObject("input")?.optJSONObject("required")
                ?.optJSONArray(input)?.optJSONArray(0).strings()
        val models = options("CheckpointLoaderSimple", "ckpt_name") + options("UNETLoader", "unet_name") +
            options("UnetLoaderGGUF", "unet_name")
        return ComfyCatalog(models.distinct(), options("VAELoader", "vae_name"),
            options("KSampler", "sampler_name"), options("KSampler", "scheduler"))
    }

    suspend fun test(provider: Provider, runPod: Boolean) {
        val result = json("${root(provider)}/${if (runPod) "health" else "system_stats"}", headers = auth(provider, runPod))
        check(result.has(if (runPod) "workers" else "system")) { "服务未返回预期的 ComfyUI 状态" }
    }

    suspend fun generate(options: ImageRequest, inputs: ComfyInputs, readImage: (ChatImage) -> ByteArray?): ByteArray {
        val runPod = options.protocol == ImageProtocol.COMFY_RUNPOD
        val root = root(options.provider)
        val headers = auth(options.provider, runPod)
        val workflow = parseComfyWorkflow(inputs.workflow.source)
        val keys = workflow.keys
        val values = options.comfyValues().toMutableMap()
        values.putAll(inputs.workflow.replacements.mapValues { (_, value) -> inputs.expandCustom(value) })
        if ("user_avatar" in keys) values["user_avatar"] = avatar(inputs.userAvatar())
        if ("char_avatar" in keys) values["char_avatar"] = avatar(inputs.characterAvatar())
        val neededReferences = (1..MAX_CHAT_IMAGES).mapNotNull { index ->
            val names = listOfNotNull("reference_image_$index", "reference_image".takeIf { index == 1 }).filter { it in keys }
            if (names.isEmpty()) null else names to requireNotNull(options.references.getOrNull(index - 1)) {
                "工作流需要第 $index 张参考图片"
            }
        }

        // Validate the remaining bindings before uploading any references.
        val withoutUploads = values + neededReferences.flatMap { (names, image) -> names.map { it to image.filename } }
        val graph = workflow.fill(withoutUploads)
        val images = JSONArray()
        for ((names, image) in neededReferences) {
            val bytes = requireNotNull(readImage(image)) { "缺少参考图片 ${image.name}" }
            val filename = if (runPod) {
                val data = Base64.getEncoder().encodeToString(bytes)
                images.put(jsonOf("name" to image.filename, "image" to "data:${image.format.mime};base64,$data"))
                image.filename
            } else uploadImage(root, headers, image, bytes)
            names.forEach { values[it] = filename }
        }
        val filled = if (runPod || neededReferences.isEmpty()) graph else workflow.fill(values)
        return if (runPod) serverless(root, headers, filled.runPod(images)) else selfHosted(root, headers, filled.graph)
    }

    private suspend fun uploadImage(root: String, headers: Map<String, String>, image: ChatImage, bytes: ByteArray): String {
        val boundary = "TavernNative${newId()}"
        val parts = ByteArrayOutputStream(bytes.size + 512).use { output ->
            fun line(text: String) = output.write("$text\r\n".toByteArray(Charsets.UTF_8))
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"image\"; filename=\"tavern-${newId()}.${image.format.suffix}\"")
            line("Content-Type: ${image.format.mime}")
            line("")
            output.write(bytes)
            line("")
            line("--$boundary--")
            HttpUpload("multipart/form-data; boundary=$boundary", output.toByteArray())
        }
        val result = request("$root/upload/image", headers = headers, upload = parts) { stream, _ ->
            JSONObject(stream.readLimited(1024 * 1024).toString(Charsets.UTF_8))
        }
        val name = result.string("name")
        check(name.isNotBlank()) { "ComfyUI 未返回上传文件名" }
        return listOf(result.string("subfolder").trim('/'), name).filter(String::isNotBlank).joinToString("/")
    }

    private suspend fun selfHosted(root: String, headers: Map<String, String>, graph: JSONObject): ByteArray {
        val id = json("$root/prompt", jsonOf("prompt" to graph), headers).string("prompt_id").takeIf(String::isNotBlank)
            ?: error("ComfyUI 未返回任务 ID")
        val segment = encode(id)
        return pollJob(cancel = { cancelComfy(root, headers, id) }) {
            val item = json("$root/history/$segment", headers = headers).optJSONObject(id) ?: return@pollJob null
            val status = item.optJSONObject("status")
            if (status?.string("status_str") == "error") {
                val messages = status.optJSONArray("messages")
                val nodeErrors = (0 until (messages?.length() ?: 0)).mapNotNull { index ->
                    val itemError = messages?.optJSONArray(index)
                    if (itemError?.optString(0) != "execution_error") null else itemError.optJSONObject(1)?.let {
                        listOf(it.string("node_type"), it.string("node_id"), it.string("exception_message"))
                            .filter(String::isNotBlank).joinToString(" · ")
                    }
                }.joinToString("\n")
                error(nodeErrors.take(1200).ifBlank { "ComfyUI 工作流执行失败" })
            }
            val outputs = item.optJSONObject("outputs") ?: JSONObject()
            val nodes = outputs.keys().asSequence().mapNotNull(outputs::optJSONObject).toList()
            val output = listOf("images", "gifs").firstNotNullOfOrNull { key ->
                nodes.firstNotNullOfOrNull { node ->
                    node.optJSONArray(key)?.objects()?.firstOrNull { it.string("filename").isNotBlank() }
                }
            } ?: error("工作流没有输出图片。请检查 SaveImage 或 PreviewImage 节点。")
            val query = listOf("filename" to output.string("filename"), "subfolder" to output.string("subfolder"),
                "type" to output.string("type").ifBlank { "output" }).joinToString("&") { (key, value) -> "$key=${encode(value)}" }
            request("$root/view?$query", headers = headers + ("Accept" to "image/*")) { stream, _ -> stream.readLimited(MAX_IMAGE_SOURCE) }
        }
    }

    private suspend fun serverless(root: String, headers: Map<String, String>, body: JSONObject): ByteArray {
        require(body.toString().toByteArray(Charsets.UTF_8).size <= 10_000_000) { "RunPod 请求超过 10 MB，请减少参考图片" }
        val initial = json("$root/run", body, headers)
        runPodImage(root, initial)?.let { return it }
        val id = encode(initial.string("id").takeIf(String::isNotBlank) ?: error("RunPod 未返回任务 ID"))
        return pollJob(cancel = { request("$root/cancel/$id", JSONObject(), headers) { _, _ -> Unit } }) {
            runPodImage(root, json("$root/status/$id", headers = headers))
        }
    }

    private suspend fun runPodImage(root: String, status: JSONObject): ByteArray? {
        val state = status.string("status")
        if (state in setOf("FAILED", "CANCELLED", "TIMED_OUT")) {
            error(status.string("error").take(450).ifBlank { "RunPod 任务结束 · $state" })
        }
        val output = status.optJSONObject("output")
        val image = output?.optJSONArray("images")?.opt(0)
        val data = when (image) {
            is JSONObject -> image.string("data")
            is String -> image
            else -> output?.string("message").orEmpty()
        }
        if (data.isBlank()) {
            check(state != "COMPLETED") { "RunPod 工作流已完成，但没有图片输出" }
            return null
        }
        if (data.startsWith("https://") || data.startsWith("http://")) {
            val url = URI(data)
            val endpoint = URI(root)
            require(url.host != null && url.rawUserInfo == null && url.rawFragment == null &&
                (url.scheme == "https" || url.scheme == endpoint.scheme && url.host.equals(endpoint.host, true) && url.port == endpoint.port)) {
                "RunPod 返回了无效的图片地址"
            }
            return request(url.toString(), headers = mapOf("Accept" to "image/*")) { stream, _ -> stream.readLimited(MAX_IMAGE_SOURCE) }
        }
        return decodeGeneratedImage(data)
    }

    private suspend fun pollJob(cancel: suspend () -> Unit, readImage: suspend () -> ByteArray?): ByteArray {
        suspend fun stop() = withContext(NonCancellable) {
            withTimeoutOrNull(3_000) { runCatching { cancel() } }
        }
        val result = try {
            withTimeoutOrNull(generationTimeout) {
                var image = readImage()
                while (image == null) {
                    delay(pollDelay)
                    image = readImage()
                }
                image
            }
        } catch (e: CancellationException) {
            stop()
            throw e
        }
        if (result != null) return result
        stop()
        error("等待 ComfyUI 图片超时")
    }

    private suspend fun cancelComfy(root: String, headers: Map<String, String>, id: String) {
        val api = if (root.endsWith("/api")) root else "$root/api"
        try {
            request("$api/jobs/${encode(id)}/cancel", JSONObject(), headers) { _, _ -> Unit }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Older servers can safely remove a single pending job by its ID.
            request("$root/queue", jsonOf("delete" to JSONArray(listOf(id))), headers) { _, _ -> Unit }
        }
    }

    private fun root(provider: Provider): String {
        val address = checkedAddress(provider.endpoint).toString().trimEnd('/')
        return listOf("/prompt", "/runsync", "/run", "/health").firstOrNull(address::endsWith)?.let(address::removeSuffix)
            ?: address
    }

    private fun auth(provider: Provider, runPod: Boolean): Map<String, String> {
        val key = provider.apiKey.trim()
        if (runPod) require(key.isNotBlank()) { "请填写 RunPod API Key" }
        if (key.isBlank()) return emptyMap()
        return if (!runPod && ':' in key) mapOf("Authorization" to "Basic " + Base64.getEncoder().encodeToString(key.toByteArray()))
        else mapOf("Authorization" to "Bearer $key")
    }

    private suspend fun json(url: String, body: JSONObject? = null, headers: Map<String, String> = emptyMap()) =
        request(url, body, headers, readTimeout = 45_000) { stream, _ -> JSONObject(stream.readLimited().toString(Charsets.UTF_8)) }

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun avatar(bytes: ByteArray?) = bytes?.let { Base64.getEncoder().encodeToString(it) }
        ?: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aLXsAAAAASUVORK5CYII="
}
