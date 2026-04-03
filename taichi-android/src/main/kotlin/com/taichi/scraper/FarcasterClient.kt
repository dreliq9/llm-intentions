package com.taichi.scraper

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*

class FarcasterClient(private val client: HttpClient) {

    private val baseUrl = "https://hub.pinata.cloud/v1"
    // Polite to free hub
    private val rateLimiter = RateLimiter(500)

    // Farcaster epoch: Jan 1, 2021
    private val farcasterEpoch = 1609459200L

    private val trackedChannels = mapOf(
        "bitcoin" to "https://bitcoin.org",
        "ethereum" to "https://ethereum.org",
        "solana" to "https://solana.com"
    )

    suspend fun fetchCasts(channel: String, limit: Int = 25): List<JsonObject> {
        val parentUrl = trackedChannels[channel.lowercase()]
            ?: "https://${channel.lowercase()}.org"

        return rateLimiter.throttled {
            val response = client.get("$baseUrl/castsByParent") {
                parameter("url", parentUrl)
                parameter("pageSize", limit)
                parameter("reverse", true)
            }
            val json = TaichiJson.parseToJsonElement(response.bodyAsText()).jsonObject
            val messages = json["messages"]?.jsonArray ?: return@throttled emptyList()

            messages.map { msg ->
                val data = msg.jsonObject["data"]?.jsonObject ?: return@map buildJsonObject {}
                val fcTs = data["timestamp"]?.jsonPrimitive?.longOrNull ?: 0
                val body = data["castAddBody"]?.jsonObject
                buildJsonObject {
                    put("hash", msg.jsonObject["hash"]?.jsonPrimitive?.content ?: "")
                    put("fid", data["fid"]?.jsonPrimitive?.int ?: 0)
                    put("text", body?.get("text")?.jsonPrimitive?.content ?: "")
                    put("channel", channel)
                    put("timestamp", fcTs + farcasterEpoch)
                }
            }
        }
    }
}
