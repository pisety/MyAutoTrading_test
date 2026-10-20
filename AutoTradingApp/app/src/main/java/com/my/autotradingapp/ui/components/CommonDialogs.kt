package com.my.autotradingapp.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.my.autotradingapp.data.SurgeTradingConfig
import com.my.autotradingapp.data.SymbolSurgeConfig
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID

@Composable
fun OrderDialog(
    serviceType: String,
    symbol: String,
    command: String,
    displayName: String,
    currentPriceNum: Double,
    availCash: Double,
    isOverseas: Boolean,
    onDismiss: () -> Unit,
    onSendOrder: (isConditional: Boolean, qty: Double, price: Double) -> Unit
) {
    var isConditional by remember { mutableStateOf(false) }
    var orderPrice by remember { mutableStateOf("") }
    var orderQty by remember { mutableStateOf("") }
    
    val targetName = if (serviceType == "crypto") "코인" else "종목"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = if (isConditional) "조건부 예약 주문" else "수동 주문") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    if (!isConditional) {
                        Button(onClick = { isConditional = false }, modifier = Modifier.weight(1f)) { Text("즉시 실행") }
                        OutlinedButton(onClick = { isConditional = true }, modifier = Modifier.weight(1f)) { Text("조건부 예약") }
                    } else {
                        OutlinedButton(onClick = { isConditional = false }, modifier = Modifier.weight(1f)) { Text("즉시 실행") }
                        Button(onClick = { isConditional = true }, modifier = Modifier.weight(1f)) { Text("조건부 예약") }
                    }
                }
                if (isConditional) {
                    Text(text = "현재가가 아래 목표가에 도달하면 자동으로 ${if (command == "BUY") "매수" else "매도"}합니다.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(text = "$displayName ${targetName}을 지정가로 즉시 ${if (command == "BUY") "매수" else "매도"}합니다.")
                }
                
                val currentPriceText = "현재가: ${if (isOverseas) "$" else "₩"}${NumberFormat.getNumberInstance(Locale.US).format(currentPriceNum)}"
                Text(text = currentPriceText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                
                if (command == "BUY" && availCash >= 0) {
                    val parsedOrderPrice = orderPrice.replace(",", "").toDoubleOrNull() ?: currentPriceNum
                    if (parsedOrderPrice > 0) {
                        val maxQty = availCash / parsedOrderPrice
                        val qtyText = if (serviceType == "crypto") {
                            if (maxQty == 0.0) "0" else {
                                val s = String.format(Locale.US, "%.4f", maxQty).trimEnd('0')
                                if (s.endsWith(".")) s.dropLast(1) else s
                            }
                        } else {
                            if (maxQty < 1.0) "0" else kotlin.math.floor(maxQty).toInt().toString()
                        }
                        Text(text = "주문 가능 수량: 약 $qtyText", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
                
                OutlinedTextField(
                    value = orderPrice,
                    onValueChange = { newValue ->
                        val clean = newValue.replace(",", "")
                        if (clean.isEmpty()) {
                            orderPrice = ""
                        } else {
                            if (!isOverseas && serviceType != "crypto") {
                                val filtered = clean.filter { char -> char.isDigit() }.substringBefore('.')
                                val num = filtered.toLongOrNull()
                                orderPrice = if (num != null) java.text.NumberFormat.getNumberInstance(java.util.Locale.US).format(num) else ""
                            } else {
                                val filtered = clean.filter { char -> char.isDigit() || char == '.' }
                                val parts = filtered.split(".")
                                val num = parts[0].toLongOrNull()
                                var formatted = if (num != null) java.text.NumberFormat.getNumberInstance(java.util.Locale.US).format(num) else ""
                                if (parts.size > 1) formatted += "." + parts[1]
                                orderPrice = formatted
                            }
                        }
                    },
                    label = { Text(if (isConditional) "목표 가격 (${if (isOverseas) "$" else "₩"})" else "주문 가격 (${if (isOverseas) "$" else "₩"})") },
                    keyboardOptions = KeyboardOptions(keyboardType = if (!isOverseas && serviceType != "crypto") KeyboardType.Number else KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = orderQty,
                    onValueChange = { 
                        if (serviceType == "crypto") {
                            orderQty = it
                        } else {
                            val filtered = it.filter { char -> char.isDigit() }
                            orderQty = filtered 
                        }
                    },
                    label = { Text(if (serviceType == "crypto") "주문 수량 (소수 가능)" else "주문 수량") },
                    keyboardOptions = KeyboardOptions(keyboardType = if (serviceType == "crypto") KeyboardType.Decimal else KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            val priceVal = orderPrice.replace(",", "").toDoubleOrNull() ?: 0.0
            val qtyVal = orderQty.toDoubleOrNull() ?: 0.0
            val isValid = priceVal > 0.0 && qtyVal > 0.0
            Button(
                enabled = isValid,
                onClick = {
                    val qty = orderQty.toDoubleOrNull() ?: 1.0
                    val price = orderPrice.replace(",", "").toDoubleOrNull() ?: 0.0
                    onSendOrder(isConditional, qty, price)
                }
            ) {
                Text(if (isConditional) "예약 전송" else "주문 전송")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        }
    )
}

@Composable
fun StopLossDialog(
    serviceType: String,
    symbol: String,
    displayName: String,
    currentPriceNum: Double,
    isOverseas: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (price: Double, percent: Int) -> Unit
) {
    var triggerPrice by remember { mutableStateOf("") }
    var volumePercent by remember { mutableStateOf("100") }
    
    val targetName = if (serviceType == "crypto") "코인" else "종목"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("자동 손절 설정") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$displayName ${targetName}이 목표가 이하로 하락 시 전량 또는 일부를 매도합니다.")
                
                val currentPriceText = "현재가: ${if (isOverseas) "$" else "₩"}${NumberFormat.getNumberInstance(Locale.US).format(currentPriceNum)}"
                Text(text = currentPriceText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                OutlinedTextField(
                    value = triggerPrice,
                    onValueChange = { newValue ->
                        val clean = newValue.replace(",", "")
                        if (clean.isEmpty()) {
                            triggerPrice = ""
                        } else {
                            if (!isOverseas && serviceType != "crypto") {
                                val filtered = clean.filter { char -> char.isDigit() }.substringBefore('.')
                                val num = filtered.toLongOrNull()
                                triggerPrice = if (num != null) java.text.NumberFormat.getNumberInstance(java.util.Locale.US).format(num) else ""
                            } else {
                                val filtered = clean.filter { char -> char.isDigit() || char == '.' }
                                val parts = filtered.split(".")
                                val num = parts[0].toLongOrNull()
                                var formatted = if (num != null) java.text.NumberFormat.getNumberInstance(java.util.Locale.US).format(num) else ""
                                if (parts.size > 1) formatted += "." + parts[1]
                                triggerPrice = formatted
                            }
                        }
                    },
                    label = { Text("손절 트리거 가격${if (isOverseas) "" else " (₩)"}") },
                    keyboardOptions = KeyboardOptions(keyboardType = if (!isOverseas && serviceType != "crypto") KeyboardType.Number else KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = volumePercent,
                    onValueChange = { 
                        val filtered = it.filter { char -> char.isDigit() }
                        volumePercent = filtered 
                    },
                    label = { Text("매도 비율 (%)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            val priceVal = triggerPrice.replace(",", "").toDoubleOrNull() ?: 0.0
            val percentVal = volumePercent.toIntOrNull() ?: 0
            val isValid = priceVal > 0.0 && percentVal in 1..100
            Button(
                enabled = isValid,
                onClick = {
                    val price = triggerPrice.replace(",", "").toDoubleOrNull() ?: 0.0
                    val percent = volumePercent.toIntOrNull() ?: 100
                    onConfirm(price, percent)
                }
            ) {
                Text("설정 완료")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}

@Composable
fun SurgeConfigDialog(
    serviceType: String,
    surgeSettings: SurgeTradingConfig,
    watchSymbolsList: List<String>,
    stockNames: Map<String, String>,
    stockPrices: Map<String, Double>,
    isOverseas: Boolean,
    onDismiss: () -> Unit,
    onSave: (SurgeTradingConfig) -> Unit
) {
    var surgeEnabled by remember { mutableStateOf(surgeSettings.enabled) }
    
    val timeWindows = remember { mutableStateMapOf<String, String>() }
    val surgePercents = remember { mutableStateMapOf<String, String>() }
    val buyQuantities = remember { mutableStateMapOf<String, String>() }
    val symConfigs = remember { mutableStateMapOf<String, List<String>>() }
    
    val targetName = if (serviceType == "crypto") "코인" else "종목"
    val unitStr = if (serviceType == "crypto") "개" else "주"

    LaunchedEffect(surgeSettings, watchSymbolsList) {
        watchSymbolsList.forEach { sym ->
            if (!symConfigs.containsKey(sym)) {
                val cfgs = surgeSettings.perSymbolConfig[sym] ?: emptyList()
                val ids = mutableListOf<String>()
                if (cfgs.isEmpty()) {
                    val id = UUID.randomUUID().toString()
                    ids.add(id)
                    timeWindows[id] = "60"
                    surgePercents[id] = "3.0"
                    buyQuantities[id] = ""
                } else {
                    cfgs.forEach { cfg ->
                        val id = UUID.randomUUID().toString()
                        ids.add(id)
                        timeWindows[id] = cfg.timeWindowSeconds.toString()
                        surgePercents[id] = cfg.surgePercentThreshold.toString()
                        buyQuantities[id] = if (cfg.buyQuantity == 0.0) "" else cfg.buyQuantity.toString()
                    }
                }
                symConfigs[sym] = ids
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("급등 $targetName 감시 설정 (${if (surgeSettings.enabled) "ON" else "OFF"})") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("기능 전체 활성화", fontWeight = FontWeight.Bold)
                    Switch(checked = surgeEnabled, onCheckedChange = { surgeEnabled = it })
                }
                
                if (watchSymbolsList.isEmpty()) {
                    Text("관심 ${targetName}이 없습니다.\n먼저 관심 ${targetName}을 추가하고 저장해주세요.", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                } else {
                    var expandedSymbol by remember { mutableStateOf<String?>(null) }
                    
                    watchSymbolsList.forEach { sym ->
                        val name = stockNames[sym] ?: sym
                        val isExpanded = expandedSymbol == sym
                        
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable {
                                expandedSymbol = if (isExpanded) null else sym
                            },
                            elevation = CardDefaults.cardElevation(defaultElevation = if (isExpanded) 4.dp else 1.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(name, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                        val currentPrice = stockPrices[sym] ?: 0.0
                                        if (currentPrice > 0) {
                                            val priceStr = if (isOverseas) "$${String.format(Locale.US, "%.2f", currentPrice)}" else "₩${String.format(Locale.US, "%,.0f", currentPrice)}"
                                            Text(priceStr, fontSize = 14.sp, color = Color.DarkGray)
                                        } else {
                                            Text("로딩중...", fontSize = 14.sp, color = Color.Gray)
                                        }
                                    }
                                    Text(if (isExpanded) "▼" else "▶", color = Color.Gray, fontSize = 12.sp)
                                }
                                
                                if (isExpanded) {
                                    symConfigs[sym]?.forEach { id ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                            OutlinedTextField(
                                                value = timeWindows[id] ?: "60",
                                                onValueChange = { timeWindows[id] = it },
                                                label = { Text("감시 (초)") },
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                modifier = Modifier.weight(1f)
                                            )
                                            OutlinedTextField(
                                                value = surgePercents[id] ?: "3.0",
                                                onValueChange = { surgePercents[id] = it },
                                                label = { Text("급등 (%)") },
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom=8.dp)) {
                                            OutlinedTextField(
                                                value = buyQuantities[id] ?: "0",
                                                onValueChange = { buyQuantities[id] = it },
                                                label = { Text("매수 수량 (0은 제외)") },
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                                modifier = Modifier.weight(1f)
                                            )
                                            IconButton(onClick = { 
                                                val ids = symConfigs[sym]?.toMutableList() ?: mutableListOf()
                                                ids.remove(id)
                                                symConfigs[sym] = ids
                                                timeWindows.remove(id)
                                                surgePercents.remove(id)
                                                buyQuantities.remove(id)
                                            }) { Text("❌") }
                                        }
                                    }
                                    TextButton(onClick = {
                                        val id = UUID.randomUUID().toString()
                                        val ids = symConfigs[sym]?.toMutableList() ?: mutableListOf()
                                        ids.add(id)
                                        symConfigs[sym] = ids
                                        timeWindows[id] = "60"
                                        surgePercents[id] = "3.0"
                                        buyQuantities[id] = ""
                                    }) { Text("+ 설정 추가") }
                                } else {
                                    val activeIds = symConfigs[sym]?.filter { (buyQuantities[it]?.toDoubleOrNull() ?: 0.0) > 0.0 } ?: emptyList()
                                    if (activeIds.isNotEmpty()) {
                                        if (surgeEnabled) {
                                            activeIds.forEach { id ->
                                                Text("설정됨: ${timeWindows[id]}초 / ${surgePercents[id]}% / ${buyQuantities[id]}$unitStr", style = MaterialTheme.typography.bodySmall, color = Color.DarkGray)
                                            }
                                        } else {
                                            activeIds.forEach { id ->
                                                Text("대기중 (OFF): ${timeWindows[id]}초 / ${surgePercents[id]}% / ${buyQuantities[id]}$unitStr", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                            }
                                        }
                                    } else {
                                        Text("감시 제외 (수량 0)", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val isAllValid = watchSymbolsList.all { sym ->
                val ids = symConfigs[sym] ?: emptyList()
                if (ids.isEmpty()) true
                else ids.all { id ->
                    val qStr = buyQuantities[id]
                    if (qStr.isNullOrBlank()) true
                    else (qStr.toDoubleOrNull() ?: 0.0) > 0.0
                }
            }
            Button(
                enabled = isAllValid,
                onClick = {
                val perSymbolMap = mutableMapOf<String, List<SymbolSurgeConfig>>()
                watchSymbolsList.forEach { sym ->
                    val list = mutableListOf<SymbolSurgeConfig>()
                    symConfigs[sym]?.forEach { id ->
                        val qty = buyQuantities[id]?.toDoubleOrNull() ?: 0.0
                        if (qty > 0.0 || symConfigs[sym]?.size == 1) {
                            list.add(SymbolSurgeConfig(
                                timeWindowSeconds = timeWindows[id]?.toIntOrNull() ?: 60,
                                surgePercentThreshold = surgePercents[id]?.toFloatOrNull() ?: 3.0f,
                                buyQuantity = qty
                            ))
                        }
                    }
                    if (list.isNotEmpty()) perSymbolMap[sym] = list
                }
                val config = SurgeTradingConfig(
                    enabled = surgeEnabled,
                    allowExtendedHours = true,
                    perSymbolConfig = perSymbolMap
                )
                onSave(config)
            }) {
                Text("저장")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}
