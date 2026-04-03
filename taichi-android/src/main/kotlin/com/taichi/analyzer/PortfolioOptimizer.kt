package com.taichi.analyzer

import com.taichi.model.Candle
import kotlinx.serialization.json.*
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Inverse-volatility risk parity portfolio optimization.
 * Allocates inversely proportional to each position's volatility.
 */
object PortfolioOptimizer {

    fun optimize(
        portfolioJson: JsonObject,
        positionCandles: Map<String, List<Candle>>
    ): JsonObject {
        val positions = portfolioJson["open_positions"]?.jsonArray ?: JsonArray(emptyList())
        val totalValue = portfolioJson["total_value"]?.jsonPrimitive?.doubleOrNull ?: 0.0

        if (positions.size < 2) return buildJsonObject {
            put("message", "Need at least 2 positions to optimize")
        }

        // Compute annualized volatility for each position
        val volatilities = mutableMapOf<String, Double>()

        for (pos in positions) {
            val symbol = pos.jsonObject["symbol"]?.jsonPrimitive?.content ?: continue
            val candles = positionCandles[symbol] ?: continue
            if (candles.size < 10) continue

            val closes = candles.map { it.close }
            val returns = DoubleArray(closes.size - 1) { i ->
                if (closes[i] > 0) (closes[i + 1] - closes[i]) / closes[i] else 0.0
            }

            val mean = returns.average()
            val variance = returns.map { (it - mean) * (it - mean) }.average()
            // Annualize: assume hourly candles * sqrt(8760 hours/year)
            val annualizedVol = sqrt(variance) * sqrt(8760.0)
            volatilities[symbol] = if (annualizedVol > 0) annualizedVol else 0.01
        }

        if (volatilities.isEmpty()) return buildJsonObject {
            put("error", "Could not compute volatility for any position")
        }

        // Inverse-volatility weights
        val invVols = volatilities.mapValues { 1.0 / it.value }
        val totalInvVol = invVols.values.sum()
        val optimalWeights = invVols.mapValues { it.value / totalInvVol }

        // Current weights
        val currentWeights = mutableMapOf<String, Double>()
        for (pos in positions) {
            val symbol = pos.jsonObject["symbol"]?.jsonPrimitive?.content ?: continue
            val marketValue = pos.jsonObject["market_value"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            currentWeights[symbol] = if (totalValue > 0) marketValue / totalValue else 0.0
        }

        // Rebalance actions (only if difference > 2%)
        val actions = mutableListOf<JsonObject>()
        for (symbol in optimalWeights.keys) {
            val current = currentWeights[symbol] ?: 0.0
            val optimal = optimalWeights[symbol] ?: 0.0
            val diff = optimal - current

            if (abs(diff) > 0.02) { // >2% difference triggers rebalance
                val usdChange = diff * totalValue
                actions.add(buildJsonObject {
                    put("symbol", symbol)
                    put("current_weight_pct", (current * 100).round())
                    put("optimal_weight_pct", (optimal * 100).round())
                    put("action", if (usdChange > 0) "buy" else "sell")
                    put("amount_usd", abs(usdChange).round())
                })
            }
        }

        return buildJsonObject {
            put("method", "inverse_volatility_risk_parity")
            put("total_value", totalValue)
            put("allocations", buildJsonObject {
                for (symbol in optimalWeights.keys) {
                    put(symbol, buildJsonObject {
                        put("current_pct", ((currentWeights[symbol] ?: 0.0) * 100).round())
                        put("optimal_pct", ((optimalWeights[symbol] ?: 0.0) * 100).round())
                        put("volatility_annualized", ((volatilities[symbol] ?: 0.0) * 100).round())
                    })
                }
            })
            put("rebalance_actions", JsonArray(actions))
            put("needs_rebalance", actions.isNotEmpty())
        }
    }

    private fun Double.round(decimals: Int = 2): Double {
        if (this.isNaN() || this.isInfinite()) return 0.0
        val factor = Math.pow(10.0, decimals.toDouble())
        return Math.round(this * factor) / factor
    }
}
