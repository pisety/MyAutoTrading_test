package com.my.autotradingapp.data

import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.my.autotradingapp.utils.CryptoUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

class TradingRepository(initialUserId: String = "user_main") {

    private val dbUrl = "https://YOUR-FIREBASE-PROJECT-default-rtdb.firebaseio.com/"
    private val database = FirebaseDatabase.getInstance(dbUrl)
    
    // 기본값 설정 ("user_main" 우선)
    private val _userId = MutableStateFlow(initialUserId.trim().takeIf { it.isNotEmpty() } ?: "user_main")
    val userId = _userId.asStateFlow()

    // Profile state flows
    private val defaultProfiles = listOf(
        UserProfile("user_main", "내 계좌", "👨"),
        UserProfile("user_sub1", "자녀1", "👧"),
        UserProfile("user_sub2", "자녀2", "👶")
    )
    private val _profiles = MutableStateFlow<List<UserProfile>>(defaultProfiles)
    val profiles = _profiles.asStateFlow()

    private val _currentProfileName = MutableStateFlow("내 계좌")
    val currentProfileName = _currentProfileName.asStateFlow()

    private var usersListener: ValueEventListener? = null

    // Stock state flows
    private val _stockStatus = MutableStateFlow(TradingStatus())
    val stockStatus = _stockStatus.asStateFlow()

    private val _stockSettings = MutableStateFlow(TradingSettings())
    val stockSettings = _stockSettings.asStateFlow()

    private val _laoreSettings = MutableStateFlow<Map<String, LaoreSettings>>(emptyMap())
    val laoreSettings = _laoreSettings.asStateFlow()

    private val _laoreStatus = MutableStateFlow<Map<String, LaoreStatus>>(emptyMap())
    val laoreStatus = _laoreStatus.asStateFlow()

    private val _cryptoLaoreSettings = MutableStateFlow<Map<String, LaoreSettings>>(emptyMap())
    val cryptoLaoreSettings = _cryptoLaoreSettings.asStateFlow()

    private val _cryptoLaoreStatus = MutableStateFlow<Map<String, LaoreStatus>>(emptyMap())
    val cryptoLaoreStatus = _cryptoLaoreStatus.asStateFlow()

    private val _stockAccountData = MutableStateFlow(UserAccountData())
    val stockAccountData = _stockAccountData.asStateFlow()

    // Crypto state flows
    private val _cryptoStatus = MutableStateFlow(TradingStatus())
    val cryptoStatus = _cryptoStatus.asStateFlow()

    private val _cryptoSettings = MutableStateFlow(TradingSettings())
    val cryptoSettings = _cryptoSettings.asStateFlow()

    private val _cryptoAccountData = MutableStateFlow(UserAccountData())
    val cryptoAccountData = _cryptoAccountData.asStateFlow()

    private val _manualOrderStatus = MutableStateFlow<ManualOrder?>(null)
    val manualOrderStatus = _manualOrderStatus.asStateFlow()

    private val _reservationOrders = MutableStateFlow<Map<String, ReservationOrder>>(emptyMap())
    val reservationOrders = _reservationOrders.asStateFlow()

    private val _dualMomentumSettings = MutableStateFlow(DualMomentumSettings())
    val dualMomentumSettings = _dualMomentumSettings.asStateFlow()

    private val _dualMomentumStatus = MutableStateFlow(DualMomentumStatus())
    val dualMomentumStatus = _dualMomentumStatus.asStateFlow()

    private val _dualMomentumTrades = MutableStateFlow<List<DualMomentumTrade>>(emptyList())
    val dualMomentumTrades = _dualMomentumTrades.asStateFlow()


    private val _notifications = MutableStateFlow<List<AppNotification>>(emptyList())
    val notifications = _notifications.asStateFlow()

    // Market data for autocomplete
    private val _domesticStocks = MutableStateFlow<Map<String, String>>(emptyMap())
    val domesticStocks = _domesticStocks.asStateFlow()

    private val _upbitCoins = MutableStateFlow<Map<String, String>>(emptyMap())
    val upbitCoins = _upbitCoins.asStateFlow()

    private val _overseasStocks = MutableStateFlow<Map<String, String>>(emptyMap())
    val overseasStocks = _overseasStocks.asStateFlow()

    // Listeners for stock
    private var stockStatusListener: ValueEventListener? = null
    private var stockSettingsListener: ValueEventListener? = null
    private var stockAccountListener: ValueEventListener? = null
    private var stockOrderListener: ValueEventListener? = null

    // Listeners for crypto
    private var cryptoStatusListener: ValueEventListener? = null
    private var cryptoSettingsListener: ValueEventListener? = null
    private var cryptoAccountListener: ValueEventListener? = null
    private var cryptoOrderListener: ValueEventListener? = null
    private var cryptoLaoreSettingsListener: ValueEventListener? = null
    private var cryptoLaoreStatusListener: ValueEventListener? = null

    private var laoreSettingsListener: ValueEventListener? = null
    private var laoreStatusListener: ValueEventListener? = null
    private var stockReservationListener: ValueEventListener? = null
    private var notificationsListener: ValueEventListener? = null

    private var stockActiveAccountId: String? = null
    private var cryptoActiveAccountId: String? = null

    private var stockStatusRef: com.google.firebase.database.DatabaseReference? = null
    private var stockSettingsRef: com.google.firebase.database.DatabaseReference? = null
    private var stockOrderRef: com.google.firebase.database.DatabaseReference? = null
    private var cryptoStatusRef: com.google.firebase.database.DatabaseReference? = null
    private var cryptoSettingsRef: com.google.firebase.database.DatabaseReference? = null
    private var cryptoOrderRef: com.google.firebase.database.DatabaseReference? = null
    private var cryptoLaoreSettingsRef: com.google.firebase.database.DatabaseReference? = null
    private var cryptoLaoreStatusRef: com.google.firebase.database.DatabaseReference? = null
    private var laoreSettingsRef: com.google.firebase.database.DatabaseReference? = null
    private var laoreStatusRef: com.google.firebase.database.DatabaseReference? = null
    private var stockReservationRef: com.google.firebase.database.DatabaseReference? = null

    private var dualMomentumSettingsListener: ValueEventListener? = null
    private var dualMomentumStatusListener: ValueEventListener? = null
    private var dualMomentumTradesListener: ValueEventListener? = null

    private var dualMomentumSettingsRef: com.google.firebase.database.DatabaseReference? = null
    private var dualMomentumStatusRef: com.google.firebase.database.DatabaseReference? = null
    private var dualMomentumTradesRef: com.google.firebase.database.DatabaseReference? = null



    init {
        updateCurrentProfileName()
        listenToUsersProfiles()
        startListening(_userId.value)
        loadMarketData()
    }

    private fun listenToUsersProfiles() {
        val usersRef = database.getReference("users")
        usersListener = usersRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = mutableListOf<UserProfile>()
                for (child in snapshot.children) {
                    val uid = child.key ?: continue
                    if (uid == "test_user") continue
                    val rawName = child.child("profile_name").value as? String ?: ""
                    val resolvedName = when {
                        rawName.isNotEmpty() -> rawName
                        uid == "user_main" -> "내 계좌"
                        uid == "user_sub1" -> "자녀1"
                        uid == "user_sub2" -> "자녀2"
                        else -> uid
                    }
                    val icon = when {
                        resolvedName.contains("자녀1") || resolvedName.contains("자녀2") || resolvedName.contains("자녀") -> "👧"
                        resolvedName.contains("내") || resolvedName.contains("본인") || uid == "user_main" -> "👨"
                        else -> "👤"
                    }
                    val pin = (child.child("pin").value as? String)?.takeIf { it.isNotEmpty() }
                        ?: (child.child("stock").child("account_data").child("pin").value as? String)?.takeIf { it.isNotEmpty() }
                        ?: (child.child("crypto").child("account_data").child("pin").value as? String)
                        ?: ""
                    list.add(UserProfile(id = uid, name = resolvedName, icon = icon, pin = pin))
                }
                val sortedList = list.sortedWith(compareBy({ it.id != "user_main" && !it.name.contains("내 계좌") }, { it.name }))
                _profiles.value = sortedList
                updateCurrentProfileName()
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("TradingApp", "Users profiles listener cancelled: ${error.message}")
            }
        })
    }

    private fun updateCurrentProfileName() {
        val currentId = _userId.value
        val found = _profiles.value.find { it.id == currentId }
        _currentProfileName.value = found?.name ?: when (currentId) {
            "user_main" -> "내 계좌"
            "user_sub1" -> "자녀1"
            "user_sub2" -> "자녀2"
            else -> currentId
        }
    }

    private fun getUserPath(id: String): String {
        val cleanId = id.trim()
        return "users/$cleanId"
    }

    private fun getAccountPath(id: String, serviceType: String): String {
        val servicePath = getServicePath(id, serviceType)
        val activeId = if (serviceType == "crypto") cryptoActiveAccountId else stockActiveAccountId
        return if (!activeId.isNullOrEmpty()) "$servicePath/accounts/$activeId" else servicePath
    }

    private fun getServicePath(id: String, serviceType: String): String {
        val cleanId = id.trim()
        return "users/$cleanId/$serviceType"
    }

    fun setUserId(newId: String) {
        val trimmedId = newId.trim()
        if (trimmedId.isEmpty()) return
        if (_userId.value == trimmedId) {
            updateCurrentProfileName()
            return
        }
        
        Log.d("TradingApp", "Switching User ID to: $trimmedId")
        stopListening()
        _userId.value = trimmedId
        updateCurrentProfileName()
        startListening(trimmedId)
    }

    private fun startListening(id: String) {
        val stockPath = getServicePath(id, "stock")
        val cryptoPath = getServicePath(id, "crypto")
        Log.d("TradingApp", "Start listening to paths: $stockPath, $cryptoPath")
        
        val notificationsRef = database.getReference("users/$id/notifications")
        notificationsListener = notificationsRef.orderByChild("timestamp").limitToLast(50).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = mutableListOf<AppNotification>()
                for (child in snapshot.children) {
                    val notif = child.getValue(AppNotification::class.java)
                    if (notif != null) {
                        notif.key = child.key ?: ""
                        list.add(notif)
                    }
                }
                _notifications.value = list.sortedByDescending { it.timestamp }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e("TradingApp", "Notifications listener error", error.toException())
            }
        })
        
        stockAccountListener = database.getReference("$stockPath/account_data").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val newActiveId = snapshot.child("active_account_id").value as? String ?: ""
                val changed = stockActiveAccountId != newActiveId
                
                _stockAccountData.value = parseAccountData(snapshot, stockPath)
                
                if (changed) {
                    stockActiveAccountId = newActiveId
                    restartStockAccountSpecificListeners(stockPath, newActiveId)
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e("TradingApp", "Stock Account Listener Cancelled: ${error.message}")
            }
        })

        cryptoAccountListener = database.getReference("$cryptoPath/account_data").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val newActiveId = snapshot.child("active_account_id").value as? String ?: ""
                val changed = cryptoActiveAccountId != newActiveId
                
                _cryptoAccountData.value = parseAccountData(snapshot, cryptoPath)
                
                if (changed) {
                    cryptoActiveAccountId = newActiveId
                    restartCryptoAccountSpecificListeners(cryptoPath, newActiveId)
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e("TradingApp", "Crypto Account Listener Cancelled: ${error.message}")
            }
        })
    }

    private fun stopStockAccountSpecificListeners() {
        stockStatusListener?.let { stockStatusRef?.removeEventListener(it) }
        stockSettingsListener?.let { stockSettingsRef?.removeEventListener(it) }
        stockOrderListener?.let { stockOrderRef?.removeEventListener(it) }
        laoreSettingsListener?.let { laoreSettingsRef?.removeEventListener(it) }
        laoreStatusListener?.let { laoreStatusRef?.removeEventListener(it) }
        stockReservationListener?.let { stockReservationRef?.removeEventListener(it) }

        stockStatusListener = null
        stockSettingsListener = null
        stockOrderListener = null
        laoreSettingsListener = null
        laoreStatusListener = null
        stockReservationListener = null

        stockStatusRef = null
        stockSettingsRef = null
        stockOrderRef = null
        laoreSettingsRef = null
        laoreStatusRef = null
        stockReservationRef = null

        dualMomentumSettingsListener?.let { dualMomentumSettingsRef?.removeEventListener(it) }
        dualMomentumStatusListener?.let { dualMomentumStatusRef?.removeEventListener(it) }
        dualMomentumTradesListener?.let { dualMomentumTradesRef?.removeEventListener(it) }
        dualMomentumSettingsListener = null
        dualMomentumStatusListener = null
        dualMomentumTradesListener = null
        dualMomentumSettingsRef = null
        dualMomentumStatusRef = null
        dualMomentumTradesRef = null

    }

    private fun restartStockAccountSpecificListeners(servicePath: String, activeId: String) {
        stopStockAccountSpecificListeners()

        if (activeId.isEmpty()) {
            _stockStatus.value = TradingStatus()
            _stockSettings.value = TradingSettings()
            _laoreSettings.value = emptyMap()
            _laoreStatus.value = emptyMap()
            _reservationOrders.value = emptyMap()

            _dualMomentumSettings.value = DualMomentumSettings()
            _dualMomentumStatus.value = DualMomentumStatus()
            _dualMomentumTrades.value = emptyList()

            return
        }

        val accountPath = "$servicePath/accounts/$activeId"
        Log.d("TradingApp", "Restarting Stock listeners on $accountPath")

        stockStatusRef = database.getReference("$accountPath/status")
        stockSettingsRef = database.getReference("$accountPath/settings")
        stockOrderRef = database.getReference("$accountPath/manual_orders")
        laoreSettingsRef = database.getReference("$accountPath/laore_settings")
        laoreStatusRef = database.getReference("$accountPath/laore_status")
        stockReservationRef = database.getReference("$accountPath/reservation_orders")

        stockStatusListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                _stockStatus.value = parseStatus(snapshot, accountPath)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        stockSettingsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                _stockSettings.value = parseSettings(snapshot)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        stockOrderListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                parseOrderStatus(snapshot)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        laoreSettingsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                _laoreSettings.value = parseLaoreSettings(snapshot)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        laoreStatusListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                _laoreStatus.value = parseLaoreStatus(snapshot)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        stockReservationListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                _reservationOrders.value = parseReservationOrders(snapshot)
            }
            override fun onCancelled(error: DatabaseError) {}
        }

        stockStatusRef?.addValueEventListener(stockStatusListener!!)
        stockSettingsRef?.addValueEventListener(stockSettingsListener!!)
        stockOrderRef?.addValueEventListener(stockOrderListener!!)
        laoreSettingsRef?.addValueEventListener(laoreSettingsListener!!)
        laoreStatusRef?.addValueEventListener(laoreStatusListener!!)
        stockReservationRef?.addValueEventListener(stockReservationListener!!)

        val currentAccount = _stockAccountData.value.savedAccounts[activeId]
        val isMockOverseasAccount = currentAccount != null && currentAccount.isMock && currentAccount.isOverseas

        // 퀀트 스윙은 오직 모의 해외 계좌(한국투자_MOCK_OS 등)에서만 동작
        // 실전 계좌(국내/해외) 및 국내 모의 계좌에서는 리스너를 등록하지 않고 영구 비활성화 유지
        if (isMockOverseasAccount) {
            dualMomentumSettingsRef = database.getReference("$accountPath/dual_momentum/settings")
            dualMomentumStatusRef = database.getReference("$accountPath/dual_momentum/status")
            dualMomentumTradesRef = database.getReference("$accountPath/dual_momentum/trades")

            dualMomentumSettingsListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    _dualMomentumSettings.value = parseDualMomentumSettings(snapshot)
                }
                override fun onCancelled(error: DatabaseError) {}
            }
            dualMomentumStatusListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    _dualMomentumStatus.value = parseDualMomentumStatus(snapshot)
                }
                override fun onCancelled(error: DatabaseError) {}
            }
            dualMomentumTradesListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    _dualMomentumTrades.value = parseDualMomentumTrades(snapshot)
                }
                override fun onCancelled(error: DatabaseError) {}
            }

            dualMomentumSettingsRef?.addValueEventListener(dualMomentumSettingsListener!!)
            dualMomentumStatusRef?.addValueEventListener(dualMomentumStatusListener!!)
            dualMomentumTradesRef?.addValueEventListener(dualMomentumTradesListener!!)
        } else {
            _dualMomentumSettings.value = DualMomentumSettings(enabled = false)
            _dualMomentumStatus.value = DualMomentumStatus()
            _dualMomentumTrades.value = emptyList()
        }

    }

    private fun stopCryptoAccountSpecificListeners() {
        cryptoStatusListener?.let { cryptoStatusRef?.removeEventListener(it) }
        cryptoSettingsListener?.let { cryptoSettingsRef?.removeEventListener(it) }
        cryptoOrderListener?.let { cryptoOrderRef?.removeEventListener(it) }
        cryptoLaoreSettingsListener?.let { cryptoLaoreSettingsRef?.removeEventListener(it) }
        cryptoLaoreStatusListener?.let { cryptoLaoreStatusRef?.removeEventListener(it) }

        cryptoStatusListener = null
        cryptoSettingsListener = null
        cryptoOrderListener = null
        cryptoLaoreSettingsListener = null
        cryptoLaoreStatusListener = null

        cryptoStatusRef = null
        cryptoSettingsRef = null
        cryptoOrderRef = null
        cryptoLaoreSettingsRef = null
        cryptoLaoreStatusRef = null
    }

    private fun restartCryptoAccountSpecificListeners(servicePath: String, activeId: String) {
        stopCryptoAccountSpecificListeners()

        if (activeId.isEmpty()) {
            _cryptoStatus.value = TradingStatus()
            _cryptoSettings.value = TradingSettings()
            _cryptoLaoreSettings.value = emptyMap()
            _cryptoLaoreStatus.value = emptyMap()
            return
        }

        val accountPath = "$servicePath/accounts/$activeId"
        Log.d("TradingApp", "Restarting Crypto listeners on $accountPath")

        cryptoStatusRef = database.getReference("$accountPath/status")
        cryptoSettingsRef = database.getReference("$accountPath/settings")
        cryptoOrderRef = database.getReference("$accountPath/manual_orders")
        cryptoLaoreSettingsRef = database.getReference("$accountPath/laore_settings")
        cryptoLaoreStatusRef = database.getReference("$accountPath/laore_status")

        cryptoStatusListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                _cryptoStatus.value = parseStatus(snapshot, accountPath)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        cryptoSettingsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                _cryptoSettings.value = parseSettings(snapshot)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        cryptoOrderListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                parseOrderStatus(snapshot)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        cryptoLaoreSettingsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                _cryptoLaoreSettings.value = parseLaoreSettings(snapshot)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        cryptoLaoreStatusListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                _cryptoLaoreStatus.value = parseLaoreStatus(snapshot)
            }
            override fun onCancelled(error: DatabaseError) {}
        }

        cryptoStatusRef?.addValueEventListener(cryptoStatusListener!!)
        cryptoSettingsRef?.addValueEventListener(cryptoSettingsListener!!)
        cryptoOrderRef?.addValueEventListener(cryptoOrderListener!!)
        cryptoLaoreSettingsRef?.addValueEventListener(cryptoLaoreSettingsListener!!)
        cryptoLaoreStatusRef?.addValueEventListener(cryptoLaoreStatusListener!!)
    }

    // ============ PARSING HELPERS ============
    private fun parseAccountData(snapshot: DataSnapshot, path: String): UserAccountData {
        try {
            if (!snapshot.exists()) {
                Log.w("TradingApp", "Account data does not exist at $path")
                return UserAccountData()
            }
            val activeAccountId = snapshot.child("active_account_id").value as? String ?: ""
            val pinCode = snapshot.child("pin").value as? String ?: ""
            
            val savedAccounts = mutableMapOf<String, AccountInfo>()
            snapshot.child("saved_accounts").children.forEach { child ->
                val key = child.key ?: return@forEach
                val broker = child.child("broker").value as? String ?: ""
                val isMock = (child.child("_mock").value ?: child.child("is_mock").value) as? Boolean ?: true
                val isOverseas = (child.child("_overseas").value ?: child.child("is_overseas").value) as? Boolean ?: true
                val accNum = child.child("account_number").value as? String ?: ""
                val rawAppKey = child.child("app_key").value as? String ?: ""
                val appKey = CryptoUtils.decrypt(rawAppKey)
                val rawAppSecret = child.child("app_secret").value as? String ?: ""
                val appSecret = CryptoUtils.decrypt(rawAppSecret)
                val lastUsed = child.child("last_used_timestamp").value as? String ?: ""
                
                savedAccounts[key] = AccountInfo(broker, isMock, isOverseas, accNum, appKey, appSecret, lastUsed)
            }
            
            Log.d("TradingApp", "Account data loaded: ${savedAccounts.size} accounts from $path")
            return UserAccountData(activeAccountId, pinCode, savedAccounts)
        } catch (e: Exception) {
            Log.e("TradingApp", "Error parsing account data: ${e.message}")
            return UserAccountData()
        }
    }

    private fun parseStatus(snapshot: DataSnapshot, path: String): TradingStatus {
        try {
            if (!snapshot.exists()) {
                return TradingStatus()
            }
            val totalBalance = (snapshot.child("total_balance").value as? Number)?.toDouble() ?: 0.0
            val currentYield = (snapshot.child("current_yield").value as? Number)?.toFloat() ?: 0.0f
            val availCash = (snapshot.child("avail_cash").value as? Number)?.toDouble() ?: 0.0
            val lastUpdated = snapshot.child("last_updated").value as? String ?: ""
            
            val stockPrices = mutableMapOf<String, Double>()
            snapshot.child("stock_prices").children.forEach { child ->
                val key = child.key ?: return@forEach
                val value = (child.value as? Number)?.toDouble() ?: 0.0
                stockPrices[key] = value
            }

            val stockPricesChange = mutableMapOf<String, Double>()
            snapshot.child("stock_prices_change").children.forEach { child ->
                val key = child.key ?: return@forEach
                val value = (child.value as? Number)?.toDouble() ?: 0.0
                stockPricesChange[key] = value
            }

            val stockNames = mutableMapOf<String, String>()
            snapshot.child("stock_names").children.forEach { child ->
                val key = child.key ?: return@forEach
                val value = child.value as? String ?: ""
                // Filter out corrupted keys inserted by old versions (e.g. KRW-KRW-BTC or Korean characters)
                if (key.contains("KRW-KRW-") || key.matches(Regex(".*[가-힣]+.*"))) {
                    return@forEach
                }
                stockNames[key] = value
            }

            val holdingStocks = mutableMapOf<String, HoldingStock>()
            val holdingsNode = snapshot.child("holdings")
            holdingsNode.children.forEach { child ->
                val symbol = child.child("symbol").value as? String ?: return@forEach
                val qty = (child.child("quantity").value as? Number)?.toDouble() ?: 0.0
                val avgPrice = (child.child("purchase_avg_price").value as? Number)?.toDouble() ?: 0.0
                val curPrice = (child.child("current_price").value as? Number)?.toDouble() ?: 0.0
                val plAmount = (child.child("profit_loss_amount").value as? Number)?.toDouble() ?: 0.0
                val yieldRate = (child.child("profit_loss_rate").value as? Number)?.toFloat() ?: 0.0f
                val name = child.child("name").value as? String ?: ""
                holdingStocks[symbol] = HoldingStock(symbol, qty, avgPrice, curPrice, plAmount, yieldRate, name)
            }
            val authError = snapshot.child("auth_error").value as? String ?: ""

            Log.d("TradingApp", "Status Updated at $path")
            return TradingStatus(totalBalance, currentYield, availCash, lastUpdated, stockPrices, stockPricesChange, stockNames, holdingStocks, authError)
        } catch (e: Exception) {
            Log.e("TradingApp", "Error parsing status: ${e.message}")
            return TradingStatus()
        }
    }

        private fun parseLaoreSettings(snapshot: DataSnapshot): Map<String, LaoreSettings> {
        if (!snapshot.exists()) return emptyMap()
        val result = mutableMapOf<String, LaoreSettings>()
        for (child in snapshot.children) {
            val key = child.key ?: continue
            result[key] = LaoreSettings(
                enabled = child.child("enabled").value as? Boolean ?: false,
                totalBudget = (child.child("total_budget").value as? Number)?.toDouble() ?: 0.0,
                splitCount = (child.child("split_count").value as? Number)?.toInt() ?: 40,
                targetYieldPercent = (child.child("target_yield_percent").value as? Number)?.toFloat() ?: 10.0f
            )
        }
        return result
    }

        private fun parseLaoreStatus(snapshot: DataSnapshot): Map<String, LaoreStatus> {
        if (!snapshot.exists()) return emptyMap()
        val result = mutableMapOf<String, LaoreStatus>()
        for (child in snapshot.children) {
            val key = child.key ?: continue
            result[key] = LaoreStatus(
                currentRound = (child.child("current_round").value as? Number)?.toInt() ?: 0,
                holdingQuantity = (child.child("holding_quantity").value as? Number)?.toDouble() ?: 0.0,
                averagePrice = (child.child("average_price").value as? Number)?.toDouble() ?: 0.0,
                statusMessage = child.child("status_message").value as? String ?: "",
                currentMode = child.child("current_mode").value as? String ?: "일반 모드(전반전)",
                tValue = (child.child("t_value").value as? Number)?.toDouble() ?: 0.0,
                targetSellPrice = (child.child("target_sell_price").value as? Number)?.toDouble() ?: 0.0,
                remainingCash = (child.child("remaining_cash").value as? Number)?.toDouble() ?: 0.0
            )
        }
        return result
    }

    fun updateStockName(symbol: String, name: String, serviceType: String = "stock") {
        var sym = symbol.uppercase()
        if (serviceType == "crypto" && !sym.contains("-")) {
            sym = "KRW-$sym"
        }
        val path = "${getAccountPath(_userId.value, serviceType)}/status/stockNames"
        database.getReference(path).child(sym).setValue(name)
    }
    fun saveLaoreSetting(compositeKey: String, settings: LaoreSettings, serviceType: String = "stock") {
        val id = _userId.value.trim()
        if (id.isEmpty()) return
        database.getReference("${getAccountPath(id, serviceType)}/laore_settings/$compositeKey").setValue(settings)
    }
    
    fun removeLaoreSetting(compositeKey: String, serviceType: String = "stock") {
        val id = _userId.value.trim()
        if (id.isEmpty()) return
        
        val accountPath = getAccountPath(id, serviceType)
        // 설정 및 상태를 모두 완전히 삭제합니다.
        database.getReference("$accountPath/laore_settings/$compositeKey").removeValue()
        database.getReference("$accountPath/laore_status/$compositeKey").removeValue()
    }

    private fun parseSettings(snapshot: DataSnapshot): TradingSettings {
        if (!snapshot.exists()) {
            return TradingSettings()
        }
        val isAutoTrading = snapshot.child("is_auto_trading").value as? Boolean ?: false
        val memo = snapshot.child("memo").value as? String ?: ""
        
        val watchSymbolsRaw = mutableMapOf<String, SymbolSettings>()
        snapshot.child("watch_symbols").children.forEach { child ->
            val key = child.key ?: return@forEach
            val enabled = child.child("enabled").value as? Boolean ?: true
            val order = (child.child("order").value as? Long)?.toInt() ?: 0
            watchSymbolsRaw[key] = SymbolSettings(enabled, order)
        }
        val watchSymbols = watchSymbolsRaw.entries.sortedBy { it.value.order }.associate { it.key to it.value }
        
        val surgeNode = snapshot.child("surge_trading")
        val surgeEnabled = surgeNode.child("enabled").value as? Boolean ?: false
        
        val perSymbolConfig = mutableMapOf<String, List<SymbolSurgeConfig>>()
        surgeNode.child("per_symbol_config").children.forEach { child ->
            val key = child.key ?: return@forEach
            val list = mutableListOf<SymbolSurgeConfig>()
            
            // Check if this child has numbered children (array) or is a single object
            if (child.hasChildren() && child.children.firstOrNull()?.key?.toIntOrNull() != null) {
                child.children.forEach { cfgChild ->
                    val timeWindow = (cfgChild.child("time_window_seconds").value as? Number)?.toInt() ?: 60
                    val threshold = (cfgChild.child("surge_percent_threshold").value as? Number)?.toFloat() ?: 3.0f
                    val qty = (cfgChild.child("buy_quantity").value as? Number)?.toDouble() ?: 0.0
                    list.add(SymbolSurgeConfig(timeWindow, threshold, qty))
                }
            } else {
                // Fallback for old single-object structure
                val timeWindow = (child.child("time_window_seconds").value as? Number)?.toInt() ?: 60
                val threshold = (child.child("surge_percent_threshold").value as? Number)?.toFloat() ?: 3.0f
                val qty = (child.child("buy_quantity").value as? Number)?.toDouble() ?: 0.0
                list.add(SymbolSurgeConfig(timeWindow, threshold, qty))
            }
            perSymbolConfig[key] = list
        }
        
        val surgeExt = surgeNode.child("allow_extended_hours").value as? Boolean ?: true
        val surgeTrading = SurgeTradingConfig(surgeEnabled, surgeExt, perSymbolConfig)
        
        return TradingSettings(isAutoTrading, watchSymbols, surgeTrading, memo)
    }

    private fun parseOrderStatus(snapshot: DataSnapshot) {
        try {
            val status = snapshot.child("status").value as? String ?: return
            if (status == "FAILED" || status == "SUCCESS") {
                val cmd = snapshot.child("command").value as? String ?: "NONE"
                val err = snapshot.child("error_message").value as? String ?: ""
                val order = ManualOrder(cmd, "", 0.0, 0.0, "", status, err)
                _manualOrderStatus.value = order
            }
        } catch(e: Exception) {}
    }

    fun updatePin(pin: String, serviceType: String) {
        val id = _userId.value.trim()
        if (id.isEmpty()) return
        val servicePath = getServicePath(id, serviceType)
        database.getReference("$servicePath/account_data/pin").setValue(CryptoUtils.encrypt(pin))
    }

    private fun resetUserData() {
        _stockStatus.value = TradingStatus()
        _stockSettings.value = TradingSettings()
        _stockAccountData.value = UserAccountData()
        _laoreSettings.value = emptyMap()
        _laoreStatus.value = emptyMap()
        _reservationOrders.value = emptyMap()

            _dualMomentumSettings.value = DualMomentumSettings()
            _dualMomentumStatus.value = DualMomentumStatus()
            _dualMomentumTrades.value = emptyList()


        _cryptoStatus.value = TradingStatus()
        _cryptoSettings.value = TradingSettings()
        _cryptoAccountData.value = UserAccountData()
        _cryptoLaoreSettings.value = emptyMap()
        _cryptoLaoreStatus.value = emptyMap()

        _notifications.value = emptyList()
        _manualOrderStatus.value = null
    }

    private fun stopListening() {
        val oldId = _userId.value
        val stockPath = getServicePath(oldId, "stock")
        val cryptoPath = getServicePath(oldId, "crypto")

        stopStockAccountSpecificListeners()
        stopCryptoAccountSpecificListeners()

        stockAccountListener?.let {
            database.getReference("$stockPath/account_data").removeEventListener(it)
        }
        stockAccountListener = null

        cryptoAccountListener?.let {
            database.getReference("$cryptoPath/account_data").removeEventListener(it)
        }
        cryptoAccountListener = null

        notificationsListener?.let {
            database.getReference("users/$oldId/notifications").removeEventListener(it)
        }
        notificationsListener = null

        stockActiveAccountId = null
        cryptoActiveAccountId = null

        resetUserData()
    }

    fun saveAccountSecurely(info: AccountInfo, newPinToSet: String, currentlyVerifiedPin: String, serviceType: String, onComplete: (Boolean, String) -> Unit) {
        val id = _userId.value.trim()
        if (id.isEmpty()) {
            onComplete(false, "User ID is empty")
            return
        }
        
        val servicePath = getServicePath(id, serviceType)
        val accRef = database.getReference("$servicePath/account_data")
        
        // 서버에서 현재 PIN을 가져와 이중 검증
        accRef.child("pin").get().addOnSuccessListener { snapshot ->
            val serverPin = snapshot.value as? String ?: ""
            
            val decryptedServerPin = if (serverPin.isNotEmpty()) {
                try { CryptoUtils.decrypt(serverPin) } catch (e: Exception) { serverPin }
            } else { "" }
            
            val decryptedCurrentlyVerifiedPin = if (currentlyVerifiedPin.isNotEmpty()) {
                try { CryptoUtils.decrypt(currentlyVerifiedPin) } catch (e: Exception) { currentlyVerifiedPin }
            } else { "" }
            
            if (decryptedServerPin.isNotEmpty() && decryptedServerPin != decryptedCurrentlyVerifiedPin) {
                onComplete(false, "보안 차단: 이미 다른 분이 PIN을 설정한 계정입니다.")
                return@addOnSuccessListener
            }
            
            // 검증 통과 - 저장 진행
            val key = if (serviceType == "crypto") {
                "${info.broker}_REAL_CRYPTO"
            } else {
                "${info.broker}_${if (info.isMock) "MOCK" else "REAL"}_${if (info.isOverseas) "OS" else "KR"}"
            }
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            
            val accountMap = mutableMapOf<String, Any>(
                "broker" to info.broker,
                "is_mock" to info.isMock,
                "is_overseas" to info.isOverseas,
                "app_key" to CryptoUtils.encrypt(info.appKey),
                "app_secret" to CryptoUtils.encrypt(info.appSecret),
                "last_used_timestamp" to timestamp
            )
            if (serviceType != "crypto") {
                accountMap["account_number"] = info.accountNumber
            }
            
            accRef.child("saved_accounts").child(key).setValue(accountMap)
                .addOnSuccessListener { 
                    accRef.child("active_account_id").setValue(key)
                    if (newPinToSet.isNotEmpty()) {
                        val encPin = CryptoUtils.encrypt(newPinToSet)
                        accRef.child("pin").setValue(encPin)
                        database.getReference("users/$id/pin").setValue(encPin)
                    }
                    database.getReference("$servicePath/status").removeValue()
                    if (serviceType == "crypto") {
                        _cryptoStatus.value = TradingStatus()
                    } else {
                        _stockStatus.value = TradingStatus()
                    }
                    onComplete(true, "저장 완료")
                }
                .addOnFailureListener { onComplete(false, "저장 실패: ${it.message}") }
                
        }.addOnFailureListener {
            onComplete(false, "서버 연결 실패")
        }
    }

    fun resetManualOrderStatus() {
        _manualOrderStatus.value = null
    }

    fun clearManualOrderFirebaseStatus(serviceType: String) {
        val servicePath = getAccountPath(_userId.value, serviceType)
        database.getReference("$servicePath/manual_orders/status").setValue("NONE")
        database.getReference("$servicePath/manual_orders/errorMessage").setValue("")
    }

    fun clearAuthErrorLocally(serviceType: String) {
        if (serviceType == "crypto") {
            _cryptoStatus.value = _cryptoStatus.value.copy(authError = "")
        } else {
            _stockStatus.value = _stockStatus.value.copy(authError = "")
        }
    }

    fun saveFcmToken(token: String) {
        val id = _userId.value
        if (id.isNotEmpty() && id != "test_user") {
            database.reference.child("users").child(id).child("fcm_token").setValue(token)
        }
    }

    private fun loadMarketData() {
        database.reference.child("market_data").child("domestic_stocks")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val map = mutableMapOf<String, String>()
                    for (child in snapshot.children) {
                        val key = child.key
                        val value = child.getValue(String::class.java)
                        if (key != null && value != null) {
                            map[key] = value
                        }
                    }
                    _domesticStocks.value = map
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e("TradingViewModel", "Failed to load domestic stocks", error.toException())
                }
            })

        database.reference.child("market_data").child("upbit_coins")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val map = mutableMapOf<String, String>()
                    for (child in snapshot.children) {
                        val key = child.key
                        val value = child.getValue(String::class.java)
                        if (key != null && value != null) {
                            map[key] = value
                        }
                    }
                    _upbitCoins.value = map
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e("TradingViewModel", "Failed to load upbit coins", error.toException())
                }
            })

        database.reference.child("market_data").child("overseas_stocks")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val map = mutableMapOf<String, String>()
                    for (child in snapshot.children) {
                        val key = child.key
                        val value = child.getValue(String::class.java)
                        if (key != null && value != null) {
                            map[key] = value
                        }
                    }
                    _overseasStocks.value = map
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e("TradingViewModel", "Failed to load overseas stocks", error.toException())
                }
            })
    }

    fun saveAccount(info: AccountInfo, serviceType: String) {
        val id = _userId.value
        val servicePath = getServicePath(id, serviceType)
        
        val key = if (serviceType == "crypto") {
            "${info.broker}_REAL_CRYPTO"
        } else {
            "${info.broker}_${if (info.isMock) "MOCK" else "REAL"}_${if (info.isOverseas) "OS" else "KR"}"
        }
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        
        val accountMap = mutableMapOf<String, Any>(
            "broker" to info.broker,
            "is_mock" to info.isMock,
            "is_overseas" to info.isOverseas,
            "app_key" to CryptoUtils.encrypt(info.appKey),
            "app_secret" to CryptoUtils.encrypt(info.appSecret),
            "last_used_timestamp" to timestamp
        )
        if (serviceType != "crypto") {
            accountMap["accountNumber"] = info.accountNumber
        }
        
        Log.d("TradingApp", "Saving account to: $servicePath/account_data/savedAccounts/$key")
        
        val accRef = database.getReference("$servicePath/account_data")
        accRef.child("saved_accounts").child(key).setValue(accountMap)
            .addOnSuccessListener { Log.d("TradingApp", "Account saved successfully") }
            .addOnFailureListener { Log.e("TradingApp", "Failed to save account: ${it.message}") }
            
        accRef.child("active_account_id").setValue(key)
        database.getReference("$servicePath/status").removeValue()
        if (serviceType == "crypto") {
            _cryptoStatus.value = TradingStatus()
        } else {
            _stockStatus.value = TradingStatus()
        }
    }

    fun toggleAutoTrading(enabled: Boolean, serviceType: String) {
        val servicePath = getAccountPath(_userId.value, serviceType)
        database.getReference("$servicePath/settings/is_auto_trading").setValue(enabled)
    }

    fun updateWatchSymbols(symbols: List<String>, serviceType: String, isOverseas: Boolean? = null) {
        val servicePath = getAccountPath(_userId.value, serviceType)
        val symbolsMap = mutableMapOf<String, SymbolSettings>()
        var index = 0
        
        if (serviceType == "stock" && isOverseas != null) {
            val currentSymbols = _stockSettings.value.watchSymbols.keys
            val otherMarketSymbols = currentSymbols.filter { sym ->
                if (isOverseas) sym.any { it.isDigit() } else !sym.any { it.isDigit() }
            }
            otherMarketSymbols.forEach { sym ->
                symbolsMap[sym] = SymbolSettings(true, index++)
            }
        }

        val stockNamesPath = "$servicePath/status/stockNames"

        symbols.forEach { symbolStr -> 
            val str = symbolStr.trim()
            var sym = str
            var name = ""
            if (str.contains("(") && str.endsWith(")")) {
                name = str.substringBeforeLast("(").trim()
                sym = str.substringAfterLast("(").removeSuffix(")")
            }
            sym = sym.uppercase()
            if (serviceType == "crypto" && !sym.contains("-")) {
                sym = "KRW-$sym"
            }
            symbolsMap[sym] = SymbolSettings(true, index++)
            
            // 이름이 있는 경우 UI 지연을 없애기 위해 Firebase에 즉시 이름 저장
            if (name.isNotEmpty()) {
                database.getReference(stockNamesPath).child(sym).setValue(name)
            }
        }
        database.getReference("$servicePath/settings/watch_symbols").setValue(symbolsMap)
    }

    fun sendManualOrder(command: String, symbol: String, quantity: Double, price: Double, serviceType: String) {
        val servicePath = getAccountPath(_userId.value, serviceType)
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val timestamp = sdf.format(Date())
        val order = ManualOrder(command, symbol, quantity, price, timestamp)
        database.getReference("$servicePath/manual_orders").setValue(order)
    }

    fun sendConditionalOrder(symbol: String, side: String, quantity: Double, targetPrice: Double, serviceType: String) {
        val servicePath = getAccountPath(_userId.value, serviceType)
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val timestamp = sdf.format(Date())
        val orderId = java.util.UUID.randomUUID().toString()
        val order = ConditionalOrder(symbol, side, quantity, targetPrice, false, "PENDING", timestamp)
        database.getReference("$servicePath/conditional_orders/$orderId").setValue(order)
    }

    fun sendStopLossOrder(symbol: String, triggerPrice: Double, volumePercent: Int, serviceType: String) {
        val servicePath = getAccountPath(_userId.value, serviceType)
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val timestamp = sdf.format(Date())
        val orderId = java.util.UUID.randomUUID().toString()
        val order = StopLossOrder(symbol, triggerPrice, volumePercent, false, "PENDING", timestamp)
        database.getReference("$servicePath/stop_loss_orders/$orderId").setValue(order)
    }

    fun updateSurgeTradingSettings(config: SurgeTradingConfig, serviceType: String) {
        val servicePath = getAccountPath(_userId.value, serviceType)
        database.getReference("$servicePath/settings/surge_trading").setValue(config)
    }

    private fun parseReservationOrders(snapshot: DataSnapshot): Map<String, ReservationOrder> {
        val result = mutableMapOf<String, ReservationOrder>()
        for (child in snapshot.children) {
            val order = child.getValue(ReservationOrder::class.java)
            if (order != null) {
                result[child.key ?: ""] = order
            }
        }
        return result
    }

    fun sendReservationOrder(symbol: String, name: String, quantity: Double, autoCancelEnabled: Boolean, autoCancelThreshold: Float) {
        val servicePath = getAccountPath(_userId.value, "stock")
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val orderId = "RESV_" + System.currentTimeMillis()
        val order = ReservationOrder(symbol, name, quantity, autoCancelEnabled, autoCancelThreshold, "대기중", "", timestamp, timestamp)
        database.getReference("$servicePath/reservation_orders/$orderId").setValue(order)
    }

    fun cancelReservationOrder(orderId: String) {
        val servicePath = getAccountPath(_userId.value, "stock")
        database.getReference("$servicePath/reservation_orders/$orderId/status").setValue("취소요청")
    }

    fun restartReservationOrder(orderId: String) {
        val servicePath = getAccountPath(_userId.value, "stock")
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        database.getReference("$servicePath/reservation_orders/$orderId/status").setValue("대기중")
        database.getReference("$servicePath/reservation_orders/$orderId/lastUpdated").setValue(timestamp)
    }

    fun toggleReservationAutoCancel(orderId: String, currentEnabled: Boolean) {
        val servicePath = getAccountPath(_userId.value, "stock")
        database.getReference("$servicePath/reservation_orders/$orderId/autoCancelEnabled").setValue(!currentEnabled)
    }

    fun deleteReservationOrder(orderId: String) {
        val servicePath = getAccountPath(_userId.value, "stock")
        database.getReference("$servicePath/reservation_orders/$orderId").removeValue()
    }

    fun checkUserIdExists(id: String, onResult: (Boolean) -> Unit) {
        val cleanId = id.trim()
        if (cleanId.isEmpty()) {
            onResult(false)
            return
        }
        database.getReference("users/$cleanId").get().addOnSuccessListener { snapshot ->
            onResult(snapshot.exists())
        }.addOnFailureListener {
            onResult(false)
        }
    }

    fun setProfileName(id: String, name: String) {
        val cleanId = id.trim()
        if (cleanId.isNotEmpty()) {
            database.getReference("users/$cleanId/profile_name").setValue(name.trim())
        }
    }

    fun deleteProfile(id: String, onComplete: (Boolean, String) -> Unit) {
        val cleanId = id.trim()
        if (cleanId.isEmpty()) {
            onComplete(false, "ID가 비어있습니다.")
            return
        }
        if (_userId.value == cleanId) {
            setUserId("user_main")
        }
        database.getReference("users/$cleanId").removeValue().addOnSuccessListener {
            onComplete(true, "프로필($cleanId)이 성공적으로 삭제되었습니다.")
        }.addOnFailureListener { e ->
            onComplete(false, "삭제 실패: ${e.message}")
        }
    }

    fun onCleared() {
        stopListening()
        usersListener?.let { database.getReference("users").removeEventListener(it) }
        usersListener = null
    }

    fun markNotificationRead(key: String) {
        val id = _userId.value
        if (id.isEmpty() || key.isEmpty()) return
        database.getReference("users/$id/notifications/$key/read").setValue(true)
    }

    fun clearNotifications() {
        val id = _userId.value
        if (id.isEmpty()) return
        database.getReference("users/$id/notifications").removeValue()
    }


    private fun parseDualMomentumSettings(snapshot: DataSnapshot): DualMomentumSettings {
        return snapshot.getValue(DualMomentumSettings::class.java) ?: DualMomentumSettings()
    }

    private fun parseDualMomentumStatus(snapshot: DataSnapshot): DualMomentumStatus {
        return snapshot.getValue(DualMomentumStatus::class.java) ?: DualMomentumStatus()
    }

    private fun parseDualMomentumTrades(snapshot: DataSnapshot): List<DualMomentumTrade> {
        val list = mutableListOf<DualMomentumTrade>()
        for (child in snapshot.children) {
            child.getValue(DualMomentumTrade::class.java)?.let {
                list.add(it.copy(id = child.key ?: ""))
            }
        }
        return list.sortedByDescending { if (it.timestamp > 0) it.timestamp else 0L }
    }

    fun updateDualMomentumSettings(settings: DualMomentumSettings) {
        val activeId = _stockAccountData.value.activeAccountId
        val activeAcc = _stockAccountData.value.savedAccounts[activeId]
        
        // [절대 안전 규칙] 국내/해외 실전 계좌는 어떠한 경우에도 퀀트 스윙 활성화 불가
        if (activeAcc == null || !activeAcc.isMock || !activeAcc.isOverseas) {
            Log.w("TradingApp", "Attempted to update DualMomentum settings on real/non-mock-os account: $activeId. Rejected.")
            return
        }
        
        val path = "users/${_userId.value}/stock/accounts/$activeId/dual_momentum/settings"
        database.getReference(path).setValue(settings)
    }

    fun requestDualMomentumEmergencyClose() {
        val activeId = _stockAccountData.value.activeAccountId
        val activeAcc = _stockAccountData.value.savedAccounts[activeId]
        
        if (activeAcc == null || !activeAcc.isMock || !activeAcc.isOverseas) {
            Log.w("TradingApp", "Emergency close rejected for non-mock-os account: $activeId")
            return
        }
        
        val path = "users/${_userId.value}/stock/accounts/$activeId/dual_momentum/command"
        val cmd = mapOf(
            "action" to "EMERGENCY_CLOSE",
            "timestamp" to System.currentTimeMillis()
        )
        database.getReference(path).setValue(cmd)
    }

}
