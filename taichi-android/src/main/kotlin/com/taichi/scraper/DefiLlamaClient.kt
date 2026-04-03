package com.taichi.scraper

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*

class DefiLlamaClient(private val client: HttpClient) {

    private val baseUrl = "https://api.llama.fi"
    private val rateLimiter = RateLimiter(1000)

    suspend fun getProtocol(name: String): JsonObject {
        return rateLimiter.throttled {
            val response = client.get("$baseUrl/protocol/$name")
            TaichiJson.parseToJsonElement(response.bodyAsText()).jsonObject
        }
    }

    suspend fun getTvl(name: String): Double? {
        return rateLimiter.throttled {
            val response = client.get("$baseUrl/tvl/$name")
            response.bodyAsText().toDoubleOrNull()
        }
    }

    suspend fun getFees(name: String): JsonObject {
        return rateLimiter.throttled {
            val response = client.get("$baseUrl/summary/fees/$name")
            TaichiJson.parseToJsonElement(response.bodyAsText()).jsonObject
        }
    }

    suspend fun listProtocols(): JsonArray {
        return rateLimiter.throttled {
            val response = client.get("$baseUrl/protocols")
            TaichiJson.parseToJsonElement(response.bodyAsText()).jsonArray
        }
    }
}
