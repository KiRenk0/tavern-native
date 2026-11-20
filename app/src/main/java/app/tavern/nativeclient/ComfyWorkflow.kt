package app.tavern.nativeclient

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

internal const val DEFAULT_COMFY_WORKFLOW = "comfy-default"
internal const val AVATAR_COMFY_WORKFLOW = "comfy-avatar"
internal const val MAX_COMFY_WORKFLOW_BYTES = 1024 * 1024
private val placeholder = Regex("%([A-Za-z0-9_-]+)%")
private val baseParameters = setOf(
    "prompt", "negative_prompt", "seed", "denoise", "clip_skip", "model", "vae", "sampler",
    "scheduler", "steps", "scale", "cfg", "width", "height", "user_avatar", "char_avatar", "reference_image",
) + (1..MAX_CHAT_IMAGES).map { "reference_image_$it" }

internal data class ComfyWorkflow(
    val id: String, val name: String, val source: String, val replacements: Map<String, String> = emptyMap(),
) {
    val isBuiltIn get() = id == DEFAULT_COMFY_WORKFLOW || id == AVATAR_COMFY_WORKFLOW

    fun validated(): ComfyWorkflow {
        require(name.isNotBlank()) { "请输入工作流名称" }
        parseComfyWorkflow(source)
        require(replacements.keys.all { key -> placeholder.matches("%$key%") && key !in baseParameters }) {
            "自定义占位符可用英文字母、数字、下划线和连字符，且需要避开内置名称"
        }
        require(JSONObject(replacements).toString().toByteArray(Charsets.UTF_8).size <= MAX_COMFY_WORKFLOW_BYTES / 4) {
            "自定义占位符内容过大"
        }
        return copy(name = name.trim(), source = source.trim().removePrefix("\uFEFF"))
    }
}

internal data class ComfyGraph(val document: JSONObject, val graph: JSONObject) {
    val keys: Set<String> get() = buildSet {
        mapWorkflow(document) { value ->
            placeholder.findAll(value).forEach { add(it.groupValues[1]) }
            value
        }
    }

    fun fill(values: Map<String, Any?>): ComfyGraph {
        val resolved = mapWorkflow(document) { value ->
            val entire = placeholder.matchEntire(value)?.groupValues?.get(1)
            if (entire != null) {
                requireNotNull(values[entire]) { "工作流需要填写 %$entire%" }
            } else placeholder.replace(value) { match ->
                val key = match.groupValues[1]
                requireNotNull(values[key]) { "工作流需要填写 %$key%" }.toString()
            }
        } as JSONObject
        return ComfyGraph(resolved, comfyGraph(resolved))
    }

    fun runPod(images: JSONArray): JSONObject {
        val input = document.optJSONObject("input")?.takeIf { it.has("workflow") }
        if (input != null) {
            if (images.length() > 0) input.put("images", JSONArray(
                input.optJSONArray("images")?.objects().orEmpty() + images.objects(),
            ))
            return document
        }
        return jsonOf("input" to jsonOf("workflow" to graph, "images" to images.takeIf { it.length() > 0 }))
    }
}

internal fun parseComfyWorkflow(raw: String): ComfyGraph {
    require(raw.toByteArray(Charsets.UTF_8).size <= MAX_COMFY_WORKFLOW_BYTES) { "工作流最大支持 1 MB" }
    val source = raw.trim().removePrefix("\uFEFF").trim()
    require(source.startsWith('{')) { "请选择以 JSON 对象保存的 ComfyUI API 工作流" }
    val root = boundedJsonObject(source, "工作流 JSON 格式无效")
    val graph = comfyGraph(root)
    require(!graph.has("nodes") && graph.length() > 0 && graph.keys().asSequence().all { key ->
        graph.optJSONObject(key)?.let { it.string("class_type").isNotBlank() && it.optJSONObject("inputs") != null } == true
    }) { "请从 ComfyUI 导出 API 格式的工作流 JSON" }
    return ComfyGraph(root, graph)
}

private fun comfyGraph(root: JSONObject) =
    root.optJSONObject("input")?.optJSONObject("workflow") ?: root.optJSONObject("prompt") ?: root

private fun mapWorkflow(value: Any?, strings: (String) -> Any): Any? = when (value) {
    is JSONObject -> JSONObject().apply { value.keys().forEach { key -> put(key, mapWorkflow(value.opt(key), strings)) } }
    is JSONArray -> JSONArray().apply { for (index in 0 until value.length()) put(mapWorkflow(value.opt(index), strings)) }
    is String -> strings(value)
    else -> value
}

internal fun ImageRequest.comfyValues(): Map<String, Any?> = mapOf(
    "prompt" to prompt, "negative_prompt" to tuning.negative, "model" to model.takeIf(String::isNotBlank),
    "seed" to (tuning.seed?.takeIf { it >= 0 } ?: Random.nextLong(1L shl 53)),
    "steps" to (tuning.steps ?: 20), "scale" to (tuning.cfg ?: 7.0), "cfg" to (tuning.cfg ?: 7.0),
    "clip_skip" to -(tuning.skip ?: 1), "denoise" to (tuning.denoise ?: 1.0),
    "width" to (tuning.width ?: shape.width), "height" to (tuning.height ?: shape.height),
    "sampler" to tuning.sampler.ifBlank { "euler" }, "scheduler" to tuning.scheduler.ifBlank { "normal" },
    "vae" to tuning.vae.takeIf(String::isNotBlank),
)

internal val defaultComfyWorkflow = ComfyWorkflow(DEFAULT_COMFY_WORKFLOW, "默认文生图", """
    {
      "3": {"class_type": "KSampler", "inputs": {
        "cfg": "%scale%", "denoise": "%denoise%", "latent_image": ["5", 0], "model": ["4", 0],
        "negative": ["7", 0], "positive": ["6", 0], "sampler_name": "%sampler%",
        "scheduler": "%scheduler%", "seed": "%seed%", "steps": "%steps%"
      }},
      "4": {"class_type": "CheckpointLoaderSimple", "inputs": {"ckpt_name": "%model%"}},
      "5": {"class_type": "EmptyLatentImage", "inputs": {"batch_size": 1, "height": "%height%", "width": "%width%"}},
      "6": {"class_type": "CLIPTextEncode", "inputs": {"clip": ["4", 1], "text": "%prompt%"}},
      "7": {"class_type": "CLIPTextEncode", "inputs": {"clip": ["4", 1], "text": "%negative_prompt%"}},
      "8": {"class_type": "VAEDecode", "inputs": {"samples": ["3", 0], "vae": ["4", 2]}},
      "9": {"class_type": "SaveImage", "inputs": {"filename_prefix": "Tavern-Native", "images": ["8", 0]}}
    }
""".trimIndent())

internal val builtInComfyWorkflows by lazy {
    val graph = JSONObject(defaultComfyWorkflow.source)
    graph.getJSONObject("3").getJSONObject("inputs").put("latent_image", JSONArray(listOf("12", 0)))
    graph.remove("5")
    graph.put("10", jsonOf("class_type" to "ETN_LoadImageBase64", "inputs" to jsonOf("image" to "%char_avatar%")))
    graph.put("12", jsonOf("class_type" to "VAEEncode", "inputs" to
        jsonOf("pixels" to JSONArray(listOf("13", 0)), "vae" to JSONArray(listOf("4", 2)))))
    graph.put("13", jsonOf("class_type" to "ImageScale", "inputs" to jsonOf(
        "image" to JSONArray(listOf("10", 0)), "upscale_method" to "bicubic", "crop" to "center",
        "width" to "%width%", "height" to "%height%",
    )))
    listOf(defaultComfyWorkflow, ComfyWorkflow(AVATAR_COMFY_WORKFLOW, "角色头像图生图", graph.toString(2)))
}
