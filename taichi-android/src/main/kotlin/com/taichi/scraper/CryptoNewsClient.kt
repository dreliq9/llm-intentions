package com.taichi.scraper

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*

/**
 * Free Crypto News API — no auth required, 200+ sources.
 * Primary news source. CryptoPanic is the optional fallback if user has a key.
 *
 * Base URL: https://cryptocurrency.cv
 * Docs: https://cryptocurrency.cv/developers
 */
class CryptoNewsClient(private val client: HttpClient) {

    private val baseUrl = "https://cryptocurrency.cv"
    private val rateLimiter = RateLimiter(500)

    suspend fun fetchLatestNews(limit: Int = 20): JsonObject {
        return rateLimiter.throttled {
            try {
                val response = client.get("$baseUrl/api/news") {
                    parameter("limit", limit)
                }
                TaichiJson.parseToJsonElement(response.bodyAsText()).jsonObject
            } catch (e: Exception) {
                buildJsonObject {
                    put("articles", JsonArray(emptyList()))
                    put("error", "Free Crypto News API unavailable: ${e.message}")
                }
            }
        }
    }

    suspend fun searchNews(query: String, limit: Int = 20): JsonObject {
        return rateLimiter.throttled {
            try {
                val response = client.get("$baseUrl/api/search") {
                    parameter("q", query)
                    parameter("limit", limit)
                }
                TaichiJson.parseToJsonElement(response.bodyAsText()).jsonObject
            } catch (e: Exception) {
                buildJsonObject {
                    put("articles", JsonArray(emptyList()))
                    put("error", "Search failed: ${e.message}")
                }
            }
        }
    }

    suspend fun getSentiment(asset: String): JsonObject {
        return rateLimiter.throttled {
            try {
                val response = client.get("$baseUrl/api/ai/sentiment") {
                    parameter("asset", asset)
                }
                TaichiJson.parseToJsonElement(response.bodyAsText()).jsonObject
            } catch (e: Exception) {
                buildJsonObject {
                    put("error", "Sentiment unavailable: ${e.message}")
                }
            }
        }
    }

    suspend fun getFearGreedIndex(): JsonObject {
        return rateLimiter.throttled {
            try {
                val response = client.get("$baseUrl/api/market/fear-greed")
                TaichiJson.parseToJsonElement(response.bodyAsText()).jsonObject
            } catch (e: Exception) {
                buildJsonObject {
                    put("error", "Fear & Greed unavailable: ${e.message}")
                }
            }
        }
    }
}
