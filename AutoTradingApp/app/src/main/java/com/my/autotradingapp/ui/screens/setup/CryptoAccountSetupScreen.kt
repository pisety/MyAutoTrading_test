package com.my.autotradingapp.ui.screens.setup

import android.widget.Toast
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
fun CryptoAccountSetupScreen(onDismiss: () -> Unit, viewModel: TradingViewModel) {
    val accountData by viewModel.cryptoAccountData.collectAsState()
    val currentUserId by viewModel.userId.collectAsState()
    val currentProfileName by viewModel.currentProfileName.collectAsState()
    val activeAcc = accountData.savedAccounts[accountData.activeAccountId]
    
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("TradingApp", android.content.Context.MODE_PRIVATE)
    
    // Crypto: broker is always 업비트, no mock/real, no overseas, no account number
    var appKey by remember { mutableStateOf("") }
    var appSecret by remember { mutableStateOf("") }
    var isEditingKeys by remember { mutableStateOf(false) }
    
    // PIN States
    var newPinInput by remember { mutableStateOf("") }
    var verifyPinInput by remember { mutableStateOf("") }
    var isPinVerified by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }

    // PIN Verification Dialog Trigger
    LaunchedEffect(accountData.pin) {
        val savedPin = prefs.getString("SavedPin_crypto_$currentUserId", "")
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

    LaunchedEffect(accountData, isPinVerified) {
        if (!isPinVerified) {
            appKey = ""
            appSecret = ""
            return@LaunchedEffect
        }
        val key = "업비트_REAL_CRYPTO"
        val saved = accountData.savedAccounts[key]
        if (saved != null) {
            appKey = saved.appKey
            appSecret = saved.appSecret
            isEditingKeys = false
        } else {
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
        Text("코인 계좌 설정", style = MaterialTheme.typography.headlineSmall)

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

        // Broker is fixed to 업비트
        OutlinedTextField(
            value = "업비트",
            onValueChange = {},
            readOnly = true,
            label = { Text("거래소") },
            modifier = Modifier.fillMaxWidth()
        )

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
                value = appKey,
                onValueChange = { appKey = it },
                label = { Text("Access Key") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = appSecret,
                onValueChange = { appSecret = it },
                label = { Text("Secret Key") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = {
                viewModel.clearAuthErrorLocally("crypto")
                viewModel.saveAccountSecurely(
                    AccountInfo("업비트", false, false, "", appKey, appSecret),
                    newPinInput,
                    accountData.pin,
                    "crypto"
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
                    prefs.edit().putString("SavedPin_crypto_$currentUserId", accountData.pin).apply()
                } else {
                    prefs.edit().remove("SavedPin_crypto_$currentUserId").apply()
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
