package com.taichi.analyzer

import com.taichi.model.Candle
import kotlinx.serialization.json.*

/**
 * Runs technical analysis on candle data. Returns indicators + signals.
 * Equivalent to Python's analyzers/technical.py run_ta().
 */
object TechnicalAnalyzer {

    fun runTa(
        candles: List<Candle>,
        indicators: List<String> = listOf("rsi", "macd", "bbands", "atr", "obv", "ema", "stochrsi", "vwap")
    ): JsonObject {
        if (candles.size < 30) {
            return buildJsonObject {
                put("error", "Insufficient data: ${candles.size} candles (need >= 30)")
            }
        }

        val closes = candles.map { it.close }.toDoubleArray()
        val highs = candles.map { it.high }.toDoubleArray()
        val lows = candles.map { it.low }.toDoubleArray()
        val volumes = candles.map { it.volume }.toDoubleArray()

        val indicatorResults = buildJsonObject { }
        val signals = mutableMapOf<String, String>()
        val details = mutableMapOf<String, JsonObject>()

        for (ind in indicators) {
            when (ind.lowercase()) {
                "rsi" -> {
                    val r = Indicators.rsi(closes)
                    signals["rsi"] = r.signal
                    details["rsi"] = buildJsonObject {
                        put("value", r.current.round())
                        put("signal", r.signal)
                    }
                }
                "macd" -> {
                    val m = Indicators.macd(closes)
                    signals["macd"] = m.signalLabel
                    details["macd"] = buildJsonObject {
                        put("macd", m.currentMacd.round())
                        put("signal", m.currentSignal.round())
                        put("histogram", m.currentHist.round())
                        put("signal_label", m.signalLabel)
                    }
                }
                "bbands" -> {
                    val b = Indicators.bbands(closes)
                    signals["bbands"] = b.signal
                    details["bbands"] = buildJsonObject {
                        put("pct_b", b.pctB.round())
                        put("signal", b.signal)
                    }
                }
                "atr" -> {
                    val a = Indicators.atr(highs, lows, closes)
                    details["atr"] = buildJsonObject {
                        put("value", a.current.round())
                        put("pct_of_price", a.pctOfPrice.round())
                    }
                }
                "obv" -> {
                    val o = Indicators.obv(closes, volumes)
                    signals["obv"] = o.signal
                    details["obv"] = buildJsonObject {
                        put("value", o.current.round())
                        put("signal", o.signal)
                    }
                }
                "ema" -> {
                    val ema9 = Indicators.ema(closes, 9)
                    val ema21 = Indicators.ema(closes, 21)
                    val ema50 = Indicators.ema(closes, 50)
                    val e9 = ema9.lastOrNull { !it.isNaN() } ?: Double.NaN
                    val e21 = ema21.lastOrNull { !it.isNaN() } ?: Double.NaN
                    val e50 = ema50.lastOrNull { !it.isNaN() } ?: Double.NaN
                    val crossSignal = when {
                        e9.isNaN() || e21.isNaN() -> "insufficient_data"
                        e9 > e21 -> "bullish"
                        else -> "bearish"
                    }
                    signals["ema"] = crossSignal
                    details["ema"] = buildJsonObject {
                        put("ema_9", e9.round())
                        put("ema_21", e21.round())
                        put("ema_50", e50.round())
                        put("signal", crossSignal)
                    }
                }
                "stochrsi" -> {
                    val sr = Indicators.stochRsi(closes)
                    signals["stochrsi"] = sr.signal
                    details["stochrsi"] = buildJsonObject {
                        put("k", sr.k.round())
                        put("d", sr.d.round())
                        put("signal", sr.signal)
                    }
                }
                "vwap" -> {
                    val v = Indicators.vwap(highs, lows, closes, volumes)
                    signals["vwap"] = v.signal
                    details["vwap"] = buildJsonObject {
                        put("value", v.current.round())
                        put("signal", v.signal)
                    }
                }
            }
        }

        return buildJsonObject {
            put("last_price", closes.last().round())
            put("timestamp", candles.last().timestamp)
            put("candle_count", candles.size)
            put("indicators", JsonObject(details.mapValues { it.value }))
            put("signals", buildJsonObject {
                signals.forEach { (k, v) -> put(k, v) }
            })
            put("summary", buildSignalSummary(signals))
        }
    }

    private fun buildSignalSummary(signals: Map<String, String>): JsonObject {
        var bullish = 0
        var bearish = 0
        var neutral = 0
        val bullishSignals = mutableListOf<String>()
        val bearishSignals = mutableListOf<String>()

        for ((name, signal) in signals) {
            when (signal) {
                "bullish", "accumulating", "oversold", "above_vwap" -> {
                    bullish++; bullishSignals.add("$name=$signal")
                }
                "bearish", "distributing", "overbought", "below_vwap", "above_upper" -> {
                    bearish++; bearishSignals.add("$name=$signal")
                }
                else -> neutral++
            }
        }

        val bias = when {
            bullish > bearish + 1 -> "bullish"
            bearish > bullish + 1 -> "bearish"
            else -> "neutral"
        }

        return buildJsonObject {
            put("bullish_count", bullish)
            put("bearish_count", bearish)
            put("neutral_count", neutral)
            put("bias", bias)
            put("bullish_signals", JsonArray(bullishSignals.map { JsonPrimitive(it) }))
            put("bearish_signals", JsonArray(bearishSignals.map { JsonPrimitive(it) }))
        }
    }

    /** Round to N decimal places; NaN/Infinity become 0.0 (JSON can't encode NaN). */
    private fun Double.round(decimals: Int = 4): Double {
        if (this.isNaN() || this.isInfinite()) return 0.0
        val factor = Math.pow(10.0, decimals.toDouble())
        return Math.round(this * factor) / factor
    }
}
