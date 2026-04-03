package com.taichi.analyzer

import com.taichi.scraper.ScraperBridge
import kotlinx.serialization.json.*
import kotlin.math.abs

/**
 * Discovery and safety tools: volatility scanner and liquidity gate.
 */
object Scanner {

    /**
     * Scan for volatile tokens with sufficient volume.
     */
    suspend fun volatilityScan(
        bridge: ScraperBridge,
        minVolume24h: Double = 500_000.0,
        minPriceChangePct: Double = 5.0,
        maxResults: Int = 10
    ): JsonObject {
        val categories = listOf("defi", "layer1", "layer2", "privacy", "meme", "ai", "gaming")
        val candidates = mutableListOf<JsonObject>()

        for (category in categories) {
            try {
                val result = bridge.dexScreener.searchPairs(category)
                val pairs = result["pairs"]?.jsonArray ?: continue

                for (pair in pairs) {
                    val obj = pair.jsonObject
                    val price = obj["priceUsd"]?.jsonPrimitive?.doubleOrNull ?: continue
                    if (price <= 0) continue

                    val volume24h = obj["volume"]?.jsonObject?.get("h24")?.jsonPrimitive?.doubleOrNull ?: 0.0
                    if (volume24h < minVolume24h) continue

                    val change24h = obj["priceChange"]?.jsonObject?.get("h24")?.jsonPrimitive?.doubleOrNull ?: 0.0
                    if (abs(change24h) < minPriceChangePct) continue

                    val buys = obj["txns"]?.jsonObject?.get("h24")?.jsonObject?.get("buys")?.jsonPrimitive?.intOrNull ?: 0
                    val sells = obj["txns"]?.jsonObject?.get("h24")?.jsonObject?.get("sells")?.jsonPrimitive?.intOrNull ?: 0
                    if (buys + sells < 50) continue

                    val symbol = obj["baseToken"]?.jsonObject?.get("symbol")?.jsonPrimitive?.content ?: "?"
                    val liquidity = obj["liquidity"]?.jsonObject?.get("usd")?.jsonPrimitive?.doubleOrNull ?: 0.0

                    candidates.add(buildJsonObject {
                        put("symbol", symbol)
                        put("price_usd", price)
                        put("change_24h_pct", change24h)
                        put("change_1h_pct", obj["priceChange"]?.jsonObject?.get("h1")?.jsonPrimitive?.doubleOrNull ?: 0.0)
                        put("volume_24h", volume24h)
                        put("liquidity_usd", liquidity)
                        put("buys_24h", buys)
                        put("sells_24h", sells)
                        put("chain", obj["chainId"]?.jsonPrimitive?.content ?: "")
                        put("dex", obj["dexId"]?.jsonPrimitive?.content ?: "")
                        put("pair_address", obj["pairAddress"]?.jsonPrimitive?.content ?: "")
                    })
                }
            } catch (_: Exception) { continue }
        }

        // Sort by absolute price change (most volatile first), dedupe by symbol
        val seen = mutableSetOf<String>()
        val sorted = candidates
            .sortedByDescending { abs(it["change_24h_pct"]?.jsonPrimitive?.doubleOrNull ?: 0.0) }
            .filter { seen.add(it["symbol"]?.jsonPrimitive?.content ?: "") }
            .take(maxResults)

        return buildJsonObject {
            put("candidates", JsonArray(sorted))
            put("count", sorted.size)
            put("filters", buildJsonObject {
                put("min_volume_24h", minVolume24h)
                put("min_price_change_pct", minPriceChangePct)
            })
        }
    }

    /**
     * Check if a position size is safe given the token's liquidity.
     */
    suspend fun liquidityGate(
        bridge: ScraperBridge,
        symbol: String,
        positionSizeUsd: Double
    ): JsonObject {
        val result = try {
            bridge.dexScreener.searchPairs(symbol)
        } catch (e: Exception) {
            return buildJsonObject {
                put("verdict", "UNKNOWN")
                put("error", "Could not fetch pair data: ${e.message}")
            }
        }

        val pairs = result["pairs"]?.jsonArray
        if (pairs == null || pairs.isEmpty()) return buildJsonObject {
            put("verdict", "UNKNOWN")
            put("error", "No pairs found for $symbol")
        }

        var totalVolume = 0.0
        var totalLiquidity = 0.0
        var bestPoolLiquidity = 0.0

        for (pair in pairs) {
            val vol = pair.jsonObject["volume"]?.jsonObject?.get("h24")?.jsonPrimitive?.doubleOrNull ?: 0.0
            val liq = pair.jsonObject["liquidity"]?.jsonObject?.get("usd")?.jsonPrimitive?.doubleOrNull ?: 0.0
            totalVolume += vol
            totalLiquidity += liq
            if (liq > bestPoolLiquidity) bestPoolLiquidity = liq
        }

        val warnings = mutableListOf<String>()
        var fails = 0

        // Size vs volume
        val volumePct = if (totalVolume > 0) positionSizeUsd / totalVolume * 100 else 100.0
        when {
            volumePct > 5 -> { fails++; warnings.add("FAIL: Position is ${"%.1f".format(volumePct)}% of 24h volume (max 5%)") }
            volumePct > 1 -> warnings.add("WARN: Position is ${"%.1f".format(volumePct)}% of 24h volume")
        }

        // Size vs liquidity
        val liqPct = if (bestPoolLiquidity > 0) positionSizeUsd / bestPoolLiquidity * 100 else 100.0
        when {
            liqPct > 10 -> { fails++; warnings.add("FAIL: Position is ${"%.1f".format(liqPct)}% of best pool liquidity (max 10%)") }
            liqPct > 2 -> warnings.add("WARN: Position is ${"%.1f".format(liqPct)}% of best pool liquidity")
        }

        // Estimated slippage
        val slippage = if (bestPoolLiquidity > 0) positionSizeUsd / (2 * bestPoolLiquidity) * 100 else 100.0
        when {
            slippage > 2 -> { fails++; warnings.add("FAIL: Estimated slippage ${"%.2f".format(slippage)}% (max 2%)") }
            slippage > 0.5 -> warnings.add("WARN: Estimated slippage ${"%.2f".format(slippage)}%")
        }

        val verdict = when {
            fails > 0 -> "BLOCKED"
            warnings.size >= 3 -> "REDUCE_SIZE"
            warnings.isNotEmpty() -> "PROCEED_WITH_CAUTION"
            else -> "CLEAR"
        }

        return buildJsonObject {
            put("verdict", verdict)
            put("symbol", symbol)
            put("position_size_usd", positionSizeUsd)
            put("total_volume_24h", totalVolume)
            put("total_liquidity", totalLiquidity)
            put("best_pool_liquidity", bestPoolLiquidity)
            put("volume_pct", volumePct)
            put("liquidity_pct", liqPct)
            put("estimated_slippage_pct", slippage)
            put("warnings", JsonArray(warnings.map { JsonPrimitive(it) }))
            put("pools_found", pairs.size)
        }
    }
}
