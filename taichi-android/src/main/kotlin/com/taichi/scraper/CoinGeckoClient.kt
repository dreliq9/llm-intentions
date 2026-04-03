package com.taichi.scraper

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*

class CoinGeckoClient(
    private val client: HttpClient,
    private val apiKey: String? = null
) {
    private val baseUrl = "https://api.coingecko.com/api/v3"
    // 24 req/min free tier
    private val rateLimiter = RateLimiter(2500)

    suspend fun getCoinData(coinId: String): JsonObject {
        return rateLimiter.throttled {
            val response = client.get("$baseUrl/coins/$coinId") {
                parameter("localization", false)
                parameter("tickers", false)
                parameter("market_data", true)
                parameter("community_data", true)
                parameter("developer_data", true)
                apiKey?.let { header("x-cg-demo-api-key", it) }
            }
            TaichiJson.parseToJsonElement(response.bodyAsText()).jsonObject
        }
    }

    suspend fun searchCoins(query: String): JsonObject {
        return rateLimiter.throttled {
            val response = client.get("$baseUrl/search") {
                parameter("query", query)
                apiKey?.let { header("x-cg-demo-api-key", it) }
            }
            TaichiJson.parseToJsonElement(response.bodyAsText()).jsonObject
        }
    }

    suspend fun getMarketData(coinIds: List<String>): JsonArray {
        return rateLimiter.throttled {
            val response = client.get("$baseUrl/coins/markets") {
                parameter("vs_currency", "usd")
                parameter("ids", coinIds.joinToString(","))
                parameter("per_page", 50)
                parameter("sparkline", false)
                apiKey?.let { header("x-cg-demo-api-key", it) }
            }
            TaichiJson.parseToJsonElement(response.bodyAsText()).jsonArray
        }
    }
}
