package com.my.autotradingapp.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.my.autotradingapp.data.UserProfile

@Composable
fun ProfileLockScreen(
    profile: UserProfile,
    onUnlockClick: () -> Unit,
    onSwitchProfileClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("🔒", fontSize = 48.sp)
                Text(
                    text = "${profile.icon} ${profile.name} 계정 잠금",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "보안을 위해 계좌 자산, 보유 종목 및 매매 정보 조회가 제한되어 있습니다.\n비밀번호(PIN)를 입력하여 잠금을 해제하세요.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onUnlockClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🔑 PIN 입력하여 잠금 해제", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onSwitchProfileClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("👤 다른 프로필 선택 / 관리")
                }
            }
        }
    }
}
