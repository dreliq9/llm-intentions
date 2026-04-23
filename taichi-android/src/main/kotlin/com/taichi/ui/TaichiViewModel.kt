package com.taichi.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.taichi.config.ApiKeyStore
import com.taichi.scraper.ScraperBridge
import com.taichi.trading.PaperEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

data class LongPositionUi(
    val symbol: String,
    val quantity: Double,
    val entryPrice: Double,
    val currentPrice: Double,
    val marketValue: Double,
    val unrealizedPnl: Double,
    val unrealizedPnlPct: Double,
    val openedAt: String,
)

data class ShortPositionUi(
    val symbol: String,
    val quantity: Double,
    val entryPrice: Double,
    val currentPrice: Double,
    val notional: Double,
    val unrealizedPnl: Double,
    val unrealizedPnlPct: Double,
    val cumulativeFunding: Double,
    val collateralLocked: Double,
    val liquidationPrice: Double?,
    val liqDistancePct: Double?,
    val leverage: Double,
    val openedAt: String,
)

data class TradeUi(
    val id: Int,
    val symbol: String,
    val action: String,
    val quantity: Double,
    val price: Double,
    val fee: Double,
    val netAmount: Double,
    val executedAt: String,
)

data class PortfolioState(
    val cash: Double = 0.0,
    val longPositionsValue: Double = 0.0,
    val shortUnrealizedPnl: Double = 0.0,
    val totalValue: Double = 0.0,
    val totalPnl: Double = 0.0,
    val totalPnlPct: Double = 0.0,
    val startingCapital: Double = 10_000.0,
    val longPositions: List<LongPositionUi> = emptyList(),
    val shortPositions: List<ShortPositionUi> = emptyList(),
    val totalTrades: Int = 0,
    val totalFees: Double = 0.0,
    val winRate: Double? = null,
    val longWinRate: Double? = null,
    val shortWinRate: Double? = null,
    val closedTrades: Int = 0,
    val liquidationEvents: List<String> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

data class HistoryState(
    val trades: List<TradeUi> = emptyList(),
    val isLoading: Boolean = true,
)

data class SettingsState(
    val cryptoPanicToken: String = "",
    val redditClientId: String = "",
    val redditClientSecret: String = "",
    val coinGeckoApiKey: String = "",
    val keyStatus: Map<String, Boolean> = emptyMap(),
    val showResetDialog: Boolean = false,
    val saveMessage: String? = null,
)

class TaichiViewModel(application: Application) : AndroidViewModel(application) {

    private val keyStore = ApiKeyStore(application)
    private val bridge = ScraperBridge(
        cryptoPanicToken = keyStore.cryptoPanicToken,
        redditClientId = keyStore.redditClientId,
        redditClientSecret = keyStore.redditClientSecret,
        coinGeckoApiKey = keyStore.coinGeckoApiKey,
    )
    private val paperEngine = PaperEngine(application) { symbol ->
        bridge.binance.getPrice(symbol)
            ?: try {
                val search = bridge.dexScreener.searchPairs(symbol)
                search["pairs"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("priceUsd")?.jsonPrimitive?.doubleOrNull
            } catch (_: Exception) { null }
    }

    private val _portfolio = MutableStateFlow(PortfolioState())
    val portfolio: StateFlow<PortfolioState> = _portfolio

    private val _history = MutableStateFlow(HistoryState())
    val history: StateFlow<HistoryState> = _history

    private val _settings = MutableStateFlow(SettingsState())
    val settings: StateFlow<SettingsState> = _settings

    init {
        loadSettings()
        refreshPortfolio()
        refreshHistory()
        startAutoRefresh()
    }

    fun refreshPortfolio() {
        viewModelScope.launch {
            _portfolio.value = _portfolio.value.copy(isLoading = true, error = null)
            try {
                val json = paperEngine.getPortfolio()

                val longs = json["long_positions"]?.jsonArray?.map { elem ->
                    val obj = elem.jsonObject
                    LongPositionUi(
                        symbol = obj["symbol"]!!.jsonPrimitive.content,
                        quantity = obj["quantity"]!!.jsonPrimitive.double,
                        entryPrice = obj["entry_price"]!!.jsonPrimitive.double,
                        currentPrice = obj["current_price"]!!.jsonPrimitive.double,
                        marketValue = obj["market_value"]!!.jsonPrimitive.double,
                        unrealizedPnl = obj["unrealized_pnl"]!!.jsonPrimitive.double,
                        unrealizedPnlPct = obj["unrealized_pnl_pct"]!!.jsonPrimitive.double,
                        openedAt = obj["opened_at"]!!.jsonPrimitive.content,
                    )
                } ?: emptyList()

                val shorts = json["short_positions"]?.jsonArray?.map { elem ->
                    val obj = elem.jsonObject
                    ShortPositionUi(
                        symbol = obj["symbol"]!!.jsonPrimitive.content,
                        quantity = obj["quantity"]!!.jsonPrimitive.double,
                        entryPrice = obj["entry_price"]!!.jsonPrimitive.double,
                        currentPrice = obj["current_price"]!!.jsonPrimitive.double,
                        notional = obj["notional"]!!.jsonPrimitive.double,
                        unrealizedPnl = obj["unrealized_pnl"]!!.jsonPrimitive.double,
                        unrealizedPnlPct = obj["unrealized_pnl_pct"]!!.jsonPrimitive.double,
                        cumulativeFunding = obj["cumulative_funding"]!!.jsonPrimitive.double,
                        collateralLocked = obj["collateral_locked"]!!.jsonPrimitive.double,
                        liquidationPrice = obj["liquidation_price"]?.jsonPrimitive?.doubleOrNull,
                        liqDistancePct = obj["liq_distance_pct"]?.let {
                            if (it is JsonNull) null else it.jsonPrimitive.doubleOrNull
                        },
                        leverage = obj["leverage"]!!.jsonPrimitive.double,
                        openedAt = obj["opened_at"]!!.jsonPrimitive.content,
                    )
                } ?: emptyList()

                val liqEvents = json["liquidation_events"]?.jsonArray?.map { elem ->
                    val obj = elem.jsonObject
                    "${obj["symbol"]?.jsonPrimitive?.content} liquidated at $${obj["cover_price"]?.jsonPrimitive?.double?.let { "%.2f".format(it) }}"
                } ?: emptyList()

                _portfolio.value = PortfolioState(
                    cash = json["cash"]!!.jsonPrimitive.double,
                    longPositionsValue = json["long_positions_value"]!!.jsonPrimitive.double,
                    shortUnrealizedPnl = json["short_unrealized_pnl"]!!.jsonPrimitive.double,
                    totalValue = json["total_value"]!!.jsonPrimitive.double,
                    totalPnl = json["total_pnl"]!!.jsonPrimitive.double,
                    totalPnlPct = json["total_pnl_pct"]!!.jsonPrimitive.double,
                    startingCapital = json["starting_capital"]!!.jsonPrimitive.double,
                    longPositions = longs,
                    shortPositions = shorts,
                    totalTrades = json["total_trades"]!!.jsonPrimitive.int,
                    totalFees = json["total_fees"]!!.jsonPrimitive.double,
                    winRate = json["win_rate"]?.let {
                        if (it is JsonNull) null else it.jsonPrimitive.doubleOrNull
                    },
                    longWinRate = json["long_win_rate"]?.let {
                        if (it is JsonNull) null else it.jsonPrimitive.doubleOrNull
                    },
                    shortWinRate = json["short_win_rate"]?.let {
                        if (it is JsonNull) null else it.jsonPrimitive.doubleOrNull
                    },
                    closedTrades = json["closed_trades"]!!.jsonPrimitive.int,
                    liquidationEvents = liqEvents,
                    isLoading = false,
                )
            } catch (e: Exception) {
                _portfolio.value = _portfolio.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load portfolio",
                )
            }
        }
    }

    fun refreshHistory() {
        viewModelScope.launch {
            _history.value = _history.value.copy(isLoading = true)
            try {
                val json = paperEngine.getTradeHistory(limit = 100)
                val trades = json["trades"]!!.jsonArray.map { elem ->
                    val obj = elem.jsonObject
                    TradeUi(
                        id = obj["id"]!!.jsonPrimitive.int,
                        symbol = obj["symbol"]!!.jsonPrimitive.content,
                        action = obj["action"]!!.jsonPrimitive.content,
                        quantity = obj["quantity"]!!.jsonPrimitive.double,
                        price = obj["price"]!!.jsonPrimitive.double,
                        fee = obj["fee"]!!.jsonPrimitive.double,
                        netAmount = obj["net_amount"]!!.jsonPrimitive.double,
                        executedAt = obj["executed_at"]!!.jsonPrimitive.content,
                    )
                }
                _history.value = HistoryState(trades = trades, isLoading = false)
            } catch (_: Exception) {
                _history.value = HistoryState(isLoading = false)
            }
        }
    }

    private fun loadSettings() {
        _settings.value = SettingsState(
            cryptoPanicToken = keyStore.cryptoPanicToken ?: "",
            redditClientId = keyStore.redditClientId ?: "",
            redditClientSecret = keyStore.redditClientSecret ?: "",
            coinGeckoApiKey = keyStore.coinGeckoApiKey ?: "",
            keyStatus = keyStore.status(),
        )
    }

    fun updateCryptoPanicToken(value: String) { _settings.value = _settings.value.copy(cryptoPanicToken = value) }
    fun updateRedditClientId(value: String) { _settings.value = _settings.value.copy(redditClientId = value) }
    fun updateRedditClientSecret(value: String) { _settings.value = _settings.value.copy(redditClientSecret = value) }
    fun updateCoinGeckoApiKey(value: String) { _settings.value = _settings.value.copy(coinGeckoApiKey = value) }

    fun saveKeys() {
        val s = _settings.value
        keyStore.cryptoPanicToken = s.cryptoPanicToken.trim()
        keyStore.redditClientId = s.redditClientId.trim()
        keyStore.redditClientSecret = s.redditClientSecret.trim()
        keyStore.coinGeckoApiKey = s.coinGeckoApiKey.trim()
        _settings.value = _settings.value.copy(keyStatus = keyStore.status(), saveMessage = "Keys saved")
    }

    fun clearSaveMessage() { _settings.value = _settings.value.copy(saveMessage = null) }
    fun showResetDialog() { _settings.value = _settings.value.copy(showResetDialog = true) }
    fun dismissResetDialog() { _settings.value = _settings.value.copy(showResetDialog = false) }

    fun resetPortfolio() {
        paperEngine.reset()
        _settings.value = _settings.value.copy(showResetDialog = false)
        refreshPortfolio()
        refreshHistory()
    }

    private fun startAutoRefresh() {
        viewModelScope.launch {
            while (true) { delay(30_000); refreshPortfolio() }
        }
    }
}
