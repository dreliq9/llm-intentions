package com.taichi.tools

import com.androidmcp.core.protocol.ContentBlock
import com.androidmcp.core.protocol.ToolCallResult
import com.androidmcp.core.protocol.ToolInfo
import com.androidmcp.core.registry.McpToolDef
import com.androidmcp.core.registry.ToolRegistry
import com.androidmcp.core.registry.jsonSchema
import com.androidmcp.intent.ToolAppService
import com.taichi.analyzer.*
import com.taichi.config.ApiKeyStore
import com.taichi.scraper.ScraperBridge
import com.taichi.scraper.TaichiJson
import com.taichi.trading.AddressStore
import com.taichi.trading.PaperEngine
import kotlinx.serialization.json.*

class TaichiToolService : ToolAppService() {

    private lateinit var bridge: ScraperBridge
    private lateinit var cycleTracker: CycleTracker
    private lateinit var paperEngine: PaperEngine
    private lateinit var addressStore: AddressStore

    override fun onCreateTools(registry: ToolRegistry) {
        val ctx = applicationContext
        val keyStore = ApiKeyStore(ctx)
        bridge = ScraperBridge(
            cryptoPanicToken = keyStore.cryptoPanicToken,
            redditClientId = keyStore.redditClientId,
            redditClientSecret = keyStore.redditClientSecret,
            coinGeckoApiKey = keyStore.coinGeckoApiKey
        )
        val dataDir = ctx.getDir("taichi", 0)
        cycleTracker = CycleTracker(dataDir)
        addressStore = AddressStore(dataDir)
        paperEngine = PaperEngine(ctx) { symbol ->
            bridge.binance.getPrice(symbol)
                ?: try {
                    val search = bridge.dexScreener.searchPairs(symbol)
                    search["pairs"]?.jsonArray?.firstOrNull()
                        ?.jsonObject?.get("priceUsd")?.jsonPrimitive?.doubleOrNull
                } catch (_: Exception) { null }
        }

        // --- search_token ---
        registry.textTool(
            "search_token",
            "Search for a crypto token across DEX aggregators (DexScreener + GeckoTerminal)",
            jsonSchema { string("query", "Token name, symbol, or address") }
        ) { args ->
            val query = args["query"]?.jsonPrimitive?.content ?: ""
            val result = bridge.searchToken(query)
            TaichiJson.encodeToString(JsonObject.serializer(), result)
        }

        // --- fetch_live_pairs ---
        registry.textTool(
            "fetch_live_pairs",
            "Get real-time pair data with price, volume, liquidity, and buy/sell counts",
            jsonSchema { string("token", "Token name or symbol") }
        ) { args ->
            val token = args["token"]?.jsonPrimitive?.content ?: ""
            val result = bridge.fetchLivePairs(token)
            TaichiJson.encodeToString(JsonObject.serializer(), result)
        }

        // --- fetch_ohlcv ---
        registry.textTool(
            "fetch_ohlcv",
            "Fetch OHLCV candlestick data. Tries Binance first, then GeckoTerminal, then DexPaprika.",
            jsonSchema {
                string("symbol", "Token symbol (e.g., ETH, BTC)")
                string("timeframe", "Candle timeframe: 1m, 5m, 15m, 1h, 4h, 1d", required = false)
                integer("limit", "Number of candles (default 200)", required = false)
                string("network", "Network for DEX data (e.g., eth, solana)", required = false)
                string("pool_address", "Specific pool address for DEX data", required = false)
            }
        ) { args ->
            val symbol = args["symbol"]?.jsonPrimitive?.content ?: ""
            val timeframe = args["timeframe"]?.jsonPrimitive?.contentOrNull ?: "1h"
            val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 200
            val network = args["network"]?.jsonPrimitive?.contentOrNull
            val poolAddress = args["pool_address"]?.jsonPrimitive?.contentOrNull

            val candles = bridge.fetchOhlcv(symbol, timeframe, limit, network, poolAddress)
            if (candles.isEmpty()) {
                "{\"status\":\"error\",\"message\":\"No OHLCV data found for $symbol\"}"
            } else {
                val arr = JsonArray(candles.map { c ->
                    buildJsonObject {
                        put("timestamp", c.timestamp)
                        put("open", c.open)
                        put("high", c.high)
                        put("low", c.low)
                        put("close", c.close)
                        put("volume", c.volume)
                    }
                })
                buildJsonObject {
                    put("status", "success")
                    put("symbol", symbol)
                    put("timeframe", timeframe)
                    put("count", candles.size)
                    put("candles", arr)
                }.toString()
            }
        }

        // --- fetch_new_pairs ---
        registry.textTool(
            "fetch_new_pairs",
            "Get recently created token pairs from DexScreener",
            jsonSchema { }
        ) { _ ->
            val result = bridge.fetchNewPairs()
            TaichiJson.encodeToString(JsonObject.serializer(), result)
        }

        // --- fetch_news ---
        registry.textTool(
            "fetch_news",
            "Fetch latest crypto news from CryptoPanic",
            jsonSchema { string("token", "Filter by token (optional)", required = false) }
        ) { args ->
            val token = args["token"]?.jsonPrimitive?.contentOrNull
            val result = bridge.fetchNews(token)
            TaichiJson.encodeToString(JsonObject.serializer(), result)
        }

        // --- fetch_reddit ---
        registry.textTool(
            "fetch_reddit",
            "Fetch recent posts from a crypto subreddit",
            jsonSchema { string("subreddit", "Subreddit name (default: cryptocurrency)", required = false) }
        ) { args ->
            val subreddit = args["subreddit"]?.jsonPrimitive?.contentOrNull ?: "cryptocurrency"
            val result = bridge.fetchRedditPosts(subreddit)
            TaichiJson.encodeToString(JsonObject.serializer(), result)
        }

        // --- fetch_farcaster ---
        registry.textTool(
            "fetch_farcaster",
            "Fetch recent casts from a Farcaster channel",
            jsonSchema { string("channel", "Channel name (default: ethereum)", required = false) }
        ) { args ->
            val channel = args["channel"]?.jsonPrimitive?.contentOrNull ?: "ethereum"
            val result = bridge.fetchFarcasterCasts(channel)
            TaichiJson.encodeToString(JsonObject.serializer(), result)
        }

        // --- fetch_sentiment ---
        registry.textTool(
            "fetch_sentiment",
            "Get AI-powered sentiment analysis for a crypto asset (no API key needed)",
            jsonSchema { string("asset", "Asset symbol (e.g., BTC, ETH)") }
        ) { args ->
            val asset = args["asset"]?.jsonPrimitive?.content ?: "BTC"
            val result = bridge.fetchSentiment(asset)
            result.toString()
        }

        // --- fetch_fear_greed ---
        registry.textTool(
            "fetch_fear_greed",
            "Get the current Crypto Fear & Greed Index (no API key needed)",
            jsonSchema { }
        ) { _ ->
            val result = bridge.fetchFearGreed()
            result.toString()
        }

        // --- technical_analysis ---
        registry.textTool(
            "technical_analysis",
            "Run full technical analysis (RSI, MACD, BBands, ATR, OBV, EMA, StochRSI, VWAP) on a token. Returns indicators, signals, and bullish/bearish summary.",
            jsonSchema {
                string("symbol", "Token symbol (e.g., ETH, BTC)")
                string("timeframe", "Candle timeframe (default: 1h)", required = false)
                integer("limit", "Number of candles (default: 200)", required = false)
            }
        ) { args ->
            val symbol = args["symbol"]?.jsonPrimitive?.content ?: ""
            val timeframe = args["timeframe"]?.jsonPrimitive?.contentOrNull ?: "1h"
            val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 200

            val candles = bridge.fetchOhlcv(symbol, timeframe, limit)
            if (candles.isEmpty()) {
                "{\"error\":\"No candle data for $symbol\"}"
            } else {
                val ta = TechnicalAnalyzer.runTa(candles)
                cycleTracker.saveSnapshot(symbol, ta)
                ta.toString()
            }
        }

        // --- signal_hierarchy ---
        registry.textTool(
            "signal_hierarchy",
            "Classify signals into Tier 1 (RSI), Tier 2 (EMA, OBV, StochRSI), Tier 3 (MACD). Returns primary signal, confirmations, contradictions, and overall strength. Run technical_analysis first.",
            jsonSchema {
                string("symbol", "Token symbol — runs TA internally")
                string("timeframe", "Candle timeframe (default: 1h)", required = false)
            }
        ) { args ->
            val symbol = args["symbol"]?.jsonPrimitive?.content ?: ""
            val timeframe = args["timeframe"]?.jsonPrimitive?.contentOrNull ?: "1h"

            val candles = bridge.fetchOhlcv(symbol, timeframe, 200)
            if (candles.isEmpty()) {
                "{\"error\":\"No candle data for $symbol\"}"
            } else {
                val ta = TechnicalAnalyzer.runTa(candles)
                cycleTracker.saveSnapshot(symbol, ta)
                val hierarchy = SignalHierarchy.classify(ta)
                buildJsonObject {
                    put("symbol", symbol)
                    put("timeframe", timeframe)
                    hierarchy.forEach { (k, v) -> put(k, v) }
                }.toString()
            }
        }

        // --- entry_gate ---
        registry.textTool(
            "entry_gate",
            "Check the 4-gate entry checklist: (1) positive EV, (2) Kelly > 0, (3) conviction >= 60, (4) regime approval. Returns CLEAR_TO_TRADE, PROCEED_WITH_CAUTION, or DO_NOT_TRADE.",
            jsonSchema {
                number("ev_per_trade", "Expected value per trade from backtest")
                number("kelly_fraction", "Kelly criterion fraction (0-1)")
                integer("conviction_score", "Conviction score from deep scan (0-100)")
                string("regime", "Current market regime (trending, compression, choppy, etc.)")
                string("signal_strength", "From signal_hierarchy: strong, moderate, weak, conflicted", required = false)
            }
        ) { args ->
            val result = SignalHierarchy.checkEntryGate(
                evPerTrade = args["ev_per_trade"]?.jsonPrimitive?.doubleOrNull,
                kellyFraction = args["kelly_fraction"]?.jsonPrimitive?.doubleOrNull,
                convictionScore = args["conviction_score"]?.jsonPrimitive?.intOrNull,
                regime = args["regime"]?.jsonPrimitive?.contentOrNull,
                signalStrength = args["signal_strength"]?.jsonPrimitive?.contentOrNull
            )
            result.toString()
        }

        // --- cycle_deltas ---
        registry.textTool(
            "cycle_deltas",
            "Compare current analysis to previous run for a symbol. Shows signal changes, indicator movement, price deltas, and divergences.",
            jsonSchema { string("symbol", "Token symbol") }
        ) { args ->
            val symbol = args["symbol"]?.jsonPrimitive?.content ?: ""
            val deltas = cycleTracker.getDeltas(symbol)
            deltas.toString()
        }

        // --- cycle_history ---
        registry.textTool(
            "cycle_history",
            "Get all saved analysis snapshots for a symbol. Useful for spotting trends across multiple analysis cycles.",
            jsonSchema { string("symbol", "Token symbol") }
        ) { args ->
            val symbol = args["symbol"]?.jsonPrimitive?.content ?: ""
            val history = cycleTracker.getHistory(symbol)
            buildJsonObject {
                put("symbol", symbol.uppercase())
                put("snapshots", history.size)
                put("history", history)
            }.toString()
        }

        // ====== PAPER TRADING ======

        // --- paper_trade ---
        registry.textTool(
            "paper_trade",
            "Execute a paper trade (buy or sell). Uses live DEX/CEX pricing. 0.1% fee per trade.",
            jsonSchema {
                string("symbol", "Token symbol (e.g., ETH, BTC)")
                string("action", "buy or sell")
                number("amount_usd", "USD amount to spend (for buys)", required = false)
                number("quantity", "Token quantity (for sells, or specific buy amount)", required = false)
                boolean("close_all", "Close entire position (for sells)", required = false)
            }
        ) { args ->
            val symbol = args["symbol"]?.jsonPrimitive?.content ?: ""
            val action = args["action"]?.jsonPrimitive?.content ?: ""
            val amountUsd = args["amount_usd"]?.jsonPrimitive?.doubleOrNull
            val quantity = args["quantity"]?.jsonPrimitive?.doubleOrNull
            val closeAll = args["close_all"]?.jsonPrimitive?.booleanOrNull ?: false

            when (action.lowercase()) {
                "buy" -> paperEngine.buy(symbol, amountUsd, quantity).toString()
                "sell" -> paperEngine.sell(symbol, quantity, closeAll).toString()
                else -> "{\"status\":\"error\",\"message\":\"Action must be 'buy' or 'sell'\"}"
            }
        }

        // --- paper_portfolio ---
        registry.textTool(
            "paper_portfolio",
            "View paper trading portfolio: cash, open positions with live prices and unrealized PnL, total value, win rate, fees.",
            jsonSchema { }
        ) { _ -> paperEngine.getPortfolio().toString() }

        // --- paper_history ---
        registry.textTool(
            "paper_history",
            "View paper trade history (buys and sells with prices, fees, timestamps).",
            jsonSchema {
                string("symbol", "Filter by symbol (optional)", required = false)
                integer("limit", "Max trades to return (default: 50)", required = false)
            }
        ) { args ->
            val symbol = args["symbol"]?.jsonPrimitive?.contentOrNull
            val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 50
            paperEngine.getTradeHistory(symbol, limit).toString()
        }

        // --- paper_closed ---
        registry.textTool(
            "paper_closed",
            "View closed positions with realized PnL (entry price, exit price, profit/loss).",
            jsonSchema { integer("limit", "Max positions to return (default: 50)", required = false) }
        ) { args ->
            val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 50
            paperEngine.getClosedPositions(limit).toString()
        }

        // --- paper_reset ---
        registry.textTool(
            "paper_reset",
            "Reset paper trading portfolio. Wipes all positions and trades, starts fresh.",
            jsonSchema { number("starting_capital", "Starting capital in USD (default: 10000)", required = false) }
        ) { args ->
            val capital = args["starting_capital"]?.jsonPrimitive?.doubleOrNull ?: 10_000.0
            paperEngine.reset(capital).toString()
        }

        // ====== ADVANCED ANALYSIS ======

        // --- backtest ---
        registry.textTool(
            "backtest",
            "Backtest trading signals against historical candles. Returns EV, Kelly sizing, Sharpe/Sortino, and max drawdown per signal.",
            jsonSchema {
                string("symbol", "Token symbol")
                string("timeframe", "Candle timeframe (default: 1h)", required = false)
                number("bankroll", "Current bankroll for Kelly sizing (default: reads from portfolio)", required = false)
                string("signals", "Comma-separated signals to test: rsi,macd,stochrsi,ema (default: all)", required = false)
            }
        ) { args ->
            val symbol = args["symbol"]?.jsonPrimitive?.content ?: ""
            val timeframe = args["timeframe"]?.jsonPrimitive?.contentOrNull ?: "1h"
            val bankroll = args["bankroll"]?.jsonPrimitive?.doubleOrNull
                ?: paperEngine.getPortfolio()["total_value"]?.jsonPrimitive?.doubleOrNull
                ?: 10_000.0
            val selectedSignals = args["signals"]?.jsonPrimitive?.contentOrNull
                ?.split(",")?.map { it.trim().lowercase() }

            val candles = bridge.fetchOhlcv(symbol, timeframe, 500)
            if (candles.size < 50) {
                "{\"error\":\"Need at least 50 candles for backtest, got ${candles.size}\"}"
            } else {
                val result = Backtest.fullBacktest(candles, bankroll, selectedSignals)
                buildJsonObject {
                    put("symbol", symbol)
                    put("timeframe", timeframe)
                    put("candles", candles.size)
                    result.forEach { (k, v) -> put(k, v) }
                }.toString()
            }
        }

        // --- drawdown_check ---
        registry.textTool(
            "drawdown_check",
            "Check hard drawdown limits: -10% per position, -15% portfolio. Non-overridable.",
            jsonSchema { }
        ) { _ ->
            val portfolio = paperEngine.getPortfolio()
            BiasTools.drawdownCheck(portfolio).toString()
        }

        // --- bear_case ---
        registry.textTool(
            "bear_case",
            "Generate the strongest bear case against holding a token. Forces contrary thinking.",
            jsonSchema {
                string("symbol", "Token symbol")
                string("timeframe", "Candle timeframe (default: 1h)", required = false)
            }
        ) { args ->
            val symbol = args["symbol"]?.jsonPrimitive?.content ?: ""
            val timeframe = args["timeframe"]?.jsonPrimitive?.contentOrNull ?: "1h"

            val candles = bridge.fetchOhlcv(symbol, timeframe, 200)
            if (candles.isEmpty()) {
                "{\"error\":\"No candle data for $symbol\"}"
            } else {
                val ta = TechnicalAnalyzer.runTa(candles)
                val bearCase = BiasTools.bearCase(ta, candles)
                buildJsonObject {
                    put("symbol", symbol)
                    bearCase.forEach { (k, v) -> put(k, v) }
                }.toString()
            }
        }

        // --- position_audit ---
        registry.textTool(
            "position_audit",
            "For each open position: would you enter it fresh today? Removes sunk-cost bias.",
            jsonSchema { }
        ) { _ ->
            val portfolio = paperEngine.getPortfolio()
            val positions = portfolio["open_positions"]?.jsonArray ?: JsonArray(emptyList())

            if (positions.isEmpty()) {
                "{\"message\":\"No open positions to audit\"}"
            } else {
                val auditFn = BiasTools.positionAudit(positions) { symbol ->
                    val candles = bridge.fetchOhlcv(symbol, "1h", 200)
                    if (candles.isNotEmpty()) TechnicalAnalyzer.runTa(candles) else null
                }
                auditFn().toString()
            }
        }

        // --- deep_scan ---
        registry.textTool(
            "deep_scan",
            "Comprehensive token evaluation: backtest EV + multi-timeframe TA + regime + anomaly detection + signal hierarchy. Returns 0-100 conviction score.",
            jsonSchema {
                string("symbol", "Token symbol")
                number("bankroll", "Bankroll for Kelly sizing (default: portfolio value)", required = false)
            }
        ) { args ->
            val symbol = args["symbol"]?.jsonPrimitive?.content ?: ""
            val bankroll = args["bankroll"]?.jsonPrimitive?.doubleOrNull
                ?: paperEngine.getPortfolio()["total_value"]?.jsonPrimitive?.doubleOrNull
                ?: 10_000.0
            DeepScan.run(symbol, bridge, bankroll).toString()
        }

        // --- trade_review ---
        registry.textTool(
            "trade_review",
            "3-evaluator trade gate. TA evaluator checks indicators, Risk evaluator checks position sizing. Any BLOCK = don't trade.",
            jsonSchema {
                string("symbol", "Token symbol")
                string("action", "buy or sell")
                number("amount_usd", "Trade amount in USD")
            }
        ) { args ->
            val symbol = args["symbol"]?.jsonPrimitive?.content ?: ""
            val action = args["action"]?.jsonPrimitive?.content ?: "buy"
            val amountUsd = args["amount_usd"]?.jsonPrimitive?.doubleOrNull ?: 0.0

            val candles = bridge.fetchOhlcv(symbol, "1h", 200)
            val ta = if (candles.isNotEmpty()) TechnicalAnalyzer.runTa(candles) else buildJsonObject {}
            val portfolio = paperEngine.getPortfolio()

            val taEval = TradeReview.taEvaluation(ta, action)
            val riskEval = TradeReview.riskEvaluation(action, amountUsd, portfolio)
            val result = TradeReview.synthesize(listOf(taEval, riskEval))

            buildJsonObject {
                put("symbol", symbol)
                put("action", action)
                put("amount_usd", amountUsd)
                result.forEach { (k, v) -> put(k, v) }
            }.toString()
        }

        // --- classify_regime ---
        registry.textTool(
            "classify_regime",
            "Classify current market regime using KMeans clustering. Returns regime label and signal guidance.",
            jsonSchema {
                string("symbol", "Token symbol")
                string("timeframe", "Candle timeframe (default: 1h)", required = false)
            }
        ) { args ->
            val symbol = args["symbol"]?.jsonPrimitive?.content ?: ""
            val timeframe = args["timeframe"]?.jsonPrimitive?.contentOrNull ?: "1h"
            val candles = bridge.fetchOhlcv(symbol, timeframe, 200)
            if (candles.size < 50) {
                "{\"error\":\"Need 50+ candles, got ${candles.size}\"}"
            } else {
                val result = MlAnalyzer.classifyRegime(candles)
                buildJsonObject {
                    put("symbol", symbol)
                    result.forEach { (k, v) -> put(k, v) }
                }.toString()
            }
        }

        // --- detect_anomalies ---
        registry.textTool(
            "detect_anomalies",
            "Z-score anomaly detection on price and volume data. Flags unusual current readings.",
            jsonSchema {
                string("symbol", "Token symbol")
                string("columns", "Comma-separated: close,volume,high,low (default: close,volume)", required = false)
                number("threshold", "Z-score threshold for anomaly (default: 2.5)", required = false)
            }
        ) { args ->
            val symbol = args["symbol"]?.jsonPrimitive?.content ?: ""
            val columns = args["columns"]?.jsonPrimitive?.contentOrNull?.split(",")?.map { it.trim() }
                ?: listOf("close", "volume")
            val threshold = args["threshold"]?.jsonPrimitive?.doubleOrNull ?: 2.5
            val candles = bridge.fetchOhlcv(symbol, "1h", 200)
            if (candles.isEmpty()) {
                "{\"error\":\"No data for $symbol\"}"
            } else {
                val result = MlAnalyzer.detectAnomalies(candles, columns, threshold = threshold)
                buildJsonObject {
                    put("symbol", symbol)
                    result.forEach { (k, v) -> put(k, v) }
                }.toString()
            }
        }

        // --- correlation_check ---
        registry.textTool(
            "correlation_check",
            "Check correlation between all open positions. Flags dangerously correlated pairs.",
            jsonSchema { }
        ) { _ ->
            val portfolio = paperEngine.getPortfolio()
            val positions = portfolio["open_positions"]?.jsonArray ?: JsonArray(emptyList())
            if (positions.size < 2) {
                "{\"message\":\"Need at least 2 open positions\"}"
            } else {
                val posCandles = mutableMapOf<String, List<com.taichi.model.Candle>>()
                for (pos in positions) {
                    val sym = pos.jsonObject["symbol"]?.jsonPrimitive?.content ?: continue
                    val candles = bridge.fetchOhlcv(sym, "1h", 100)
                    if (candles.isNotEmpty()) posCandles[sym] = candles
                }
                PortfolioAnalyzer.correlationCheck(posCandles).toString()
            }
        }

        // --- thesis_check ---
        registry.textTool(
            "thesis_check",
            "Check if your original trade thesis is still valid against current TA signals.",
            jsonSchema {
                string("symbol", "Token symbol")
                string("thesis", "Your original trade thesis")
            }
        ) { args ->
            val symbol = args["symbol"]?.jsonPrimitive?.content ?: ""
            val thesis = args["thesis"]?.jsonPrimitive?.content ?: ""
            val candles = bridge.fetchOhlcv(symbol, "1h", 200)
            if (candles.isEmpty()) {
                "{\"error\":\"No data for $symbol\"}"
            } else {
                val ta = TechnicalAnalyzer.runTa(candles)
                PortfolioAnalyzer.thesisCheck(thesis, ta).toString()
            }
        }

        // --- volatility_scanner ---
        registry.textTool(
            "volatility_scanner",
            "Scan for volatile tokens with sufficient volume and liquidity.",
            jsonSchema {
                number("min_volume_24h", "Minimum 24h volume in USD (default: 500000)", required = false)
                number("min_price_change_pct", "Minimum absolute price change % (default: 5)", required = false)
                integer("max_results", "Max candidates to return (default: 10)", required = false)
            }
        ) { args ->
            val minVol = args["min_volume_24h"]?.jsonPrimitive?.doubleOrNull ?: 500_000.0
            val minChange = args["min_price_change_pct"]?.jsonPrimitive?.doubleOrNull ?: 5.0
            val maxResults = args["max_results"]?.jsonPrimitive?.intOrNull ?: 10
            Scanner.volatilityScan(bridge, minVol, minChange, maxResults).toString()
        }

        // --- liquidity_gate ---
        registry.textTool(
            "liquidity_gate",
            "Check if a position size is safe given a token's liquidity. Returns CLEAR/PROCEED_WITH_CAUTION/REDUCE_SIZE/BLOCKED.",
            jsonSchema {
                string("symbol", "Token symbol")
                number("position_size_usd", "Proposed position size in USD")
            }
        ) { args ->
            val symbol = args["symbol"]?.jsonPrimitive?.content ?: ""
            val size = args["position_size_usd"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            Scanner.liquidityGate(bridge, symbol, size).toString()
        }

        // --- fundamentals ---
        registry.textTool(
            "fundamentals",
            "Get token fundamentals from CoinGecko: market data, tokenomics, dev activity, community stats.",
            jsonSchema { string("symbol", "Token symbol or CoinGecko ID") }
        ) { args ->
            val symbol = args["symbol"]?.jsonPrimitive?.content?.lowercase() ?: ""
            val coinId = symbolToCoinGeckoId(symbol)
            try {
                val data = bridge.coinGecko.getCoinData(coinId)
                data.toString()
            } catch (e: Exception) {
                "{\"error\":\"CoinGecko lookup failed for '$coinId': ${e.message}\"}"
            }
        }

        // --- dev_activity ---
        registry.textTool(
            "dev_activity",
            "Get developer activity metrics from CoinGecko: commits, PRs, stars, forks, contributors.",
            jsonSchema { string("symbol", "Token symbol or CoinGecko ID") }
        ) { args ->
            val symbol = args["symbol"]?.jsonPrimitive?.content?.lowercase() ?: ""
            val coinId = symbolToCoinGeckoId(symbol)
            try {
                val data = bridge.coinGecko.getCoinData(coinId)
                val devData = data["developer_data"]?.jsonObject
                val repoUrl = data["links"]?.jsonObject?.get("repos_url")?.jsonObject
                    ?.get("github")?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull

                buildJsonObject {
                    put("symbol", symbol)
                    put("coin_id", coinId)
                    if (devData != null) put("developer_data", devData)
                    if (repoUrl != null) put("github_url", repoUrl)

                    val commits = devData?.get("commit_count_4_weeks")?.jsonPrimitive?.intOrNull ?: 0
                    val prs = devData?.get("pull_request_contributors")?.jsonPrimitive?.intOrNull ?: 0
                    val stars = devData?.get("stars")?.jsonPrimitive?.intOrNull ?: 0
                    var score = 0
                    score += when { commits >= 100 -> 3; commits >= 30 -> 2; commits >= 5 -> 1; else -> 0 }
                    score += when { prs >= 20 -> 2; prs >= 5 -> 1; else -> 0 }
                    score += when { stars >= 1000 -> 2; stars >= 100 -> 1; else -> 0 }
                    val level = when (score) {
                        0 -> "dead"; 1 -> "minimal"; 2 -> "low"; 3 -> "moderate"
                        4 -> "active"; 5 -> "very_active"; else -> "highly_active"
                    }
                    put("activity_score", score)
                    put("activity_level", level)
                }.toString()
            } catch (e: Exception) {
                "{\"error\":\"Dev activity lookup failed: ${e.message}\"}"
            }
        }

        // ====== ON-CHAIN ANALYSIS ======

        // --- exchange_flow_analysis ---
        registry.textTool(
            "exchange_flow_analysis",
            "Analyze exchange flow proxies from DEX buy/sell data. Shows net flow trend, momentum, and volume spikes.",
            jsonSchema { string("symbol", "Token symbol") }
        ) { args ->
            val symbol = args["symbol"]?.jsonPrimitive?.content ?: ""
            OnchainAnalyzer.analyzeExchangeFlows(bridge, symbol).toString()
        }

        // --- wallet_analysis ---
        registry.textTool(
            "wallet_analysis",
            "Analyze token supply distribution, holder metrics, and concentration from CoinGecko data.",
            jsonSchema { string("symbol", "Token symbol") }
        ) { args ->
            val symbol = args["symbol"]?.jsonPrimitive?.content ?: ""
            OnchainAnalyzer.analyzeWallets(bridge, symbol).toString()
        }

        // --- liquidity_analysis ---
        registry.textTool(
            "liquidity_analysis",
            "Analyze DEX liquidity depth, pool concentration, capital efficiency, and TVL.",
            jsonSchema { string("symbol", "Token symbol") }
        ) { args ->
            val symbol = args["symbol"]?.jsonPrimitive?.content ?: ""
            OnchainAnalyzer.analyzeLiquidity(bridge, symbol).toString()
        }

        // ====== ADDRESS REGISTRY ======

        // --- add_exchange_address ---
        registry.textTool(
            "add_exchange_address",
            "Register a known exchange wallet address for flow tracking.",
            jsonSchema {
                string("address", "Wallet address")
                string("label", "Exchange name (e.g., Binance, Coinbase)")
            }
        ) { args ->
            val address = args["address"]?.jsonPrimitive?.content ?: ""
            val label = args["label"]?.jsonPrimitive?.content ?: ""
            addressStore.addAddress(address, label).toString()
        }

        // --- check_exchange_address ---
        registry.textTool(
            "check_exchange_address",
            "Check if an address is a known exchange wallet.",
            jsonSchema { string("address", "Wallet address to check") }
        ) { args ->
            val address = args["address"]?.jsonPrimitive?.content ?: ""
            val isExchange = addressStore.isExchange(address)
            val label = addressStore.getLabel(address)
            buildJsonObject {
                put("address", address)
                put("is_exchange", isExchange)
                if (label != null) put("label", label) else put("label", JsonNull)
            }.toString()
        }

        // --- list_exchange_addresses ---
        registry.textTool(
            "list_exchange_addresses",
            "View the exchange address registry: seed addresses, confirmed, candidates, counts.",
            jsonSchema { }
        ) { _ -> addressStore.getSummary().toString() }

        // ====== PORTFOLIO OPTIMIZATION ======

        // --- portfolio_optimize ---
        registry.textTool(
            "portfolio_optimize",
            "Inverse-volatility risk parity optimization. Shows current vs optimal allocation and rebalance actions.",
            jsonSchema { }
        ) { _ ->
            val portfolio = paperEngine.getPortfolio()
            val positions = portfolio["open_positions"]?.jsonArray ?: JsonArray(emptyList())

            if (positions.size < 2) {
                "{\"message\":\"Need at least 2 positions to optimize\"}"
            } else {
                val posCandles = mutableMapOf<String, List<com.taichi.model.Candle>>()
                for (pos in positions) {
                    val sym = pos.jsonObject["symbol"]?.jsonPrimitive?.content ?: continue
                    val candles = bridge.fetchOhlcv(sym, "1h", 168)
                    if (candles.isNotEmpty()) posCandles[sym] = candles
                }
                PortfolioOptimizer.optimize(portfolio, posCandles).toString()
            }
        }

        // --- protocol_revenue ---
        registry.textTool(
            "protocol_revenue",
            "Get protocol fees and revenue data from DefiLlama. Shows 24h, 7d, 30d fees and revenue.",
            jsonSchema { string("protocol", "Protocol name (e.g., uniswap, aave, lido)") }
        ) { args ->
            val protocol = args["protocol"]?.jsonPrimitive?.content ?: ""
            try {
                val fees = bridge.defiLlama.getFees(protocol)
                // Extract only summary fields — full response can be 2MB+ and blow the IPC limit
                buildJsonObject {
                    put("protocol", protocol)
                    fees["name"]?.let { put("name", it) }
                    fees["defillamaId"]?.let { put("defillamaId", it) }
                    fees["total24h"]?.let { put("fees_24h", it) }
                    fees["total48hto24h"]?.let { put("fees_48h_to_24h", it) }
                    fees["total7d"]?.let { put("fees_7d", it) }
                    fees["total30d"]?.let { put("fees_30d", it) }
                    fees["total1y"]?.let { put("fees_1y", it) }
                    fees["totalAllTime"]?.let { put("fees_all_time", it) }
                    fees["revenue24h"]?.let { put("revenue_24h", it) }
                    fees["revenue7d"]?.let { put("revenue_7d", it) }
                    fees["revenue30d"]?.let { put("revenue_30d", it) }
                    fees["methodologyURL"]?.let { put("methodology_url", it) }
                }.toString()
            } catch (e: Exception) {
                "{\"error\":\"DefiLlama lookup failed for '$protocol': ${e.message}\"}"
            }
        }
    }

    companion object {
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

        /**
         * Extension to register text-returning tools on a ToolRegistry,
         * mirroring the McpServerBuilder.textTool DSL.
         */
        private fun ToolRegistry.textTool(
            name: String,
            description: String,
            params: JsonObject,
            handler: suspend (JsonObject) -> String
        ) {
            register(McpToolDef(
                info = ToolInfo(name = name, description = description, inputSchema = params),
                handler = { args ->
                    ToolCallResult(content = listOf(ContentBlock.text(handler(args))))
                }
            ))
        }
    }
}
