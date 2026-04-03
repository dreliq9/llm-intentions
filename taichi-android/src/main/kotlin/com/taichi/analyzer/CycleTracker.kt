package com.taichi.analyzer

import kotlinx.serialization.json.*
import java.io.File
import java.time.Instant

/**
 * Tracks analysis results across cycles and computes deltas.
 * Persists snapshots to a JSON file so Claude can spot divergences
 * like "AAVE MACD building while price flat" across analysis runs.
 */
class CycleTracker(private val dataDir: File) {

    private val snapshotFile = File(dataDir, "cycle_snapshots.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    init {
        dataDir.mkdirs()
    }

    /**
     * Save an analysis snapshot for a symbol.
     */
    fun saveSnapshot(symbol: String, analysis: JsonObject) {
        val snapshots = loadSnapshots()
        val symbolKey = symbol.uppercase()

        val existing = snapshots[symbolKey]?.jsonArray?.toMutableList() ?: mutableListOf()

        // Add timestamp and store
        val timestamped = buildJsonObject {
            put("timestamp", Instant.now().epochSecond)
            put("timestamp_iso", Instant.now().toString())
            analysis.forEach { (k, v) -> put(k, v) }
        }

        existing.add(timestamped)

        // Keep last 20 snapshots per symbol
        while (existing.size > 20) existing.removeAt(0)

        val updated = snapshots.toMutableMap()
        updated[symbolKey] = JsonArray(existing)

        snapshotFile.writeText(json.encodeToString(JsonObject.serializer(), JsonObject(updated)))
    }

    /**
     * Compare latest snapshot to previous for a symbol.
     * Returns deltas for key metrics.
     */
    fun getDeltas(symbol: String): JsonObject {
        val snapshots = loadSnapshots()
        val history = snapshots[symbol.uppercase()]?.jsonArray ?: return buildJsonObject {
            put("error", "No history for $symbol")
        }

        if (history.size < 2) return buildJsonObject {
            put("status", "first_reading")
            put("message", "Only one snapshot — no deltas yet")
        }

        val current = history.last().jsonObject
        val previous = history[history.size - 2].jsonObject

        val deltas = mutableMapOf<String, JsonObject>()

        // Compare signals
        val curSignals = current["signals"]?.jsonObject
        val prevSignals = previous["signals"]?.jsonObject
        if (curSignals != null && prevSignals != null) {
            val changes = mutableListOf<String>()
            for ((name, curVal) in curSignals) {
                val prevVal = prevSignals[name]?.jsonPrimitive?.content
                val curStr = curVal.jsonPrimitive.content
                if (prevVal != null && prevVal != curStr) {
                    changes.add("$name: $prevVal → $curStr")
                }
            }
            deltas["signal_changes"] = buildJsonObject {
                put("count", changes.size)
                put("changes", JsonArray(changes.map { JsonPrimitive(it) }))
            }
        }

        // Compare numeric indicators
        val curIndicators = current["indicators"]?.jsonObject
        val prevIndicators = previous["indicators"]?.jsonObject
        if (curIndicators != null && prevIndicators != null) {
            val numericDeltas = mutableMapOf<String, JsonObject>()

            for ((name, curObj) in curIndicators) {
                val prevObj = prevIndicators[name]?.jsonObject ?: continue
                val curObjJ = curObj.jsonObject

                // Track "value" field changes
                val curValue = curObjJ["value"]?.jsonPrimitive?.doubleOrNull
                val prevValue = prevObj["value"]?.jsonPrimitive?.doubleOrNull
                if (curValue != null && prevValue != null) {
                    numericDeltas[name] = buildJsonObject {
                        put("current", curValue)
                        put("previous", prevValue)
                        put("delta", curValue - prevValue)
                        put("direction", if (curValue > prevValue) "rising" else "falling")
                    }
                }

                // Track histogram for MACD
                if (name == "macd") {
                    val curHist = curObjJ["histogram"]?.jsonPrimitive?.doubleOrNull
                    val prevHist = prevObj["histogram"]?.jsonPrimitive?.doubleOrNull
                    if (curHist != null && prevHist != null) {
                        numericDeltas["macd_histogram"] = buildJsonObject {
                            put("current", curHist)
                            put("previous", prevHist)
                            put("delta", curHist - prevHist)
                            put("building", curHist > prevHist && curHist > 0)
                            put("weakening", curHist < prevHist && curHist > 0)
                        }
                    }
                }
            }

            deltas["indicator_deltas"] = JsonObject(numericDeltas)
        }

        // Price delta
        val curPrice = current["last_price"]?.jsonPrimitive?.doubleOrNull
        val prevPrice = previous["last_price"]?.jsonPrimitive?.doubleOrNull
        if (curPrice != null && prevPrice != null && prevPrice > 0) {
            deltas["price"] = buildJsonObject {
                put("current", curPrice)
                put("previous", prevPrice)
                put("delta", curPrice - prevPrice)
                put("delta_pct", (curPrice - prevPrice) / prevPrice * 100)
            }
        }

        // Divergence detection: indicator moving opposite to price
        val divergences = detectDivergences(deltas, curPrice, prevPrice)

        // Timestamps
        val curTs = current["timestamp_iso"]?.jsonPrimitive?.contentOrNull
        val prevTs = previous["timestamp_iso"]?.jsonPrimitive?.contentOrNull

        return buildJsonObject {
            put("symbol", symbol.uppercase())
            put("current_snapshot", curTs ?: "unknown")
            put("previous_snapshot", prevTs ?: "unknown")
            put("snapshots_available", history.size)
            deltas.forEach { (k, v) -> put(k, v) }
            put("divergences", divergences)
        }
    }

    /**
     * Get full history for a symbol (all snapshots).
     */
    fun getHistory(symbol: String): JsonArray {
        val snapshots = loadSnapshots()
        return snapshots[symbol.uppercase()]?.jsonArray ?: JsonArray(emptyList())
    }

    private fun detectDivergences(
        deltas: Map<String, JsonObject>,
        curPrice: Double?,
        prevPrice: Double?
    ): JsonArray {
        if (curPrice == null || prevPrice == null) return JsonArray(emptyList())
        val priceRising = curPrice > prevPrice
        val divergences = mutableListOf<String>()

        val indicatorDeltas = deltas["indicator_deltas"]?.jsonObject
        if (indicatorDeltas != null) {
            // MACD histogram building while price flat/falling
            val macdHist = indicatorDeltas["macd_histogram"]?.jsonObject
            if (macdHist != null) {
                val building = macdHist["building"]?.jsonPrimitive?.booleanOrNull == true
                if (building && !priceRising) {
                    divergences.add("Bullish divergence: MACD histogram building while price flat/falling")
                }
                val weakening = macdHist["weakening"]?.jsonPrimitive?.booleanOrNull == true
                if (weakening && priceRising) {
                    divergences.add("Bearish divergence: MACD histogram weakening while price rising")
                }
            }

            // OBV divergence
            val obvDelta = indicatorDeltas["obv"]?.jsonObject
            if (obvDelta != null) {
                val obvRising = obvDelta["direction"]?.jsonPrimitive?.content == "rising"
                if (obvRising && !priceRising) {
                    divergences.add("Bullish divergence: OBV accumulating while price flat/falling")
                }
                if (!obvRising && priceRising) {
                    divergences.add("Bearish divergence: OBV distributing while price rising")
                }
            }

            // RSI divergence
            val rsiDelta = indicatorDeltas["rsi"]?.jsonObject
            if (rsiDelta != null) {
                val rsiRising = rsiDelta["direction"]?.jsonPrimitive?.content == "rising"
                if (rsiRising && !priceRising) {
                    divergences.add("Bullish divergence: RSI rising while price flat/falling")
                }
                if (!rsiRising && priceRising) {
                    divergences.add("Bearish divergence: RSI falling while price rising")
                }
            }
        }

        return JsonArray(divergences.map { JsonPrimitive(it) })
    }

    private fun loadSnapshots(): Map<String, JsonElement> {
        if (!snapshotFile.exists()) return emptyMap()
        return try {
            json.parseToJsonElement(snapshotFile.readText()).jsonObject
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
