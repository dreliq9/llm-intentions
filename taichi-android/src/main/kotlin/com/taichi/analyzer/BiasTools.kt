package com.taichi.analyzer

import com.taichi.model.Candle
import kotlinx.serialization.json.*
import kotlin.math.abs

/**
 * Anti-bias guardrails: drawdown check, bear case, position audit, thesis check.
 * These enforce discipline — hard stops and contrary perspectives.
 */
object BiasTools {

    /**
     * Hard drawdown limits. Non-overridable.
     * -10% per position, -15% portfolio.
     */
    fun drawdownCheck(portfolioJson: JsonObject): JsonObject {
        val totalPnlPct = portfolioJson["total_pnl_pct"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val positions = portfolioJson["open_positions"]?.jsonArray ?: JsonArray(emptyList())

        val alerts = mutableListOf<JsonObject>()
        var portfolioBreached = false
        var positionBreached = false

        // Portfolio-level check
        if (totalPnlPct <= -15.0) {
            portfolioBreached = true
            alerts.add(buildJsonObject {
                put("level", "CRITICAL")
                put("type", "portfolio")
                put("message", "PORTFOLIO DRAWDOWN LIMIT BREACHED: ${totalPnlPct.round()}%. CLOSE ALL POSITIONS IMMEDIATELY.")
            })
        } else if (totalPnlPct <= -10.5) {
            alerts.add(buildJsonObject {
                put("level", "WARNING")
                put("type", "portfolio")
                put("message", "Portfolio approaching limit: ${totalPnlPct.round()}% (limit: -15%)")
            })
        }

        // Per-position check
        for (pos in positions) {
            val symbol = pos.jsonObject["symbol"]?.jsonPrimitive?.content ?: "?"
            val pnlPct = pos.jsonObject["unrealized_pnl_pct"]?.jsonPrimitive?.doubleOrNull ?: 0.0

            if (pnlPct <= -10.0) {
                positionBreached = true
                alerts.add(buildJsonObject {
                    put("level", "CRITICAL")
                    put("type", "position")
                    put("symbol", symbol)
                    put("message", "$symbol POSITION DRAWDOWN LIMIT BREACHED: ${pnlPct.round()}%. CLOSE POSITION.")
                })
            } else if (pnlPct <= -7.0) {
                alerts.add(buildJsonObject {
                    put("level", "WARNING")
                    put("type", "position")
                    put("symbol", symbol)
                    put("message", "$symbol approaching limit: ${pnlPct.round()}% (limit: -10%)")
                })
            }
        }

        val verdict = when {
            portfolioBreached -> "CLOSE_ALL"
            positionBreached -> "CLOSE_BREACHED"
            alerts.isNotEmpty() -> "WARNING"
            else -> "CLEAR"
        }

        return buildJsonObject {
            put("verdict", verdict)
            put("portfolio_pnl_pct", totalPnlPct.round())
            put("alerts", JsonArray(alerts))
            put("limits", buildJsonObject {
                put("position_max_loss", "-10%")
                put("portfolio_max_loss", "-15%")
                put("non_overridable", true)
            })
        }
    }

    /**
     * Generate the strongest bear case for a token.
     * Forces contrary thinking against anchoring bias.
     */
    fun bearCase(taResult: JsonObject, candles: List<Candle>): JsonObject {
        val bearPoints = mutableListOf<String>()
        var severity = 0

        val signals = taResult["signals"]?.jsonObject
        val indicators = taResult["indicators"]?.jsonObject

        // RSI overbought
        val rsiValue = indicators?.get("rsi")?.jsonObject?.get("value")?.jsonPrimitive?.doubleOrNull
        if (rsiValue != null) {
            if (rsiValue > 70) { bearPoints.add("RSI overbought at ${rsiValue.round()} — reversal risk"); severity += 2 }
            else if (rsiValue > 60) { bearPoints.add("RSI elevated at ${rsiValue.round()} — limited upside"); severity += 1 }
        }

        // MACD bearish
        val macdHist = indicators?.get("macd")?.jsonObject?.get("histogram")?.jsonPrimitive?.doubleOrNull
        if (macdHist != null && macdHist < 0) {
            bearPoints.add("MACD histogram negative (${macdHist.round()}) — bearish momentum")
            severity += 1
            // Declining histogram is worse
            if (candles.size > 2) {
                val prevCloses = candles.takeLast(30).map { it.close }.toDoubleArray()
                val prevMacd = Indicators.macd(prevCloses)
                if (prevMacd.currentHist < macdHist) {
                    bearPoints.add("MACD histogram declining — momentum weakening")
                    severity += 1
                }
            }
        }

        // OBV distributing
        if (signals?.get("obv")?.jsonPrimitive?.content == "distributing") {
            bearPoints.add("OBV distributing — smart money may be exiting")
            severity += 2
        }

        // ATR high volatility
        val atrPct = indicators?.get("atr")?.jsonObject?.get("pct_of_price")?.jsonPrimitive?.doubleOrNull
        if (atrPct != null && atrPct > 3.0) {
            bearPoints.add("ATR ${atrPct.round()}% of price — high volatility increases risk")
            severity += 1
        }

        // Recent drawdown from high
        if (candles.size >= 20) {
            val recent = candles.takeLast(20)
            val high = recent.maxOf { it.high }
            val current = candles.last().close
            val drawdown = (current - high) / high * 100
            if (drawdown < -5) {
                bearPoints.add("Down ${abs(drawdown).round()}% from 20-period high")
                severity += 1
            }
            if (drawdown < -10) severity += 1
        }

        // Below key EMAs
        if (signals?.get("ema")?.jsonPrimitive?.content == "bearish") {
            bearPoints.add("EMA 9 below EMA 21 — short-term trend bearish")
            severity += 1
        }

        val classification = when {
            severity >= 6 -> "critical"
            severity >= 4 -> "significant"
            severity >= 2 -> "moderate"
            else -> "mild"
        }

        return buildJsonObject {
            put("severity", classification)
            put("severity_score", severity)
            put("bear_points", JsonArray(bearPoints.map { JsonPrimitive(it) }))
            put("point_count", bearPoints.size)
        }
    }

    /**
     * For each open position: would you enter it fresh today?
     * Removes sunk-cost bias.
     */
    fun positionAudit(
        positions: JsonArray,
        runTaForSymbol: suspend (String) -> JsonObject?
    ): suspend () -> JsonObject = suspend {
        val audits = mutableListOf<JsonObject>()
        var exitRecommended = 0

        for (pos in positions) {
            val symbol = pos.jsonObject["symbol"]?.jsonPrimitive?.content ?: continue
            val ta = runTaForSymbol(symbol) ?: continue
            val signals = ta["signals"]?.jsonObject ?: continue

            var bullish = 0; var bearish = 0
            for ((_, v) in signals) {
                when (v.jsonPrimitive.content) {
                    "bullish", "accumulating", "oversold", "above_vwap" -> bullish++
                    "bearish", "distributing", "overbought", "below_vwap", "above_upper" -> bearish++
                }
            }

            val wouldEnter = when {
                bullish > bearish -> "yes"
                bearish > bullish -> "no"
                else -> "marginal"
            }
            if (wouldEnter == "no") exitRecommended++

            audits.add(buildJsonObject {
                put("symbol", symbol)
                put("bullish_signals", bullish)
                put("bearish_signals", bearish)
                put("would_enter_today", wouldEnter)
                put("recommendation", when (wouldEnter) {
                    "yes" -> "HOLD"
                    "no" -> "EXIT"
                    else -> "WATCH"
                })
            })
        }

        buildJsonObject {
            put("audits", JsonArray(audits))
            put("exit_recommended", exitRecommended)
            put("total_positions", positions.size)
        }
    }

    private fun Double.round(decimals: Int = 2): Double {
        if (this.isNaN() || this.isInfinite()) return 0.0
        val factor = Math.pow(10.0, decimals.toDouble())
        return Math.round(this * factor) / factor
    }
}
