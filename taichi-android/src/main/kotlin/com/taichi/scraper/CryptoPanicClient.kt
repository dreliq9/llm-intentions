package com.taichi.scraper

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*

class CryptoPanicClient(
    private val client: HttpClient,
    private val authToken: String? = null
) {
    private val baseUrl = "https://cryptopanic.com/api/developer/v2/posts/"

    suspend fun fetchNews(kind: String? = null): JsonObject {
        if (authToken.isNullOrBlank()) {
            return buildJsonObject {
                put("results", JsonArray(emptyList()))
                put("status", "not_configured")
                put("message", "CryptoPanic API key not set. Get a free key at cryptopanic.com/developers/api/keys and enter it in the Taichi app settings.")
            }
        }
        val response = client.get(baseUrl) {
            parameter("auth_token", authToken)
            parameter("public", "true")
            kind?.let { parameter("kind", it) }
        }
        return TaichiJson.parseToJsonElement(response.bodyAsText()).jsonObject
    }
}
