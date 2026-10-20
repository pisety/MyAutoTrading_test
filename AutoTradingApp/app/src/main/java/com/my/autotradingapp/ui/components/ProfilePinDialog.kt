package com.my.autotradingapp.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.my.autotradingapp.data.UserProfile
import com.my.autotradingapp.utils.ProfileSecurityManager

@Composable
fun ProfilePinDialog(
    profile: UserProfile,
    onDismiss: () -> Unit,
    onSuccess: (saveOnDevice: Boolean) -> Unit
) {
    val context = LocalContext.current
    var pinInput by remember { mutableStateOf("") }
    var savePinChecked by remember { mutableStateOf(true) }

    val onConfirm = {
        if (ProfileSecurityManager.verifyPin(pinInput, profile.pin)) {
            if (savePinChecked) {
                ProfileSecurityManager.saveProfilePin(context, profile.id, profile.pin)
            }
            onSuccess(savePinChecked)
            Toast.makeText(context, "${profile.name} 프로필 잠금이 해제되었습니다.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "PIN 번호가 일치하지 않습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(profile.icon, fontSize = 22.sp)
                Text("${profile.name} 프로필 잠금 해제", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "계좌 자산 및 매매 정보 확인을 위해 설정된 4자리 PIN(비밀번호)을 입력해 주세요.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { if (it.length <= 10) pinInput = it },
                    label = { Text("PIN 비밀번호") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { onConfirm() }),
                    visualTransformation = PasswordVisualTransformation()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Checkbox(
                        checked = savePinChecked,
                        onCheckedChange = { savePinChecked = it }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        "이 기기에서 PIN 저장 (다음부터 자동 해제)",
                        fontSize = 13.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm() }) {
                Text("잠금 해제")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        }
    )
}
