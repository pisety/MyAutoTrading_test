package com.my.autotradingapp.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyColumnState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.core.animateDpAsState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchlistManagementDialog(
    initialSymbols: List<String>,
    isOverseas: Boolean,
    isCrypto: Boolean,
    suggestions: Map<String, String>,
    onDismiss: () -> Unit,
    onSave: (List<String>, Map<String, String>) -> Unit
) {
    var managedList by remember { mutableStateOf(initialSymbols) }
    var searchInput by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var apiSuggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    val newNamesMap = remember { mutableMapOf<String, String>() }

    val context = androidx.compose.ui.platform.LocalContext.current

    // 검색 입력 변경 시 자동완성 검색 수행
    LaunchedEffect(searchInput) {
        if (searchInput.isNotBlank()) {
            kotlinx.coroutines.delay(300)
            try {
                val results = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val list = mutableListOf<String>()
                    val query = searchInput.lowercase()

                    if (isCrypto) {
                        // ===== 코인: 업비트 마켓 전체 목록에서 검색 =====
                        try {
                            val url = java.net.URL("https://api.upbit.com/v1/market/all")
                            val conn = url.openConnection() as java.net.HttpURLConnection
                            conn.requestMethod = "GET"
                            conn.connectTimeout = 3000
                            conn.readTimeout = 3000
                            
                            if (conn.responseCode == 200) {
                                val response = conn.inputStream.bufferedReader().readText()
                                val jsonArray = org.json.JSONArray(response)
                                for (i in 0 until jsonArray.length()) {
                                    val item = jsonArray.optJSONObject(i) ?: continue
                                    val market = item.optString("market", "")
                                    val name = item.optString("korean_name", "")
                                    if (market.startsWith("KRW-")) {
                                        val cleanCode = market.removePrefix("KRW-")
                                        if (name.lowercase().contains(query) || 
                                            cleanCode.lowercase().contains(query) ||
                                            market.lowercase().contains(query)) {
                                            list.add("$name ($cleanCode)")
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }

                    } else if (isOverseas) {
                        // ===== 해외주식: 야후 파이낸스 검색 API =====
                        try {
                            val encoded = java.net.URLEncoder.encode(searchInput, "UTF-8")
                            val url = java.net.URL("https://query2.finance.yahoo.com/v1/finance/search?q=$encoded")
                            val conn = url.openConnection() as java.net.HttpURLConnection
                            conn.requestMethod = "GET"
                            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                            conn.connectTimeout = 3000
                            conn.readTimeout = 3000
                            
                            if (conn.responseCode == 200) {
                                val response = conn.inputStream.bufferedReader().readText()
                                val json = org.json.JSONObject(response)
                                val quotes = json.optJSONArray("quotes")
                                if (quotes != null) {
                                    for (i in 0 until quotes.length()) {
                                        val item = quotes.optJSONObject(i) ?: continue
                                        val symbol = item.optString("symbol", "")
                                        val shortname = item.optString("shortname", "")
                                        // US 주식만 (점이나 콜론 없는 심볼)
                                        if (symbol.isNotBlank() && !symbol.contains(".") && !symbol.contains(":")) {
                                            list.add(if (shortname.isNotBlank()) "$shortname ($symbol)" else "($symbol)")
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }

                    } else {
                        // ===== 국내주식: 로컬 KRX 데이터 (우선주 + 네이버 이름 포함) =====
                        try {
                            val jsonString = context.assets.open("krx_stocks.json").bufferedReader().use { it.readText() }
                            val jsonArray = org.json.JSONArray(jsonString)
                            
                            for (i in 0 until jsonArray.length()) {
                                val item = jsonArray.optJSONObject(i) ?: continue
                                val name = item.optString("name", "")
                                val code = item.optString("code", "")
                                if (name.lowercase().contains(query) || code.lowercase().contains(query)) {
                                    list.add("$name ($code)")
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }

                        // 6자리 숫자/영문 코드 직접 입력 시 네이버 API로 종목명 조회
                        val trimmed = searchInput.trim()
                        if (trimmed.length == 6 && !list.any { it.contains("($trimmed)") }) {
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
                                        list.add(0, "$stockName ($trimmed)")
                                    }
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                    
                    list.distinct().take(50)
                }
                apiSuggestions = results
            } catch (e: Exception) {
                apiSuggestions = emptyList()
            }
        } else {
            apiSuggestions = emptyList()
        }
    }

    val filteredSuggestions = remember(searchInput, apiSuggestions) {
        if (searchInput.isBlank()) emptyList()
        else {
            if (apiSuggestions.isNotEmpty()) {
                apiSuggestions
            } else {
                // fallback: suggestions map에서 검색
                val query = searchInput.lowercase()
                suggestions.entries.filter { (code, name) ->
                    val cleanCode = code.removePrefix("KRW-")
                    cleanCode.isNotBlank() && (name.lowercase().contains(query) || cleanCode.lowercase().contains(query))
                }.map { (code, name) ->
                    val cleanCode = code.removePrefix("KRW-")
                    "$name ($cleanCode)"
                }.take(10)
            }
        }
    }

    val extractCode: (String) -> String = { str ->
        if (str.contains("(") && str.endsWith(")")) {
            str.substringAfterLast("(").removeSuffix(")").trim()
        } else {
            str.trim()
        }
    }

    val extractName: (String) -> String = { str ->
        if (str.contains("(")) {
            str.substringBeforeLast("(").trim().ifEmpty { extractCode(str) }
        } else {
            str.trim()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("관심 종목 관리") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchInput,
                        onValueChange = { searchInput = it },
                        label = { Text("종목명 또는 코드 검색/입력", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                            if (searchInput.isNotBlank()) {
                                val input = searchInput.trim().uppercase()
                                val inputCode = extractCode(input)
                                val inputName = extractName(input)
                                if (inputCode.isNotBlank() && !managedList.contains(inputCode)) {
                                    managedList = managedList + inputCode
                                    if (inputName != inputCode) newNamesMap[inputCode] = inputName
                                }
                                searchInput = ""
                            }
                        })
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (searchInput.isNotBlank()) {
                                val input = searchInput.trim().uppercase()
                                val inputCode = extractCode(input)
                                val inputName = extractName(input)
                                if (inputCode.isNotBlank() && !managedList.contains(inputCode)) {
                                    managedList = managedList + inputCode
                                    if (inputName != inputCode) newNamesMap[inputCode] = inputName
                                }
                                searchInput = ""
                            }
                        }
                    ) {
                        Text("추가")
                    }
                }

                if (filteredSuggestions.isNotEmpty() && searchInput.isNotBlank()) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column {
                            filteredSuggestions.forEach { suggestion ->
                                Text(
                                    text = suggestion,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val sugCode = extractCode(suggestion)
                                            val sugName = extractName(suggestion)
                                            if (!managedList.contains(sugCode)) {
                                                managedList = managedList + sugCode
                                                if (sugName != sugCode) newNamesMap[sugCode] = sugName
                                            }
                                            searchInput = ""
                                        }
                                        .padding(12.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (managedList.isEmpty()) {
                    Text(
                        text = "등록된 관심 종목이 없습니다.\n검색하여 추가해주세요.",
                        color = Color.Gray,
                        modifier = Modifier.padding(16.dp)
                    )
                } else {
                    val lazyListState = rememberLazyListState()
                    val reorderableState = rememberReorderableLazyColumnState(lazyListState) { from, to ->
                        val list = managedList.toMutableList()
                        val item = list.removeAt(from.index)
                        list.add(to.index, item)
                        managedList = list
                    }

                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(managedList.size, key = { index -> managedList[index] }) { index ->
                            val itemCode = managedList[index]
                            ReorderableItem(reorderableState, key = itemCode) { isDragging ->
                                val elevation by animateDpAsState(if (isDragging) 8.dp else 1.dp)
                                val fallbackKey = if (isCrypto && !itemCode.startsWith("KRW-")) "KRW-$itemCode" else itemCode
                                val savedName = suggestions[fallbackKey] ?: itemCode
                                val displayName = newNamesMap[itemCode] ?: savedName

                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    elevation = CardDefaults.cardElevation(defaultElevation = elevation),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = if (displayName == itemCode || displayName == fallbackKey) itemCode else "$displayName ($itemCode)",
                                            modifier = Modifier.weight(1f),
                                            fontWeight = FontWeight.Medium
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(
                                                onClick = { },
                                                modifier = Modifier.size(32.dp).draggableHandle()
                                            ) {
                                                Text("☰", color = Color.Gray, fontSize = 20.sp)
                                            }
                                            IconButton(
                                                onClick = {
                                                    managedList = managedList.filter { it != itemCode }
                                                },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Text("❌", color = Color.Red)
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
            Button(onClick = {
                onSave(managedList, newNamesMap)
            }) {
                Text("저장")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        }
    )
}
