package com.taichi.analyzer

import kotlinx.serialization.json.*
import kotlin.math.abs

/**
 * 3-evaluator trade gate: TA + Fundamentals + Risk.
 * Each evaluator votes PASS / CAUTION / BLOCK.
 * Synthesis: any BLOCK → BLOCK, all PASS → EXECUTE, mixed → CAUTION.
 */
object TradeReview {

    enum class Vote { PASS, CAUTION, BLOCK }

    data class Evaluation(
        val evaluator: String,
        val vote: Vote,
        val reasons: List<String>
    )

    /**
     * Run TA evaluation for a proposed trade.
     */
    fun taEvaluation(taResult: JsonObject, action: String): Evaluation {
        val reasons = mutableListOf<String>()
        var fails = 0
        var warns = 0

        val indicators = taResult["indicators"]?.jsonObject
        val signals = taResult["signals"]?.jsonObject
        val candleCount = taResult["candle_count"]?.jsonPrimitive?.intOrNull ?: 0

        // Data depth
        if (candleCount < 50) {
            fails++
            reasons.add("FAIL: Only $candleCount candles (need 50+)")
        }

        val rsiValue = indicators?.get("rsi")?.jsonObject?.get("value")?.jsonPrimitive?.doubleOrNull
        val macdHist = indicators?.get("macd")?.jsonObject?.get("histogram")?.jsonPrimitive?.doubleOrNull
        val obvSignal = signals?.get("obv")?.jsonPrimitive?.contentOrNull

        if (action == "buy") {
            // RSI checks for buy
            if (rsiValue != null) {
                when {
                    rsiValue > 70 -> { fails++; reasons.add("FAIL: RSI overbought at ${"%.1f".format(rsiValue)} — buying into resistance") }
                    rsiValue > 60 -> { warns++; reasons.add("WARN: RSI elevated at ${"%.1f".format(rsiValue)}") }
                    rsiValue < 30 -> reasons.add("OK: RSI oversold at ${"%.1f".format(rsiValue)} — buy signal")
                }
            }
            // MACD checks for buy
            if (macdHist != null && macdHist < 0) {
                warns++
                reasons.add("WARN: MACD histogram negative (${"%.4f".format(macdHist)}) — bearish momentum")
            }
            // OBV check
            if (obvSignal == "distributing") {
                warns++
                reasons.add("WARN: OBV distributing — volume diverging from price")
            }
        } else if (action == "sell") {
            if (rsiValue != null && rsiValue < 30) {
                warns++
                reasons.add("WARN: RSI oversold at ${"%.1f".format(rsiValue)} — selling at potential bottom")
            }
            if (macdHist != null && macdHist > 0) {
                warns++
                reasons.add("WARN: MACD histogram positive — selling into bullish momentum")
            }
        }

        val vote = when {
            fails >= 2 -> Vote.BLOCK
            fails >= 1 || warns >= 3 -> Vote.CAUTION
            else -> Vote.PASS
        }

        if (reasons.isEmpty()) reasons.add("OK: No issues detected")

        return Evaluation("TA", vote, reasons)
    }

    /**
     * Run risk evaluation for a proposed trade.
     */
    fun riskEvaluation(
        action: String,
        amountUsd: Double,
        portfolioJson: JsonObject
    ): Evaluation {
        val reasons = mutableListOf<String>()
        var fails = 0
        var warns = 0

        val cash = portfolioJson["cash"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val totalValue = portfolioJson["total_value"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val totalPnlPct = portfolioJson["total_pnl_pct"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val positions = portfolioJson["open_positions"]?.jsonArray ?: JsonArray(emptyList())

        if (action == "buy") {
            // Cash check
            if (amountUsd > cash) {
                fails++
                reasons.add("FAIL: Insufficient cash (${"%.2f".format(cash)} available, ${"%.2f".format(amountUsd)} requested)")
            }

            // Position sizing
            val pctOfPortfolio = if (totalValue > 0) amountUsd / totalValue * 100 else 100.0
            when {
                pctOfPortfolio > 40 -> { fails++; reasons.add("FAIL: Position ${"%.1f".format(pctOfPortfolio)}% of portfolio (max 40%)") }
                pctOfPortfolio > 25 -> { warns++; reasons.add("WARN: Position ${"%.1f".format(pctOfPortfolio)}% of portfolio (caution >25%)") }
                else -> reasons.add("OK: Position sizing ${"%.1f".format(pctOfPortfolio)}% of portfolio")
            }

            // Cash reserve
            val cashAfter = cash - amountUsd
            val reservePct = if (totalValue > 0) cashAfter / totalValue * 100 else 0.0
            if (reservePct < 10) {
                warns++
                reasons.add("WARN: Cash reserve drops to ${"%.1f".format(reservePct)}% after trade (want >10%)")
            }

            // Portfolio health
            when {
                totalPnlPct < -10 -> { fails++; reasons.add("FAIL: Portfolio down ${"%.1f".format(totalPnlPct)}% — not adding risk") }
                totalPnlPct < -5 -> { warns++; reasons.add("WARN: Portfolio down ${"%.1f".format(totalPnlPct)}%") }
            }
        }

        val vote = when {
            fails >= 1 -> Vote.BLOCK
            warns >= 3 -> Vote.CAUTION
            warns >= 1 -> Vote.CAUTION
            else -> Vote.PASS
        }

        if (reasons.isEmpty()) reasons.add("OK: Risk parameters acceptable")

        return Evaluation("Risk", vote, reasons)
    }

    /**
     * Synthesize evaluator votes into a final verdict.
     */
    fun synthesize(evaluations: List<Evaluation>): JsonObject {
        val blocks = evaluations.filter { it.vote == Vote.BLOCK }
        val passes = evaluations.filter { it.vote == Vote.PASS }
        val cautions = evaluations.filter { it.vote == Vote.CAUTION }

        val verdict = when {
            blocks.isNotEmpty() -> "BLOCK"
            passes.size == evaluations.size -> "EXECUTE"
            passes.size >= evaluations.size - 1 -> "EXECUTE_WITH_CAUTION"
            cautions.size >= 2 && passes.isNotEmpty() -> "CAUTION"
            else -> "REVIEW"
        }

        return buildJsonObject {
            put("verdict", verdict)
            put("evaluators", JsonArray(evaluations.map { eval ->
                buildJsonObject {
                    put("name", eval.evaluator)
                    put("vote", eval.vote.name)
                    put("reasons", JsonArray(eval.reasons.map { JsonPrimitive(it) }))
                }
            }))
            put("summary", buildJsonObject {
                put("pass", passes.size)
                put("caution", cautions.size)
                put("block", blocks.size)
            })
            if (blocks.isNotEmpty()) {
                put("blockers", JsonArray(blocks.flatMap { it.reasons.filter { r -> r.startsWith("FAIL") } }.map { JsonPrimitive(it) }))
            }
        }
    }
}
