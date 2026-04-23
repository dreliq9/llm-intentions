package com.taichi.trading

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.Duration

/**
 * Paper trading engine with SQLite persistence.
 * Supports long and short positions with margin simulation.
 * Ports Python's paper_engine.py — buy/sell/short/cover with 0.1% fee model,
 * position averaging, partial closes, funding rates, liquidation, PnL tracking.
 */
class PaperEngine(
    context: Context,
    private val defaultCapital: Double = 10_000.0,
    private val feePct: Double = 0.001,
    private val getPriceFn: suspend (String) -> Double?
) {
    private val db: SQLiteDatabase

    companion object {
        const val FUNDING_RATE = 0.0001        // 0.01% per 8h interval
        const val FUNDING_INTERVAL_HOURS = 8.0
        const val DEFAULT_BORROW_RATE = 0.10    // 10% annualized
        const val SHORT_EXPOSURE_LIMIT = 0.5    // 50% of portfolio
        const val FARM_GAS_FEE = 3.0            // simulated gas per deposit/withdraw
        const val FARM_MIN_DEPOSIT = 100.0      // below this, gas eats yield
    }

    init {
        val helper = PaperDbHelper(context)
        db = helper.writableDatabase
        ensurePortfolio()
    }

    // ── Buy (open/average long) ─────────────────────────────────────────

    suspend fun buy(symbol: String, amountUsd: Double? = null, quantity: Double? = null): JsonObject {
        val price = getPriceFn(symbol.uppercase())
            ?: return errorResult("Could not fetch price for $symbol")
        if (price <= 0) return errorResult("Invalid price: $price")

        val cash = getCash()
        val qty: Double
        val totalCost: Double
        val fee: Double

        if (amountUsd != null) {
            if (amountUsd > cash) return errorResult("Insufficient cash: $${"%.2f".format(cash)} available")
            fee = amountUsd * feePct
            qty = (amountUsd - fee) / price
            totalCost = amountUsd
        } else if (quantity != null) {
            totalCost = quantity * price
            fee = totalCost * feePct
            if (totalCost + fee > cash) return errorResult("Insufficient cash: $${"%.2f".format(cash)} available")
            qty = quantity
        } else {
            return errorResult("Specify amount_usd or quantity")
        }

        val sym = symbol.uppercase()
        val now = Instant.now().toString()

        val existing = getOpenPosition(sym, "long")
        if (existing != null) {
            val newQty = existing.quantity + qty
            val newCost = (existing.entryPrice * existing.quantity + price * qty) / newQty
            val newFees = existing.buyFees + fee
            db.execSQL(
                "UPDATE positions SET quantity=?, entry_price=?, buy_fees=? WHERE id=?",
                arrayOf<Any>(newQty, newCost, newFees, existing.id)
            )
        } else {
            val cv = ContentValues().apply {
                put("symbol", sym); put("side", "long")
                put("quantity", qty); put("entry_price", price)
                put("buy_fees", fee); put("opened_at", now)
                put("status", "open"); put("cumulative_realized_pnl", 0.0)
            }
            db.insert("positions", null, cv)
        }

        db.insert("trades", null, ContentValues().apply {
            put("symbol", sym); put("action", "buy")
            put("quantity", qty); put("price", price)
            put("fee", fee); put("net_amount", -totalCost)
            put("executed_at", now)
        })
        db.execSQL("UPDATE portfolio SET cash = cash - ?", arrayOf(totalCost))

        return buildJsonObject {
            put("status", "success"); put("action", "buy")
            put("symbol", sym); put("quantity", qty)
            put("price", price); put("fee", fee)
            put("total_cost", totalCost); put("remaining_cash", getCash())
        }
    }

    // ── Sell (close/reduce long) ────────────────────────────────────────

    suspend fun sell(symbol: String, quantity: Double? = null, closeAll: Boolean = false): JsonObject {
        val sym = symbol.uppercase()
        val position = getOpenPosition(sym, "long")
            ?: return errorResult("No open long position for $sym")
        val price = getPriceFn(sym) ?: return errorResult("Could not fetch price for $sym")

        val sellQty = if (closeAll || quantity == null) position.quantity else minOf(quantity, position.quantity)
        val grossProceeds = sellQty * price
        val fee = grossProceeds * feePct
        val netProceeds = grossProceeds - fee
        val costBasis = position.entryPrice * sellQty
        val realizedPnl = netProceeds - costBasis
        val now = Instant.now().toString()

        if (sellQty >= position.quantity) {
            db.execSQL(
                "UPDATE positions SET status='closed', closed_at=?, exit_price=?, realized_pnl=?, quantity=0 WHERE id=?",
                arrayOf<Any>(now, price, position.cumulativeRealizedPnl + realizedPnl, position.id)
            )
        } else {
            val remainingQty = position.quantity - sellQty
            val remainingFees = position.buyFees * (1 - sellQty / position.quantity)
            db.execSQL(
                "UPDATE positions SET quantity=?, buy_fees=?, cumulative_realized_pnl=? WHERE id=?",
                arrayOf<Any>(remainingQty, remainingFees, position.cumulativeRealizedPnl + realizedPnl, position.id)
            )
        }

        db.insert("trades", null, ContentValues().apply {
            put("symbol", sym); put("action", "sell")
            put("quantity", sellQty); put("price", price)
            put("fee", fee); put("net_amount", netProceeds)
            put("executed_at", now)
        })
        db.execSQL("UPDATE portfolio SET cash = cash + ?", arrayOf(netProceeds))

        return buildJsonObject {
            put("status", "success"); put("action", "sell")
            put("symbol", sym); put("quantity", sellQty)
            put("price", price); put("fee", fee)
            put("net_proceeds", netProceeds)
            put("realized_pnl", realizedPnl)
            put("remaining_cash", getCash())
        }
    }

    // ── Short sell (open/average short) ─────────────────────────────────

    suspend fun shortSell(
        symbol: String,
        amountUsd: Double? = null,
        quantity: Double? = null,
        leverage: Double = 1.0
    ): JsonObject {
        if (leverage < 1.0) return errorResult("Leverage must be >= 1.0")

        val sym = symbol.uppercase()
        val price = getPriceFn(sym) ?: return errorResult("Could not fetch price for $sym")
        if (price <= 0) return errorResult("Invalid price: $price")

        val cash = getCash()
        val qty: Double
        val notional: Double
        val fee: Double

        if (amountUsd != null) {
            notional = amountUsd
            fee = notional * feePct
            qty = notional / price
        } else if (quantity != null) {
            qty = quantity
            notional = qty * price
            fee = notional * feePct
        } else {
            return errorResult("Specify amount_usd or quantity")
        }

        val collateral = notional / leverage
        val cashNeeded = collateral + fee
        if (cashNeeded > cash) {
            return errorResult(
                "Insufficient cash for short. Need $${"%.2f".format(cashNeeded)} " +
                "($${"%.2f".format(collateral)} collateral + $${"%.2f".format(fee)} fee), " +
                "have $${"%.2f".format(cash)}"
            )
        }

        // Exposure limit check
        val existingShortNotional = getTotalShortNotional()
        val totalPortfolio = cash + getTotalLongValue()
        if (existingShortNotional + notional > totalPortfolio * SHORT_EXPOSURE_LIMIT) {
            return errorResult(
                "Short exposure limit: adding $${"%.2f".format(notional)} would push total " +
                "short notional to $${"%.2f".format(existingShortNotional + notional)}, " +
                "exceeding 50% of portfolio"
            )
        }

        // Liquidation price: loss = (liq - entry) * qty = collateral
        val liqPrice = price + (collateral / qty)
        val now = Instant.now().toString()

        val existing = getOpenPosition(sym, "short")
        if (existing != null) {
            val oldNotional = existing.quantity * existing.entryPrice
            val newNotional = qty * price
            val combinedQty = existing.quantity + qty
            val avgPrice = (oldNotional + newNotional) / combinedQty
            val totalCollateral = existing.collateral + collateral
            val totalFees = existing.buyFees + fee
            val newLiq = avgPrice + (totalCollateral / combinedQty)

            db.execSQL(
                "UPDATE positions SET quantity=?, entry_price=?, buy_fees=?, " +
                "collateral=?, liquidation_price=?, leverage=? WHERE id=?",
                arrayOf<Any>(combinedQty, avgPrice, totalFees, totalCollateral, newLiq, leverage, existing.id)
            )
        } else {
            val cv = ContentValues().apply {
                put("symbol", sym); put("side", "short")
                put("quantity", qty); put("entry_price", price)
                put("buy_fees", fee); put("opened_at", now)
                put("status", "open"); put("cumulative_realized_pnl", 0.0)
                put("leverage", leverage); put("collateral", collateral)
                put("liquidation_price", liqPrice)
                put("cumulative_funding", 0.0)
                put("borrow_rate_annual", DEFAULT_BORROW_RATE)
                put("last_funding_at", now)
            }
            db.insert("positions", null, cv)
        }

        db.insert("trades", null, ContentValues().apply {
            put("symbol", sym); put("action", "short_sell")
            put("quantity", qty); put("price", price)
            put("fee", fee); put("net_amount", -(cashNeeded))
            put("executed_at", now)
        })
        db.execSQL("UPDATE portfolio SET cash = cash - ?", arrayOf(cashNeeded))

        return buildJsonObject {
            put("status", "success"); put("action", "short_sell")
            put("symbol", sym); put("quantity", qty)
            put("entry_price", price); put("fee", fee)
            put("collateral_locked", collateral)
            put("liquidation_price", liqPrice)
            put("leverage", leverage)
            put("remaining_cash", getCash())
        }
    }

    // ── Cover (close/reduce short) ──────────────────────────────────────

    suspend fun cover(symbol: String, quantity: Double? = null, coverAll: Boolean = false): JsonObject {
        val sym = symbol.uppercase()

        // Apply pending funding before closing
        applyFundingSingle(sym)

        val position = getOpenPosition(sym, "short")
            ?: return errorResult("No open short position for $sym")
        val price = getPriceFn(sym) ?: return errorResult("Could not fetch price for $sym")

        val coverQty = if (coverAll || quantity == null) position.quantity else minOf(quantity, position.quantity)
        val coverFraction = coverQty / position.quantity

        val coverCost = coverQty * price
        val coverFee = coverCost * feePct

        // Gross PnL from price movement (positive if price fell)
        val grossPnl = (position.entryPrice - price) * coverQty
        val fundingPortion = position.cumulativeFunding * coverFraction
        val realizedPnl = grossPnl - coverFee - fundingPortion

        val collateralReleased = position.collateral * coverFraction
        // Net cash: collateral back + gross PnL - cover fee
        // (funding already deducted from cash as it accrued)
        val netCash = collateralReleased + grossPnl - coverFee

        val now = Instant.now().toString()
        val remainingQty = position.quantity - coverQty

        if (remainingQty < 1e-10) {
            val cumulative = position.cumulativeRealizedPnl + realizedPnl
            db.execSQL(
                "UPDATE positions SET status='closed', closed_at=?, exit_price=?, " +
                "realized_pnl=?, cumulative_realized_pnl=?, collateral=0 WHERE id=?",
                arrayOf<Any>(now, price, cumulative, cumulative, position.id)
            )
        } else {
            val remainingCollateral = position.collateral * (1 - coverFraction)
            val remainingFunding = position.cumulativeFunding * (1 - coverFraction)
            val remainingFees = position.buyFees * (1 - coverFraction)
            val newLiq = position.entryPrice + (remainingCollateral / remainingQty)
            db.execSQL(
                "UPDATE positions SET quantity=?, buy_fees=?, collateral=?, " +
                "liquidation_price=?, cumulative_funding=?, " +
                "cumulative_realized_pnl=cumulative_realized_pnl+? WHERE id=?",
                arrayOf<Any>(remainingQty, remainingFees, remainingCollateral,
                    newLiq, remainingFunding, realizedPnl, position.id)
            )
        }

        db.insert("trades", null, ContentValues().apply {
            put("symbol", sym); put("action", "cover")
            put("quantity", coverQty); put("price", price)
            put("fee", coverFee); put("net_amount", netCash)
            put("executed_at", now)
        })
        db.execSQL("UPDATE portfolio SET cash = cash + ?", arrayOf(netCash))

        return buildJsonObject {
            put("status", "success"); put("action", "cover")
            put("symbol", sym); put("quantity", coverQty)
            put("cover_price", price); put("entry_price", position.entryPrice)
            put("fee", coverFee); put("gross_pnl", grossPnl)
            put("funding_cost", fundingPortion)
            put("realized_pnl", realizedPnl)
            put("collateral_released", collateralReleased)
            put("remaining_quantity", remainingQty)
            put("remaining_cash", getCash())
        }
    }

    // ── Funding & liquidation ───────────────────────────────────────────

    private fun applyFundingSingle(symbol: String) {
        val cursor = db.rawQuery(
            "SELECT * FROM positions WHERE symbol=? AND status='open' AND side='short'",
            arrayOf(symbol)
        )
        if (cursor.moveToFirst()) applyFundingToRow(cursor)
        cursor.close()
    }

    private fun applyFundingAll() {
        val cursor = db.rawQuery(
            "SELECT * FROM positions WHERE status='open' AND side='short'", null
        )
        while (cursor.moveToNext()) applyFundingToRow(cursor)
        cursor.close()
    }

    private fun applyFundingToRow(cursor: android.database.Cursor) {
        val id = cursor.getInt(cursor.getColumnIndexOrThrow("id"))
        val lastAtStr = cursor.getString(cursor.getColumnIndexOrThrow("last_funding_at")) ?: return
        val entryPrice = cursor.getDouble(cursor.getColumnIndexOrThrow("entry_price"))
        val qty = cursor.getDouble(cursor.getColumnIndexOrThrow("quantity"))

        val now = Instant.now()
        val lastAt = try { Instant.parse(lastAtStr) } catch (_: Exception) { return }
        val elapsedHours = Duration.between(lastAt, now).seconds / 3600.0

        if (elapsedHours < 0.1) return

        val notional = qty * entryPrice
        val fundingPeriods = elapsedHours / FUNDING_INTERVAL_HOURS
        val charge = notional * FUNDING_RATE * fundingPeriods

        if (charge < 0.0001) return

        db.execSQL(
            "UPDATE positions SET cumulative_funding = cumulative_funding + ?, last_funding_at = ? WHERE id = ?",
            arrayOf<Any>(charge, now.toString(), id)
        )
        db.execSQL("UPDATE portfolio SET cash = cash - ?", arrayOf(charge))
    }

    private fun checkLiquidations(): List<JsonObject> {
        val cursor = db.rawQuery(
            "SELECT * FROM positions WHERE status='open' AND side='short'", null
        )
        val liquidated = mutableListOf<JsonObject>()
        val now = Instant.now().toString()

        while (cursor.moveToNext()) {
            val id = cursor.getInt(cursor.getColumnIndexOrThrow("id"))
            val sym = cursor.getString(cursor.getColumnIndexOrThrow("symbol"))
            val entryPrice = cursor.getDouble(cursor.getColumnIndexOrThrow("entry_price"))
            val qty = cursor.getDouble(cursor.getColumnIndexOrThrow("quantity"))
            val liqPrice = cursor.getDouble(cursor.getColumnIndexOrThrow("liquidation_price"))
            val collateral = cursor.getDouble(cursor.getColumnIndexOrThrow("collateral"))
            val funding = cursor.getDouble(cursor.getColumnIndexOrThrow("cumulative_funding"))

            // Need a synchronous price check — use entry price as fallback
            // Liquidation is checked during getPortfolio which already has prices
            // For now, skip if liqPrice is 0
            if (liqPrice <= 0) continue

            // We can't call suspend getPriceFn here — liquidation is checked in getPortfolio
            // where we already have current prices. This method is for the tool endpoint.
        }
        cursor.close()
        return liquidated
    }

    // Called from getPortfolio with already-fetched price
    private fun forceLiquidate(posId: Int, sym: String, entryPrice: Double, qty: Double,
                               collateral: Double, funding: Double, currentPrice: Double) {
        val now = Instant.now().toString()
        val coverFee = qty * currentPrice * feePct
        val grossPnl = (entryPrice - currentPrice) * qty
        val realizedPnl = grossPnl - coverFee - funding
        val netCash = collateral + grossPnl - coverFee

        db.execSQL(
            "UPDATE positions SET status='closed', closed_at=?, exit_price=?, " +
            "realized_pnl=?, cumulative_realized_pnl=?, collateral=0 WHERE id=?",
            arrayOf<Any>(now, currentPrice, realizedPnl, realizedPnl, posId)
        )
        db.insert("trades", null, ContentValues().apply {
            put("symbol", sym); put("action", "liquidation")
            put("quantity", qty); put("price", currentPrice)
            put("fee", coverFee); put("net_amount", netCash)
            put("executed_at", now)
        })
        db.execSQL("UPDATE portfolio SET cash = cash + ?", arrayOf(netCash))
    }

    // ── Portfolio ───────────────────────────────────────────────────────

    suspend fun getPortfolio(): JsonObject {
        applyFundingAll()

        val cash = getCash()
        val startingCapital = getStartingCapital()
        val allPositions = getOpenPositions()

        val longList = mutableListOf<JsonObject>()
        val shortList = mutableListOf<JsonObject>()
        var longValue = 0.0
        var shortUnrealized = 0.0
        val liquidationEvents = mutableListOf<JsonObject>()

        for (pos in allPositions) {
            val currentPrice = getPriceFn(pos.symbol) ?: pos.entryPrice

            if (pos.side == "long") {
                val marketValue = currentPrice * pos.quantity
                val unrealizedPnl = marketValue - (pos.entryPrice * pos.quantity)
                val unrealizedPct = if (pos.entryPrice > 0) unrealizedPnl / (pos.entryPrice * pos.quantity) * 100 else 0.0
                longValue += marketValue

                longList.add(buildJsonObject {
                    put("symbol", pos.symbol); put("side", "long")
                    put("quantity", pos.quantity); put("entry_price", pos.entryPrice)
                    put("current_price", currentPrice)
                    put("market_value", marketValue)
                    put("unrealized_pnl", unrealizedPnl)
                    put("unrealized_pnl_pct", unrealizedPct)
                    put("buy_fees", pos.buyFees)
                    put("partial_realized_pnl", pos.cumulativeRealizedPnl)
                    put("opened_at", pos.openedAt)
                })
            } else {
                // Check liquidation first
                if (pos.liquidationPrice > 0 && currentPrice >= pos.liquidationPrice) {
                    forceLiquidate(pos.id, pos.symbol, pos.entryPrice, pos.quantity,
                        pos.collateral, pos.cumulativeFunding, currentPrice)
                    liquidationEvents.add(buildJsonObject {
                        put("symbol", pos.symbol); put("entry_price", pos.entryPrice)
                        put("liquidation_price", pos.liquidationPrice)
                        put("cover_price", currentPrice)
                        put("quantity", pos.quantity)
                    })
                    continue
                }

                val notional = pos.quantity * pos.entryPrice
                val grossPnl = (pos.entryPrice - currentPrice) * pos.quantity
                val unrealized = grossPnl - pos.cumulativeFunding
                val unrealizedPct = if (notional > 0) unrealized / notional * 100 else 0.0
                val liqDistPct = if (pos.liquidationPrice > 0 && currentPrice > 0)
                    (pos.liquidationPrice - currentPrice) / currentPrice * 100 else null

                shortUnrealized += unrealized
                shortList.add(buildJsonObject {
                    put("symbol", pos.symbol); put("side", "short")
                    put("quantity", pos.quantity); put("entry_price", pos.entryPrice)
                    put("current_price", currentPrice)
                    put("notional", notional)
                    put("unrealized_pnl", unrealized)
                    put("unrealized_pnl_pct", unrealizedPct)
                    put("cumulative_funding", pos.cumulativeFunding)
                    put("collateral_locked", pos.collateral)
                    put("liquidation_price", pos.liquidationPrice)
                    put("liq_distance_pct", liqDistPct)
                    put("leverage", pos.leverage)
                    put("buy_fees", pos.buyFees)
                    put("partial_realized_pnl", pos.cumulativeRealizedPnl)
                    put("opened_at", pos.openedAt)
                })
            }
        }

        val farmValue = computeFarmTotalValue()
        val totalValue = cash + longValue + shortUnrealized + farmValue
        val totalPnl = totalValue - startingCapital

        // Win rates
        val closedCursor = db.rawQuery(
            "SELECT realized_pnl, side FROM positions WHERE status='closed' AND realized_pnl IS NOT NULL", null
        )
        var wins = 0; var closedCount = 0
        var longWins = 0; var longClosed = 0
        var shortWins = 0; var shortClosed = 0
        while (closedCursor.moveToNext()) {
            closedCount++
            val pnl = closedCursor.getDouble(0)
            val side = closedCursor.getString(1)
            if (pnl > 0) wins++
            if (side == "long") { longClosed++; if (pnl > 0) longWins++ }
            else { shortClosed++; if (pnl > 0) shortWins++ }
        }
        closedCursor.close()

        val feeCursor = db.rawQuery("SELECT COALESCE(SUM(fee), 0) FROM trades", null)
        feeCursor.moveToFirst(); val totalFees = feeCursor.getDouble(0); feeCursor.close()
        val tradeCursor = db.rawQuery("SELECT COUNT(*) FROM trades", null)
        tradeCursor.moveToFirst(); val totalTrades = tradeCursor.getInt(0); tradeCursor.close()

        return buildJsonObject {
            put("cash", cash)
            put("long_positions_value", longValue)
            put("short_unrealized_pnl", shortUnrealized)
            put("farm_value", farmValue)
            put("total_value", totalValue)
            put("total_pnl", totalPnl)
            put("total_pnl_pct", if (startingCapital > 0) totalPnl / startingCapital * 100 else 0.0)
            put("starting_capital", startingCapital)
            put("long_positions", JsonArray(longList))
            put("short_positions", JsonArray(shortList))
            put("total_trades", totalTrades)
            put("total_fees", totalFees)
            put("win_rate", if (closedCount > 0) wins.toDouble() / closedCount * 100 else null)
            put("long_win_rate", if (longClosed > 0) longWins.toDouble() / longClosed * 100 else null)
            put("short_win_rate", if (shortClosed > 0) shortWins.toDouble() / shortClosed * 100 else null)
            put("closed_trades", closedCount)
            if (liquidationEvents.isNotEmpty()) {
                put("liquidation_events", JsonArray(liquidationEvents))
            }
        }
    }

    // ── Exposure summary ────────────────────────────────────────────────

    suspend fun getExposureSummary(): JsonObject {
        val cash = getCash()
        val allPositions = getOpenPositions()

        var longNotional = 0.0
        var shortNotional = 0.0
        var lockedCollateral = 0.0
        var longCount = 0
        var shortCount = 0

        for (pos in allPositions) {
            val price = getPriceFn(pos.symbol) ?: pos.entryPrice
            if (pos.side == "long") {
                longNotional += price * pos.quantity
                longCount++
            } else {
                shortNotional += price * pos.quantity
                lockedCollateral += pos.collateral
                shortCount++
            }
        }

        val totalValue = cash + longNotional
        val netExposure = longNotional - shortNotional
        val grossExposure = longNotional + shortNotional
        val netDeltaPct = if (totalValue > 0) netExposure / totalValue * 100 else 0.0

        return buildJsonObject {
            put("cash_available", cash)
            put("locked_collateral", lockedCollateral)
            put("long_notional", longNotional)
            put("short_notional", shortNotional)
            put("net_exposure", netExposure)
            put("gross_exposure", grossExposure)
            put("net_delta_pct", netDeltaPct)
            put("total_portfolio_value", totalValue)
            put("long_positions", longCount)
            put("short_positions", shortCount)
        }
    }

    // ── Trade History ───────────────────────────────────────────────────

    fun getTradeHistory(symbol: String? = null, limit: Int = 50): JsonObject {
        val query = if (symbol != null) {
            "SELECT * FROM trades WHERE symbol=? ORDER BY executed_at DESC LIMIT ?"
        } else {
            "SELECT * FROM trades ORDER BY executed_at DESC LIMIT ?"
        }
        val args = if (symbol != null) arrayOf(symbol.uppercase(), limit.toString()) else arrayOf(limit.toString())
        val cursor = db.rawQuery(query, args)

        val trades = mutableListOf<JsonObject>()
        while (cursor.moveToNext()) {
            trades.add(buildJsonObject {
                put("id", cursor.getInt(cursor.getColumnIndexOrThrow("id")))
                put("symbol", cursor.getString(cursor.getColumnIndexOrThrow("symbol")))
                put("action", cursor.getString(cursor.getColumnIndexOrThrow("action")))
                put("quantity", cursor.getDouble(cursor.getColumnIndexOrThrow("quantity")))
                put("price", cursor.getDouble(cursor.getColumnIndexOrThrow("price")))
                put("fee", cursor.getDouble(cursor.getColumnIndexOrThrow("fee")))
                put("net_amount", cursor.getDouble(cursor.getColumnIndexOrThrow("net_amount")))
                put("executed_at", cursor.getString(cursor.getColumnIndexOrThrow("executed_at")))
            })
        }
        cursor.close()

        return buildJsonObject {
            put("trades", JsonArray(trades))
            put("count", trades.size)
        }
    }

    // ── Closed Positions ────────────────────────────────────────────────

    fun getClosedPositions(limit: Int = 50): JsonObject {
        val cursor = db.rawQuery(
            "SELECT * FROM positions WHERE status='closed' ORDER BY closed_at DESC LIMIT ?",
            arrayOf(limit.toString())
        )
        val positions = mutableListOf<JsonObject>()
        while (cursor.moveToNext()) {
            val side = cursor.getString(cursor.getColumnIndexOrThrow("side"))
            positions.add(buildJsonObject {
                put("symbol", cursor.getString(cursor.getColumnIndexOrThrow("symbol")))
                put("side", side)
                put("entry_price", cursor.getDouble(cursor.getColumnIndexOrThrow("entry_price")))
                put("exit_price", cursor.getDouble(cursor.getColumnIndexOrThrow("exit_price")))
                put("realized_pnl", cursor.getDouble(cursor.getColumnIndexOrThrow("realized_pnl")))
                put("opened_at", cursor.getString(cursor.getColumnIndexOrThrow("opened_at")))
                put("closed_at", cursor.getString(cursor.getColumnIndexOrThrow("closed_at")))
                if (side == "short") {
                    put("cumulative_funding", cursor.getDouble(cursor.getColumnIndexOrThrow("cumulative_funding")))
                    put("collateral", cursor.getDouble(cursor.getColumnIndexOrThrow("collateral")))
                }
            })
        }
        cursor.close()

        return buildJsonObject {
            put("closed_positions", JsonArray(positions))
            put("count", positions.size)
        }
    }

    // ── Paper Yield Farming ─────────────────────────────────────────────

    /**
     * Simulate depositing stablecoins into a DeFi yield farm.
     * Cash pool is shared with paper trading: deduct principal + gas.
     */
    fun farmDeposit(
        poolId: String,
        protocol: String,
        chain: String,
        symbol: String,
        amountUsd: Double,
        apy: Double
    ): JsonObject {
        if (amountUsd < FARM_MIN_DEPOSIT) {
            return errorResult("Minimum deposit is \$${FARM_MIN_DEPOSIT.toInt()}")
        }
        val cash = getCash()
        val totalCost = amountUsd + FARM_GAS_FEE
        if (cash < totalCost) {
            return errorResult(
                "Insufficient cash. Available: \$${"%.2f".format(cash)}, " +
                "needed: \$${"%.2f".format(totalCost)} (including \$${FARM_GAS_FEE} gas)"
            )
        }

        val nowSec = Instant.now().epochSecond
        val cv = ContentValues().apply {
            put("pool_id", poolId)
            put("protocol", protocol)
            put("chain", chain)
            put("symbol", symbol.uppercase())
            put("principal", amountUsd)
            put("apy_at_deposit", apy)
            put("current_apy", apy)
            put("deposit_timestamp", nowSec)
            put("gas_paid", FARM_GAS_FEE)
            put("last_apy_update_timestamp", nowSec)
            put("status", "open")
        }
        db.insert("farms", null, cv)
        db.execSQL("UPDATE portfolio SET cash = cash - ?", arrayOf(totalCost))

        return buildJsonObject {
            put("status", "success")
            put("pool_id", poolId)
            put("protocol", protocol)
            put("chain", chain)
            put("symbol", symbol.uppercase())
            put("deposited", amountUsd)
            put("gas_fee", FARM_GAS_FEE)
            put("total_cost", totalCost)
            put("apy_at_deposit", apy)
            put("remaining_cash", getCash())
        }
    }

    /**
     * Withdraw from a farm position. If [withdrawAll] is true, closes every open farm.
     * Otherwise withdraws [amountUsd] from pool [poolId], or the full position if amountUsd is null.
     */
    fun farmWithdraw(
        poolId: String?,
        amountUsd: Double? = null,
        withdrawAll: Boolean = false
    ): JsonObject {
        if (withdrawAll) {
            val positions = getOpenFarmPositions()
            if (positions.isEmpty()) return errorResult("No open farm positions")
            val results = positions.map { withdrawFarmRow(it, amountUsd = null) }
            val totalPrincipal = results.sumOf { it["principal_returned"]?.jsonPrimitive?.doubleOrNull ?: 0.0 }
            val totalYield = results.sumOf { it["yield_earned"]?.jsonPrimitive?.doubleOrNull ?: 0.0 }
            val totalGas = results.sumOf { it["gas_fee"]?.jsonPrimitive?.doubleOrNull ?: 0.0 }
            return buildJsonObject {
                put("status", "success")
                put("withdrawals", JsonArray(results))
                put("total_principal_returned", totalPrincipal)
                put("total_yield_earned", totalYield)
                put("total_gas_fees", totalGas)
                put("remaining_cash", getCash())
            }
        }

        if (poolId == null) return errorResult("pool_id required when withdraw_all is false")
        val position = getOpenFarmByPoolId(poolId)
            ?: return errorResult("No open farm position for pool_id $poolId")
        return withdrawFarmRow(position, amountUsd)
    }

    private fun withdrawFarmRow(pos: FarmPosition, amountUsd: Double?): JsonObject {
        val nowSec = Instant.now().epochSecond
        val elapsedDays = (nowSec - pos.depositTimestamp) / 86400.0
        val effectiveApy = if (pos.currentApy > 0) pos.currentApy else pos.apyAtDeposit

        return if (amountUsd == null || amountUsd >= pos.principal) {
            // Full withdrawal
            val yieldEarned = pos.principal * (effectiveApy / 100.0 / 365.0) * elapsedDays
            val totalReturned = pos.principal + yieldEarned - FARM_GAS_FEE
            db.execSQL("UPDATE farms SET status='closed' WHERE id=?", arrayOf(pos.id))
            db.execSQL("UPDATE portfolio SET cash = cash + ?", arrayOf(totalReturned))
            buildJsonObject {
                put("status", "success")
                put("pool_id", pos.poolId)
                put("protocol", pos.protocol)
                put("principal_returned", pos.principal)
                put("yield_earned", yieldEarned)
                put("gas_fee", FARM_GAS_FEE)
                put("total_returned", totalReturned)
                put("days_farmed", elapsedDays)
                put("effective_apy", effectiveApy)
            }
        } else {
            // Partial withdrawal — proportional yield
            val fraction = amountUsd / pos.principal
            val yieldEarned = amountUsd * (effectiveApy / 100.0 / 365.0) * elapsedDays
            val totalReturned = amountUsd + yieldEarned - FARM_GAS_FEE
            val remainingPrincipal = pos.principal - amountUsd
            db.execSQL(
                "UPDATE farms SET principal=? WHERE id=?",
                arrayOf<Any>(remainingPrincipal, pos.id)
            )
            db.execSQL("UPDATE portfolio SET cash = cash + ?", arrayOf(totalReturned))
            buildJsonObject {
                put("status", "success")
                put("pool_id", pos.poolId)
                put("protocol", pos.protocol)
                put("principal_returned", amountUsd)
                put("yield_earned", yieldEarned)
                put("gas_fee", FARM_GAS_FEE)
                put("total_returned", totalReturned)
                put("days_farmed", elapsedDays)
                put("effective_apy", effectiveApy)
                put("remaining_principal", remainingPrincipal)
                put("withdrawn_fraction", fraction)
            }
        }
    }

    /**
     * Optionally refresh current APYs before reporting. Caller passes a lookup function
     * (typically bridge.defiLlama.getPoolApy). If null, uses stored APYs.
     */
    suspend fun farmPortfolio(apyLookup: (suspend (String) -> Double?)? = null): JsonObject {
        val positions = getOpenFarmPositions()
        val nowSec = Instant.now().epochSecond

        if (apyLookup != null) {
            for (pos in positions) {
                // Refresh at most once per hour per position
                if (nowSec - pos.lastApyUpdateTimestamp < 3600) continue
                try {
                    val fresh = apyLookup(pos.poolId)
                    if (fresh != null) {
                        db.execSQL(
                            "UPDATE farms SET current_apy=?, last_apy_update_timestamp=? WHERE id=?",
                            arrayOf<Any>(fresh, nowSec, pos.id)
                        )
                        pos.currentApy = fresh
                        pos.lastApyUpdateTimestamp = nowSec
                    }
                } catch (_: Exception) {
                    // Ignore refresh failures — fall back to stored APY
                }
            }
        }

        val list = JsonArray(positions.map { pos ->
            val elapsedDays = (nowSec - pos.depositTimestamp) / 86400.0
            val effectiveApy = if (pos.currentApy > 0) pos.currentApy else pos.apyAtDeposit
            val accrued = pos.principal * (effectiveApy / 100.0 / 365.0) * elapsedDays
            buildJsonObject {
                put("pool_id", pos.poolId)
                put("protocol", pos.protocol)
                put("chain", pos.chain)
                put("symbol", pos.symbol)
                put("principal", pos.principal)
                put("current_apy", pos.currentApy)
                put("apy_at_deposit", pos.apyAtDeposit)
                put("accrued_yield", accrued)
                put("current_value", pos.principal + accrued)
                put("deposit_date", Instant.ofEpochSecond(pos.depositTimestamp).toString())
                put("days_farmed", elapsedDays)
                put("gas_paid", pos.gasPaid)
            }
        })

        var totalDeposited = 0.0
        var totalValue = 0.0
        var totalGas = 0.0
        var weightedApyNumerator = 0.0
        for (entry in list) {
            val o = entry.jsonObject
            val principal = o["principal"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val current = o["current_value"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val gas = o["gas_paid"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val apy = o["current_apy"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            totalDeposited += principal
            totalValue += current
            totalGas += gas
            weightedApyNumerator += apy * principal
        }
        val totalYield = totalValue - totalDeposited
        val netYield = totalYield - totalGas
        val weightedApy = if (totalDeposited > 0) weightedApyNumerator / totalDeposited else 0.0

        return buildJsonObject {
            put("positions", list)
            put("summary", buildJsonObject {
                put("total_deposited", totalDeposited)
                put("total_current_value", totalValue)
                put("total_yield_earned", totalYield)
                put("total_gas_costs", totalGas)
                put("net_yield", netYield)
                put("weighted_avg_apy", weightedApy)
                put("position_count", list.size)
            })
        }
    }

    /** Sum of principal + accrued yield across all open farm positions. */
    private fun computeFarmTotalValue(): Double {
        val positions = getOpenFarmPositions()
        val nowSec = Instant.now().epochSecond
        var total = 0.0
        for (pos in positions) {
            val elapsedDays = (nowSec - pos.depositTimestamp) / 86400.0
            val effectiveApy = if (pos.currentApy > 0) pos.currentApy else pos.apyAtDeposit
            val accrued = pos.principal * (effectiveApy / 100.0 / 365.0) * elapsedDays
            total += pos.principal + accrued
        }
        return total
    }

    private data class FarmPosition(
        val id: Int,
        val poolId: String,
        val protocol: String,
        val chain: String,
        val symbol: String,
        var principal: Double,
        val apyAtDeposit: Double,
        var currentApy: Double,
        val depositTimestamp: Long,
        val gasPaid: Double,
        var lastApyUpdateTimestamp: Long
    )

    private fun readFarm(cursor: android.database.Cursor): FarmPosition {
        fun col(name: String) = cursor.getColumnIndexOrThrow(name)
        return FarmPosition(
            id = cursor.getInt(col("id")),
            poolId = cursor.getString(col("pool_id")),
            protocol = cursor.getString(col("protocol")),
            chain = cursor.getString(col("chain")),
            symbol = cursor.getString(col("symbol")),
            principal = cursor.getDouble(col("principal")),
            apyAtDeposit = cursor.getDouble(col("apy_at_deposit")),
            currentApy = cursor.getDouble(col("current_apy")),
            depositTimestamp = cursor.getLong(col("deposit_timestamp")),
            gasPaid = cursor.getDouble(col("gas_paid")),
            lastApyUpdateTimestamp = cursor.getLong(col("last_apy_update_timestamp")),
        )
    }

    private fun getOpenFarmPositions(): List<FarmPosition> {
        val cursor = db.rawQuery("SELECT * FROM farms WHERE status='open'", null)
        val list = mutableListOf<FarmPosition>()
        while (cursor.moveToNext()) list.add(readFarm(cursor))
        cursor.close()
        return list
    }

    private fun getOpenFarmByPoolId(poolId: String): FarmPosition? {
        val cursor = db.rawQuery(
            "SELECT * FROM farms WHERE pool_id=? AND status='open' LIMIT 1",
            arrayOf(poolId)
        )
        val pos = if (cursor.moveToFirst()) readFarm(cursor) else null
        cursor.close()
        return pos
    }

    // ── Reset ───────────────────────────────────────────────────────────

    fun reset(capital: Double = defaultCapital): JsonObject {
        db.execSQL("DELETE FROM trades")
        db.execSQL("DELETE FROM positions")
        db.execSQL("DELETE FROM farms")
        db.execSQL("DELETE FROM portfolio")
        val cv = ContentValues().apply {
            put("id", 1); put("cash", capital)
            put("starting_capital", capital); put("fee_pct", feePct)
            put("created_at", Instant.now().toString())
        }
        db.insert("portfolio", null, cv)
        return buildJsonObject {
            put("status", "success")
            put("message", "Portfolio reset to $${"%.2f".format(capital)}")
            put("cash", capital)
        }
    }

    // ── Internal helpers ────────────────────────────────────────────────

    private fun getCash(): Double {
        val cursor = db.rawQuery("SELECT cash FROM portfolio WHERE id=1", null)
        cursor.moveToFirst(); val cash = cursor.getDouble(0); cursor.close(); return cash
    }

    private fun getStartingCapital(): Double {
        val cursor = db.rawQuery("SELECT starting_capital FROM portfolio WHERE id=1", null)
        cursor.moveToFirst(); val cap = cursor.getDouble(0); cursor.close(); return cap
    }

    private fun ensurePortfolio() {
        val cursor = db.rawQuery("SELECT COUNT(*) FROM portfolio", null)
        cursor.moveToFirst()
        if (cursor.getInt(0) == 0) reset()
        cursor.close()
    }

    private fun getTotalShortNotional(): Double {
        val cursor = db.rawQuery(
            "SELECT COALESCE(SUM(entry_price * quantity), 0) FROM positions WHERE status='open' AND side='short'", null
        )
        cursor.moveToFirst(); val v = cursor.getDouble(0); cursor.close(); return v
    }

    private fun getTotalLongValue(): Double {
        val cursor = db.rawQuery(
            "SELECT COALESCE(SUM(entry_price * quantity), 0) FROM positions WHERE status='open' AND side='long'", null
        )
        cursor.moveToFirst(); val v = cursor.getDouble(0); cursor.close(); return v
    }

    private data class Position(
        val id: Int, val symbol: String, val side: String,
        val quantity: Double, val entryPrice: Double, val buyFees: Double,
        val cumulativeRealizedPnl: Double, val openedAt: String,
        val leverage: Double = 1.0, val collateral: Double = 0.0,
        val liquidationPrice: Double = 0.0, val cumulativeFunding: Double = 0.0,
    )

    private fun getOpenPosition(symbol: String, side: String): Position? {
        val cursor = db.rawQuery(
            "SELECT * FROM positions WHERE symbol=? AND status='open' AND side=? LIMIT 1",
            arrayOf(symbol, side)
        )
        val pos = if (cursor.moveToFirst()) readPosition(cursor) else null
        cursor.close()
        return pos
    }

    private fun getOpenPositions(): List<Position> {
        val cursor = db.rawQuery("SELECT * FROM positions WHERE status='open'", null)
        val list = mutableListOf<Position>()
        while (cursor.moveToNext()) list.add(readPosition(cursor))
        cursor.close()
        return list
    }

    private fun readPosition(cursor: android.database.Cursor): Position {
        fun col(name: String) = cursor.getColumnIndexOrThrow(name)
        fun doubleOrDefault(name: String, default: Double = 0.0): Double {
            val idx = cursor.getColumnIndex(name)
            return if (idx >= 0 && !cursor.isNull(idx)) cursor.getDouble(idx) else default
        }
        fun stringOrDefault(name: String, default: String = ""): String {
            val idx = cursor.getColumnIndex(name)
            return if (idx >= 0 && !cursor.isNull(idx)) cursor.getString(idx) else default
        }

        return Position(
            id = cursor.getInt(col("id")),
            symbol = cursor.getString(col("symbol")),
            side = stringOrDefault("side", "long"),
            quantity = cursor.getDouble(col("quantity")),
            entryPrice = cursor.getDouble(col("entry_price")),
            buyFees = cursor.getDouble(col("buy_fees")),
            cumulativeRealizedPnl = cursor.getDouble(col("cumulative_realized_pnl")),
            openedAt = cursor.getString(col("opened_at")),
            leverage = doubleOrDefault("leverage", 1.0),
            collateral = doubleOrDefault("collateral", 0.0),
            liquidationPrice = doubleOrDefault("liquidation_price", 0.0),
            cumulativeFunding = doubleOrDefault("cumulative_funding", 0.0),
        )
    }

    private fun errorResult(message: String) = buildJsonObject {
        put("status", "error"); put("message", message)
    }
}

// ── SQLite Helper ───────────────────────────────────────────────────

private class PaperDbHelper(context: Context) : SQLiteOpenHelper(context, "paper_portfolio.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE portfolio (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                cash REAL NOT NULL,
                starting_capital REAL NOT NULL,
                fee_pct REAL NOT NULL,
                created_at TEXT NOT NULL
            )
        """)
        db.execSQL("""
            CREATE TABLE positions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                symbol TEXT NOT NULL,
                side TEXT NOT NULL DEFAULT 'long',
                quantity REAL NOT NULL,
                entry_price REAL NOT NULL,
                buy_fees REAL NOT NULL DEFAULT 0,
                opened_at TEXT NOT NULL,
                closed_at TEXT,
                exit_price REAL,
                realized_pnl REAL,
                cumulative_realized_pnl REAL NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'open',
                leverage REAL NOT NULL DEFAULT 1.0,
                collateral REAL NOT NULL DEFAULT 0,
                liquidation_price REAL,
                cumulative_funding REAL NOT NULL DEFAULT 0,
                borrow_rate_annual REAL NOT NULL DEFAULT 0.10,
                last_funding_at TEXT
            )
        """)
        db.execSQL("""
            CREATE TABLE trades (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                symbol TEXT NOT NULL,
                action TEXT NOT NULL,
                quantity REAL NOT NULL,
                price REAL NOT NULL,
                fee REAL NOT NULL,
                net_amount REAL NOT NULL,
                executed_at TEXT NOT NULL
            )
        """)
        db.execSQL("CREATE INDEX idx_positions_status ON positions(status)")
        db.execSQL("CREATE INDEX idx_positions_symbol ON positions(symbol)")

        db.execSQL("""
            CREATE TABLE farms (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                pool_id TEXT NOT NULL,
                protocol TEXT NOT NULL,
                chain TEXT NOT NULL,
                symbol TEXT NOT NULL,
                principal REAL NOT NULL,
                apy_at_deposit REAL NOT NULL,
                current_apy REAL NOT NULL,
                deposit_timestamp INTEGER NOT NULL,
                gas_paid REAL NOT NULL DEFAULT 3.0,
                last_apy_update_timestamp INTEGER NOT NULL,
                status TEXT NOT NULL DEFAULT 'open'
            )
        """)
        db.execSQL("CREATE INDEX idx_farms_status ON farms(status)")
        db.execSQL("CREATE INDEX idx_farms_pool_id ON farms(pool_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Add short-trading columns
            val cols = mutableSetOf<String>()
            val cursor = db.rawQuery("PRAGMA table_info(positions)", null)
            while (cursor.moveToNext()) cols.add(cursor.getString(1))
            cursor.close()

            val migrations = listOf(
                "leverage" to "REAL NOT NULL DEFAULT 1.0",
                "collateral" to "REAL NOT NULL DEFAULT 0",
                "liquidation_price" to "REAL",
                "cumulative_funding" to "REAL NOT NULL DEFAULT 0",
                "borrow_rate_annual" to "REAL NOT NULL DEFAULT 0.10",
                "last_funding_at" to "TEXT",
            )
            for ((name, def) in migrations) {
                if (name !in cols) db.execSQL("ALTER TABLE positions ADD COLUMN $name $def")
            }
        }
        if (oldVersion < 3) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS farms (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    pool_id TEXT NOT NULL,
                    protocol TEXT NOT NULL,
                    chain TEXT NOT NULL,
                    symbol TEXT NOT NULL,
                    principal REAL NOT NULL,
                    apy_at_deposit REAL NOT NULL,
                    current_apy REAL NOT NULL,
                    deposit_timestamp INTEGER NOT NULL,
                    gas_paid REAL NOT NULL DEFAULT 3.0,
                    last_apy_update_timestamp INTEGER NOT NULL,
                    status TEXT NOT NULL DEFAULT 'open'
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_farms_status ON farms(status)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_farms_pool_id ON farms(pool_id)")
        }
    }
}
