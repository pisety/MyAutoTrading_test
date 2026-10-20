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
import com.my.autotradingapp.ui.components.StockItem
import com.my.autotradingapp.ui.components.WatchlistManagementDialog
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CryptoScreen(
    viewModel: TradingViewModel,
    onSettingsClick: () -> Unit = {}
) {
    val status by viewModel.cryptoStatus.collectAsState()
    val settings by viewModel.cryptoSettings.collectAsState()
    var isSurgeStatusExpanded by remember { mutableStateOf(true) }
    val orderStatus by viewModel.manualOrderStatus.collectAsState()
    val context = LocalContext.current
    var showHoldings by remember { mutableStateOf(true) }
    var showWatchList by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    
    LaunchedEffect(orderStatus) {
        orderStatus?.let {
            if (it.status == "FAILED") {
                Toast.makeText(context, "주문 실패: ${it.errorMessage}", Toast.LENGTH_LONG).show()
            } else if (it.status == "SUCCESS") {
                Toast.makeText(context, "주문 접수 성공!", Toast.LENGTH_SHORT).show()
            }
            viewModel.resetManualOrderStatus()
            viewModel.clearManualOrderFirebaseStatus("crypto")
        }
    }
    
    val accountData by viewModel.cryptoAccountData.collectAsState()
    val activeAcc = accountData.savedAccounts[accountData.activeAccountId]
    val brokerName = activeAcc?.broker ?: "설정안됨"
    
    val suggestions = status.stockNames
    var showWatchlistDialog by remember { mutableStateOf(false) }

    if (showWatchlistDialog) {
        WatchlistManagementDialog(
            initialSymbols = settings.watchSymbols.entries.sortedBy { it.value.order }.map { (code, _) ->
                val cleanCode = code.removePrefix("KRW-")
                val name = status.stockNames[code] ?: suggestions[code] // Look up by full code (e.g. KRW-BTC)
                if (name != null) "$name ($cleanCode)" else cleanCode
            },
            isOverseas = false,
            isCrypto = true,
            suggestions = suggestions,
            onDismiss = { showWatchlistDialog = false },
            onSave = { managedList, newNames ->
                viewModel.updateWatchSymbols(managedList, "crypto")
                newNames.forEach { (sym, name) -> viewModel.updateStockName("KRW-$sym", name, "crypto") }
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
            serviceType = "crypto",
            symbol = symbol,
            command = command,
            displayName = displayName,
            currentPriceNum = currentPriceNum,
            availCash = status.availCash,
            isOverseas = false,
            onDismiss = { showOrderDialog = null },
            onSendOrder = { isConditional, qty, price ->
                if (isConditional) {
                    viewModel.sendConditionalOrder(symbol, command, qty, price, "crypto")
                    Toast.makeText(context, "조건부 예약 전송 완료", Toast.LENGTH_SHORT).show()
                } else {
                    viewModel.sendManualOrder(command, symbol, qty, price, "crypto")
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
            serviceType = "crypto",
            symbol = symbol,
            displayName = displayName,
            currentPriceNum = currentPriceNum,
            isOverseas = false,
            onDismiss = { showStopLossDialog = null },
            onConfirm = { price, percent ->
                viewModel.sendStopLossOrder(symbol, price, percent, "crypto")
                Toast.makeText(context, "자동 손절 설정 완료", Toast.LENGTH_SHORT).show()
                showStopLossDialog = null
            }
        )
    }

    val watchSymbolsList = settings.watchSymbols.entries.sortedBy { it.value.order }.map { it.key }

    var showSurgeDialog by remember { mutableStateOf(false) }

    if (showSurgeDialog) {
        com.my.autotradingapp.ui.components.SurgeConfigDialog(
            serviceType = "crypto",
            surgeSettings = settings.surgeTrading,
            watchSymbolsList = watchSymbolsList,
            stockNames = status.stockNames,
            stockPrices = status.stockPrices,
            isOverseas = false,
            onDismiss = { showSurgeDialog = false },
            onSave = { config ->
                viewModel.updateSurgeTradingSettings(config, "crypto")
                Toast.makeText(context, "급등 코인 감시 설정 완료", Toast.LENGTH_SHORT).show()
                showSurgeDialog = false
            }
        )
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
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
                    Text(text = if (activeAcc == null) "[계좌 미설정]" else "[${brokerName}]", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
                    
                    Text(text = "총 평가자산", style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = "₩${NumberFormat.getNumberInstance(Locale.US).format(status.totalBalance)}",
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
                        Text(text = "보유 현금: ", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "₩${NumberFormat.getNumberInstance(Locale.US).format(status.availCash)}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }

                    var lastKnownTime by remember { mutableStateOf("") }
                    if (status.lastUpdated.isNotBlank()) {
                        lastKnownTime = status.lastUpdated.take(19).replace("T", " ")
                    }
                    
                    Text(
                        text = if (lastKnownTime.isNotEmpty()) "최종 갱신: $lastKnownTime" else " ",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showHoldings = !showHoldings }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("보유 코인 현황", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(if (showHoldings) "▲" else "▼", color = Color.Gray)
            }
        }

        if (showHoldings) {
            val visibleHoldings = status.holdingStocks.filter { it.value.currentPrice > 0.0 && (it.value.currentPrice * it.value.quantity) > 500.0 }
            if (visibleHoldings.isEmpty()) {
                item {
                    val emptyMsg = if (activeAcc == null) {
                        "연동된 코인 거래소 계좌가 없습니다. '⚙ 계좌 설정'에서 계좌를 등록해 주세요."
                    } else {
                        "보유 중인 코인이 없습니다."
                    }
                    Text(emptyMsg, color = Color.Gray, modifier = Modifier.padding(vertical = 8.dp))
                }
            } else {
                items(visibleHoldings.toList()) { (symbol, stock) ->
                    HoldingStockItem(
                        symbol = symbol, 
                        stock = stock, 
                        isOverseas = false, 
                        isCrypto = true, 
                        onStopLossClick = { showStopLossDialog = symbol },
                        onBuyClick = { showOrderDialog = symbol to "BUY" },
                        onSellClick = { showOrderDialog = symbol to "SELL" }
                    )
                }
            }
        }

        item {
            Button(
                onClick = { showWatchlistDialog = true },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            ) {
                Text("관심 종목 관리")
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showWatchList = !showWatchList }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("실시간 시세 및 주문", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(if (showWatchList) "▲" else "▼", color = Color.Gray)
            }
        }

        if (showWatchList) {
            if (watchSymbolsList.isEmpty()) {
                item {
                    Text("관심 코인이 없습니다.", color = Color.Gray)
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
                        isOverseas = false,
                        onBuyClick = { showOrderDialog = symbol to "BUY" }
                    )
                }
            }
        }

        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "⚡ 자동매매 엔진 가동", fontWeight = FontWeight.Bold)
                Switch(
                    checked = settings.isAutoTrading,
                    onCheckedChange = { viewModel.toggleAutoTrading(it, "crypto") }
                )
            }
            if (settings.isAutoTrading) {
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { showSurgeDialog = true },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("🔥 급등 코인 감시 설정 (${if (settings.surgeTrading.enabled) "ON" else "OFF"})", fontWeight = FontWeight.Bold)
                }
                
                if (settings.surgeTrading.enabled) {
                    val activeSurge = settings.surgeTrading.perSymbolConfig.filter { it.value.any { cfg -> cfg.buyQuantity > 0 } }
                    if (activeSurge.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { isSurgeStatusExpanded = !isSurgeStatusExpanded }.padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("급등 코인 감시 현황", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text(if (isSurgeStatusExpanded) "접기 ▲" else "펼치기 ▼", fontSize = 12.sp, color = Color.Gray)
                        }
                        if (isSurgeStatusExpanded) {
                            activeSurge.forEach { (sym, cfgs) ->
                                val name = status.stockNames[sym] ?: status.holdingStocks[sym]?.name?.ifEmpty { null } ?: sym
                                val currentPrice = status.stockPrices[sym] ?: 0.0
                                val priceStr = if (currentPrice > 0) {
                                    "₩${String.format(java.util.Locale.US, "%,.0f", currentPrice)}"
                                } else "로딩중..."

                                cfgs.filter { it.buyQuantity > 0 }.forEach { cfg ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("$name ($priceStr)")
                                        Text("${cfg.timeWindowSeconds}초 / ${cfg.surgePercentThreshold}% / ${cfg.buyQuantity}개", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(100.dp)) // padding for bottom nav
        }
    }
}
