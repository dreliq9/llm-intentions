package com.taichi.analyzer

import com.taichi.model.Candle
import kotlinx.serialization.json.*
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Portfolio-level analysis: correlation check and thesis validation.
 */
object PortfolioAnalyzer {

    /**
     * Check correlation between all open positions.
     * Flags dangerously correlated pairs and computes diversification score.
     */
    fun correlationCheck(
        positionCandles: Map<String, List<Candle>>
    ): JsonObject {
        val symbols = positionCandles.keys.toList()
        if (symbols.size < 2) return buildJsonObject {
            put("message", "Need at least 2 positions for correlation check")
            put("diversification", "n/a")
        }

        // Compute daily returns for each
        val returns = mutableMapOf<String, DoubleArray>()
        for ((symbol, candles) in positionCandles) {
            if (candles.size < 10) continue
            val closes = candles.map { it.close }
            returns[symbol] = DoubleArray(closes.size - 1) { i ->
                if (closes[i] > 0) (closes[i + 1] - closes[i]) / closes[i] else 0.0
            }
        }

        val validSymbols = returns.keys.toList()
        if (validSymbols.size < 2) return buildJsonObject {
            put("message", "Insufficient data for correlation")
        }

        // Pairwise correlation
        val pairs = mutableListOf<JsonObject>()
        var totalCorr = 0.0
        var pairCount = 0

        for (i in validSymbols.indices) {
            for (j in i + 1 until validSymbols.size) {
                val a = returns[validSymbols[i]]!!
                val b = returns[validSymbols[j]]!!
                val minLen = minOf(a.size, b.size)
                val corr = pearsonCorrelation(
                    a.takeLast(minLen).toDoubleArray(),
                    b.takeLast(minLen).toDoubleArray()
                )

                totalCorr += abs(corr)
                pairCount++

                val risk = when {
                    abs(corr) > 0.85 -> "dangerously_correlated"
                    abs(corr) > 0.7 -> "highly_correlated"
                    abs(corr) > 0.5 -> "moderately_correlated"
                    else -> "low_correlation"
                }

                pairs.add(buildJsonObject {
                    put("pair", "${validSymbols[i]} / ${validSymbols[j]}")
                    put("correlation", "%.3f".format(corr).toDouble())
                    put("risk", risk)
                })
            }
        }

        val avgCorr = if (pairCount > 0) totalCorr / pairCount else 0.0
        val n = validSymbols.size.toDouble()
        val effectivePositions = n / (1 + (n - 1) * avgCorr)

        val diversification = when {
            effectivePositions < 1.3 -> "poor"
            effectivePositions < 1.7 -> "weak"
            effectivePositions < 2.5 -> "moderate"
            else -> "good"
        }

        return buildJsonObject {
            put("pairs", JsonArray(pairs))
            put("avg_absolute_correlation", "%.3f".format(avgCorr).toDouble())
            put("effective_independent_positions", "%.1f".format(effectivePositions).toDouble())
            put("actual_positions", validSymbols.size)
            put("diversification", diversification)
        }
    }

    /**
     * Check if original trade thesis is still valid by matching
     * keywords against current TA state.
     */
    fun thesisCheck(thesis: String, taResult: JsonObject): JsonObject {
        val signals = taResult["signals"]?.jsonObject ?: return buildJsonObject {
            put("error", "No signals in TA result")
        }
        val indicators = taResult["indicators"]?.jsonObject

        val claims = mutableListOf<JsonObject>()
        var intact = 0
        var broken = 0
        val thesisLower = thesis.lowercase()

        // Check each potential claim in the thesis
        val checks = listOf(
            Triple("bullish", { signals["rsi"]?.jsonPrimitive?.content != "overbought" }, "Overall bias not overbought"),
            Triple("bearish", { signals["rsi"]?.jsonPrimitive?.content != "oversold" }, "Overall bias not oversold"),
            Triple("macd bullish", { signals["macd"]?.jsonPrimitive?.content == "bullish" }, "MACD is bullish"),
            Triple("macd bearish", { signals["macd"]?.jsonPrimitive?.content == "bearish" }, "MACD is bearish"),
            Triple("obv accumulating", { signals["obv"]?.jsonPrimitive?.content == "accumulating" }, "OBV is accumulating"),
            Triple("obv distributing", { signals["obv"]?.jsonPrimitive?.content == "distributing" }, "OBV is distributing"),
            Triple("oversold", { signals["rsi"]?.jsonPrimitive?.content == "oversold" }, "RSI is oversold"),
            Triple("overbought", { signals["rsi"]?.jsonPrimitive?.content == "overbought" }, "RSI is overbought"),
            Triple("ema bullish", { signals["ema"]?.jsonPrimitive?.content == "bullish" }, "EMA crossover bullish"),
            Triple("trend up", { signals["ema"]?.jsonPrimitive?.content == "bullish" }, "Trend is up (EMA)"),
            Triple("trend down", { signals["ema"]?.jsonPrimitive?.content == "bearish" }, "Trend is down (EMA)"),
        )

        for ((keyword, check, description) in checks) {
            if (keyword in thesisLower) {
                val valid = check()
                if (valid) intact++ else broken++
                claims.add(buildJsonObject {
                    put("claim", keyword)
                    put("description", description)
                    put("still_valid", valid)
                })
            }
        }

        val total = intact + broken
        val verdict = when {
            total == 0 -> "no_checkable_claims"
            broken == 0 -> "thesis_intact"
            broken.toDouble() / total <= 0.5 -> "thesis_weakening"
            else -> "thesis_broken"
        }

        return buildJsonObject {
            put("verdict", verdict)
            put("claims_checked", total)
            put("claims_intact", intact)
            put("claims_broken", broken)
            put("claims", JsonArray(claims))
            put("original_thesis", thesis)
        }
    }

    private fun pearsonCorrelation(a: DoubleArray, b: DoubleArray): Double {
        val n = minOf(a.size, b.size)
        if (n < 3) return 0.0
        val meanA = a.take(n).average()
        val meanB = b.take(n).average()
        var cov = 0.0; var varA = 0.0; var varB = 0.0
        for (i in 0 until n) {
            val da = a[i] - meanA
            val db = b[i] - meanB
            cov += da * db
            varA += da * da
            varB += db * db
        }
        val denom = sqrt(varA * varB)
        return if (denom > 0) cov / denom else 0.0
    }
}
