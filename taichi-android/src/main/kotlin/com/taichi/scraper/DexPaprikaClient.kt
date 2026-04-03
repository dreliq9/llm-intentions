package com.taichi.scraper

import com.taichi.model.Candle
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*

class DexPaprikaClient(private val client: HttpClient) {

    private val baseUrl = "https://api.dexpaprika.com"
    private val rateLimiter = RateLimiter(500)

    suspend fun searchTokens(query: String): JsonObject {
        return rateLimiter.throttled {
            val response = client.get("$baseUrl/search") {
                parameter("query", query)
            }
            TaichiJson.parseToJsonElement(response.bodyAsText()).jsonObject
        }
    }

    suspend fun getTokenPools(network: String, tokenAddress: String, limit: Int = 5): JsonObject {
        return rateLimiter.throttled {
            val response = client.get("$baseUrl/networks/$network/tokens/$tokenAddress/pools") {
                parameter("limit", limit)
                parameter("order_by", "volume_usd")
                parameter("sort", "desc")
            }
            TaichiJson.parseToJsonElement(response.bodyAsText()).jsonObject
        }
    }

    suspend fun getOhlcv(
        network: String,
        poolAddress: String,
        interval: String = "1h",
        limit: Int = 200
    ): List<Candle> {
        return rateLimiter.throttled {
            val response = client.get("$baseUrl/networks/$network/pools/$poolAddress/ohlcv") {
                parameter("interval", interval)
                parameter("limit", limit)
            }
            val json = TaichiJson.parseToJsonElement(response.bodyAsText())

            val arr = when (json) {
                is JsonArray -> json
                is JsonObject -> json["ohlcv"]?.jsonArray ?: return@throttled emptyList()
                else -> return@throttled emptyList()
            }

            arr.mapNotNull { elem ->
                val obj = elem.jsonObject
                val ts = obj["timestamp"]?.jsonPrimitive?.longOrNull
                    ?: obj["time_open"]?.jsonPrimitive?.longOrNull
                    ?: obj["t"]?.jsonPrimitive?.longOrNull
                    ?: return@mapNotNull null

                // Normalize: if ts > 1e12, it's milliseconds
                val tsSec = if (ts > 1_000_000_000_000) ts / 1000 else ts

                Candle(
                    timestamp = tsSec,
                    open = obj["open"]?.jsonPrimitive?.doubleOrNull
                        ?: obj["o"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    high = obj["high"]?.jsonPrimitive?.doubleOrNull
                        ?: obj["h"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    low = obj["low"]?.jsonPrimitive?.doubleOrNull
                        ?: obj["l"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    close = obj["close"]?.jsonPrimitive?.doubleOrNull
                        ?: obj["c"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    volume = obj["volume"]?.jsonPrimitive?.doubleOrNull
                        ?: obj["v"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                )
            }
        }
    }
}
