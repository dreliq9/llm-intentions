package com.taichi.analyzer

import com.taichi.scraper.ScraperBridge
import com.taichi.scraper.TaichiJson
import kotlinx.serialization.json.*

/**
 * On-chain analysis: exchange flows (estimated from DEX data), wallet metrics,
 * and liquidity analysis. Adapted from Python's analyzers/onchain.py.
 *
 * Note: Python version reads from a dedicated scraper DB with historical
 * exchange flow/wallet/liquidity snapshots. On Android we estimate from
 * available API data (DexScreener pairs, CoinGecko, DefiLlama).
 */
object OnchainAnalyzer {

    /**
     * Analyze exchange flow proxies from DEX pair data.
     * Uses buy/sell transaction counts and volume as flow proxies.
     */
    suspend fun analyzeExchangeFlows(bridge: ScraperBridge, symbol: String): JsonObject {
        val result = try {
            bridge.dexScreener.searchPairs(symbol)
        } catch (e: Exception) {
            return buildJsonObject { put("error", "Failed to fetch pair data: ${e.message}") }
        }

        val pairs = result["pairs"]?.jsonArray
        if (pairs == null || pairs.isEmpty()) return buildJsonObject {
            put("error", "No pairs found for $symbol")
        }

        // Aggregate across all pairs
        var totalBuys24h = 0; var totalSells24h = 0
        var totalBuys1h = 0; var totalSells1h = 0
        var totalVolume24h = 0.0; var totalVolume1h = 0.0

        for (pair in pairs) {
            val obj = pair.jsonObject
            val txns24 = obj["txns"]?.jsonObject?.get("h24")?.jsonObject
            val txns1h = obj["txns"]?.jsonObject?.get("h1")?.jsonObject
            totalBuys24h += txns24?.get("buys")?.jsonPrimitive?.intOrNull ?: 0
            totalSells24h += txns24?.get("sells")?.jsonPrimitive?.intOrNull ?: 0
            totalBuys1h += txns1h?.get("buys")?.jsonPrimitive?.intOrNull ?: 0
            totalSells1h += txns1h?.get("sells")?.jsonPrimitive?.intOrNull ?: 0
            totalVolume24h += obj["volume"]?.jsonObject?.get("h24")?.jsonPrimitive?.doubleOrNull ?: 0.0
            totalVolume1h += obj["volume"]?.jsonObject?.get("h1")?.jsonPrimitive?.doubleOrNull ?: 0.0
        }

        val totalTxns24h = totalBuys24h + totalSells24h
        val buyRatio24h = if (totalTxns24h > 0) totalBuys24h.toDouble() / totalTxns24h else 0.5
        val buyRatio1h = if (totalBuys1h + totalSells1h > 0) totalBuys1h.toDouble() / (totalBuys1h + totalSells1h) else 0.5

        // Flow signals
        val flowSignal = when {
            buyRatio24h > 0.6 -> "net_inflow" // more buying = inflow to DEX
            buyRatio24h < 0.4 -> "net_outflow" // more selling = outflow from DEX
            else -> "balanced"
        }

        val momentumSignal = when {
            buyRatio1h > buyRatio24h + 0.1 -> "inflow_accelerating"
            buyRatio1h < buyRatio24h - 0.1 -> "outflow_accelerating"
            else -> "stable"
        }

        // Spike detection: 1h volume vs 24h average hourly
        val avgHourlyVol = totalVolume24h / 24
        val spikeRatio = if (avgHourlyVol > 0) totalVolume1h / avgHourlyVol else 1.0
        val spikeSignal = when {
            spikeRatio > 3.0 && buyRatio1h > 0.6 -> "inflow_spike"
            spikeRatio > 3.0 && buyRatio1h < 0.4 -> "outflow_spike"
            spikeRatio > 3.0 -> "volume_spike"
            else -> "none"
        }

        return buildJsonObject {
            put("symbol", symbol)
            put("data_source", "dexscreener_proxy")
            put("note", "Exchange flow proxied from DEX buy/sell counts — not direct on-chain data")
            put("metrics", buildJsonObject {
                put("buys_24h", totalBuys24h)
                put("sells_24h", totalSells24h)
                put("buy_ratio_24h", "%.3f".format(buyRatio24h).toDouble())
                put("buy_ratio_1h", "%.3f".format(buyRatio1h).toDouble())
                put("volume_24h", totalVolume24h)
                put("volume_1h", totalVolume1h)
                put("hourly_volume_ratio", "%.2f".format(spikeRatio).toDouble())
                put("pairs_analyzed", pairs.size)
            })
            put("signals", buildJsonObject {
                put("net_flow_trend", flowSignal)
                put("flow_momentum", momentumSignal)
                put("spike", spikeSignal)
                put("flow_ratio", when {
                    buyRatio24h > 0.65 -> "strong_inflow"
                    buyRatio24h < 0.35 -> "strong_outflow"
                    else -> "balanced"
                })
            })
        }
    }

    /**
     * Analyze wallet/holder metrics from CoinGecko data.
     */
    suspend fun analyzeWallets(bridge: ScraperBridge, symbol: String): JsonObject {
        val coinId = TaichiMcpProviderCompanion.symbolToCoinGeckoId(symbol.lowercase())
        val data = try {
            bridge.coinGecko.getCoinData(coinId)
        } catch (e: Exception) {
            return buildJsonObject { put("error", "CoinGecko lookup failed: ${e.message}") }
        }

        val marketData = data["market_data"]?.jsonObject
        val communityData = data["community_data"]?.jsonObject

        val circulatingSupply = marketData?.get("circulating_supply")?.jsonPrimitive?.doubleOrNull
        val totalSupply = marketData?.get("total_supply")?.jsonPrimitive?.doubleOrNull
        val maxSupply = marketData?.get("max_supply")?.jsonPrimitive?.doubleOrNull
        val mcap = marketData?.get("market_cap")?.jsonObject?.get("usd")?.jsonPrimitive?.doubleOrNull
        val fdv = marketData?.get("fully_diluted_valuation")?.jsonObject?.get("usd")?.jsonPrimitive?.doubleOrNull

        val circulatingPct = if (totalSupply != null && totalSupply > 0 && circulatingSupply != null) {
            circulatingSupply / totalSupply * 100
        } else null

        val mcapFdvRatio = if (mcap != null && fdv != null && fdv > 0) mcap / fdv else null

        // Holder signals from supply metrics
        val supplySignal = when {
            circulatingPct != null && circulatingPct < 60 -> "high_dilution_risk"
            circulatingPct != null && circulatingPct < 80 -> "moderate_dilution"
            maxSupply == null -> "inflationary"
            else -> "healthy"
        }

        val concentrationSignal = when {
            mcapFdvRatio != null && mcapFdvRatio < 0.5 -> "high_concentration"
            mcapFdvRatio != null && mcapFdvRatio < 0.75 -> "moderate_concentration"
            else -> "distributed"
        }

        return buildJsonObject {
            put("symbol", symbol)
            put("data_source", "coingecko")
            put("tokenomics", buildJsonObject {
                put("circulating_supply", circulatingSupply)
                put("total_supply", totalSupply)
                put("max_supply", maxSupply)
                put("circulating_pct", circulatingPct)
                put("market_cap_usd", mcap)
                put("fdv_usd", fdv)
                put("mcap_fdv_ratio", mcapFdvRatio)
            })
            put("community", buildJsonObject {
                put("reddit_subscribers", communityData?.get("reddit_subscribers")?.jsonPrimitive?.intOrNull)
                put("telegram_users", communityData?.get("telegram_channel_user_count")?.jsonPrimitive?.intOrNull)
            })
            put("signals", buildJsonObject {
                put("supply_health", supplySignal)
                put("concentration", concentrationSignal)
            })
        }
    }

    /**
     * Analyze liquidity metrics from DefiLlama + DexScreener.
     */
    suspend fun analyzeLiquidity(bridge: ScraperBridge, symbol: String): JsonObject {
        val coinId = TaichiMcpProviderCompanion.symbolToCoinGeckoId(symbol.lowercase())

        // Try DefiLlama for TVL
        val tvl = try { bridge.defiLlama.getTvl(coinId) } catch (_: Exception) { null }

        // DEX liquidity from DexScreener
        val dexResult = try {
            bridge.dexScreener.searchPairs(symbol)
        } catch (_: Exception) { null }

        val pairs = dexResult?.get("pairs")?.jsonArray
        var totalDexLiquidity = 0.0
        var totalDexVolume = 0.0
        var poolCount = 0
        var bestPoolLiquidity = 0.0
        var bestPoolName = ""

        if (pairs != null) {
            for (pair in pairs) {
                val obj = pair.jsonObject
                val liq = obj["liquidity"]?.jsonObject?.get("usd")?.jsonPrimitive?.doubleOrNull ?: 0.0
                val vol = obj["volume"]?.jsonObject?.get("h24")?.jsonPrimitive?.doubleOrNull ?: 0.0
                totalDexLiquidity += liq
                totalDexVolume += vol
                poolCount++
                if (liq > bestPoolLiquidity) {
                    bestPoolLiquidity = liq
                    bestPoolName = "${obj["dexId"]?.jsonPrimitive?.content ?: "?"} (${obj["chainId"]?.jsonPrimitive?.content ?: "?"})"
                }
            }
        }

        val capitalEfficiency = if (totalDexLiquidity > 0) totalDexVolume / totalDexLiquidity else 0.0

        // TVL trend signal (we only have a point-in-time, not historical)
        val tvlSignal = when {
            tvl == null -> "unavailable"
            tvl > 1_000_000_000 -> "very_high"
            tvl > 100_000_000 -> "high"
            tvl > 10_000_000 -> "moderate"
            tvl > 1_000_000 -> "low"
            else -> "very_low"
        }

        val efficiencySignal = when {
            capitalEfficiency > 1.0 -> "very_efficient"
            capitalEfficiency > 0.3 -> "efficient"
            capitalEfficiency > 0.1 -> "moderate"
            else -> "inefficient"
        }

        val concentrationRisk = if (poolCount > 0 && bestPoolLiquidity > 0) {
            bestPoolLiquidity / totalDexLiquidity * 100
        } else 0.0

        return buildJsonObject {
            put("symbol", symbol)
            put("tvl_usd", tvl)
            put("tvl_level", tvlSignal)
            put("dex_metrics", buildJsonObject {
                put("total_liquidity_usd", totalDexLiquidity)
                put("total_volume_24h", totalDexVolume)
                put("pool_count", poolCount)
                put("best_pool", bestPoolName)
                put("best_pool_liquidity", bestPoolLiquidity)
                put("top_pool_concentration_pct", "%.1f".format(concentrationRisk).toDouble())
                put("capital_efficiency", "%.3f".format(capitalEfficiency).toDouble())
            })
            put("signals", buildJsonObject {
                put("tvl_level", tvlSignal)
                put("capital_efficiency", efficiencySignal)
                put("pool_concentration", when {
                    concentrationRisk > 80 -> "highly_concentrated"
                    concentrationRisk > 60 -> "moderately_concentrated"
                    else -> "distributed"
                })
            })
        }
    }
}

/**
 * Companion object accessor for symbolToCoinGeckoId from TaichiMcpProvider.
 * Duplicated here to avoid circular dependency.
 */
object TaichiMcpProviderCompanion {
    private val coinGeckoMap = mapOf(
        "eth" to "ethereum", "btc" to "bitcoin", "sol" to "solana",
        "uni" to "uniswap", "aave" to "aave", "link" to "chainlink",
        "matic" to "matic-network", "arb" to "arbitrum", "op" to "optimism",
        "avax" to "avalanche-2", "dot" to "polkadot", "atom" to "cosmos",
        "ada" to "cardano", "xrp" to "ripple", "doge" to "dogecoin",
        "shib" to "shiba-inu", "paxg" to "pax-gold", "zec" to "zcash",
        "mkr" to "maker", "comp" to "compound-governance-token",
        "snx" to "havven", "crv" to "curve-dao-token", "ldo" to "lido-dao",
    )

    fun symbolToCoinGeckoId(symbol: String): String {
        val lower = symbol.lowercase()
        return coinGeckoMap[lower] ?: lower
    }
}
