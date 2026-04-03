package com.taichi.scraper

import com.taichi.model.Candle
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*

class GeckoTerminalClient(private val client: HttpClient) {

    private val baseUrl = "https://api.geckoterminal.com/api/v2"
    // 30 req/min
    private val rateLimiter = RateLimiter(3000)

    private val supportedNetworks = listOf(
        "eth", "solana", "base", "arbitrum", "polygon", "bsc",
        "avalanche", "fantom", "optimism"
    )

    suspend fun searchPools(query: String): JsonObject {
        return rateLimiter.throttled {
            val response = client.get("$baseUrl/search/pools") {
                parameter("query", query)
                parameter("page", 1)
            }
            TaichiJson.parseToJsonElement(response.bodyAsText()).jsonObject
        }
    }

    suspend fun getOhlcv(
        network: String,
        poolAddress: String,
        timeframe: String = "hour",
        limit: Int = 200
    ): List<Candle> {
        return rateLimiter.throttled {
            val tf = when (timeframe) {
                "1m", "minute" -> "minute"
                "1h", "hour" -> "hour"
                "1d", "day" -> "day"
                else -> "hour"
            }
            val response = client.get(
                "$baseUrl/networks/$network/pools/$poolAddress/ohlcv/$tf"
            ) {
                parameter("aggregate", 1)
                parameter("limit", limit)
            }
            val json = TaichiJson.parseToJsonElement(response.bodyAsText()).jsonObject
            val ohlcvList = json["data"]?.jsonObject
                ?.get("attributes")?.jsonObject
                ?.get("ohlcv_list")?.jsonArray ?: return@throttled emptyList()

            ohlcvList.map { row ->
                val arr = row.jsonArray
                Candle(
                    timestamp = arr[0].jsonPrimitive.long,
                    open = arr[1].jsonPrimitive.double,
                    high = arr[2].jsonPrimitive.double,
                    low = arr[3].jsonPrimitive.double,
                    close = arr[4].jsonPrimitive.double,
                    volume = arr[5].jsonPrimitive.double
                )
            }
        }
    }

    suspend fun getTrendingPools(network: String = "eth"): JsonObject {
        return rateLimiter.throttled {
            val response = client.get("$baseUrl/networks/$network/trending_pools")
            TaichiJson.parseToJsonElement(response.bodyAsText()).jsonObject
        }
    }
}
