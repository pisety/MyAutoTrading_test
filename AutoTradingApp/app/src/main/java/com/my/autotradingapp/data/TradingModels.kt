package com.my.autotradingapp.data

import com.google.firebase.database.IgnoreExtraProperties
import com.google.firebase.database.PropertyName

enum class OrderStatus {
    PENDING, COMPLETED, CANCELLED, NONE;
    companion object {
        fun fromString(value: String): OrderStatus = 
            entries.find { it.name == value.uppercase() } ?: NONE
    }
}

enum class OrderCommand {
    BUY, SELL, NONE;
    companion object {
        fun fromString(value: String): OrderCommand = 
            entries.find { it.name == value.uppercase() } ?: NONE
    }
}


@IgnoreExtraProperties
data class AccountInfo(
    val broker: String = "",
    @get:PropertyName("_mock") @set:PropertyName("_mock")
    var isMock: Boolean = true,
    @get:PropertyName("_overseas") @set:PropertyName("_overseas")
    var isOverseas: Boolean = true,
    @get:PropertyName("account_number") @set:PropertyName("account_number")
    var accountNumber: String = "",
    @get:PropertyName("app_key") @set:PropertyName("app_key")
    var appKey: String = "",
    @get:PropertyName("app_secret") @set:PropertyName("app_secret")
    var appSecret: String = "",
    @get:PropertyName("last_used_timestamp") @set:PropertyName("last_used_timestamp")
    var lastUsedTimestamp: String = ""
)

@IgnoreExtraProperties
data class UserAccountData(
    @get:PropertyName("active_account_id") @set:PropertyName("active_account_id")
    var activeAccountId: String = "",
    val pin: String = "",
    @get:PropertyName("saved_accounts") @set:PropertyName("saved_accounts")
    var savedAccounts: Map<String, AccountInfo> = emptyMap()
)

@IgnoreExtraProperties
data class HoldingStock(
    val symbol: String = "",
    val quantity: Double = 0.0,
    @get:PropertyName("avg_price") @set:PropertyName("avg_price")
    var avgPrice: Double = 0.0,
    @get:PropertyName("current_price") @set:PropertyName("current_price")
    var currentPrice: Double = 0.0,
    @get:PropertyName("profit_loss") @set:PropertyName("profit_loss")
    var profitLoss: Double = 0.0,
    val yield: Float = 0.0f,
    val name: String = ""
)

@IgnoreExtraProperties
data class TradingStatus(
    @get:PropertyName("total_balance") @set:PropertyName("total_balance")
    var totalBalance: Double = 0.0,
    @get:PropertyName("current_yield") @set:PropertyName("current_yield")
    var currentYield: Float = 0.0f,
    @get:PropertyName("avail_cash") @set:PropertyName("avail_cash")
    var availCash: Double = 0.0,
    @get:PropertyName("last_updated") @set:PropertyName("last_updated")
    var lastUpdated: String = "",
    @get:PropertyName("stock_prices") @set:PropertyName("stock_prices")
    var stockPrices: Map<String, Double> = emptyMap(),
    @get:PropertyName("stock_prices_change") @set:PropertyName("stock_prices_change")
    var stockPricesChange: Map<String, Double> = emptyMap(),
    @get:PropertyName("stock_names") @set:PropertyName("stock_names")
    var stockNames: Map<String, String> = emptyMap(),
    @get:PropertyName("holding_stocks") @set:PropertyName("holding_stocks")
    var holdingStocks: Map<String, HoldingStock> = emptyMap(),
    val authError: String = ""
)

@IgnoreExtraProperties
data class SymbolSurgeConfig(
    @get:PropertyName("time_window_seconds") @set:PropertyName("time_window_seconds")
    var timeWindowSeconds: Int = 60,
    @get:PropertyName("surge_percent_threshold") @set:PropertyName("surge_percent_threshold")
    var surgePercentThreshold: Float = 3.0f,
    @get:PropertyName("buy_quantity") @set:PropertyName("buy_quantity")
    var buyQuantity: Double = 0.0
)

@IgnoreExtraProperties
data class SurgeTradingConfig(
    val enabled: Boolean = false,
    @get:PropertyName("allow_extended_hours") @set:PropertyName("allow_extended_hours")
    var allowExtendedHours: Boolean = true,
    @get:PropertyName("per_symbol_config") @set:PropertyName("per_symbol_config")
    var perSymbolConfig: Map<String, List<SymbolSurgeConfig>> = emptyMap()
)

@IgnoreExtraProperties
data class TradingSettings(
    @get:PropertyName("is_auto_trading") @set:PropertyName("is_auto_trading")
    var isAutoTrading: Boolean = false,
    @get:PropertyName("watch_symbols") @set:PropertyName("watch_symbols")
    var watchSymbols: Map<String, SymbolSettings> = emptyMap(),
    @get:PropertyName("surge_trading") @set:PropertyName("surge_trading")
    var surgeTrading: SurgeTradingConfig = SurgeTradingConfig(),
    val memo: String = ""
)

@IgnoreExtraProperties
data class SymbolSettings(
    val enabled: Boolean = true,
    val order: Int = 0
)

@IgnoreExtraProperties
data class ManualOrder(
    val command: String = "NONE",
    val symbol: String = "",
    val quantity: Double = 1.0,
    @get:PropertyName("target_price") @set:PropertyName("target_price")
    var targetPrice: Double = 0.0,
    val timestamp: String = "",
    val status: String = "",
    @get:PropertyName("error_message") @set:PropertyName("error_message")
    var errorMessage: String = ""
)

@IgnoreExtraProperties
data class ConditionalOrder(
    val symbol: String = "",
    val side: String = "BUY",
    val quantity: Double = 1.0,
    @get:PropertyName("target_price") @set:PropertyName("target_price")
    var targetPrice: Double = 0.0,
    @get:PropertyName("allow_extended_hours") @set:PropertyName("allow_extended_hours")
    var allowExtendedHours: Boolean = false,
    val status: String = "PENDING",
    val timestamp: String = "",
    @get:PropertyName("executed_at") @set:PropertyName("executed_at")
    var executedAt: String = "",
    @get:PropertyName("executed_price") @set:PropertyName("executed_price")
    var executedPrice: Double = 0.0
)

@IgnoreExtraProperties
data class StopLossOrder(
    val symbol: String = "",
    @get:PropertyName("trigger_price") @set:PropertyName("trigger_price")
    var triggerPrice: Double = 0.0,
    @get:PropertyName("volume_percent") @set:PropertyName("volume_percent")
    var volumePercent: Int = 100,
    @get:PropertyName("allow_extended_hours") @set:PropertyName("allow_extended_hours")
    var allowExtendedHours: Boolean = false,
    val status: String = "PENDING",
    val timestamp: String = "",
    @get:PropertyName("executed_at") @set:PropertyName("executed_at")
    var executedAt: String = "",
    @get:PropertyName("executed_price") @set:PropertyName("executed_price")
    var executedPrice: Double = 0.0
)

@IgnoreExtraProperties
data class LaoreSettings(
    val enabled: Boolean = false,
    @get:PropertyName("total_budget") @set:PropertyName("total_budget")
    var totalBudget: Double = 0.0,
    @get:PropertyName("split_count") @set:PropertyName("split_count")
    var splitCount: Int = 40,
    @get:PropertyName("target_yield_percent") @set:PropertyName("target_yield_percent")
    var targetYieldPercent: Float = 10.0f,
    @get:PropertyName("interval_hours") @set:PropertyName("interval_hours")
    var intervalHours: Float = 24.0f
)

@IgnoreExtraProperties
data class LaoreStatus(
    @get:PropertyName("current_round") @set:PropertyName("current_round")
    var currentRound: Int = 0,
    @get:PropertyName("holding_quantity") @set:PropertyName("holding_quantity")
    var holdingQuantity: Double = 0.0,
    @get:PropertyName("average_price") @set:PropertyName("average_price")
    var averagePrice: Double = 0.0,
    @get:PropertyName("status_message") @set:PropertyName("status_message")
    var statusMessage: String = "",
    @get:PropertyName("current_mode") @set:PropertyName("current_mode")
    var currentMode: String = "일반 모드(전반전)",
    @get:PropertyName("t_value") @set:PropertyName("t_value")
    var tValue: Double = 0.0,
    @get:PropertyName("target_sell_price") @set:PropertyName("target_sell_price")
    var targetSellPrice: Double = 0.0,
    @get:PropertyName("remaining_cash") @set:PropertyName("remaining_cash")
    var remainingCash: Double = 0.0
)

@IgnoreExtraProperties
data class ReservationOrder(
    val symbol: String = "",
    val name: String = "",
    val quantity: Double = 0.0,
    @get:PropertyName("auto_cancel_enabled") @set:PropertyName("auto_cancel_enabled")
    var autoCancelEnabled: Boolean = false,
    @get:PropertyName("auto_cancel_threshold") @set:PropertyName("auto_cancel_threshold")
    var autoCancelThreshold: Float = 1.0f,
    val status: String = "대기중",
    @get:PropertyName("order_no") @set:PropertyName("order_no")
    var orderNo: String = "",
    val timestamp: String = "",
    @get:PropertyName("last_updated") @set:PropertyName("last_updated")
    var lastUpdated: String = ""
)

@IgnoreExtraProperties
data class AppNotification(
    val title: String = "",
    val body: String = "",
    val timestamp: Long = 0L,
    val read: Boolean = false,
    var key: String = "" // Local property for UI
)

@IgnoreExtraProperties
data class UserProfile(
    val id: String = "",
    val name: String = "",
    val icon: String = "👤",
    val pin: String = ""
)



@IgnoreExtraProperties
data class DualMomentumSettings(
    val enabled: Boolean = false,
    @get:PropertyName("target_account_id") @set:PropertyName("target_account_id")
    var targetAccountId: String = "한국투자_MOCK_OS",
    @get:PropertyName("max_budget") @set:PropertyName("max_budget")
    var maxBudget: Double = 10000.0,
    @get:PropertyName("tp_target_percent") @set:PropertyName("tp_target_percent")
    var tpTargetPercent: Float = 18.0f,
    @get:PropertyName("sl_percent") @set:PropertyName("sl_percent")
    var slPercent: Float = 5.0f
)

@IgnoreExtraProperties
data class DualMomentumStatus(
    @get:PropertyName("engine_running") @set:PropertyName("engine_running")
    var engineRunning: Boolean = false,
    @get:PropertyName("active_ticker") @set:PropertyName("active_ticker")
    var activeTicker: Int = 0, // 0: None, 1: SOXL, 2: SOXS
    @get:PropertyName("active_symbol") @set:PropertyName("active_symbol")
    var activeSymbol: String = "NONE",
    val shares: Double = 0.0,
    @get:PropertyName("avg_price") @set:PropertyName("avg_price")
    var avgPrice: Double = 0.0,
    @get:PropertyName("current_price") @set:PropertyName("current_price")
    var currentPrice: Double = 0.0,
    @get:PropertyName("profit_loss_rate") @set:PropertyName("profit_loss_rate")
    var profitLossRate: Float = 0.0f,
    @get:PropertyName("profit_loss_amount") @set:PropertyName("profit_loss_amount")
    var profitLossAmount: Double = 0.0,
    @get:PropertyName("days_held") @set:PropertyName("days_held")
    var daysHeld: Int = 0,
    val regime: String = "BULL",
    @get:PropertyName("soxx_rsi") @set:PropertyName("soxx_rsi")
    var soxxRsi: Float = 50.0f,
    @get:PropertyName("tp_target_price") @set:PropertyName("tp_target_price")
    var tpTargetPrice: Double = 0.0,
    @get:PropertyName("tp_trailing_stop") @set:PropertyName("tp_trailing_stop")
    var tpTrailingStop: Double = 0.0,
    @get:PropertyName("sl_price") @set:PropertyName("sl_price")
    var slPrice: Double = 0.0,
    @get:PropertyName("bought_levels") @set:PropertyName("bought_levels")
    var boughtLevels: List<Boolean> = listOf(false, false, false, false),
    @get:PropertyName("last_updated") @set:PropertyName("last_updated")
    var lastUpdated: String = "",
    @get:PropertyName("last_log") @set:PropertyName("last_log")
    var lastLog: String = ""
)

@IgnoreExtraProperties
data class DualMomentumTrade(
    val id: String = "",
    val timestamp: Long = 0L,
    val datetime: String = "",
    val symbol: String = "",
    val action: String = "", // BUY, SELL
    val quantity: Double = 0.0,
    val price: Double = 0.0,
    @get:PropertyName("pnl_rate") @set:PropertyName("pnl_rate")
    var pnlRate: Float = 0.0f,
    val reason: String = ""
)

