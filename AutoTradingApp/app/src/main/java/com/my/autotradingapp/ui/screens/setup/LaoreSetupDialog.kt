package com.my.autotradingapp.ui.screens.setup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun LaoreSetupDialog(
    isOverseas: Boolean,
    suggestions: Map<String, String>,
    cryptoSuggestions: Map<String, String> = emptyMap(),
    serviceType: String = "stock",
    onDismiss: () -> Unit,
    onSave: (String, String, Double, Int, Float, Float) -> Unit
) {
    var searchInput by remember { mutableStateOf("") }
    var selectedSymbol by remember { mutableStateOf("") }
    var selectedName by remember { mutableStateOf("") }
    var budgetInput by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("")) }
    var splitInput by remember { mutableStateOf("40") }
    var yieldInput by remember { mutableStateOf("10.0") }
    var intervalInput by remember { mutableStateOf("24") }
    
    var apiSuggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    val context = androidx.compose.ui.platform.LocalContext.current
    
    LaunchedEffect(searchInput) {
        if (searchInput.length >= 2 && selectedSymbol.isEmpty()) {
            if (isOverseas) {
                val results = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val encoded = java.net.URLEncoder.encode(searchInput, "UTF-8")
                        val url = java.net.URL("https://query2.finance.yahoo.com/v1/finance/search?q=${encoded}")
                        val conn = url.openConnection() as java.net.HttpURLConnection
                        conn.requestMethod = "GET"
                        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                        conn.connectTimeout = 3000
                        conn.readTimeout = 3000
                        if (conn.responseCode == 200) {
                            val response = conn.inputStream.bufferedReader().readText()
                            val json = org.json.JSONObject(response)
                            val quotes = json.optJSONArray("quotes")
                            val list = mutableListOf<String>()
                            if (quotes != null) {
                                for (i in 0 until quotes.length()) {
                                    val item = quotes.optJSONObject(i) ?: continue
                                    val sym = item.optString("symbol", "")
                                    val shortname = item.optString("shortname", "")
                                    if (sym.isNotEmpty()) {
                                        list.add(if (shortname.isNotEmpty()) "$shortname ($sym)" else sym)
                                    }
                                }
                            }
                            list
                        } else emptyList()
                    } catch (e: Exception) { emptyList() }
                }
                apiSuggestions = results
            } else {
                val results = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val jsonString = context.assets.open("krx_stocks.json").bufferedReader().use { it.readText() }
                        val jsonArray = org.json.JSONArray(jsonString)
                        val list = mutableListOf<String>()
                        val query = searchInput.lowercase()
                        for (i in 0 until jsonArray.length()) {
                            val item = jsonArray.optJSONObject(i) ?: continue
                            val sym = item.optString("symbol", item.optString("code", ""))
                            val name = item.optString("name", "")
                            if (sym.contains(query) || name.lowercase().contains(query)) {
                                list.add("$name ($sym)")
                            }
                            if (list.size >= 20) break
                        }
                        list
                    } catch (e: Exception) { emptyList() }
                }
                apiSuggestions = results
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
                val query = searchInput.lowercase()
                suggestions.filter {
                    it.key.isNotBlank() && (it.key.lowercase().contains(query) || it.value.lowercase().contains(query))
                }.map { "${it.value} (${it.key})" }.toList()
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
        title = { Text("무한매수 세트 추가") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selectedSymbol.isEmpty()) {
                    OutlinedTextField(
                        value = searchInput,
                        onValueChange = { searchInput = it },
                        label = { Text("종목명 검색") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    if (filteredSuggestions.isNotEmpty()) {
                        Card(modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                filteredSuggestions.forEach { suggestion ->
                                    Text(
                                        text = suggestion,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                selectedSymbol = extractCode(suggestion)
                                                selectedName = extractName(suggestion)
                                                searchInput = suggestion
                                            }
                                            .padding(12.dp)
                                    )
                                }
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = if (selectedName.isNotEmpty() && selectedName != selectedSymbol) "$selectedName ($selectedSymbol)" else selectedSymbol,
                        onValueChange = {},
                        label = { Text("선택된 종목") },
                        modifier = Modifier.fillMaxWidth(),
                        readOnly = true,
                        trailingIcon = {
                            IconButton(onClick = { selectedSymbol = ""; selectedName = ""; searchInput = "" }) {
                                Text("X", color = Color.Red)
                            }
                        }
                    )
                    
                    OutlinedTextField(
                        value = budgetInput,
                        onValueChange = { newValue ->
                            val clean = newValue.text.replace(",", "")
                            val filtered = clean.filter { char -> char.isDigit() || char == '.' }
                            val parts = filtered.split(".")
                            val num = parts[0].toLongOrNull()
                            var formatted = if (num != null) java.text.NumberFormat.getNumberInstance(java.util.Locale.US).format(num) else ""
                            if (parts.size > 1) formatted += "." + parts[1]
                            
                            val finalText = if (clean.isEmpty()) "" else formatted
                            
                            val charsFromEnd = newValue.text.length - newValue.selection.end
                            val newCursorPos = Math.max(0, finalText.length - charsFromEnd)
                            
                            budgetInput = androidx.compose.ui.text.input.TextFieldValue(
                                text = finalText,
                                selection = androidx.compose.ui.text.TextRange(newCursorPos)
                            )
                        },
                        label = { Text("투자 자금 (${if(isOverseas) "USD" else "KRW"})") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                    )
                    
                    OutlinedTextField(
                        value = splitInput,
                        onValueChange = { splitInput = it.filter { char -> char.isDigit() } },
                        label = { Text("분할 횟수 (기본 40)") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                    )
                    
                    OutlinedTextField(
                        value = yieldInput,
                        onValueChange = { yieldInput = it.filter { char -> char.isDigit() || char == '.' } },
                        label = { Text("목표 수익률 (%) (기본 10)") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal)
                    )
                    
                    if (serviceType == "crypto") {
                        OutlinedTextField(
                            value = intervalInput,
                            onValueChange = { intervalInput = it.filter { char -> char.isDigit() || char == '.' } },
                            label = { Text("매수 주기(시간) (기본 24)") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val budget = budgetInput.text.replace(",", "").toDoubleOrNull() ?: 0.0
                    val split = splitInput.toIntOrNull() ?: 40
                    val yieldVal = yieldInput.toFloatOrNull() ?: 10.0f
                    val intervalVal = intervalInput.toFloatOrNull() ?: 24.0f
                    if (selectedSymbol.isNotEmpty() && budget > 0) {
                        onSave(selectedSymbol, selectedName, budget, split, yieldVal, intervalVal)
                    }
                },
                enabled = selectedSymbol.isNotEmpty() && (budgetInput.text.replace(",", "").toDoubleOrNull() ?: 0.0) > 0
            ) {
                Text("저장")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}
