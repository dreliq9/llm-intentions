package com.taichi.scraper

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import java.util.Base64

class RedditClient(
    private val client: HttpClient,
    private val clientId: String? = null,
    private val clientSecret: String? = null,
    private val userAgent: String = "taichi-mcp:0.1.0 (by /u/taichi_bot)"
) {
    private var accessToken: String? = null

    private suspend fun authenticate(): String? {
        if (clientId.isNullOrBlank() || clientSecret.isNullOrBlank()) return null

        val credentials = Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray())
        val response = client.post("https://www.reddit.com/api/v1/access_token") {
            header(HttpHeaders.Authorization, "Basic $credentials")
            header(HttpHeaders.UserAgent, userAgent)
            setBody(FormDataContent(Parameters.build {
                append("grant_type", "client_credentials")
            }))
        }
        val json = TaichiJson.parseToJsonElement(response.bodyAsText()).jsonObject
        accessToken = json["access_token"]?.jsonPrimitive?.contentOrNull
        return accessToken
    }

    private val hasOAuth = !clientId.isNullOrBlank() && !clientSecret.isNullOrBlank()

    // Public JSON endpoint — no auth needed, ~10 req/min rate limit
    private val publicRateLimiter = RateLimiter(6000)

    suspend fun fetchPosts(subreddit: String = "cryptocurrency", limit: Int = 25): JsonObject {
        // Try OAuth first if credentials are available
        if (hasOAuth) {
            try {
                val result = fetchPostsOAuth(subreddit, limit)
                if (result["error"] == null) return result
            } catch (_: Exception) { }
        }

        // Fallback: public JSON endpoint (no OAuth required)
        return fetchPostsPublic(subreddit, limit)
    }

    private suspend fun fetchPostsOAuth(subreddit: String, limit: Int): JsonObject {
        var token = accessToken ?: authenticate() ?: return buildJsonObject {
            put("error", "Reddit OAuth failed")
        }

        val response = client.get("https://oauth.reddit.com/r/$subreddit/new") {
            parameter("limit", limit)
            parameter("raw_json", 1)
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.UserAgent, userAgent)
        }

        // Retry on 401
        if (response.status.value == 401) {
            token = authenticate() ?: return buildJsonObject {
                put("error", "Reddit auth failed")
            }
            val retry = client.get("https://oauth.reddit.com/r/$subreddit/new") {
                parameter("limit", limit)
                parameter("raw_json", 1)
                header(HttpHeaders.Authorization, "Bearer $token")
                header(HttpHeaders.UserAgent, userAgent)
            }
            return TaichiJson.parseToJsonElement(retry.bodyAsText()).jsonObject
        }

        return TaichiJson.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private suspend fun fetchPostsPublic(subreddit: String, limit: Int): JsonObject {
        return publicRateLimiter.throttled {
            try {
                val response = client.get("https://www.reddit.com/r/$subreddit/new.json") {
                    parameter("limit", limit)
                    parameter("raw_json", 1)
                    header(HttpHeaders.UserAgent, userAgent)
                }
                TaichiJson.parseToJsonElement(response.bodyAsText()).jsonObject
            } catch (e: Exception) {
                buildJsonObject {
                    put("error", "Reddit public endpoint failed: ${e.message}")
                }
            }
        }
    }
}
