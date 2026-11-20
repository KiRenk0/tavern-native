package app.tavern.nativeclient

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class SharedWorldMemoryTest {

    private class InMemoryVectorCache : VectorCache {
        private val store = mutableMapOf<String, ByteArray>()
        override fun readVectors(scope: String, space: String, keys: List<String>): Map<String, FloatArray> =
            keys.mapNotNull { key ->
                store["$scope|$space|$key"]?.let(::vectorFloats)?.let { key to it }
            }.toMap()

        override fun saveVectors(scope: String, space: String, values: Map<String, FloatArray>) {
            values.forEach { (key, vec) -> store["$scope|$space|$key"] = vectorBytes(vec) }
        }

        override fun clearVectors(scope: String) {
            store.keys.removeAll { it.startsWith("$scope|") }
        }
    }

    private fun testCard(id: String = "card-1", name: String = "艾琳", worldName: String = "艾尔登世界"): Card =
        Card(id, JSONObject().put("name", name).put("extensions", jsonOf("world" to worldName)).toString())

    @Test
    fun `hybrid shared world memory combines vector semantics with pin edit and block overrides`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/embeddings") { ex ->
            val req = JSONObject(ex.requestBody.readBytes().toString(Charsets.UTF_8))
            val inputs = req.getJSONArray("input").strings()
            val data = JSONArray()
            inputs.forEachIndexed { idx, text ->
                // Vector space: [astronomy/starlight, culinary/cooking, combat]
                val vec = when {
                    "星" in text || "银河" in text || "天文" in text -> listOf(1.0, 0.0, 0.0)
                    "料理" in text || "蛋糕" in text -> listOf(0.0, 1.0, 0.0)
                    else -> listOf(0.0, 0.0, 1.0)
                }
                data.put(jsonOf("index" to idx, "embedding" to JSONArray(vec)))
            }
            val bytes = jsonOf("data" to data).toString().toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val card = testCard()
            var world = WorldBook(id = "wb-1", name = "艾尔登世界", enabled = false).withSharedMemory(true)
            val active = Chat(id = "chat-active", cardId = card.id, title = "当前对话")

            // Semantic turn shares NO exact CJK bigrams with query "今晚去看银河" except concept!
            val semanticTurn = SharedWorldTurn(
                chatId = "chat-old-1", title = "天文台之夜", group = false, name = "艾琳",
                reply = "我最喜欢在山顶观测璀璨的繁星了。", userName = "旅者", userText = "我们去观星吧",
                time = 1000L, position = 1, relevance = 0, replyKey = "我最喜欢在山顶观测璀璨的繁星了。".memoryReplyKey(),
            )
            val unrelatedTurn = SharedWorldTurn(
                chatId = "chat-old-2", title = "厨房日常", group = true, name = "艾琳",
                reply = "刚烤好的草莓蛋糕味道真不错。", userName = "旅者", userText = "晚饭吃什么",
                time = 2000L, position = 1, relevance = 0, replyKey = "刚烤好的草莓蛋糕味道真不错。".memoryReplyKey(),
            )
            val blockedTurn = SharedWorldTurn(
                chatId = "chat-old-3", title = "被屏蔽的经历", group = false, name = "艾琳",
                reply = "这条记忆应当被屏蔽，不能发给模型。", userName = "旅者", userText = "秘密",
                time = 3000L, position = 1, relevance = 5, replyKey = "这条记忆应当被屏蔽，不能发给模型。".memoryReplyKey(),
            )

            // Apply overrides: pin one custom memory, edit semanticTurn's summary, block blockedTurn
            world = world.withMemoryOverrides(
                card.id,
                WorldMemoryOverrides(
                    pinned = listOf(PinnedWorldMemory(id = "pin-1", sourceChatId = "chat-old-0", sourceTitle = "核心约定", group = false, text = "艾琳曾答应一起去北方圣地。")),
                    blockedKeys = setOf(blockedTurn.replyKey),
                    editedTexts = mapOf(semanticTurn.replyKey to "艾琳曾在山顶天文台与你一起观星。"),
                ),
            )

            val embedProvider = Provider("emb", "LocalEmbed", "http://127.0.0.1:${server.address.port}", defaultModel = "test-emb")
            val source = EmbeddingSource.Remote(embedProvider, "test-emb")
            val result = selectSharedWorldMemoriesHybrid(
                world = world,
                card = card,
                active = active,
                candidates = listOf(unrelatedTurn, blockedTurn, semanticTurn),
                queryText = "今晚的银河好亮啊",
                embeddingSource = source,
                embeddingService = EmbeddingService(),
                vectorCache = InMemoryVectorCache(),
            )

            assertTrue(result.usedVectors)
            assertTrue(result.promptText.contains("艾琳曾答应一起去北方圣地。"))
            assertTrue(result.promptText.contains("艾琳曾在山顶天文台与你一起观星。"))
            assertFalse(result.promptText.contains("这条记忆应当被屏蔽"))
            assertEquals(1, result.blockedItems.size)
            // Semantic astronomy turn must outrank newer culinary turn despite 0 keyword overlap
            val unpinned = result.items.filterNot { it.pinned }
            assertEquals(semanticTurn.replyKey, unpinned.first().turn.replyKey)
            assertTrue(unpinned.first().semanticScore > 0.9f)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `promoteMemoryToLoreEntry adds valid entry and preserves isolation invariants`() {
        val card = testCard(id = "card-a", name = "赛琳娜")
        val world = WorldBook(id = "wb-1", name = "艾尔登世界").withSharedMemory(true)
        val updated = promoteMemoryToLoreEntry(world, card, "赛琳娜在暴风雪中遗失了银色怀表。", "雪山营地")
        assertEquals(1, updated.entries.size)
        val entry = updated.entries.single()
        assertTrue(entry.content.contains("银色怀表"))
        assertTrue("赛琳娜" in entry.keywords)

        // Different card with identical name must not see card-a's turns
        val cardSameName = testCard(id = "card-b", name = "赛琳娜")
        val chatFromCardA = Chat(
            id = "chat-a",
            cardId = card.id,
            title = "雪山",
            messages = listOf(Message("assistant", "赛琳娜", "我的怀表不见了。", characterId = card.id)),
        )
        assertTrue(chatFromCardA.sharedWorldTurns(cardSameName, null, listOf(card, cardSameName)).isEmpty())
    }
}
