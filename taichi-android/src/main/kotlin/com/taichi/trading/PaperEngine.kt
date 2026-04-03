package com.taichi.trading

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import kotlinx.serialization.json.*
import java.time.Instant

/**
 * Paper trading engine with SQLite persistence.
 * Ports Python's paper_engine.py — buy/sell with 0.1% fee model,
 * position averaging, partial closes, PnL tracking.
 */
class PaperEngine(
    context: Context,
    private val defaultCapital: Double = 10_000.0,
    private val feePct: Double = 0.001,
    private val getPriceFn: suspend (String) -> Double?
) {
    private val db: SQLiteDatabase

    init {
        val helper = PaperDbHelper(context)
        db = helper.writableDatabase
        ensurePortfolio()
    }

    // --- Buy ---

    suspend fun buy(symbol: String, amountUsd: Double? = null, quantity: Double? = null): JsonObject {
        val price = getPriceFn(symbol.uppercase())
            ?: return errorResult("Could not fetch price for $symbol")

        if (price <= 0) return errorResult("Invalid price: $price")

        val cash = getCash()

        val qty: Double
        val totalCost: Double
        val fee: Double

        if (amountUsd != null) {
            if (amountUsd > cash) return errorResult("Insufficient cash: $${"%.2f".format(cash)} available, $${"%.2f".format(amountUsd)} requested")
            fee = amountUsd * feePct
            qty = (amountUsd - fee) / price
            totalCost = amountUsd
        } else if (quantity != null) {
            totalCost = quantity * price
            fee = totalCost * feePct
            if (totalCost + fee > cash) return errorResult("Insufficient cash: $${"%.2f".format(cash)} available, $${"%.2f".format(totalCost + fee)} needed")
            qty = quantity
        } else {
            return errorResult("Specify amount_usd or quantity")
        }

        val sym = symbol.uppercase()
        val now = Instant.now().toString()

        // Check for existing open position to average into
        val existing = getOpenPosition(sym)
        if (existing != null) {
            val newQty = existing.quantity + qty
            val newCost = (existing.entryPrice * existing.quantity + price * qty) / newQty
            val newFees = existing.buyFees + fee

            db.execSQL(
                "UPDATE positions SET quantity=?, entry_price=?, buy_fees=? WHERE id=?",
                arrayOf(newQty, newCost, newFees, existing.id)
            )
        } else {
            val cv = ContentValues().apply {
                put("symbol", sym)
                put("side", "long")
                put("quantity", qty)
                put("entry_price", price)
                put("buy_fees", fee)
                put("opened_at", now)
                put("status", "open")
                put("cumulative_realized_pnl", 0.0)
            }
            db.insert("positions", null, cv)
        }

        // Record trade
        val tradeCv = ContentValues().apply {
            put("symbol", sym)
            put("action", "buy")
            put("quantity", qty)
            put("price", price)
            put("fee", fee)
            put("net_amount", -totalCost)
            put("executed_at", now)
        }
        db.insert("trades", null, tradeCv)

        // Deduct cash
        db.execSQL("UPDATE portfolio SET cash = cash - ?", arrayOf(totalCost))

        return buildJsonObject {
            put("status", "success")
            put("action", "buy")
            put("symbol", sym)
            put("quantity", qty)
            put("price", price)
            put("fee", fee)
            put("total_cost", totalCost)
            put("remaining_cash", getCash())
        }
    }

    // --- Sell ---

    suspend fun sell(symbol: String, quantity: Double? = null, closeAll: Boolean = false): JsonObject {
        val sym = symbol.uppercase()
        val position = getOpenPosition(sym) ?: return errorResult("No open position for $sym")

        val price = getPriceFn(sym) ?: return errorResult("Could not fetch price for $sym")
        val sellQty = if (closeAll || quantity == null) position.quantity else minOf(quantity, position.quantity)

        val grossProceeds = sellQty * price
        val fee = grossProceeds * feePct
        val netProceeds = grossProceeds - fee

        val costBasis = position.entryPrice * sellQty
        val proportionalBuyFees = position.buyFees * (sellQty / position.quantity)
        val realizedPnl = netProceeds - costBasis

        val now = Instant.now().toString()

        if (sellQty >= position.quantity) {
            // Full close
            db.execSQL(
                "UPDATE positions SET status='closed', closed_at=?, exit_price=?, realized_pnl=?, quantity=0 WHERE id=?",
                arrayOf(now, price, position.cumulativeRealizedPnl + realizedPnl, position.id)
            )
        } else {
            // Partial close — reduce quantity and buy fees proportionally
            val remainingQty = position.quantity - sellQty
            val remainingFees = position.buyFees - proportionalBuyFees
            db.execSQL(
                "UPDATE positions SET quantity=?, buy_fees=?, cumulative_realized_pnl=? WHERE id=?",
                arrayOf(remainingQty, remainingFees, position.cumulativeRealizedPnl + realizedPnl, position.id)
            )
        }

        // Record trade
        val tradeCv = ContentValues().apply {
            put("symbol", sym)
            put("action", "sell")
            put("quantity", sellQty)
            put("price", price)
            put("fee", fee)
            put("net_amount", netProceeds)
            put("executed_at", now)
        }
        db.insert("trades", null, tradeCv)

        // Add cash
        db.execSQL("UPDATE portfolio SET cash = cash + ?", arrayOf(netProceeds))

        return buildJsonObject {
            put("status", "success")
            put("action", "sell")
            put("symbol", sym)
            put("quantity", sellQty)
            put("price", price)
            put("fee", fee)
            put("gross_proceeds", grossProceeds)
            put("net_proceeds", netProceeds)
            put("realized_pnl", realizedPnl)
            put("remaining_cash", getCash())
        }
    }

    // --- Portfolio ---

    suspend fun getPortfolio(): JsonObject {
        val cash = getCash()
        val startingCapital = getStartingCapital()
        val positions = getOpenPositions()

        var positionsValue = 0.0
        val positionList = mutableListOf<JsonObject>()

        for (pos in positions) {
            val currentPrice = getPriceFn(pos.symbol) ?: pos.entryPrice
            val marketValue = currentPrice * pos.quantity
            val unrealizedPnl = marketValue - (pos.entryPrice * pos.quantity)
            val unrealizedPnlPct = if (pos.entryPrice > 0) unrealizedPnl / (pos.entryPrice * pos.quantity) * 100 else 0.0
            positionsValue += marketValue

            positionList.add(buildJsonObject {
                put("symbol", pos.symbol)
                put("quantity", pos.quantity)
                put("entry_price", pos.entryPrice)
                put("current_price", currentPrice)
                put("market_value", marketValue)
                put("unrealized_pnl", unrealizedPnl)
                put("unrealized_pnl_pct", unrealizedPnlPct)
                put("buy_fees", pos.buyFees)
                put("partial_realized_pnl", pos.cumulativeRealizedPnl)
                put("opened_at", pos.openedAt)
            })
        }

        val totalValue = cash + positionsValue
        val totalPnl = totalValue - startingCapital

        // Win rate from closed trades
        val closedCursor = db.rawQuery(
            "SELECT realized_pnl FROM positions WHERE status='closed' AND realized_pnl IS NOT NULL", null
        )
        var wins = 0; var closedCount = 0
        while (closedCursor.moveToNext()) {
            closedCount++
            if (closedCursor.getDouble(0) > 0) wins++
        }
        closedCursor.close()

        // Total fees
        val feeCursor = db.rawQuery("SELECT COALESCE(SUM(fee), 0) FROM trades", null)
        feeCursor.moveToFirst()
        val totalFees = feeCursor.getDouble(0)
        feeCursor.close()

        // Total trades
        val tradeCursor = db.rawQuery("SELECT COUNT(*) FROM trades", null)
        tradeCursor.moveToFirst()
        val totalTrades = tradeCursor.getInt(0)
        tradeCursor.close()

        return buildJsonObject {
            put("cash", cash)
            put("positions_value", positionsValue)
            put("total_value", totalValue)
            put("total_pnl", totalPnl)
            put("total_pnl_pct", if (startingCapital > 0) totalPnl / startingCapital * 100 else 0.0)
            put("starting_capital", startingCapital)
            put("open_positions", JsonArray(positionList))
            put("total_trades", totalTrades)
            put("total_fees", totalFees)
            put("win_rate", if (closedCount > 0) wins.toDouble() / closedCount * 100 else null)
            put("closed_trades", closedCount)
        }
    }

    // --- Trade History ---

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

    // --- Closed Positions ---

    fun getClosedPositions(limit: Int = 50): JsonObject {
        val cursor = db.rawQuery(
            "SELECT * FROM positions WHERE status='closed' ORDER BY closed_at DESC LIMIT ?",
            arrayOf(limit.toString())
        )
        val positions = mutableListOf<JsonObject>()
        while (cursor.moveToNext()) {
            positions.add(buildJsonObject {
                put("symbol", cursor.getString(cursor.getColumnIndexOrThrow("symbol")))
                put("entry_price", cursor.getDouble(cursor.getColumnIndexOrThrow("entry_price")))
                put("exit_price", cursor.getDouble(cursor.getColumnIndexOrThrow("exit_price")))
                put("realized_pnl", cursor.getDouble(cursor.getColumnIndexOrThrow("realized_pnl")))
                put("opened_at", cursor.getString(cursor.getColumnIndexOrThrow("opened_at")))
                put("closed_at", cursor.getString(cursor.getColumnIndexOrThrow("closed_at")))
            })
        }
        cursor.close()

        return buildJsonObject {
            put("closed_positions", JsonArray(positions))
            put("count", positions.size)
        }
    }

    // --- Reset ---

    fun reset(capital: Double = defaultCapital): JsonObject {
        db.execSQL("DELETE FROM trades")
        db.execSQL("DELETE FROM positions")
        db.execSQL("DELETE FROM portfolio")
        val cv = ContentValues().apply {
            put("id", 1)
            put("cash", capital)
            put("starting_capital", capital)
            put("fee_pct", feePct)
            put("created_at", Instant.now().toString())
        }
        db.insert("portfolio", null, cv)

        return buildJsonObject {
            put("status", "success")
            put("message", "Portfolio reset to $${"%.2f".format(capital)}")
            put("cash", capital)
        }
    }

    // --- Internal helpers ---

    private fun getCash(): Double {
        val cursor = db.rawQuery("SELECT cash FROM portfolio WHERE id=1", null)
        cursor.moveToFirst()
        val cash = cursor.getDouble(0)
        cursor.close()
        return cash
    }

    private fun getStartingCapital(): Double {
        val cursor = db.rawQuery("SELECT starting_capital FROM portfolio WHERE id=1", null)
        cursor.moveToFirst()
        val cap = cursor.getDouble(0)
        cursor.close()
        return cap
    }

    private fun ensurePortfolio() {
        val cursor = db.rawQuery("SELECT COUNT(*) FROM portfolio", null)
        cursor.moveToFirst()
        if (cursor.getInt(0) == 0) {
            reset()
        }
        cursor.close()
    }

    private data class Position(
        val id: Int, val symbol: String, val quantity: Double,
        val entryPrice: Double, val buyFees: Double,
        val cumulativeRealizedPnl: Double, val openedAt: String
    )

    private fun getOpenPosition(symbol: String): Position? {
        val cursor = db.rawQuery(
            "SELECT * FROM positions WHERE symbol=? AND status='open' LIMIT 1",
            arrayOf(symbol)
        )
        val pos = if (cursor.moveToFirst()) Position(
            id = cursor.getInt(cursor.getColumnIndexOrThrow("id")),
            symbol = cursor.getString(cursor.getColumnIndexOrThrow("symbol")),
            quantity = cursor.getDouble(cursor.getColumnIndexOrThrow("quantity")),
            entryPrice = cursor.getDouble(cursor.getColumnIndexOrThrow("entry_price")),
            buyFees = cursor.getDouble(cursor.getColumnIndexOrThrow("buy_fees")),
            cumulativeRealizedPnl = cursor.getDouble(cursor.getColumnIndexOrThrow("cumulative_realized_pnl")),
            openedAt = cursor.getString(cursor.getColumnIndexOrThrow("opened_at"))
        ) else null
        cursor.close()
        return pos
    }

    private fun getOpenPositions(): List<Position> {
        val cursor = db.rawQuery("SELECT * FROM positions WHERE status='open'", null)
        val list = mutableListOf<Position>()
        while (cursor.moveToNext()) {
            list.add(Position(
                id = cursor.getInt(cursor.getColumnIndexOrThrow("id")),
                symbol = cursor.getString(cursor.getColumnIndexOrThrow("symbol")),
                quantity = cursor.getDouble(cursor.getColumnIndexOrThrow("quantity")),
                entryPrice = cursor.getDouble(cursor.getColumnIndexOrThrow("entry_price")),
                buyFees = cursor.getDouble(cursor.getColumnIndexOrThrow("buy_fees")),
                cumulativeRealizedPnl = cursor.getDouble(cursor.getColumnIndexOrThrow("cumulative_realized_pnl")),
                openedAt = cursor.getString(cursor.getColumnIndexOrThrow("opened_at"))
            ))
        }
        cursor.close()
        return list
    }

    private fun errorResult(message: String) = buildJsonObject {
        put("status", "error")
        put("message", message)
    }
}

// --- SQLite Helper ---

private class PaperDbHelper(context: Context) : SQLiteOpenHelper(context, "paper_portfolio.db", null, 1) {
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
                status TEXT NOT NULL DEFAULT 'open'
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
}
