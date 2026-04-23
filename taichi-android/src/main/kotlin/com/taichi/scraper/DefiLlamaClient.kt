package com.taichi.scraper

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

class DefiLlamaClient(private val client: HttpClient) {

    private val baseUrl = "https://api.llama.fi"
    private val yieldsUrl = "https://yields.llama.fi"
    private val rateLimiter = RateLimiter(1000)

    // In-memory cache for /pools — response is ~2MB, refresh every 30 min
    private val poolsMutex = Mutex()
    private var cachedPools: JsonArray? = null
    private var cachedAtMs: Long = 0
    private val poolsTtlMs: Long = 30 * 60 * 1000

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

    /**
     * Fetch the full yield pools list from DefiLlama.
     * Result cached in-memory for 30 minutes — endpoint response is ~2MB.
     */
    suspend fun getPools(forceRefresh: Boolean = false): JsonArray {
        poolsMutex.withLock {
            val fresh = cachedPools
            val age = System.currentTimeMillis() - cachedAtMs
            if (!forceRefresh && fresh != null && age < poolsTtlMs) return fresh
        }

        val data = rateLimiter.throttled {
            val response = client.get("$yieldsUrl/pools")
            val root = TaichiJson.parseToJsonElement(response.bodyAsText()).jsonObject
            root["data"]?.jsonArray ?: JsonArray(emptyList())
        }

        poolsMutex.withLock {
            cachedPools = data
            cachedAtMs = System.currentTimeMillis()
        }
        return data
    }

    /**
     * Look up the current APY for a single pool by its DefiLlama pool UUID.
     * Uses the cached pool list. Returns null if the pool isn't found.
     */
    suspend fun getPoolApy(poolId: String): Double? {
        val pools = getPools()
        for (p in pools) {
            val obj = p.jsonObject
            if (obj["pool"]?.jsonPrimitive?.contentOrNull == poolId) {
                return obj["apy"]?.jsonPrimitive?.doubleOrNull
            }
        }
        return null
    }
}
