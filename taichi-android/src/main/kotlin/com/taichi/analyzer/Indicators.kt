package com.taichi.analyzer

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Hand-rolled TA indicators operating on DoubleArrays.
 * Ports the 8 indicators from Python's pandas-ta usage.
 */
object Indicators {

    // --- Simple Moving Average ---
    fun sma(values: DoubleArray, period: Int): DoubleArray {
        if (values.size < period) return DoubleArray(values.size) { Double.NaN }
        val result = DoubleArray(values.size) { Double.NaN }
        var sum = 0.0
        for (i in values.indices) {
            sum += values[i]
            if (i >= period) sum -= values[i - period]
            if (i >= period - 1) result[i] = sum / period
        }
        return result
    }

    // --- Exponential Moving Average ---
    fun ema(values: DoubleArray, period: Int): DoubleArray {
        if (values.isEmpty()) return doubleArrayOf()
        val result = DoubleArray(values.size) { Double.NaN }
        val multiplier = 2.0 / (period + 1)
        // Seed with SMA of first `period` values
        var sum = 0.0
        for (i in 0 until minOf(period, values.size)) sum += values[i]
        if (values.size >= period) {
            result[period - 1] = sum / period
            for (i in period until values.size) {
                result[i] = (values[i] - result[i - 1]) * multiplier + result[i - 1]
            }
        }
        return result
    }

    // --- RSI (Wilder's smoothing) ---
    data class RsiResult(val values: DoubleArray, val current: Double, val signal: String)

    fun rsi(closes: DoubleArray, period: Int = 14): RsiResult {
        val result = DoubleArray(closes.size) { Double.NaN }
        if (closes.size <= period) return RsiResult(result, Double.NaN, "insufficient_data")

        val deltas = DoubleArray(closes.size - 1) { closes[it + 1] - closes[it] }
        var avgGain = 0.0
        var avgLoss = 0.0

        for (i in 0 until period) {
            if (deltas[i] > 0) avgGain += deltas[i] else avgLoss += abs(deltas[i])
        }
        avgGain /= period
        avgLoss /= period

        result[period] = if (avgLoss == 0.0) 100.0 else 100.0 - (100.0 / (1.0 + avgGain / avgLoss))

        for (i in period until deltas.size) {
            val gain = if (deltas[i] > 0) deltas[i] else 0.0
            val loss = if (deltas[i] < 0) abs(deltas[i]) else 0.0
            avgGain = (avgGain * (period - 1) + gain) / period
            avgLoss = (avgLoss * (period - 1) + loss) / period
            result[i + 1] = if (avgLoss == 0.0) 100.0 else 100.0 - (100.0 / (1.0 + avgGain / avgLoss))
        }

        val current = result.last { !it.isNaN() }
        val signal = when {
            current < 30 -> "oversold"
            current > 70 -> "overbought"
            else -> "neutral"
        }
        return RsiResult(result, current, signal)
    }

    // --- MACD ---
    data class MacdResult(
        val macd: DoubleArray, val signal: DoubleArray, val histogram: DoubleArray,
        val currentMacd: Double, val currentSignal: Double, val currentHist: Double,
        val signalLabel: String
    )

    fun macd(closes: DoubleArray, fast: Int = 12, slow: Int = 26, signalPeriod: Int = 9): MacdResult {
        val emaFast = ema(closes, fast)
        val emaSlow = ema(closes, slow)
        val macdLine = DoubleArray(closes.size) { i ->
            if (emaFast[i].isNaN() || emaSlow[i].isNaN()) Double.NaN
            else emaFast[i] - emaSlow[i]
        }
        // Signal line is EMA of MACD line (skip NaN prefix)
        val validStart = macdLine.indexOfFirst { !it.isNaN() }
        val signalLine = if (validStart >= 0) {
            val validMacd = macdLine.drop(validStart).toDoubleArray()
            val sig = ema(validMacd, signalPeriod)
            DoubleArray(validStart) { Double.NaN } + sig
        } else {
            DoubleArray(closes.size) { Double.NaN }
        }
        val hist = DoubleArray(closes.size) { i ->
            if (macdLine[i].isNaN() || signalLine[i].isNaN()) Double.NaN
            else macdLine[i] - signalLine[i]
        }

        val cm = macdLine.lastOrNull { !it.isNaN() } ?: Double.NaN
        val cs = signalLine.lastOrNull { !it.isNaN() } ?: Double.NaN
        val ch = hist.lastOrNull { !it.isNaN() } ?: Double.NaN

        val label = when {
            ch.isNaN() -> "insufficient_data"
            ch > 0 && cm > cs -> "bullish"
            ch < 0 && cm < cs -> "bearish"
            else -> "neutral"
        }
        return MacdResult(macdLine, signalLine, hist, cm, cs, ch, label)
    }

    // --- Bollinger Bands ---
    data class BbandsResult(
        val upper: DoubleArray, val middle: DoubleArray, val lower: DoubleArray,
        val pctB: Double, val signal: String
    )

    fun bbands(closes: DoubleArray, period: Int = 20, stdDev: Double = 2.0): BbandsResult {
        val middle = sma(closes, period)
        val upper = DoubleArray(closes.size) { Double.NaN }
        val lower = DoubleArray(closes.size) { Double.NaN }

        for (i in period - 1 until closes.size) {
            var sumSq = 0.0
            for (j in i - period + 1..i) {
                val diff = closes[j] - middle[i]
                sumSq += diff * diff
            }
            val std = sqrt(sumSq / period)
            upper[i] = middle[i] + stdDev * std
            lower[i] = middle[i] - stdDev * std
        }

        val lastClose = closes.last()
        val lastUpper = upper.last { !it.isNaN() }
        val lastLower = lower.last { !it.isNaN() }
        val pctB = if (lastUpper != lastLower) (lastClose - lastLower) / (lastUpper - lastLower) else 0.5

        val signal = when {
            lastClose > lastUpper -> "above_upper"
            lastClose < lastLower -> "below_lower"
            else -> "within_bands"
        }
        return BbandsResult(upper, middle, lower, pctB, signal)
    }

    // --- ATR (Average True Range) ---
    data class AtrResult(val values: DoubleArray, val current: Double, val pctOfPrice: Double)

    fun atr(highs: DoubleArray, lows: DoubleArray, closes: DoubleArray, period: Int = 14): AtrResult {
        val tr = DoubleArray(closes.size) { i ->
            if (i == 0) highs[0] - lows[0]
            else maxOf(
                highs[i] - lows[i],
                abs(highs[i] - closes[i - 1]),
                abs(lows[i] - closes[i - 1])
            )
        }
        val atrValues = ema(tr, period)
        val current = atrValues.last { !it.isNaN() }
        val pctOfPrice = if (closes.last() > 0) current / closes.last() * 100 else 0.0
        return AtrResult(atrValues, current, pctOfPrice)
    }

    // --- OBV (On-Balance Volume) ---
    data class ObvResult(val values: DoubleArray, val current: Double, val signal: String)

    fun obv(closes: DoubleArray, volumes: DoubleArray): ObvResult {
        val result = DoubleArray(closes.size)
        result[0] = volumes[0]
        for (i in 1 until closes.size) {
            result[i] = when {
                closes[i] > closes[i - 1] -> result[i - 1] + volumes[i]
                closes[i] < closes[i - 1] -> result[i - 1] - volumes[i]
                else -> result[i - 1]
            }
        }
        val current = result.last()
        val prev = if (result.size > 1) result[result.size - 2] else current
        val signal = if (current > prev) "accumulating" else "distributing"
        return ObvResult(result, current, signal)
    }

    // --- StochRSI ---
    data class StochRsiResult(val k: Double, val d: Double, val signal: String)

    fun stochRsi(
        closes: DoubleArray, rsiPeriod: Int = 14, stochPeriod: Int = 14,
        kSmooth: Int = 3, dSmooth: Int = 3
    ): StochRsiResult {
        val rsiResult = rsi(closes, rsiPeriod)
        val rsiValues = rsiResult.values

        // StochRSI = (RSI - min(RSI, N)) / (max(RSI, N) - min(RSI, N))
        val stochRaw = DoubleArray(rsiValues.size) { Double.NaN }
        for (i in stochPeriod - 1 until rsiValues.size) {
            var minRsi = Double.MAX_VALUE
            var maxRsi = Double.MIN_VALUE
            var valid = true
            for (j in i - stochPeriod + 1..i) {
                if (rsiValues[j].isNaN()) { valid = false; break }
                minRsi = minOf(minRsi, rsiValues[j])
                maxRsi = maxOf(maxRsi, rsiValues[j])
            }
            if (valid && maxRsi > minRsi) {
                stochRaw[i] = (rsiValues[i] - minRsi) / (maxRsi - minRsi) * 100
            }
        }

        val kLine = sma(stochRaw, kSmooth)
        val dLine = sma(kLine, dSmooth)

        val k = kLine.lastOrNull { !it.isNaN() } ?: Double.NaN
        val d = dLine.lastOrNull { !it.isNaN() } ?: Double.NaN

        val signal = when {
            k.isNaN() -> "insufficient_data"
            k < 20 -> "oversold"
            k > 80 -> "overbought"
            else -> "neutral"
        }
        return StochRsiResult(k, d, signal)
    }

    // --- VWAP ---
    data class VwapResult(val values: DoubleArray, val current: Double, val signal: String)

    fun vwap(highs: DoubleArray, lows: DoubleArray, closes: DoubleArray, volumes: DoubleArray): VwapResult {
        val tp = DoubleArray(closes.size) { (highs[it] + lows[it] + closes[it]) / 3.0 }
        val result = DoubleArray(closes.size)
        var cumTpVol = 0.0
        var cumVol = 0.0
        for (i in closes.indices) {
            cumTpVol += tp[i] * volumes[i]
            cumVol += volumes[i]
            result[i] = if (cumVol > 0) cumTpVol / cumVol else tp[i]
        }
        val current = result.last()
        val signal = if (closes.last() > current) "above_vwap" else "below_vwap"
        return VwapResult(result, current, signal)
    }
}
