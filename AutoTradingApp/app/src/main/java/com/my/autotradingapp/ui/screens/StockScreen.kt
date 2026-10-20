package com.my.autotradingapp.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.my.autotradingapp.data.SurgeTradingConfig
import com.my.autotradingapp.data.SymbolSurgeConfig
import com.my.autotradingapp.ui.TradingViewModel
import com.my.autotradingapp.ui.components.HoldingStockItem
import com.my.autotradingapp.ui.components.ReservationManagementDialog
import com.my.autotradingapp.ui.components.StockItem
import com.my.autotradingapp.ui.components.WatchlistManagementDialog
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockScreen(
    viewModel: TradingViewModel,
    onSettingsClick: () -> Unit = {}
) {
    val status by viewModel.stockStatus.collectAsState()
    val settings by viewModel.stockSettings.collectAsState()
    var isSurgeStatusExpanded by remember { mutableStateOf(true) }
    val orderStatus by viewModel.manualOrderStatus.collectAsState()
    val context = LocalContext.current
    var selectedSubTab by remember { mutableIntStateOf(0) }
    val subTabs = listOf("자산/보유", "관심/주문", "자동매매")
    val listState = rememberLazyListState()
    
    LaunchedEffect(orderStatus) {
        orderStatus?.let {
            if (it.status == "FAILED") {
                Toast.makeText(context, "주문 실패: ${it.errorMessage}", Toast.LENGTH_LONG).show()
            } else if (it.status == "SUCCESS") {
                Toast.makeText(context, "주문 접수 성공!", Toast.LENGTH_SHORT).show()
            }
            viewModel.resetManualOrderStatus()
            viewModel.clearManualOrderFirebaseStatus("stock")
        }
    }
    
    val accountData by viewModel.stockAccountData.collectAsState()
    val activeAcc = accountData.savedAccounts[accountData.activeAccountId]
    val isOverseas = activeAcc?.isOverseas ?: true
    val isMock = activeAcc?.isMock ?: true
    val brokerName = activeAcc?.broker ?: "설정안됨"
    val isKisRealDomestic = brokerName in listOf("한국투자", "한국투자증권", "메리츠", "메리츠증권") && !isMock && !isOverseas
    var showReservationDialog by remember { mutableStateOf(false) }
    val reservationOrders by viewModel.reservationOrders.collectAsState()
    val suggestions = status.stockNames

    if (showReservationDialog) {
        ReservationManagementDialog(
            reservationOrders = reservationOrders,
            suggestions = suggestions,
            stockPrices = status.stockPrices,
            onDismiss = { showReservationDialog = false },
            onAddOrder = { sym, name, qty, enabled, thresh ->
                viewModel.sendReservationOrder(sym, name, qty, enabled, thresh)
                Toast.makeText(context, "장전시간외 예약주문 설정 완료", Toast.LENGTH_SHORT).show()
            },
            onCancelOrder = { id ->
                viewModel.cancelReservationOrder(id)
                Toast.makeText(context, "주문 취소 요청을 전송했습니다.", Toast.LENGTH_SHORT).show()
            },
            onToggleAutoCancel = { id, cur ->
                viewModel.toggleReservationAutoCancel(id, cur)
                Toast.makeText(context, "자동취소 설정을 변경했습니다.", Toast.LENGTH_SHORT).show()
            },
            onDeleteOrder = { id ->
                viewModel.deleteReservationOrder(id)
                Toast.makeText(context, "예약주문 내역을 삭제했습니다.", Toast.LENGTH_SHORT).show()
            },
            onRestartOrder = { id ->
                viewModel.restartReservationOrder(id)
                Toast.makeText(context, "예약주문을 다시 활성화했습니다.", Toast.LENGTH_SHORT).show()
            }
        )
    }
    
    var showWatchlistDialog by remember { mutableStateOf(false) }

    if (showWatchlistDialog) {
        val filteredWatchSymbols = settings.watchSymbols.filterKeys { sym -> if (isOverseas) !sym.any { it.isDigit() } else sym.any { it.isDigit() } }
        WatchlistManagementDialog(
            initialSymbols = filteredWatchSymbols.entries.sortedBy { it.value.order }.map { (code, _) ->
                val name = status.stockNames[code] ?: suggestions[code]
                if (name != null) "$name ($code)" else code
            },
            isOverseas = isOverseas,
            isCrypto = false,
            suggestions = suggestions,
            onDismiss = { showWatchlistDialog = false },
            onSave = { managedList, newNames ->
                viewModel.updateWatchSymbols(managedList, "stock", isOverseas)
                newNames.forEach { (sym, name) -> viewModel.updateStockName(sym, name, "stock") }
                showWatchlistDialog = false
            }
        )
    }

    val authError = status.authError
    LaunchedEffect(authError) {
        if (authError.isNotEmpty()) {
            Toast.makeText(context, authError, Toast.LENGTH_LONG).show()
        }
    }

    var showOrderDialog by remember { mutableStateOf<Pair<String, String>?>(null) }

    if (showOrderDialog != null) {
        val (symbol, command) = showOrderDialog!!
        val displayName = status.stockNames[symbol] ?: status.holdingStocks[symbol]?.name?.ifEmpty { null } ?: symbol
        val currentPriceNum = status.stockPrices[symbol] ?: status.holdingStocks[symbol]?.currentPrice ?: 0.0

        com.my.autotradingapp.ui.components.OrderDialog(
            serviceType = "stock",
            symbol = symbol,
            command = command,
            displayName = displayName,
            currentPriceNum = currentPriceNum,
            availCash = status.availCash,
            isOverseas = isOverseas,
            onDismiss = { showOrderDialog = null },
            onSendOrder = { isConditional, qty, price ->
                if (isConditional) {
                    viewModel.sendConditionalOrder(symbol, command, qty, price, "stock")
                    Toast.makeText(context, "조건부 예약 전송 완료", Toast.LENGTH_SHORT).show()
                } else {
                    viewModel.sendManualOrder(command, symbol, qty, price, "stock")
                }
                showOrderDialog = null
            }
        )
    }

    var showStopLossDialog by remember { mutableStateOf<String?>(null) }

    if (showStopLossDialog != null) {
        val symbol = showStopLossDialog!!
        val displayName = status.stockNames[symbol] ?: status.holdingStocks[symbol]?.name?.ifEmpty { null } ?: symbol
        val currentPriceNum = status.stockPrices[symbol] ?: status.holdingStocks[symbol]?.currentPrice ?: 0.0

        com.my.autotradingapp.ui.components.StopLossDialog(
            serviceType = "stock",
            symbol = symbol,
            displayName = displayName,
            currentPriceNum = currentPriceNum,
            isOverseas = isOverseas,
            onDismiss = { showStopLossDialog = null },
            onConfirm = { price, percent ->
                viewModel.sendStopLossOrder(symbol, price, percent, "stock")
                Toast.makeText(context, "자동 손절 설정 완료", Toast.LENGTH_SHORT).show()
                showStopLossDialog = null
            }
        )
    }

    val filteredWatchSymbols = settings.watchSymbols.filterKeys { sym -> if (isOverseas) !sym.any { it.isDigit() } else sym.any { it.isDigit() } }
    val watchSymbolsList = filteredWatchSymbols.entries.sortedBy { it.value.order }.map { it.key }

    var showSurgeDialog by remember { mutableStateOf(false) }

    if (showSurgeDialog) {
        com.my.autotradingapp.ui.components.SurgeConfigDialog(
            serviceType = "stock",
            surgeSettings = settings.surgeTrading,
            watchSymbolsList = watchSymbolsList,
            stockNames = status.stockNames,
            stockPrices = status.stockPrices,
            isOverseas = isOverseas,
            onDismiss = { showSurgeDialog = false },
            onSave = { config ->
                viewModel.updateSurgeTradingSettings(config, "stock")
                Toast.makeText(context, "급등주 감시 설정 완료", Toast.LENGTH_SHORT).show()
                showSurgeDialog = false
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = selectedSubTab) {
            subTabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedSubTab == index,
                    onClick = { selectedSubTab = index },
                    text = { 
                        Text(
                            title, 
                            fontWeight = if (selectedSubTab == index) FontWeight.Bold else FontWeight.Normal,
                            color = if (selectedSubTab == index) MaterialTheme.colorScheme.primary else Color.Gray
                        ) 
                    }
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(modifier = Modifier.height(8.dp)) }

            when (selectedSubTab) {
                0 -> {
                    // 자산/보유 탭
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                val statusText = if (activeAcc == null) "[계좌 미설정]" else "[${brokerName} | ${if (isMock) "모의" else "실전"} | ${if (isOverseas) "해외주식" else "국내주식"}]"
                                Text(text = statusText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
                                
                                Text(text = "총 평가자산", style = MaterialTheme.typography.labelLarge)
                                Text(
                                    text = "${if (isOverseas) "$" else "₩"}${NumberFormat.getNumberInstance(Locale.US).format(status.totalBalance)}",
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Text(text = "수익률: ", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = "${String.format(Locale.US, "%.2f", status.currentYield)}%",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (status.currentYield >= 0) Color(0xFFD32F2F) else Color(0xFF1976D2)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(text = "예수금: ", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = "${if (isOverseas) "$" else "₩"}${NumberFormat.getNumberInstance(Locale.US).format(status.availCash)}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }

                                var lastKnownTime by remember { mutableStateOf("") }
                                if (status.lastUpdated.isNotBlank()) {
                                    lastKnownTime = status.lastUpdated.take(19).replace("T", " ") // temporary formatting
                                }
                                
                                Text(
                                    text = if (lastKnownTime.isNotEmpty()) "최종 갱신: $lastKnownTime" else " ",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }

                    item {
                        Text("보유 종목 현황", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
                    }

                    val threshold = if (isOverseas) 0.5 else 500.0
                    val visibleHoldings = status.holdingStocks.filter { it.value.currentPrice > 0.0 && (it.value.currentPrice * it.value.quantity) > threshold }
                    if (visibleHoldings.isEmpty()) {
                        item {
                            val emptyMsg = if (activeAcc == null) {
                                "연동된 증권 계좌가 없습니다. '⚙ 계좌 설정'에서 계좌를 등록해 주세요."
                            } else {
                                "보유 중인 종목이 없습니다."
                            }
                            Text(emptyMsg, color = Color.Gray, modifier = Modifier.padding(vertical = 8.dp))
                        }
                    } else {
                        items(visibleHoldings.toList()) { (symbol, stock) ->
                            HoldingStockItem(
                                symbol = symbol, 
                                stock = stock, 
                                isOverseas = isOverseas, 
                                isCrypto = false, 
                                onStopLossClick = { showStopLossDialog = symbol },
                                onBuyClick = { showOrderDialog = symbol to "BUY" },
                                onSellClick = { showOrderDialog = symbol to "SELL" }
                            )
                        }
                    }
                }
                1 -> {
                    // 관심/주문 탭
                    item {
                        Button(
                            onClick = { showWatchlistDialog = true },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        ) {
                            Text("관심 종목 관리")
                        }
                        Text("실시간 시세 및 주문", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                    }

                    if (watchSymbolsList.isEmpty()) {
                        item {
                            Text("관심 종목이 없습니다.", color = Color.Gray)
                        }
                    } else {
                        items(watchSymbolsList) { symbol ->
                            val price = status.stockPrices[symbol] ?: 0.0
                            val changeRate = status.stockPricesChange[symbol]
                            StockItem(
                                symbol = symbol,
                                name = status.stockNames[symbol] ?: symbol,
                                price = price,
                                changeRate = changeRate,
                                isOverseas = isOverseas,
                                onBuyClick = { showOrderDialog = symbol to "BUY" }
                            )
                        }
                    }
                }
                2 -> {
                    // 자동매매 탭
                    item {
                        if (isKisRealDomestic) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("🕒 장전시간외 주문예약 관리", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
                                        Text("${reservationOrders.size}건 등록중", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                    }
                                    Button(
                                        onClick = { showReservationDialog = true },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.tertiary,
                                            contentColor = MaterialTheme.colorScheme.onTertiary
                                        ),
                                        modifier = Modifier.fillMaxWidth().height(48.dp)
                                    ) {
                                        Text("🚀 장전시간외 매수 주문예약 설정", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(text = "⚡ 자동매매 엔진 가동", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Switch(
                                        checked = settings.isAutoTrading,
                                        onCheckedChange = { viewModel.toggleAutoTrading(it, "stock") }
                                    )
                                }
                                
                                if (settings.isAutoTrading) {
                                    Spacer(modifier = Modifier.height(16.dp))
                                    OutlinedButton(
                                        onClick = { showSurgeDialog = true },
                                        modifier = Modifier.fillMaxWidth().height(48.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                                    ) {
                                        Text("🔥 급등주 단타 감시 설정 (${if (settings.surgeTrading.enabled) "ON" else "OFF"})", fontWeight = FontWeight.Bold)
                                    }
                                    
                                    if (settings.surgeTrading.enabled) {
                                        val activeSurge = settings.surgeTrading.perSymbolConfig.filter { it.value.any { cfg -> cfg.buyQuantity > 0 } }
                                        if (activeSurge.isNotEmpty()) {
                                            Spacer(modifier = Modifier.height(16.dp))
                                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                            Text("급등주 감시 현황", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
                                            
                                            activeSurge.forEach { (sym, cfgs) ->
                                                val name = status.stockNames[sym] ?: status.holdingStocks[sym]?.name?.ifEmpty { null } ?: sym
                                                val currentPrice = status.stockPrices[sym] ?: 0.0
                                                val priceStr = if (currentPrice > 0) {
                                                    if (isOverseas) "$${String.format(java.util.Locale.US, "%.2f", currentPrice)}" else "₩${String.format(java.util.Locale.US, "%,.0f", currentPrice)}"
                                                } else "로딩중..."

                                                cfgs.filter { it.buyQuantity > 0 }.forEach { cfg ->
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Text("$name ($priceStr)")
                                                        Text("${cfg.timeWindowSeconds}초 / ${cfg.surgePercentThreshold}% / ${cfg.buyQuantity}주", fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(100.dp)) }
        }
    }
}
