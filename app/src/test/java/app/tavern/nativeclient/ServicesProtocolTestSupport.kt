package app.tavern.nativeclient

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

internal fun startProtocolTestServer(
    handler: (path: String, headers: Map<String, String>, body: String) -> Pair<Int, Pair<String, String>>,
): Pair<HttpServer, String> {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { ex ->
        val reqBody = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        val headers = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") }
        val path = ex.requestURI.rawPath + (ex.requestURI.rawQuery?.let { "?$it" } ?: "")
        val (code, payload) = handler(path, headers, reqBody)
        val (contentType, bodyText) = payload
        val bytes = bodyText.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", contentType)
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
    server.start()
    return server to "http://127.0.0.1:${server.address.port}"
}
