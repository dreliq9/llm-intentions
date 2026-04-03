package com.taichi.scraper

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*

class DexScreenerClient(private val client: HttpClient) {

    private val baseUrl = "https://api.dexscreener.com"
    // 300 req/min for pairs
    private val rateLimiter = RateLimiter(200)

    suspend fun searchPairs(query: String): JsonObject {
        return rateLimiter.throttled {
            val response = client.get("$baseUrl/latest/dex/search") {
                parameter("q", query)
            }
            TaichiJson.parseToJsonElement(response.bodyAsText()).jsonObject
        }
    }

    suspend fun getTokenPairs(tokenAddress: String): JsonObject {
        return rateLimiter.throttled {
            val response = client.get("$baseUrl/latest/dex/tokens/$tokenAddress")
            TaichiJson.parseToJsonElement(response.bodyAsText()).jsonObject
        }
    }

    suspend fun getNewPairs(): JsonArray {
        return rateLimiter.throttled {
            val response = client.get("$baseUrl/token-profiles/latest/v1")
            TaichiJson.parseToJsonElement(response.bodyAsText()).jsonArray
        }
    }

    suspend fun getLivePrice(tokenAddress: String): Double? {
        return try {
            val result = getTokenPairs(tokenAddress)
            val pairs = result["pairs"]?.jsonArray ?: return null
            pairs.firstOrNull()?.jsonObject?.get("priceUsd")?.jsonPrimitive?.doubleOrNull
        } catch (_: Exception) {
            null
        }
    }
}
