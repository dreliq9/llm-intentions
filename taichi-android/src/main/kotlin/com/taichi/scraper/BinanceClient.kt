package com.taichi.scraper

import com.taichi.model.Candle
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*

class BinanceClient(private val client: HttpClient) {

    private val baseUrl = "https://api.binance.us/api/v3"
    private val rateLimiter = RateLimiter(200)

    // Cached pair map: base symbol -> best pair name (e.g., "ETH" -> "ETHUSDT")
    private var pairMap: Map<String, String>? = null

    private val quotePreference = listOf("USDT", "USDC", "USD", "BTC")

    private val timeframeMap = mapOf(
        "1m" to "1m", "5m" to "5m", "15m" to "15m",
        "1h" to "1h", "4h" to "4h", "1d" to "1d",
        "hour" to "1h", "day" to "1d", "minute" to "1m"
    )

    private suspend fun ensurePairMap(): Map<String, String> {
        pairMap?.let { return it }

        val response = client.get("$baseUrl/exchangeInfo")
        val json = TaichiJson.parseToJsonElement(response.bodyAsText()).jsonObject
        val symbols = json["symbols"]?.jsonArray ?: JsonArray(emptyList())

        val map = mutableMapOf<String, String>()
        for (sym in symbols) {
            val obj = sym.jsonObject
            val status = obj["status"]?.jsonPrimitive?.content
            if (status != "TRADING") continue

            val base = obj["baseAsset"]?.jsonPrimitive?.content?.uppercase() ?: continue
            val quote = obj["quoteAsset"]?.jsonPrimitive?.content?.uppercase() ?: continue
            val pair = obj["symbol"]?.jsonPrimitive?.content ?: continue

            val existing = map[base]
            if (existing == null) {
                map[base] = pair
            } else {
                val existingQuote = quotePreference.indexOfFirst { existing.endsWith(it) }
                val newQuote = quotePreference.indexOf(quote)
                if (newQuote in 0 until (if (existingQuote < 0) Int.MAX_VALUE else existingQuote)) {
                    map[base] = pair
                }
            }
        }

        pairMap = map
        return map
    }

    suspend fun getOhlcv(
        symbol: String,
        timeframe: String = "1h",
        limit: Int = 200
    ): List<Candle> {
        return rateLimiter.throttled {
            val pairs = ensurePairMap()
            val pair = pairs[symbol.uppercase()] ?: return@throttled emptyList()
            val interval = timeframeMap[timeframe] ?: "1h"

            val response = client.get("$baseUrl/klines") {
                parameter("symbol", pair)
                parameter("interval", interval)
                parameter("limit", limit)
            }
            val json = TaichiJson.parseToJsonElement(response.bodyAsText()).jsonArray

            json.map { row ->
                val arr = row.jsonArray
                Candle(
                    timestamp = arr[0].jsonPrimitive.long / 1000, // ms -> seconds
                    open = arr[1].jsonPrimitive.content.toDouble(),
                    high = arr[2].jsonPrimitive.content.toDouble(),
                    low = arr[3].jsonPrimitive.content.toDouble(),
                    close = arr[4].jsonPrimitive.content.toDouble(),
                    volume = arr[5].jsonPrimitive.content.toDouble()
                )
            }
        }
    }

    suspend fun getPrice(symbol: String): Double? {
        return rateLimiter.throttled {
            val pairs = ensurePairMap()
            val pair = pairs[symbol.uppercase()] ?: return@throttled null
            val response = client.get("$baseUrl/ticker/price") {
                parameter("symbol", pair)
            }
            val json = TaichiJson.parseToJsonElement(response.bodyAsText()).jsonObject
            json["price"]?.jsonPrimitive?.content?.toDoubleOrNull()
        }
    }
}
