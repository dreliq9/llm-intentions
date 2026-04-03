package com.taichi.scraper

import com.taichi.model.Candle
import io.ktor.client.*
import kotlinx.serialization.json.*

/**
 * Central access point for all scrapers. Handles fallback chains and
 * data normalization — equivalent to Python's scraper_bridge.py.
 */
class ScraperBridge(
    private val client: HttpClient = createHttpClient(),
    cryptoPanicToken: String? = null,
    redditClientId: String? = null,
    redditClientSecret: String? = null,
    coinGeckoApiKey: String? = null
) {
    val dexScreener = DexScreenerClient(client)
    val geckoTerminal = GeckoTerminalClient(client)
    val binance = BinanceClient(client)
    val coinGecko = CoinGeckoClient(client, coinGeckoApiKey)
    val defiLlama = DefiLlamaClient(client)
    val cryptoNews = CryptoNewsClient(client)
    val cryptoPanic = CryptoPanicClient(client, cryptoPanicToken)
    val reddit = RedditClient(client, redditClientId, redditClientSecret)
    val farcaster = FarcasterClient(client)
    val dexPaprika = DexPaprikaClient(client)

    // Pool cache: symbol -> (source, id)
    private val poolCache = mutableMapOf<String, Pair<String, String>>()

    suspend fun searchToken(query: String): JsonObject {
        return try {
            val dexResult = dexScreener.searchPairs(query)
            val pairs = dexResult["pairs"]?.jsonArray
            if (pairs != null && pairs.isNotEmpty()) {
                buildJsonObject {
                    put("status", "success")
                    put("source", "dexscreener")
                    put("count", pairs.size)
                    put("pairs", pairs)
                }
            } else {
                // Fallback to GeckoTerminal
                val geckoResult = geckoTerminal.searchPools(query)
                buildJsonObject {
                    put("status", "success")
                    put("source", "geckoterminal")
                    put("data", geckoResult)
                }
            }
        } catch (e: Exception) {
            buildJsonObject {
                put("status", "error")
                put("message", e.message ?: "Search failed")
            }
        }
    }

    suspend fun fetchLivePairs(token: String): JsonObject {
        return try {
            val result = dexScreener.searchPairs(token)
            buildJsonObject {
                put("status", "success")
                put("source", "dexscreener")
                put("pairs", result["pairs"] ?: JsonArray(emptyList()))
                put("count", result["pairs"]?.jsonArray?.size ?: 0)
            }
        } catch (e: Exception) {
            buildJsonObject {
                put("status", "error")
                put("message", e.message ?: "Failed to fetch pairs")
            }
        }
    }

    /**
     * Fetch OHLCV candles with fallback chain:
     * GeckoTerminal -> Binance -> DexPaprika
     */
    suspend fun fetchOhlcv(
        symbol: String,
        timeframe: String = "1h",
        limit: Int = 200,
        network: String? = null,
        poolAddress: String? = null
    ): List<Candle> {
        // Direct pool address if provided
        if (network != null && poolAddress != null) {
            return try {
                geckoTerminal.getOhlcv(network, poolAddress, timeframe, limit)
            } catch (_: Exception) {
                emptyList()
            }
        }

        // Check cache
        val cached = poolCache[symbol.uppercase()]
        if (cached != null) {
            return try {
                when (cached.first) {
                    "binance" -> binance.getOhlcv(symbol, timeframe, limit)
                    else -> geckoTerminal.getOhlcv(cached.first, cached.second, timeframe, limit)
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        // Try Binance first (fastest, no pool lookup needed)
        try {
            val candles = binance.getOhlcv(symbol, timeframe, limit)
            if (candles.isNotEmpty()) {
                poolCache[symbol.uppercase()] = "binance" to symbol
                return candles
            }
        } catch (_: Exception) { }

        // GeckoTerminal search
        try {
            val search = geckoTerminal.searchPools(symbol)
            val data = search["data"]?.jsonArray?.firstOrNull()?.jsonObject
            val pool = data?.get("attributes")?.jsonObject
            val poolAddr = data?.get("id")?.jsonPrimitive?.content
            val net = pool?.get("network")?.jsonObject?.get("identifier")?.jsonPrimitive?.content
                ?: poolAddr?.substringBefore("_")

            if (poolAddr != null && net != null) {
                val addr = poolAddr.substringAfter("_")
                val candles = geckoTerminal.getOhlcv(net, addr, timeframe, limit)
                if (candles.isNotEmpty()) {
                    poolCache[symbol.uppercase()] = net to addr
                    return candles
                }
            }
        } catch (_: Exception) { }

        return emptyList()
    }

    suspend fun fetchNewPairs(): JsonObject {
        return try {
            val result = dexScreener.getNewPairs()
            buildJsonObject {
                put("status", "success")
                put("source", "dexscreener")
                put("pairs", result)
                put("count", result.size)
            }
        } catch (e: Exception) {
            buildJsonObject {
                put("status", "error")
                put("message", e.message ?: "Failed to fetch new pairs")
            }
        }
    }

    suspend fun fetchNews(token: String? = null): JsonObject {
        // Primary: Free Crypto News API (no auth needed)
        val primary = try {
            if (token != null) {
                cryptoNews.searchNews(token)
            } else {
                cryptoNews.fetchLatestNews()
            }
        } catch (_: Exception) { null }

        val primaryArticles = primary?.get("articles")?.jsonArray

        // Optional: CryptoPanic (if key configured)
        val cpArticles = try {
            val cp = cryptoPanic.fetchNews()
            cp["results"]?.jsonArray
        } catch (_: Exception) { null }

        // Merge results
        val allArticles = mutableListOf<JsonElement>()
        primaryArticles?.let { allArticles.addAll(it) }
        cpArticles?.let { allArticles.addAll(it) }

        return buildJsonObject {
            put("status", if (allArticles.isNotEmpty()) "success" else "error")
            put("sources", buildJsonObject {
                put("crypto_news_api", primaryArticles != null)
                put("cryptopanic", cpArticles != null)
            })
            put("articles", JsonArray(allArticles))
            put("count", allArticles.size)
            if (allArticles.isEmpty()) {
                put("message", "No news sources available")
            }
        }
    }

    suspend fun fetchSentiment(asset: String): JsonObject {
        return try {
            cryptoNews.getSentiment(asset)
        } catch (e: Exception) {
            buildJsonObject {
                put("status", "error")
                put("message", e.message ?: "Sentiment fetch failed")
            }
        }
    }

    suspend fun fetchFearGreed(): JsonObject {
        return try {
            cryptoNews.getFearGreedIndex()
        } catch (e: Exception) {
            buildJsonObject {
                put("status", "error")
                put("message", e.message ?: "Fear & Greed fetch failed")
            }
        }
    }

    suspend fun fetchRedditPosts(subreddit: String = "cryptocurrency"): JsonObject {
        return try {
            val result = reddit.fetchPosts(subreddit)
            val posts = result["data"]?.jsonObject?.get("children")?.jsonArray
                ?: JsonArray(emptyList())
            buildJsonObject {
                put("status", "success")
                put("source", "reddit")
                put("posts", posts)
                put("count", posts.size)
            }
        } catch (e: Exception) {
            buildJsonObject {
                put("status", "error")
                put("message", e.message ?: "Failed to fetch reddit")
            }
        }
    }

    suspend fun fetchFarcasterCasts(channel: String = "ethereum"): JsonObject {
        return try {
            val casts = farcaster.fetchCasts(channel)
            buildJsonObject {
                put("status", "success")
                put("source", "farcaster")
                put("casts", JsonArray(casts))
                put("count", casts.size)
            }
        } catch (e: Exception) {
            buildJsonObject {
                put("status", "error")
                put("message", e.message ?: "Failed to fetch farcaster")
            }
        }
    }
}
