package com.my.autotradingapp.ui.screens.setup

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.my.autotradingapp.data.AccountInfo
import com.my.autotradingapp.ui.TradingViewModel
import com.my.autotradingapp.utils.CryptoUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockAccountSetupScreen(onDismiss: () -> Unit, viewModel: TradingViewModel) {
    val accountData by viewModel.stockAccountData.collectAsState()
    val currentUserId by viewModel.userId.collectAsState()
    val currentProfileName by viewModel.currentProfileName.collectAsState()
    val activeAcc = accountData.savedAccounts[accountData.activeAccountId]
    
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("TradingApp", android.content.Context.MODE_PRIVATE)
    
    var broker by remember { mutableStateOf(activeAcc?.broker ?: "한국투자") }
    var isMock by remember { mutableStateOf(activeAcc?.isMock ?: true) }
    var isOverseas by remember { mutableStateOf(activeAcc?.isOverseas ?: true) }
    var accountNumber by remember { mutableStateOf("") }
    var appKey by remember { mutableStateOf("") }
    var appSecret by remember { mutableStateOf("") }
    var isEditingKeys by remember { mutableStateOf(false) }
    
    // PIN States
    var newPinInput by remember { mutableStateOf("") }
    var verifyPinInput by remember { mutableStateOf("") }
    var isPinVerified by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }

    val brokers = listOf("한국투자", "키움", "메리츠")
    var brokerExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(broker) {
        if (broker == "키움") {
            isOverseas = false
        }
        if (broker == "메리츠") {
            isMock = false
        }
    }

    // PIN Verification Dialog Trigger
    LaunchedEffect(accountData.pin) {
        val savedPin = prefs.getString("SavedPin_stock_$currentUserId", "")
        if (accountData.pin.isNotEmpty() && !isPinVerified) {
            if (savedPin == accountData.pin) {
                isPinVerified = true
            } else {
                showPinDialog = true
            }
        } else {
            isPinVerified = true
        }
    }

    LaunchedEffect(accountData, broker, isMock, isOverseas, isPinVerified) {
        if (!isPinVerified) {
            accountNumber = ""
            appKey = ""
            appSecret = ""
            return@LaunchedEffect
        }
        val key = "${broker}_${if (isMock) "MOCK" else "REAL"}_${if (isOverseas) "OS" else "KR"}"
        val saved = accountData.savedAccounts[key]
        if (saved != null) {
            accountNumber = saved.accountNumber
            appKey = saved.appKey
            appSecret = saved.appSecret
            isEditingKeys = false
        } else {
            accountNumber = ""
            appKey = ""
            appSecret = ""
            isEditingKeys = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("주식 계좌 설정", style = MaterialTheme.typography.headlineSmall)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("설정 대상 계정 프로필", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text("$currentProfileName (ID: $currentUserId)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }

        OutlinedTextField(
            value = newPinInput,
            onValueChange = { newPinInput = it },
            label = { Text("새로운 비밀번호(PIN) 4자리 설정 (선택)") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            visualTransformation = PasswordVisualTransformation()
        )

        HorizontalDivider()

        ExposedDropdownMenuBox(
            expanded = brokerExpanded,
            onExpandedChange = { brokerExpanded = !brokerExpanded }
        ) {
            OutlinedTextField(
                value = broker,
                onValueChange = {},
                readOnly = true,
                label = { Text("증권사 선택") },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = brokerExpanded) },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
            )
            ExposedDropdownMenu(
                expanded = brokerExpanded,
                onDismissRequest = { brokerExpanded = false }
            ) {
                brokers.forEach { name ->
                    DropdownMenuItem(text = { Text(name) }, onClick = {
                        broker = name
                        brokerExpanded = false
                    })
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text("투자 구분", style = MaterialTheme.typography.labelMedium)
                Row(modifier = Modifier.clickable(enabled = broker != "메리츠") { isMock = true }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = isMock, onClick = { isMock = true }, enabled = broker != "메리츠")
                    Text("모의" + if (broker == "메리츠") " (미지원)" else "")
                }
                Row(modifier = Modifier.clickable { isMock = false }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !isMock, onClick = { isMock = false })
                    Text("실전")
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("시장 구분", style = MaterialTheme.typography.labelMedium)
                Row(modifier = Modifier.clickable { isOverseas = false }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !isOverseas, onClick = { isOverseas = false })
                    Text("국내")
                }
                Row(modifier = Modifier.clickable(enabled = broker != "키움") { isOverseas = true }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = isOverseas, onClick = { isOverseas = true }, enabled = broker != "키움")
                    Text("해외" + if (broker == "키움") " (미지원)" else "")
                }
            }
        }

        if (!isEditingKeys) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("계좌 정보가 저장되어 있습니다.", color = androidx.compose.ui.graphics.Color.Gray, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { isEditingKeys = true }) {
                    Text("계좌정보 수정")
                }
            }
        } else {
            OutlinedTextField(
                value = accountNumber,
                onValueChange = { accountNumber = it },
                label = { Text("계좌번호 (예: 12345678-01)") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            OutlinedTextField(
                value = appKey,
                onValueChange = { appKey = it },
                label = { Text("App Key") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = appSecret,
                onValueChange = { appSecret = it },
                label = { Text("App Secret") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = {
                viewModel.clearAuthErrorLocally("stock")
                viewModel.saveAccountSecurely(
                    AccountInfo(broker, isMock, isOverseas, accountNumber, appKey, appSecret),
                    newPinInput,
                    accountData.pin,
                    "stock"
                ) { success, msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    if (success) {
                        onDismiss()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("설정 저장 및 적용")
        }
        
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text("닫기")
        }
    }

    if (showPinDialog) {
        var savePinChecked by remember { mutableStateOf(false) }
        val onPinConfirm = {
            val decryptedPin = try { CryptoUtils.decrypt(accountData.pin) } catch(e: Exception) { accountData.pin }
            if (verifyPinInput == decryptedPin) {
                isPinVerified = true
                showPinDialog = false
                verifyPinInput = ""
                if (savePinChecked) {
                    prefs.edit().putString("SavedPin_stock_$currentUserId", accountData.pin).apply()
                } else {
                    prefs.edit().remove("SavedPin_stock_$currentUserId").apply()
                }
            } else {
                Toast.makeText(context, "PIN이 일치하지 않습니다.", Toast.LENGTH_SHORT).show()
            }
        }
        AlertDialog(
            onDismissRequest = { 
                showPinDialog = false
                isPinVerified = false
            },
            title = { Text("보안 계정 확인") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = verifyPinInput,
                        onValueChange = { verifyPinInput = it },
                        label = { Text("비밀번호") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onPinConfirm() }),
                        visualTransformation = PasswordVisualTransformation()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = savePinChecked, onCheckedChange = { savePinChecked = it })
                        Text("비밀번호 기억하기")
                    }
                }
            },
            confirmButton = {
                Button(onClick = onPinConfirm) { Text("확인") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPinDialog = false
                    isPinVerified = false
                }) { Text("취소") }
            }
        )
    }
}
