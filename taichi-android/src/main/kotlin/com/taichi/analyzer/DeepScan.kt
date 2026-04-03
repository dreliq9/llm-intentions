package com.taichi.analyzer

import com.taichi.model.Candle
import com.taichi.scraper.ScraperBridge
import kotlinx.serialization.json.*
import kotlin.math.max
import kotlin.math.min

/**
 * Deep scan: composite 0-100 conviction scoring.
 * Combines: backtest EV + multi-TF TA alignment + regime + anomaly.
 */
object DeepScan {

    suspend fun run(
        symbol: String,
        bridge: ScraperBridge,
        bankroll: Double = 10_000.0
    ): JsonObject {
        // 1. Fetch candles at multiple timeframes
        val candles1h = bridge.fetchOhlcv(symbol, "1h", 200)
        val candles4h = bridge.fetchOhlcv(symbol, "4h", 200)

        if (candles1h.size < 50) return buildJsonObject {
            put("error", "Insufficient 1h candle data for $symbol (${candles1h.size})")
        }

        // 2. Quick scan: EV + Kelly
        val backtest = Backtest.fullBacktest(candles1h, bankroll)
        val bestSignal = backtest["best_signal"]?.jsonPrimitive?.contentOrNull
        val bestKellyUsd = backtest["best_kelly_usd"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val bestEv = bestSignal?.let {
            backtest["signals"]?.jsonObject?.get(it)?.jsonObject
                ?.get("ev")?.jsonObject?.get("ev_per_trade")?.jsonPrimitive?.doubleOrNull
        } ?: 0.0

        // 3. Multi-timeframe TA
        val ta1h = TechnicalAnalyzer.runTa(candles1h)
        val ta4h = if (candles4h.size >= 30) TechnicalAnalyzer.runTa(candles4h) else null
        val multiTf = computeMultiTfAlignment(ta1h, ta4h)

        // 4. Regime classification
        val regime = MlAnalyzer.classifyRegime(candles1h)
        val currentRegime = regime["current_regime"]?.jsonPrimitive?.contentOrNull ?: "unknown"

        // 5. Anomaly detection
        val anomaly = MlAnalyzer.detectAnomalies(candles1h)
        val isAnomaly = anomaly["is_anomaly"]?.jsonPrimitive?.booleanOrNull ?: false
        val recentAnomalies = anomaly["recent_anomaly_count"]?.jsonPrimitive?.intOrNull ?: 0

        // 6. Signal hierarchy
        val hierarchy = SignalHierarchy.classify(ta1h)
        val signalStrength = hierarchy["signal_strength"]?.jsonPrimitive?.contentOrNull ?: "no_signal"

        // 7. Compute conviction score
        val conviction = computeConviction(
            evPerTrade = bestEv,
            multiTfAlignment = multiTf["agreement_pct"]?.jsonPrimitive?.doubleOrNull ?: 50.0,
            regime = currentRegime,
            isAnomaly = isAnomaly,
            recentAnomalies = recentAnomalies,
            signalStrength = signalStrength
        )

        val score = conviction["score"]?.jsonPrimitive?.intOrNull ?: 50
        val verdict = when {
            bestKellyUsd <= 0 -> "NO_EDGE"
            score >= 75 -> "STRONG_BUY"
            score >= 55 -> "TRADEABLE"
            score >= 40 -> "WEAK_SIGNAL"
            else -> "AVOID"
        }

        return buildJsonObject {
            put("symbol", symbol)
            put("verdict", verdict)
            put("conviction", conviction)
            put("backtest_summary", buildJsonObject {
                put("best_signal", bestSignal)
                put("best_ev_per_trade", bestEv)
                put("best_kelly_usd", bestKellyUsd)
            })
            put("multi_timeframe", multiTf)
            put("regime", regime)
            put("anomaly", anomaly)
            put("signal_hierarchy", hierarchy)
            put("ta_1h", ta1h)
            if (ta4h != null) put("ta_4h", ta4h)
        }
    }

    private fun computeMultiTfAlignment(ta1h: JsonObject, ta4h: JsonObject?): JsonObject {
        if (ta4h == null) return buildJsonObject {
            put("agreement_pct", 50.0)
            put("verdict", "single_timeframe_only")
            put("note", "4h data unavailable — using 1h only")
        }

        val signals1h = ta1h["signals"]?.jsonObject ?: return buildJsonObject {
            put("agreement_pct", 50.0)
        }
        val signals4h = ta4h["signals"]?.jsonObject ?: return buildJsonObject {
            put("agreement_pct", 50.0)
        }

        var agree = 0
        var total = 0

        for ((key, val1h) in signals1h) {
            val val4h = signals4h[key]?.jsonPrimitive?.contentOrNull ?: continue
            total++
            if (val1h.jsonPrimitive.content == val4h) agree++
        }

        val pct = if (total > 0) agree.toDouble() / total * 100 else 50.0
        val verdict = when {
            pct >= 80 -> "strong"
            pct >= 60 -> "moderate"
            else -> "weak"
        }

        return buildJsonObject {
            put("agreement_pct", pct)
            put("agreeing_signals", agree)
            put("total_signals", total)
            put("verdict", verdict)
        }
    }

    private fun computeConviction(
        evPerTrade: Double,
        multiTfAlignment: Double,
        regime: String,
        isAnomaly: Boolean,
        recentAnomalies: Int,
        signalStrength: String
    ): JsonObject {
        var score = 50 // baseline neutral
        val reasoning = mutableListOf<String>()

        // EV contribution
        when {
            evPerTrade > 1.0 -> { score += 15; reasoning.add("+15: strong EV (${"%.2f".format(evPerTrade)}%)") }
            evPerTrade > 0 -> { score += 8; reasoning.add("+8: positive EV (${"%.2f".format(evPerTrade)}%)") }
            else -> { score -= 15; reasoning.add("-15: no positive EV") }
        }

        // Multi-TF alignment
        when {
            multiTfAlignment >= 80 -> { score += 12; reasoning.add("+12: strong multi-TF alignment (${"%.0f".format(multiTfAlignment)}%)") }
            multiTfAlignment >= 60 -> { score += 5; reasoning.add("+5: moderate multi-TF alignment") }
            else -> { score -= 10; reasoning.add("-10: weak multi-TF alignment") }
        }

        // Regime
        when (regime) {
            "trending", "trending_high_volume" -> { score += 10; reasoning.add("+10: trending regime") }
            "compression" -> { score += 5; reasoning.add("+5: compression (potential breakout)") }
            "choppy" -> { score -= 15; reasoning.add("-15: choppy regime (no-trade zone)") }
            "distribution" -> { score -= 10; reasoning.add("-10: distribution regime") }
            "low_vol_accumulation" -> { score += 3; reasoning.add("+3: low-vol accumulation") }
        }

        // Anomaly
        if (isAnomaly) { score += 8; reasoning.add("+8: anomalous activity detected") }
        if (recentAnomalies >= 3) { score -= 5; reasoning.add("-5: $recentAnomalies recent anomalies (unstable)") }

        // Signal strength
        when (signalStrength) {
            "strong" -> { score += 8; reasoning.add("+8: strong signal confirmation") }
            "moderate" -> { score += 3; reasoning.add("+3: moderate signal confirmation") }
            "conflicted" -> { score -= 8; reasoning.add("-8: conflicting signals") }
            "no_signal" -> { score -= 5; reasoning.add("-5: no actionable signal") }
        }

        score = max(0, min(100, score))

        val level = when {
            score >= 75 -> "HIGH_CONVICTION"
            score >= 55 -> "MODERATE"
            score >= 40 -> "LOW_CONVICTION"
            else -> "AVOID"
        }

        return buildJsonObject {
            put("score", score)
            put("level", level)
            put("reasoning", JsonArray(reasoning.map { JsonPrimitive(it) }))
        }
    }
}
