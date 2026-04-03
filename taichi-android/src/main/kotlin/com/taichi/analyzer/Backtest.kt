package com.taichi.analyzer

import com.taichi.model.Candle
import kotlinx.serialization.json.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Signal backtesting engine. Tests RSI/MACD/StochRSI/EMA signals
 * against historical candle data and computes EV, Kelly, and risk metrics.
 */
object Backtest {

    data class Trade(val entryPrice: Double, val exitPrice: Double, val barsHeld: Int) {
        val returnPct: Double get() = (exitPrice - entryPrice) / entryPrice
        val isWin: Boolean get() = returnPct > 0
    }

    // --- Signal functions ---

    private fun rsiSignal(closes: DoubleArray, i: Int, rsiValues: DoubleArray): Int {
        if (rsiValues[i].isNaN()) return 0
        return when {
            rsiValues[i] < 30 -> 1   // buy
            rsiValues[i] > 70 -> -1  // sell
            else -> 0
        }
    }

    private fun macdSignal(histValues: DoubleArray, i: Int): Int {
        if (i < 1 || histValues[i].isNaN() || histValues[i - 1].isNaN()) return 0
        return when {
            histValues[i] > 0 && histValues[i - 1] <= 0 -> 1   // bullish crossover
            histValues[i] < 0 && histValues[i - 1] >= 0 -> -1  // bearish crossover
            else -> 0
        }
    }

    private fun stochRsiSignal(closes: DoubleArray, i: Int): Int {
        // Simplified: compute StochRSI at position i using lookback
        if (i < 28) return 0
        val slice = closes.sliceArray(0..i)
        val sr = Indicators.stochRsi(slice)
        return when {
            sr.k < 20 -> 1
            sr.k > 80 -> -1
            else -> 0
        }
    }

    private fun emaSignal(closes: DoubleArray, i: Int): Int {
        if (i < 21) return 0
        val slice = closes.sliceArray(0..i)
        val ema9 = Indicators.ema(slice, 9)
        val ema21 = Indicators.ema(slice, 21)
        val e9 = ema9.lastOrNull { !it.isNaN() } ?: return 0
        val e21 = ema21.lastOrNull { !it.isNaN() } ?: return 0
        return if (e9 > e21) 1 else -1
    }

    /**
     * Backtest a signal across candle data. Returns list of trades.
     */
    fun backtestSignal(
        candles: List<Candle>,
        signalName: String,
        feePct: Double = 0.001
    ): List<Trade> {
        val closes = candles.map { it.close }.toDoubleArray()
        if (closes.size < 30) return emptyList()

        val trades = mutableListOf<Trade>()
        var inPosition = false
        var entryPrice = 0.0
        var entryBar = 0

        // Pre-compute indicators for efficiency
        val rsiResult = if (signalName == "rsi") Indicators.rsi(closes) else null
        val macdResult = if (signalName == "macd") Indicators.macd(closes) else null

        for (i in 1 until closes.size) {
            val signal = when (signalName) {
                "rsi" -> rsiSignal(closes, i, rsiResult!!.values)
                "macd" -> macdSignal(macdResult!!.histogram, i)
                "stochrsi" -> if (i % 5 == 0) stochRsiSignal(closes, i) else 0 // sample every 5 bars
                "ema" -> emaSignal(closes, i)
                else -> 0
            }

            if (!inPosition && signal == 1) {
                entryPrice = closes[i] * (1 + feePct) // slippage + fee
                entryBar = i
                inPosition = true
            } else if (inPosition && (signal == -1 || i == closes.size - 1)) {
                val exitPrice = closes[i] * (1 - feePct)
                trades.add(Trade(entryPrice, exitPrice, i - entryBar))
                inPosition = false
            }
        }

        return trades
    }

    /**
     * Compute expected value metrics from trades.
     */
    fun computeEv(trades: List<Trade>): JsonObject {
        if (trades.isEmpty()) return buildJsonObject {
            put("trades", 0)
            put("verdict", "insufficient_data")
        }

        val wins = trades.filter { it.isWin }
        val losses = trades.filter { !it.isWin }
        val winRate = wins.size.toDouble() / trades.size
        val avgWin = if (wins.isNotEmpty()) wins.map { it.returnPct }.average() * 100 else 0.0
        val avgLoss = if (losses.isNotEmpty()) losses.map { abs(it.returnPct) }.average() * 100 else 0.0
        val payoffRatio = if (avgLoss > 0) avgWin / avgLoss else Double.MAX_VALUE
        val evPerTrade = (winRate * avgWin) - ((1 - winRate) * avgLoss)

        return buildJsonObject {
            put("trades", trades.size)
            put("wins", wins.size)
            put("losses", losses.size)
            put("win_rate", (winRate * 100).round())
            put("avg_win_pct", avgWin.round())
            put("avg_loss_pct", avgLoss.round())
            put("payoff_ratio", payoffRatio.round())
            put("ev_per_trade", evPerTrade.round())
            put("verdict", if (evPerTrade > 0) "positive_ev" else "negative_ev")
        }
    }

    /**
     * Compute Kelly criterion for position sizing.
     */
    fun computeKelly(trades: List<Trade>, bankroll: Double): JsonObject {
        if (trades.size < 3) return buildJsonObject {
            put("kelly_fraction", 0.0)
            put("half_kelly", 0.0)
            put("kelly_usd", 0.0)
            put("verdict", "insufficient_trades")
        }

        val wins = trades.filter { it.isWin }
        val losses = trades.filter { !it.isWin }
        val p = wins.size.toDouble() / trades.size
        val q = 1 - p
        val avgWin = if (wins.isNotEmpty()) wins.map { it.returnPct }.average() else 0.0
        val avgLoss = if (losses.isNotEmpty()) losses.map { abs(it.returnPct) }.average() else 0.001
        val b = avgWin / avgLoss

        // Kelly: f = (p*b - q) / b
        val kelly = if (b > 0) (p * b - q) / b else 0.0
        val cappedKelly = kelly.coerceIn(0.0, 0.25) // cap at 25%
        val halfKelly = cappedKelly / 2

        return buildJsonObject {
            put("kelly_fraction", cappedKelly.round())
            put("half_kelly", halfKelly.round())
            put("kelly_usd", (halfKelly * bankroll).round())
            put("win_prob", (p * 100).round())
            put("payoff_ratio", b.round())
            put("verdict", if (kelly > 0) "edge_exists" else "no_edge")
        }
    }

    /**
     * Compute risk-adjusted returns (Sharpe, Sortino, max drawdown).
     */
    fun computeRiskMetrics(trades: List<Trade>): JsonObject {
        if (trades.size < 3) return buildJsonObject {
            put("verdict", "insufficient_trades")
        }

        val returns = trades.map { it.returnPct }
        val mean = returns.average()
        val std = sqrt(returns.map { (it - mean) * (it - mean) }.average())
        val downsideReturns = returns.filter { it < 0 }
        val downsideStd = if (downsideReturns.isNotEmpty()) {
            sqrt(downsideReturns.map { it * it }.average())
        } else 0.0

        val sharpe = if (std > 0) mean / std * sqrt(250.0) else 0.0
        val sortino = if (downsideStd > 0) mean / downsideStd * sqrt(250.0) else 0.0

        // Max drawdown
        var peak = 0.0
        var maxDd = 0.0
        var cumulative = 1.0
        for (r in returns) {
            cumulative *= (1 + r)
            peak = max(peak, cumulative)
            val dd = (peak - cumulative) / peak
            maxDd = max(maxDd, dd)
        }

        return buildJsonObject {
            put("sharpe_ratio", sharpe.round())
            put("sortino_ratio", sortino.round())
            put("max_drawdown_pct", (maxDd * 100).round())
            put("total_return_pct", ((cumulative - 1) * 100).round())
            put("trades", trades.size)
        }
    }

    val availableSignals = listOf("rsi", "macd", "stochrsi", "ema")

    /**
     * Run backtest across selected signals (or all if none specified).
     */
    fun fullBacktest(
        candles: List<Candle>,
        bankroll: Double = 10_000.0,
        selectedSignals: List<String>? = null
    ): JsonObject {
        val signals = if (selectedSignals.isNullOrEmpty()) {
            availableSignals
        } else {
            selectedSignals.filter { it in availableSignals }
        }
        val results = mutableMapOf<String, JsonObject>()

        for (signal in signals) {
            val trades = backtestSignal(candles, signal)
            val ev = computeEv(trades)
            val kelly = computeKelly(trades, bankroll)
            val risk = computeRiskMetrics(trades)

            results[signal] = buildJsonObject {
                put("ev", ev)
                put("kelly", kelly)
                put("risk", risk)
            }
        }

        // Find best signal
        val best = results.maxByOrNull { entry ->
            entry.value["kelly"]?.jsonObject?.get("kelly_usd")?.jsonPrimitive?.doubleOrNull ?: 0.0
        }

        return buildJsonObject {
            put("signals", JsonObject(results))
            put("best_signal", best?.key)
            put("best_kelly_usd", best?.value?.get("kelly")?.jsonObject?.get("kelly_usd") ?: JsonNull)
            put("bankroll", bankroll)
        }
    }

    private fun Double.round(decimals: Int = 4): Double {
        if (this.isNaN() || this.isInfinite()) return 0.0
        val factor = Math.pow(10.0, decimals.toDouble())
        return Math.round(this * factor) / factor
    }
}
