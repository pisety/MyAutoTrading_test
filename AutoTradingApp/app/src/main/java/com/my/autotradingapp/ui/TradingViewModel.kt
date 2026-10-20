package com.my.autotradingapp.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import com.my.autotradingapp.data.*
import kotlinx.coroutines.flow.StateFlow

class TradingViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("TradingApp", Context.MODE_PRIVATE)
    private val initialUserId = prefs.getString("LastUsedUserId", "")?.trim()?.takeIf { it.isNotEmpty() && it != "test_user" } ?: "user_main"
    private val repository = TradingRepository(initialUserId)

    val userId: StateFlow<String> = repository.userId
    val profiles: StateFlow<List<UserProfile>> = repository.profiles
    val currentProfileName: StateFlow<String> = repository.currentProfileName
    val stockStatus: StateFlow<TradingStatus> = repository.stockStatus
    val stockSettings: StateFlow<TradingSettings> = repository.stockSettings
    val laoreSettings: StateFlow<Map<String, LaoreSettings>> = repository.laoreSettings
    val laoreStatus: StateFlow<Map<String, LaoreStatus>> = repository.laoreStatus
    val cryptoLaoreSettings: StateFlow<Map<String, LaoreSettings>> = repository.cryptoLaoreSettings
    val cryptoLaoreStatus: StateFlow<Map<String, LaoreStatus>> = repository.cryptoLaoreStatus
    val stockAccountData: StateFlow<UserAccountData> = repository.stockAccountData
    val cryptoStatus: StateFlow<TradingStatus> = repository.cryptoStatus
    val cryptoSettings: StateFlow<TradingSettings> = repository.cryptoSettings
    val cryptoAccountData: StateFlow<UserAccountData> = repository.cryptoAccountData
    val manualOrderStatus: StateFlow<ManualOrder?> = repository.manualOrderStatus
    val reservationOrders: StateFlow<Map<String, ReservationOrder>> = repository.reservationOrders
    val notifications: StateFlow<List<AppNotification>> = repository.notifications
    val domesticStocks: StateFlow<Map<String, String>> = repository.domesticStocks
    val upbitCoins: StateFlow<Map<String, String>> = repository.upbitCoins
    val overseasStocks: StateFlow<Map<String, String>> = repository.overseasStocks

    val dualMomentumSettings: StateFlow<DualMomentumSettings> = repository.dualMomentumSettings
    val dualMomentumStatus: StateFlow<DualMomentumStatus> = repository.dualMomentumStatus
    val dualMomentumTrades: StateFlow<List<DualMomentumTrade>> = repository.dualMomentumTrades


    fun setUserId(newId: String) = repository.setUserId(newId)
    fun updateStockName(symbol: String, name: String, serviceType: String = "stock") = repository.updateStockName(symbol, name, serviceType)
    fun saveLaoreSetting(compositeKey: String, settings: LaoreSettings, serviceType: String = "stock") = repository.saveLaoreSetting(compositeKey, settings, serviceType)
    fun removeLaoreSetting(compositeKey: String, serviceType: String = "stock") = repository.removeLaoreSetting(compositeKey, serviceType)
    fun updatePin(pin: String, serviceType: String) = repository.updatePin(pin, serviceType)
    fun saveAccountSecurely(info: AccountInfo, newPinToSet: String, currentlyVerifiedPin: String, serviceType: String, onComplete: (Boolean, String) -> Unit) = repository.saveAccountSecurely(info, newPinToSet, currentlyVerifiedPin, serviceType, onComplete)
    fun resetManualOrderStatus() = repository.resetManualOrderStatus()
    fun clearManualOrderFirebaseStatus(serviceType: String) = repository.clearManualOrderFirebaseStatus(serviceType)
    fun clearAuthErrorLocally(serviceType: String) = repository.clearAuthErrorLocally(serviceType)
    fun saveFcmToken(token: String) = repository.saveFcmToken(token)
    fun saveAccount(info: AccountInfo, serviceType: String) = repository.saveAccount(info, serviceType)
    fun toggleAutoTrading(enabled: Boolean, serviceType: String) = repository.toggleAutoTrading(enabled, serviceType)
    fun updateWatchSymbols(symbols: List<String>, serviceType: String, isOverseas: Boolean? = null) = repository.updateWatchSymbols(symbols, serviceType, isOverseas)
    fun sendManualOrder(command: String, symbol: String, quantity: Double, price: Double, serviceType: String) = repository.sendManualOrder(command, symbol, quantity, price, serviceType)
    fun sendConditionalOrder(symbol: String, side: String, quantity: Double, targetPrice: Double, serviceType: String) = repository.sendConditionalOrder(symbol, side, quantity, targetPrice, serviceType)
    fun sendStopLossOrder(symbol: String, triggerPrice: Double, volumePercent: Int, serviceType: String) = repository.sendStopLossOrder(symbol, triggerPrice, volumePercent, serviceType)
    fun updateSurgeTradingSettings(config: SurgeTradingConfig, serviceType: String) = repository.updateSurgeTradingSettings(config, serviceType)
    fun sendReservationOrder(symbol: String, name: String, quantity: Double, autoCancelEnabled: Boolean, autoCancelThreshold: Float) = repository.sendReservationOrder(symbol, name, quantity, autoCancelEnabled, autoCancelThreshold)
    fun cancelReservationOrder(orderId: String) = repository.cancelReservationOrder(orderId)
    fun restartReservationOrder(orderId: String) = repository.restartReservationOrder(orderId)
    fun toggleReservationAutoCancel(orderId: String, currentEnabled: Boolean) = repository.toggleReservationAutoCancel(orderId, currentEnabled)
    fun deleteReservationOrder(orderId: String) = repository.deleteReservationOrder(orderId)
    fun markNotificationRead(key: String) = repository.markNotificationRead(key)
    fun clearNotifications() = repository.clearNotifications()
    fun checkUserIdExists(id: String, onResult: (Boolean) -> Unit) = repository.checkUserIdExists(id, onResult)
    fun setProfileName(id: String, name: String) = repository.setProfileName(id, name)
    fun deleteProfile(id: String, onComplete: (Boolean, String) -> Unit) = repository.deleteProfile(id, onComplete)

    override fun onCleared() {
        super.onCleared()
        repository.onCleared()
    }


    fun updateDualMomentumSettings(settings: DualMomentumSettings) = repository.updateDualMomentumSettings(settings)
    fun toggleDualMomentum(enabled: Boolean) {
        val current = dualMomentumSettings.value
        repository.updateDualMomentumSettings(current.copy(enabled = enabled))
    }
    fun requestDualMomentumEmergencyClose() = repository.requestDualMomentumEmergencyClose()

}
