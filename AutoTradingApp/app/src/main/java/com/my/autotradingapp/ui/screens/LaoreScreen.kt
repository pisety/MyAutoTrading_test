package com.my.autotradingapp.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.my.autotradingapp.ui.TradingViewModel
import com.my.autotradingapp.ui.screens.setup.CryptoAccountSetupScreen
import com.my.autotradingapp.ui.screens.setup.LaoreSetupDialog
import com.my.autotradingapp.ui.screens.setup.StockAccountSetupScreen

@Composable
fun LaoreScreen(viewModel: TradingViewModel) {
    var showAccountSetup by remember { mutableStateOf(false) }
    var showSetupDialog by remember { mutableStateOf(false) }
    var selectedServiceType by remember { mutableStateOf("stock") } // "stock" or "crypto"
    var deleteCandidate by remember { mutableStateOf<String?>(null) }
    
    if (showAccountSetup) {
        if (selectedServiceType == "stock") {
            StockAccountSetupScreen(onDismiss = { showAccountSetup = false }, viewModel = viewModel)
        } else {
            CryptoAccountSetupScreen(onDismiss = { showAccountSetup = false }, viewModel = viewModel)
        }
        return
    }

    val settingsMap by if (selectedServiceType == "stock") viewModel.laoreSettings.collectAsState() else viewModel.cryptoLaoreSettings.collectAsState()
    val statusMap by if (selectedServiceType == "stock") viewModel.laoreStatus.collectAsState() else viewModel.cryptoLaoreStatus.collectAsState()
    
    val suggestions = if (selectedServiceType == "stock") {
        viewModel.stockStatus.collectAsState().value.stockNames
    } else {
        viewModel.cryptoStatus.collectAsState().value.stockNames
    }

    val activeAcc = if (selectedServiceType == "stock") {
        viewModel.stockAccountData.collectAsState().value.savedAccounts[viewModel.stockAccountData.collectAsState().value.activeAccountId]
    } else {
        viewModel.cryptoAccountData.collectAsState().value.savedAccounts[viewModel.cryptoAccountData.collectAsState().value.activeAccountId]
    }
    val isOverseas = if (selectedServiceType == "stock") activeAcc?.isOverseas ?: true else false

    val fullStatus = if (selectedServiceType == "stock") {
        viewModel.stockStatus.collectAsState().value
    } else {
        viewModel.cryptoStatus.collectAsState().value
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        contentPadding = PaddingValues(bottom = 100.dp) // padded for bottom nav
    ) {
        item {
            // Settings button row
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(onClick = { showAccountSetup = true }) {
                    Text("앱계좌 설정")
                }
            }
        }
        
        item {
            TabRow(selectedTabIndex = if (selectedServiceType == "stock") 0 else 1) {
                Tab(selected = selectedServiceType == "stock", onClick = { selectedServiceType = "stock" }, text = { Text("주식") })
                Tab(selected = selectedServiceType == "crypto", onClick = { selectedServiceType = "crypto" }, text = { Text("코인") })
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
        
        item {
            Text("무한매수법 대시보드", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        if (settingsMap.isEmpty()) {
            item {
                Text("현재 설정된 무한매수 세트가 없습니다.", color = Color.Gray, modifier = Modifier.padding(vertical = 32.dp))
            }
        } else {
            val sortedSettings = settingsMap.toList().sortedWith(compareBy({ it.first.substringAfter("_") }, { it.first.substringBefore("_").toIntOrNull() ?: 1 }))
            items(sortedSettings) { (compositeKey, setting) ->
                val parts = compositeKey.split("_", limit = 2)
                val setId = if (parts.size == 2) parts[0] else "1"
                val symbol = if (parts.size == 2) parts[1] else compositeKey
                
                val status = statusMap[compositeKey] ?: com.my.autotradingapp.data.LaoreStatus()
                val name = suggestions[symbol] ?: symbol
                
                val holding = fullStatus.holdingStocks[symbol]
                val currentPrice = fullStatus.stockPrices[symbol] ?: (holding?.currentPrice ?: 0.0)
                val effectiveAvgPrice = if (holding != null && holding.avgPrice > 0.0) holding.avgPrice else status.averagePrice
                val effectiveQty = if (holding != null && holding.avgPrice > 0.0) holding.quantity else status.holdingQuantity
                val currentYield = if (currentPrice > 0 && effectiveAvgPrice > 0) {
                    (currentPrice - effectiveAvgPrice) / effectiveAvgPrice * 100
                } else {
                    0.0
                }
                
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text(text = "[세트 $setId] ${name} (${symbol})", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                Text(text = "상태: ${if (setting.enabled) "활성화" else "중지됨"}", color = if (setting.enabled) Color(0xFF1976D2) else Color.Gray)
                            }
                            Switch(
                                checked = setting.enabled,
                                onCheckedChange = { isChecked ->
                                    viewModel.saveLaoreSetting(compositeKey, setting.copy(enabled = isChecked), selectedServiceType)
                                }
                            )
                        }
                        
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("모드: ${status.currentMode}", style = MaterialTheme.typography.bodyMedium)
                            Text("진행률: ${status.currentRound} / ${setting.splitCount}", style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            val avgPriceText = if (isOverseas) {
                                "$${String.format(java.util.Locale.US, "%,.2f", effectiveAvgPrice)}"
                            } else {
                                "₩${String.format(java.util.Locale.US, "%,.0f", effectiveAvgPrice)}"
                            }
                            val curPriceText = if (isOverseas) {
                                "$${String.format(java.util.Locale.US, "%,.2f", currentPrice)}"
                            } else {
                                "₩${String.format(java.util.Locale.US, "%,.0f", currentPrice)}"
                            }
                            Text("평단가: $avgPriceText", style = MaterialTheme.typography.bodyMedium)
                            Text("현재가: $curPriceText", style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            val qtyText = if (selectedServiceType == "stock") {
                                "${String.format(java.util.Locale.US, "%,.0f", effectiveQty)}주"
                            } else {
                                String.format(java.util.Locale.US, "%,.4f", effectiveQty)
                            }
                            Text("보유수량: $qtyText", style = MaterialTheme.typography.bodyMedium)
                            Text("투자자금: ${if(isOverseas) "$" else "₩"}${String.format(java.util.Locale.US, "%,.0f", setting.totalBudget)}", style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("목표수익률: ${setting.targetYieldPercent}%", style = MaterialTheme.typography.bodyMedium)
                            val yieldColor = if (currentYield > 0) Color.Red else if (currentYield < 0) Color.Blue else Color.Black
                            val sign = if (currentYield > 0) "+" else ""
                            Text("현재수익률: $sign${String.format(java.util.Locale.US, "%.2f", currentYield)}%", 
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = yieldColor)
                        }
                        
                        if (status.statusMessage.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = "최근 메시지: ${status.statusMessage}", style = MaterialTheme.typography.bodySmall, color = Color.DarkGray)
                        }
                        
                        Row(modifier = Modifier.fillMaxWidth().padding(top=8.dp), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { deleteCandidate = compositeKey }) {
                                Text("삭제/종료", color = Color.Red)
                            }
                        }
                    }
                }
            }
        }
        
        item {
            Button(
                onClick = { showSetupDialog = true },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            ) {
                Text("+ 새 무한매수 세트 추가")
            }
        }
    }

    if (showSetupDialog) {
        LaoreSetupDialog(
            isOverseas = isOverseas,
            suggestions = suggestions,
            serviceType = selectedServiceType,
            onDismiss = { showSetupDialog = false },
            onSave = { symbol, name, budget, split, yield, intervalHours ->
                if (name.isNotEmpty() && name != symbol) {
                    viewModel.updateStockName(symbol, name, selectedServiceType)
                }
                
                val existingSets = settingsMap.keys.filter { it.endsWith("_$symbol") }
                var maxSetId = 0
                for (k in existingSets) {
                    val id = k.substringBefore("_").toIntOrNull() ?: 1
                    if (id > maxSetId) maxSetId = id
                }
                val nextSetId = maxSetId + 1
                val newCompositeKey = "${nextSetId}_${symbol}"

                viewModel.saveLaoreSetting(newCompositeKey, com.my.autotradingapp.data.LaoreSettings(
                    enabled = true,
                    totalBudget = budget,
                    splitCount = split,
                    targetYieldPercent = yield,
                    intervalHours = intervalHours
                ), selectedServiceType)
                showSetupDialog = false
            }
        )
    }

    if (deleteCandidate != null) {
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("무한매수 세트 삭제") },
            text = { Text("정말 삭제하시겠습니까?\n삭제 시 해당 세트의 매수가 중지되고 리스트에서 영구히 삭제됩니다.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.removeLaoreSetting(deleteCandidate!!, selectedServiceType)
                        deleteCandidate = null
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text("삭제")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) {
                    Text("취소")
                }
            }
        )
    }
}
