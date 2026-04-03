package com.taichi.analyzer

import com.taichi.model.Candle
import kotlinx.serialization.json.*
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * ML-style analyzers: KMeans regime classification and Z-score anomaly detection.
 * Hand-rolled replacements for sklearn's KMeans and IsolationForest.
 */
object MlAnalyzer {

    // ====== KMEANS ======

    private data class KMeansResult(
        val labels: IntArray,
        val centroids: Array<DoubleArray>,
        val iterations: Int
    )

    /**
     * Simple KMeans implementation. Enough for 4 clusters on 200 data points.
     */
    private fun kmeans(
        data: Array<DoubleArray>,
        k: Int = 4,
        maxIterations: Int = 100,
        seed: Long = 42
    ): KMeansResult {
        val n = data.size
        val dims = data[0].size
        val rng = Random(seed)

        // Initialize centroids with k-means++ style
        val centroids = Array(k) { DoubleArray(dims) }
        val usedIndices = mutableSetOf<Int>()
        usedIndices.add(rng.nextInt(n))
        centroids[0] = data[usedIndices.first()].copyOf()

        for (c in 1 until k) {
            // Pick next centroid weighted by distance to nearest existing centroid
            var bestIdx = 0
            var bestDist = 0.0
            for (i in data.indices) {
                if (i in usedIndices) continue
                val minDist = (0 until c).minOf { euclideanDist(data[i], centroids[it]) }
                if (minDist > bestDist) {
                    bestDist = minDist
                    bestIdx = i
                }
            }
            usedIndices.add(bestIdx)
            centroids[c] = data[bestIdx].copyOf()
        }

        val labels = IntArray(n)
        var iterations = 0

        for (iter in 0 until maxIterations) {
            iterations = iter + 1
            var changed = false

            // Assign each point to nearest centroid
            for (i in data.indices) {
                val nearest = (0 until k).minByOrNull { euclideanDist(data[i], centroids[it]) } ?: 0
                if (labels[i] != nearest) {
                    labels[i] = nearest
                    changed = true
                }
            }

            if (!changed) break

            // Update centroids
            for (c in 0 until k) {
                val members = data.indices.filter { labels[it] == c }
                if (members.isEmpty()) continue
                for (d in 0 until dims) {
                    centroids[c][d] = members.map { data[it][d] }.average()
                }
            }
        }

        return KMeansResult(labels, centroids, iterations)
    }

    private fun euclideanDist(a: DoubleArray, b: DoubleArray): Double {
        var sum = 0.0
        for (i in a.indices) {
            val diff = a[i] - b[i]
            sum += diff * diff
        }
        return sqrt(sum)
    }

    // ====== STANDARD SCALER ======

    private data class ScaledData(
        val data: Array<DoubleArray>,
        val means: DoubleArray,
        val stds: DoubleArray
    )

    private fun standardScale(data: Array<DoubleArray>): ScaledData {
        val dims = data[0].size
        val means = DoubleArray(dims)
        val stds = DoubleArray(dims)

        for (d in 0 until dims) {
            val col = data.map { it[d] }
            means[d] = col.average()
            val variance = col.map { (it - means[d]) * (it - means[d]) }.average()
            stds[d] = if (variance > 0) sqrt(variance) else 1.0
        }

        val scaled = Array(data.size) { i ->
            DoubleArray(dims) { d -> (data[i][d] - means[d]) / stds[d] }
        }

        return ScaledData(scaled, means, stds)
    }

    // ====== REGIME CLASSIFICATION ======

    /**
     * Classify market regime from candle data using KMeans on engineered features.
     * Labels: trending, trending_high_volume, compression, low_vol_accumulation, distribution, choppy
     */
    fun classifyRegime(candles: List<Candle>, nRegimes: Int = 4, lookback: Int = 200): JsonObject {
        val data = candles.takeLast(lookback)
        if (data.size < 50) return buildJsonObject {
            put("error", "Need at least 50 candles, got ${data.size}")
        }

        val closes = data.map { it.close }.toDoubleArray()
        val highs = data.map { it.high }.toDoubleArray()
        val lows = data.map { it.low }.toDoubleArray()
        val volumes = data.map { it.volume }.toDoubleArray()

        // Engineer features per candle (need lookback within the data)
        val featureStart = 30 // skip first 30 for rolling calculations
        val features = mutableListOf<DoubleArray>()

        for (i in featureStart until closes.size) {
            val slice10 = closes.sliceArray((i - 10)..i)
            val slice30 = if (i >= 30) closes.sliceArray((i - 30)..i) else slice10

            // ATR % of price
            val atrPct = if (closes[i] > 0) {
                val tr = (highs[i] - lows[i]) / closes[i] * 100
                tr
            } else 0.0

            // Rolling volatility (10-period std of returns)
            val returns10 = DoubleArray(slice10.size - 1) { j ->
                if (slice10[j] > 0) (slice10[j + 1] - slice10[j]) / slice10[j] else 0.0
            }
            val vol10 = if (returns10.isNotEmpty()) {
                val mean = returns10.average()
                sqrt(returns10.map { (it - mean) * (it - mean) }.average()) * 100
            } else 0.0

            // 30-period volatility
            val returns30 = DoubleArray(slice30.size - 1) { j ->
                if (slice30[j] > 0) (slice30[j + 1] - slice30[j]) / slice30[j] else 0.0
            }
            val vol30 = if (returns30.isNotEmpty()) {
                val mean = returns30.average()
                sqrt(returns30.map { (it - mean) * (it - mean) }.average()) * 100
            } else 0.0

            // Compression ratio
            val compressionRatio = if (vol30 > 0) vol10 / vol30 else 1.0

            // Trend strength (10-period return)
            val trendStrength = if (slice10.first() > 0) {
                (slice10.last() - slice10.first()) / slice10.first() * 100
            } else 0.0

            // Volume vs SMA ratio
            val volSlice = volumes.sliceArray(maxOf(0, i - 20)..i)
            val volSma = volSlice.average()
            val volRatio = if (volSma > 0) volumes[i] / volSma else 1.0

            features.add(doubleArrayOf(atrPct, vol10, vol30, compressionRatio, trendStrength, volRatio))
        }

        if (features.size < nRegimes * 2) return buildJsonObject {
            put("error", "Insufficient feature data")
        }

        // Scale and cluster
        val featureArray = features.toTypedArray()
        val scaled = standardScale(featureArray)
        val result = kmeans(scaled.data, k = nRegimes)

        // Characterize each cluster
        val clusterChars = mutableMapOf<Int, JsonObject>()
        for (c in 0 until nRegimes) {
            val members = features.indices.filter { result.labels[it] == c }
            if (members.isEmpty()) continue

            val avgVol = members.map { features[it][1] }.average()
            val avgCompression = members.map { features[it][3] }.average()
            val avgTrend = members.map { features[it][4] }.average()
            val avgVolRatio = members.map { features[it][5] }.average()

            // Label the regime
            val label = when {
                abs(avgTrend) > 1.5 && avgVolRatio > 1.2 -> "trending_high_volume"
                abs(avgTrend) > 1.0 -> "trending"
                avgCompression < 0.7 -> "compression"
                avgVol < 0.5 && avgVolRatio < 0.8 -> "low_vol_accumulation"
                avgVol > 1.5 && abs(avgTrend) < 0.5 -> "choppy"
                avgTrend < -0.5 && avgVolRatio > 1.0 -> "distribution"
                else -> "mixed"
            }

            clusterChars[c] = buildJsonObject {
                put("label", label)
                put("avg_volatility", avgVol.round())
                put("avg_compression", avgCompression.round())
                put("avg_trend_strength", avgTrend.round())
                put("avg_volume_ratio", avgVolRatio.round())
                put("member_count", members.size)
            }
        }

        // Current regime = label of last candle's cluster
        val currentCluster = result.labels.last()
        val currentRegime = clusterChars[currentCluster]?.get("label")?.jsonPrimitive?.content ?: "unknown"

        // How long in current regime
        var regimeDuration = 1
        for (i in result.labels.size - 2 downTo 0) {
            if (result.labels[i] == currentCluster) regimeDuration++ else break
        }

        return buildJsonObject {
            put("current_regime", currentRegime)
            put("regime_id", currentCluster)
            put("candles_in_regime", regimeDuration)
            put("n_regimes", nRegimes)
            put("clusters", JsonObject(clusterChars.map { (k, v) -> k.toString() to v }.toMap()))
            put("signal_guidance", regimeGuidance(currentRegime))
        }
    }

    private fun regimeGuidance(regime: String): JsonObject = buildJsonObject {
        when (regime) {
            "trending", "trending_high_volume" -> {
                put("trust", JsonArray(listOf("macd", "obv", "ema").map { JsonPrimitive(it) }))
                put("avoid", JsonArray(listOf("rsi_mean_reversion").map { JsonPrimitive(it) }))
                put("note", "Trend-following signals are reliable. Avoid fading the trend.")
            }
            "compression" -> {
                put("trust", JsonArray(listOf("bbands", "atr").map { JsonPrimitive(it) }))
                put("avoid", JsonArray(listOf("macd").map { JsonPrimitive(it) }))
                put("note", "Volatility compression often precedes breakout. Watch for expansion.")
            }
            "low_vol_accumulation" -> {
                put("trust", JsonArray(listOf("obv", "rsi").map { JsonPrimitive(it) }))
                put("avoid", JsonArray(listOf("macd").map { JsonPrimitive(it) }))
                put("note", "Quiet accumulation phase. OBV divergences are most informative.")
            }
            "distribution" -> {
                put("trust", JsonArray(listOf("obv", "rsi_overbought").map { JsonPrimitive(it) }))
                put("avoid", JsonArray(listOf("macd_bullish").map { JsonPrimitive(it) }))
                put("note", "Distribution phase — smart money may be exiting. Be cautious on longs.")
            }
            "choppy" -> {
                put("trust", JsonArray(emptyList()))
                put("avoid", JsonArray(listOf("all_signals").map { JsonPrimitive(it) }))
                put("note", "No-trade zone. Signals are unreliable in choppy conditions.")
            }
            else -> {
                put("note", "Mixed regime — use multiple confirmations before acting.")
            }
        }
    }

    // ====== ANOMALY DETECTION ======

    /**
     * Z-score based anomaly detection. Simpler than IsolationForest but
     * effective for detecting unusual price/volume behavior.
     */
    fun detectAnomalies(
        candles: List<Candle>,
        columns: List<String> = listOf("close", "volume"),
        lookback: Int = 90,
        threshold: Double = 2.5
    ): JsonObject {
        val data = candles.takeLast(lookback)
        if (data.size < 20) return buildJsonObject {
            put("error", "Need at least 20 candles, got ${data.size}")
        }

        val columnData = mutableMapOf<String, DoubleArray>()
        for (col in columns) {
            columnData[col] = when (col) {
                "close" -> data.map { it.close }.toDoubleArray()
                "volume" -> data.map { it.volume }.toDoubleArray()
                "high" -> data.map { it.high }.toDoubleArray()
                "low" -> data.map { it.low }.toDoubleArray()
                "open" -> data.map { it.open }.toDoubleArray()
                else -> continue
            }
        }

        val zScores = mutableMapOf<String, Double>()
        var isAnomaly = false
        val anomalyDetails = mutableListOf<String>()

        for ((col, values) in columnData) {
            val mean = values.average()
            val std = sqrt(values.map { (it - mean) * (it - mean) }.average())
            val latestZ = if (std > 0) (values.last() - mean) / std else 0.0
            zScores[col] = latestZ

            if (abs(latestZ) > threshold) {
                isAnomaly = true
                val direction = if (latestZ > 0) "unusually high" else "unusually low"
                anomalyDetails.add("$col is $direction (z-score: ${"%.2f".format(latestZ)})")
            }
        }

        // Count recent anomalies (last 10 candles)
        var recentAnomalies = 0
        val closeValues = columnData["close"] ?: data.map { it.close }.toDoubleArray()
        val closeMean = closeValues.average()
        val closeStd = sqrt(closeValues.map { (it - closeMean) * (it - closeMean) }.average())
        if (closeStd > 0) {
            for (i in maxOf(0, closeValues.size - 10) until closeValues.size) {
                val z = abs((closeValues[i] - closeMean) / closeStd)
                if (z > threshold) recentAnomalies++
            }
        }

        return buildJsonObject {
            put("is_anomaly", isAnomaly)
            put("z_scores", buildJsonObject {
                zScores.forEach { (k, v) -> put(k, v.round()) }
            })
            put("threshold", threshold)
            put("details", JsonArray(anomalyDetails.map { JsonPrimitive(it) }))
            put("recent_anomaly_count", recentAnomalies)
            put("lookback", data.size)
        }
    }

    private fun Double.round(decimals: Int = 4): Double {
        if (this.isNaN() || this.isInfinite()) return 0.0
        val factor = Math.pow(10.0, decimals.toDouble())
        return Math.round(this * factor) / factor
    }
}
