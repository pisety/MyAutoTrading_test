package com.my.autotradingapp.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.my.autotradingapp.data.ReservationOrder
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReservationManagementDialog(
    reservationOrders: Map<String, ReservationOrder>,
    suggestions: Map<String, String>,
    stockPrices: Map<String, Double> = emptyMap(),
    onDismiss: () -> Unit,
    onAddOrder: (symbol: String, name: String, quantity: Double, autoCancelEnabled: Boolean, threshold: Float) -> Unit,
    onCancelOrder: (orderId: String) -> Unit,
    onToggleAutoCancel: (orderId: String, currentEnabled: Boolean) -> Unit,
    onDeleteOrder: (orderId: String) -> Unit,
    onRestartOrder: (orderId: String) -> Unit
) {
    var symbolInput by remember { mutableStateOf("") }
    var nameInput by remember { mutableStateOf("") }
    var quantityInput by remember { mutableStateOf("") }
    var autoCancelEnabled by remember { mutableStateOf(false) } // 기본 OFF
    var autoCancelThreshold by remember { mutableStateOf("1.0") } // 기본 +1.0%
    var showSuggestions by remember { mutableStateOf(false) }
    var selectedTabIndex by remember { mutableStateOf(if (reservationOrders.isNotEmpty()) 1 else 0) }
    var orderToDelete by remember { mutableStateOf<Pair<String, ReservationOrder>?>(null) }

    val context = androidx.compose.ui.platform.LocalContext.current
    var apiSuggestions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    LaunchedEffect(symbolInput) {
        if (symbolInput.isNotBlank()) {
            kotlinx.coroutines.delay(200)
            try {
                val results = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val list = mutableListOf<Pair<String, String>>()
                    val query = symbolInput.lowercase()

                    // 1. 기존 suggestions (보유/관심종목)에서 검색
                    suggestions.forEach { (code, name) ->
                        if (code.lowercase().contains(query) || name.lowercase().contains(query)) {
                            list.add(code to name)
                        }
                    }

                    // 2. krx_stocks.json 에셋 전체 종목에서 검색
                    try {
                        val jsonString = context.assets.open("krx_stocks.json").bufferedReader().use { it.readText() }
                        val jsonArray = org.json.JSONArray(jsonString)
                        for (i in 0 until jsonArray.length()) {
                            val item = jsonArray.optJSONObject(i) ?: continue
                            val name = item.optString("name", "")
                            val code = item.optString("code", "")
                            if (name.lowercase().contains(query) || code.lowercase().contains(query)) {
                                if (!list.any { it.first == code }) {
                                    list.add(code to name)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    // 3. 6자리 숫자 코드 직접 입력 시 네이버 API로 종목명 즉시 조회
                    val trimmed = symbolInput.trim()
                    if (trimmed.length == 6 && !list.any { it.first == trimmed }) {
                        try {
                            val apiUrl = java.net.URL("https://m.stock.naver.com/api/stock/$trimmed/basic")
                            val conn = apiUrl.openConnection() as java.net.HttpURLConnection
                            conn.requestMethod = "GET"
                            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                            conn.connectTimeout = 3000
                            conn.readTimeout = 3000
                            if (conn.responseCode == 200) {
                                val response = conn.inputStream.bufferedReader().readText()
                                val json = org.json.JSONObject(response)
                                val stockName = json.optString("stockName", "")
                                if (stockName.isNotBlank()) {
                                    list.add(0, trimmed to stockName)
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    list.take(15)
                }
                apiSuggestions = results
            } catch (e: Exception) {
                apiSuggestions = emptyList()
            }
        } else {
            apiSuggestions = emptyList()
        }
    }

    var estimatedPrice by remember { mutableStateOf(0.0) }
    var isFetchingPrice by remember { mutableStateOf(false) }

    LaunchedEffect(symbolInput) {
        val trimmed = symbolInput.trim()
        if (trimmed.length == 6) {
            val localPrice = stockPrices[trimmed] ?: 0.0
            if (localPrice > 0.0) {
                estimatedPrice = localPrice
            } else {
                isFetchingPrice = true
                try {
                    val priceVal = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            val apiUrl = java.net.URL("https://m.stock.naver.com/api/stock/$trimmed/basic")
                            val conn = apiUrl.openConnection() as java.net.HttpURLConnection
                            conn.requestMethod = "GET"
                            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                            conn.connectTimeout = 3000
                            conn.readTimeout = 3000
                            if (conn.responseCode == 200) {
                                val response = conn.inputStream.bufferedReader().readText()
                                val json = org.json.JSONObject(response)
                                val closeStr = json.optString("closePrice", "").replace(",", "").trim()
                                closeStr.toDoubleOrNull() ?: 0.0
                            } else 0.0
                        } catch (e: Exception) {
                            0.0
                        }
                    }
                    estimatedPrice = priceVal
                } finally {
                    isFetchingPrice = false
                }
            }
        } else {
            estimatedPrice = 0.0
            isFetchingPrice = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Column {
                Text("🚀 장전시간외 매수 주문예약 관리", fontWeight = FontWeight.Bold)
                Text("실전 국내주식 전용 (한국투자/메리츠 · 08:00 검증 ➔ 08:20 대기열 선점)", fontSize = 12.sp, color = Color.Gray)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(min = 420.dp, max = 580.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 상단 탭 버튼 (새 주문 설정 vs 등록 내역)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { selectedTabIndex = 0 },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (selectedTabIndex == 0) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            contentColor = if (selectedTabIndex == 0) MaterialTheme.colorScheme.onPrimaryContainer else Color.Gray
                        ),
                        border = BorderStroke(1.dp, if (selectedTabIndex == 0) MaterialTheme.colorScheme.primary else Color.LightGray)
                    ) {
                        Text("➕ 새 주문 설정", fontWeight = if (selectedTabIndex == 0) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp)
                    }
                    OutlinedButton(
                        onClick = { selectedTabIndex = 1 },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (selectedTabIndex == 1) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            contentColor = if (selectedTabIndex == 1) MaterialTheme.colorScheme.onPrimaryContainer else Color.Gray
                        ),
                        border = BorderStroke(1.dp, if (selectedTabIndex == 1) MaterialTheme.colorScheme.primary else Color.LightGray)
                    ) {
                        Text("📋 등록 내역 (${reservationOrders.size})", fontWeight = if (selectedTabIndex == 1) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp)
                    }
                }

                if (selectedTabIndex == 0) {
                    // 1. 신규 예약주문 등록 폼
                    Card(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("➕ 새 예약주문 설정", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        
                        OutlinedTextField(
                            value = symbolInput,
                            onValueChange = { 
                                symbolInput = it
                                showSuggestions = it.isNotBlank()
                                if (suggestions.containsKey(it)) {
                                    nameInput = suggestions[it] ?: ""
                                }
                            },
                            label = { Text("종목 코드 또는 명칭 (예: 0183J0, TIGER 등)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        if (showSuggestions && apiSuggestions.isNotEmpty()) {
                            Card(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
                                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                LazyColumn(modifier = Modifier.padding(4.dp)) {
                                    items(apiSuggestions) { (code, name) ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    symbolInput = code
                                                    nameInput = name
                                                    showSuggestions = false
                                                }
                                                .padding(horizontal = 12.dp, vertical = 10.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(name, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
                                            Text(code, color = Color.Gray, fontSize = 12.sp)
                                        }
                                        HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f))
                                    }
                                }
                            }
                        }

                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = { nameInput = it },
                            label = { Text("종목명 (선택)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = quantityInput,
                            onValueChange = { qty -> quantityInput = qty.filter { it.isDigit() } },
                            label = { Text("매수 주문 수량 (주)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        // 전일 종가 기준 예상 주문 금액 표시 영역
                        if (symbolInput.trim().length == 6) {
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (isFetchingPrice) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                            Text("전일 종가 시세 조회 중...", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                                        }
                                    } else if (estimatedPrice > 0) {
                                        val qty = quantityInput.toDoubleOrNull() ?: 0.0
                                        val totalAmt = estimatedPrice * qty
                                        Text(
                                            text = "💰 전일 종가 기준 예상 가격: ${NumberFormat.getNumberInstance(Locale.KOREA).format(estimatedPrice)}원",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                        if (qty > 0) {
                                            Text(
                                                text = "💵 예상 총 주문금액: 약 ${NumberFormat.getNumberInstance(Locale.KOREA).format(totalAmt)}원",
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        } else {
                                            Text(
                                                text = "ℹ️ 매수 수량을 입력하시면 예상 총 주문금액이 계산됩니다.",
                                                fontSize = 12.sp,
                                                color = Color.Gray
                                            )
                                        }
                                    } else {
                                        Text("⚠️ 전일 종가를 불러오지 못했습니다.", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        // 자동 판단 옵션 토글
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { autoCancelEnabled = !autoCancelEnabled },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("🇺🇸 미국 증시 자동 판단 취소", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text("아침 8시에 구성종목 산술평균 하락 시 자동 취소", fontSize = 11.sp, color = Color.Gray)
                            }
                            Switch(
                                checked = autoCancelEnabled,
                                onCheckedChange = { autoCancelEnabled = it }
                            )
                        }

                        if (autoCancelEnabled) {
                            OutlinedTextField(
                                value = autoCancelThreshold,
                                onValueChange = { val str = it.filter { ch -> ch.isDigit() || ch == '.' || ch == '-' }; autoCancelThreshold = str },
                                label = { Text("취소 기준 산술평균 등락률 (%) [기본: 1.0]") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }

                        Button(
                            onClick = {
                                val qty = quantityInput.toDoubleOrNull() ?: 0.0
                                val thresh = autoCancelThreshold.toFloatOrNull() ?: 1.0f
                                if (symbolInput.isNotBlank() && qty > 0) {
                                    onAddOrder(symbolInput, if (nameInput.isNotBlank()) nameInput else symbolInput, qty, autoCancelEnabled, thresh)
                                    symbolInput = ""
                                    nameInput = ""
                                    quantityInput = ""
                                    selectedTabIndex = 1 // 추가 즉시 등록 내역 탭으로 전환!
                                }
                            },
                            enabled = symbolInput.isNotBlank() && (quantityInput.toDoubleOrNull() ?: 0.0) > 0,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        ) {
                            Text("예약주문 리스트에 추가")
                        }
                    }
                }
            } else {
                // 2. 등록된 예약주문 리스트
                    if (reservationOrders.isEmpty()) {
                        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("등록된 장전시간외 예약주문이 없습니다.", color = Color.Gray, fontSize = 13.sp)
                                Button(onClick = { selectedTabIndex = 0 }) {
                                    Text("➕ 새 예약주문 설정하기")
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(reservationOrders.entries.sortedByDescending { it.value.lastUpdated }.toList()) { (orderId, order) ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if ((order.status.contains("취소") && !order.status.contains("자동취소")) || order.status.contains("실패")) 
                                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                                    else 
                                        MaterialTheme.colorScheme.surface
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${order.name} (${order.symbol})", 
                                            fontWeight = FontWeight.Bold, 
                                            color = MaterialTheme.colorScheme.primary,
                                            fontSize = 15.sp,
                                            modifier = Modifier.weight(1f, fill = false),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        val isDark = isSystemInDarkTheme()
                                        
                                        val badgeBgColor = when {
                                            order.status == "대기중" || order.status.contains("전송대기") -> MaterialTheme.colorScheme.primaryContainer
                                            order.status.contains("전송완료") || order.status.contains("접수완료") || order.status.contains("체결") -> if (isDark) Color(0xFF1B5E20) else Color(0xFFE8F5E9)
                                            order.status.contains("자동취소") -> if (isDark) Color(0xFF5D4037) else Color(0xFFFFF3E0)
                                            (order.status.contains("취소") && !order.status.contains("자동취소")) || order.status.contains("실패") || order.status.contains("미체결") -> MaterialTheme.colorScheme.errorContainer
                                            else -> MaterialTheme.colorScheme.secondaryContainer
                                        }
                                        
                                        val badgeTextColor = when {
                                            order.status == "대기중" || order.status.contains("전송대기") -> MaterialTheme.colorScheme.onPrimaryContainer
                                            order.status.contains("전송완료") || order.status.contains("접수완료") || order.status.contains("체결") -> if (isDark) Color(0xFFA5D6A7) else Color(0xFF1B5E20)
                                            order.status.contains("자동취소") -> if (isDark) Color(0xFFFFCC80) else Color(0xFFE65100)
                                            (order.status.contains("취소") && !order.status.contains("자동취소")) || order.status.contains("실패") || order.status.contains("미체결") -> MaterialTheme.colorScheme.onErrorContainer
                                            else -> MaterialTheme.colorScheme.onSecondaryContainer
                                        }

                                        Surface(
                                            color = badgeBgColor,
                                            shape = MaterialTheme.shapes.small
                                        ) {
                                            Text(
                                                text = order.status, 
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = badgeTextColor,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("매수 수량: ${NumberFormat.getNumberInstance(Locale.US).format(order.quantity)}주", fontSize = 13.sp)
                                        if (order.orderNo.isNotBlank()) {
                                            Text("주문번호: ${order.orderNo}", fontSize = 12.sp, color = Color.Gray)
                                        }
                                    }

                                    if (order.autoCancelEnabled) {
                                        Text(
                                            "🇺🇸 미국 증시 자동판단: 산술평균 +${order.autoCancelThreshold}% 미만 시 취소", 
                                            fontSize = 12.sp, 
                                            color = MaterialTheme.colorScheme.secondary,
                                            fontWeight = FontWeight.Medium
                                        )
                                    } else {
                                        Text("🇺🇸 미국 증시 자동판단: OFF (무조건 08:40 유지)", fontSize = 12.sp, color = Color.Gray)
                                    }

                                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                                    // 주문 취소 버튼과 자동 판단 토글 스위치 나란히 배치
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically, 
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text("자동취소:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            Switch(
                                                checked = order.autoCancelEnabled,
                                                onCheckedChange = { onToggleAutoCancel(orderId, order.autoCancelEnabled) },
                                                enabled = !(order.status.contains("취소") && !order.status.contains("자동취소"))
                                            )
                                        }

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            if ((order.status.contains("취소") && !order.status.contains("자동취소")) || order.status.contains("실패")) {
                                                Button(
                                                    onClick = { onRestartOrder(orderId) },
                                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                                    modifier = Modifier.height(32.dp)
                                                ) {
                                                    Text("🔄 다시 활성화", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                }
                                            } else {
                                                OutlinedButton(
                                                    onClick = { onCancelOrder(orderId) },
                                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                                    modifier = Modifier.height(32.dp)
                                                ) {
                                                    Text("주문 취소", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                            IconButton(
                                                onClick = { orderToDelete = orderId to order },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Text("🗑️", fontSize = 14.sp)
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
    },
    confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("닫기", fontWeight = FontWeight.Bold)
            }
        }
    )

    orderToDelete?.let { (orderId, order) ->
        AlertDialog(
            onDismissRequest = { orderToDelete = null },
            title = { Text("🗑️ 예약주문 삭제 확인", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = "'${order.name} (${order.symbol})' 예약주문 내역을 리스트에서 삭제하시겠습니까?\n\n※ 이미 증권사로 전송되었거나 대기 중인 실제 주문에는 영향을 주지 않고, 앱 내 예약 리스트에서만 삭제됩니다.",
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteOrder(orderId)
                        orderToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("삭제", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { orderToDelete = null }) {
                    Text("취소")
                }
            }
        )
    }
}
