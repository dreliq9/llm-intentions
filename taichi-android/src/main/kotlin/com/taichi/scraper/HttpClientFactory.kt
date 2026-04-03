package com.taichi.scraper

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

val TaichiJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    encodeDefaults = true
}

fun createHttpClient(): HttpClient = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(TaichiJson)
    }
    engine {
        requestTimeout = 30_000
    }
}

/**
 * Per-scraper rate limiter. Enforces a minimum interval between requests.
 */
class RateLimiter(private val minIntervalMs: Long) {
    private val mutex = Mutex()
    private var lastRequestTime = 0L

    suspend fun <T> throttled(block: suspend () -> T): T {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val elapsed = now - lastRequestTime
            if (elapsed < minIntervalMs) {
                kotlinx.coroutines.delay(minIntervalMs - elapsed)
            }
            lastRequestTime = System.currentTimeMillis()
        }
        return block()
    }
}
