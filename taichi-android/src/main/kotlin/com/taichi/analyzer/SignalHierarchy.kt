package com.taichi.analyzer

import kotlinx.serialization.json.*

/**
 * Codifies the signal hierarchy (Tier 1/2/3) and entry checklist.
 * These rules were previously enforced by Claude from memory.
 *
 * Signal Tiers:
 *   Tier 1 (strongest): RSI — best backtested EV, clearest thresholds
 *   Tier 2 (supporting): EMA crossover — trend confirmation
 *   Tier 3 (weakest): MACD — unreliable alone, confirmation only
 *
 * Entry Checklist (ALL must pass):
 *   1. Positive expected value for the primary signal
 *   2. Kelly criterion > 0 (edge exists)
 *   3. Conviction score >= 60 (from deep_scan)
 *   4. Regime approves the signal (not a choppy/distribution regime)
 */
object SignalHierarchy {

    enum class Tier(val weight: Double) {
        TIER_1(1.0),  // RSI
        TIER_2(0.6),  // EMA
        TIER_3(0.3)   // MACD
    }

    data class SignalClassification(
        val name: String,
        val tier: Tier,
        val signal: String,
        val actionable: Boolean,
        val direction: String // "buy", "sell", "hold"
    )

    /**
     * Classify all signals from a TA result into the tier hierarchy.
     */
    fun classify(taResult: JsonObject): JsonObject {
        val signals = taResult["signals"]?.jsonObject ?: return buildJsonObject {
            put("error", "No signals in TA result")
        }

        val classified = mutableListOf<SignalClassification>()

        // Tier 1: RSI
        signals["rsi"]?.jsonPrimitive?.content?.let { rsi ->
            classified.add(SignalClassification(
                name = "rsi", tier = Tier.TIER_1, signal = rsi,
                actionable = rsi == "oversold" || rsi == "overbought",
                direction = when (rsi) {
                    "oversold" -> "buy"
                    "overbought" -> "sell"
                    else -> "hold"
                }
            ))
        }

        // Tier 2: EMA crossover
        signals["ema"]?.jsonPrimitive?.content?.let { ema ->
            classified.add(SignalClassification(
                name = "ema", tier = Tier.TIER_2, signal = ema,
                actionable = ema == "bullish" || ema == "bearish",
                direction = when (ema) {
                    "bullish" -> "buy"
                    "bearish" -> "sell"
                    else -> "hold"
                }
            ))
        }

        // Tier 3: MACD (confirmation only)
        signals["macd"]?.jsonPrimitive?.content?.let { macd ->
            classified.add(SignalClassification(
                name = "macd", tier = Tier.TIER_3, signal = macd,
                actionable = false, // never trade on MACD alone
                direction = when (macd) {
                    "bullish" -> "buy"
                    "bearish" -> "sell"
                    else -> "hold"
                }
            ))
        }

        // Supporting: OBV, StochRSI, BBands
        signals["obv"]?.jsonPrimitive?.content?.let { obv ->
            classified.add(SignalClassification(
                name = "obv", tier = Tier.TIER_2, signal = obv,
                actionable = false,
                direction = when (obv) {
                    "accumulating" -> "buy"
                    "distributing" -> "sell"
                    else -> "hold"
                }
            ))
        }

        signals["stochrsi"]?.jsonPrimitive?.content?.let { sr ->
            classified.add(SignalClassification(
                name = "stochrsi", tier = Tier.TIER_2, signal = sr,
                actionable = sr == "oversold" || sr == "overbought",
                direction = when (sr) {
                    "oversold" -> "buy"
                    "overbought" -> "sell"
                    else -> "hold"
                }
            ))
        }

        // Primary signal: highest-tier actionable signal
        val primary = classified
            .filter { it.actionable }
            .minByOrNull { it.tier.ordinal } // lowest ordinal = highest tier

        // Confirmation: do lower tiers agree with primary?
        val confirmations = if (primary != null) {
            classified.filter { it.name != primary.name && it.direction == primary.direction }
        } else emptyList()

        val contradictions = if (primary != null) {
            classified.filter {
                it.name != primary.name &&
                it.direction != "hold" &&
                it.direction != primary.direction
            }
        } else emptyList()

        return buildJsonObject {
            put("primary_signal", primary?.let {
                buildJsonObject {
                    put("name", it.name)
                    put("tier", it.tier.name)
                    put("signal", it.signal)
                    put("direction", it.direction)
                }
            } ?: JsonNull)

            put("confirmations", JsonArray(confirmations.map {
                buildJsonObject {
                    put("name", it.name)
                    put("tier", it.tier.name)
                    put("signal", it.signal)
                }
            }))

            put("contradictions", JsonArray(contradictions.map {
                buildJsonObject {
                    put("name", it.name)
                    put("tier", it.tier.name)
                    put("signal", it.signal)
                }
            }))

            put("all_signals", JsonArray(classified.map {
                buildJsonObject {
                    put("name", it.name)
                    put("tier", it.tier.name)
                    put("signal", it.signal)
                    put("direction", it.direction)
                    put("actionable", it.actionable)
                }
            }))

            val strength = when {
                primary == null -> "no_signal"
                confirmations.size >= 2 && contradictions.isEmpty() -> "strong"
                confirmations.isNotEmpty() && contradictions.isEmpty() -> "moderate"
                contradictions.isNotEmpty() -> "conflicted"
                else -> "weak"
            }
            put("signal_strength", strength)
        }
    }

    /**
     * Entry checklist — checks all four gates.
     * Returns pass/fail for each gate and overall verdict.
     *
     * Inputs are the results from other tools (EV, Kelly, conviction, regime).
     * This tool doesn't fetch data — it evaluates what you've already gathered.
     */
    fun checkEntryGate(
        evPerTrade: Double?,
        kellyFraction: Double?,
        convictionScore: Int?,
        regime: String?,
        signalStrength: String?
    ): JsonObject {
        val gates = mutableMapOf<String, JsonObject>()
        var passCount = 0

        // Gate 1: Positive EV
        val evPass = evPerTrade != null && evPerTrade > 0
        if (evPass) passCount++
        gates["positive_ev"] = buildJsonObject {
            put("pass", evPass)
            put("value", evPerTrade ?: 0.0)
            put("requirement", "> 0")
        }

        // Gate 2: Kelly > 0
        val kellyPass = kellyFraction != null && kellyFraction > 0
        if (kellyPass) passCount++
        gates["kelly_positive"] = buildJsonObject {
            put("pass", kellyPass)
            put("value", kellyFraction ?: 0.0)
            put("requirement", "> 0")
        }

        // Gate 3: Conviction >= 60
        val convictionPass = convictionScore != null && convictionScore >= 60
        if (convictionPass) passCount++
        gates["conviction"] = buildJsonObject {
            put("pass", convictionPass)
            put("value", convictionScore ?: 0)
            put("requirement", ">= 60")
        }

        // Gate 4: Regime approves
        val blockedRegimes = listOf("choppy", "distribution")
        val regimePass = regime != null && regime !in blockedRegimes
        if (regimePass) passCount++
        gates["regime_approval"] = buildJsonObject {
            put("pass", regimePass)
            put("value", regime ?: "unknown")
            put("blocked_regimes", JsonArray(blockedRegimes.map { JsonPrimitive(it) }))
        }

        val allPass = passCount == 4
        val verdict = when {
            allPass && signalStrength == "strong" -> "CLEAR_TO_TRADE"
            allPass && signalStrength == "moderate" -> "PROCEED_WITH_CAUTION"
            allPass -> "MARGINAL"
            passCount >= 3 -> "ONE_GATE_FAILED"
            passCount >= 2 -> "MULTIPLE_GATES_FAILED"
            else -> "DO_NOT_TRADE"
        }

        return buildJsonObject {
            put("verdict", verdict)
            put("gates_passed", passCount)
            put("gates_total", 4)
            put("all_pass", allPass)
            put("gates", JsonObject(gates.mapValues { it.value }))
            put("signal_strength", signalStrength ?: "unknown")
        }
    }
}
