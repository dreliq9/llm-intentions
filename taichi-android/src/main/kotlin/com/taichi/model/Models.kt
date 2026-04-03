package com.taichi.model

import kotlinx.serialization.Serializable

// --- Timeframe ---

enum class TimeFrame(val value: String) {
    M1("1m"), M5("5m"), M15("15m"), M30("30m"),
    H1("1h"), H4("4h"), D1("1d"), W1("1w");

    companion object {
        fun fromString(s: String): TimeFrame = entries.firstOrNull {
            it.value == s || it.name.equals(s, ignoreCase = true)
        } ?: H1
    }
}

// --- OHLCV ---

@Serializable
data class Candle(
    val timestamp: Long, // Unix seconds
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double = 0.0
)

// --- Token Pair (DexScreener format) ---

@Serializable
data class TokenMeta(
    val symbol: String,
    val address: String? = null
)

@Serializable
data class TokenPair(
    val pairAddress: String,
    val chainId: String,
    val dexId: String,
    val baseToken: TokenMeta,
    val quoteToken: TokenMeta,
    val priceUsd: Double? = null,
    val volume: Map<String, Double> = emptyMap(),
    val liquidity: Map<String, Double> = emptyMap(),
    val priceChange: Map<String, Double> = emptyMap(),
    val txns: Map<String, Map<String, Int>> = emptyMap(),
    val fdv: Double? = null,
    val pairCreatedAt: Long? = null
)

// --- Exchange Flow ---

@Serializable
data class ExchangeFlowRecord(
    val timestamp: Long,
    val inflow: Double,
    val outflow: Double,
    val netFlow: Double? = null,
    val exchange: String? = null
)

// --- Wallet Snapshot ---

@Serializable
data class WalletSnapshot(
    val timestamp: Long,
    val whaleBalance: Double,
    val whaleCount: Int,
    val totalHolders: Int,
    val top10Pct: Double? = null,
    val newWallets: Int? = null,
    val exitingWallets: Int? = null
)

// --- Liquidity Snapshot ---

@Serializable
data class LiquiditySnapshot(
    val timestamp: Long,
    val tvl: Double,
    val volume24h: Double? = null,
    val lpAdds: Double? = 0.0,
    val lpRemoves: Double? = 0.0,
    val pool: String? = null
)

// --- News / Social ---

@Serializable
data class Article(
    val title: String,
    val url: String,
    val source: String,
    val domain: String? = null,
    val currencies: List<String> = emptyList(),
    val kind: String? = null,
    val publishedAt: String? = null
)

@Serializable
data class RedditPost(
    val id: String,
    val subreddit: String,
    val title: String,
    val selftext: String = "",
    val author: String,
    val score: Int,
    val numComments: Int,
    val createdUtc: Long
)

@Serializable
data class FarcasterCast(
    val hash: String,
    val authorFid: Int,
    val text: String,
    val channel: String,
    val timestamp: Long
)

// --- Scraper Response Envelope ---

@Serializable
data class ScraperResult(
    val status: String, // "success" or "error"
    val source: String,
    val message: String? = null,
    val count: Int = 0,
    val data: kotlinx.serialization.json.JsonElement? = null
)
