package com.my.autotradingapp.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.my.autotradingapp.data.UserProfile
import com.my.autotradingapp.ui.TradingViewModel
import com.my.autotradingapp.utils.ProfileSecurityManager

@Composable
fun ProfileManagementDialog(
    viewModel: TradingViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("TradingApp", android.content.Context.MODE_PRIVATE) }
    val profiles by viewModel.profiles.collectAsState()
    val currentUserId by viewModel.userId.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var profileToDelete by remember { mutableStateOf<UserProfile?>(null) }
    var profileToEdit by remember { mutableStateOf<UserProfile?>(null) }
    var pendingUnlockProfile by remember { mutableStateOf<UserProfile?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("가족 및 계정 프로필 관리", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "원클릭으로 다른 가족 계정으로 전환하거나, 새로운 프로필을 추가/관리할 수 있습니다.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(profiles) { profile ->
                        val isSelected = profile.id == currentUserId
                        val isUnlocked = ProfileSecurityManager.isProfileUnlocked(context, profile.id, profile.pin)
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (!isSelected) {
                                        if (isUnlocked) {
                                            viewModel.setUserId(profile.id)
                                            prefs.edit().putString("LastUsedUserId", profile.id).apply()
                                            Toast.makeText(context, "${profile.name} 계정으로 전환되었습니다.", Toast.LENGTH_SHORT).show()
                                            onDismiss()
                                        } else {
                                            pendingUnlockProfile = profile
                                        }
                                    }
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.weight(1f, fill = false)
                                ) {
                                    Text(if (profile.pin.isNotEmpty() && !isUnlocked) "🔒" else profile.icon, fontSize = 24.sp)
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = profile.name,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 16.sp
                                            )
                                            if (isSelected) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                                    Text("현재 선택됨", color = Color.White, fontSize = 10.sp)
                                                }
                                            } else if (profile.pin.isNotEmpty() && !isUnlocked) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Badge(containerColor = MaterialTheme.colorScheme.errorContainer) {
                                                    Text("잠김", color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 10.sp)
                                                }
                                            }
                                        }
                                        Text(
                                            text = "ID: ${profile.id}",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = {
                                            if (profile.pin.isNotEmpty() && !isUnlocked) {
                                                pendingUnlockProfile = profile
                                            } else {
                                                profileToEdit = profile
                                            }
                                        }
                                    ) {
                                        Text("✏️", fontSize = 16.sp)
                                    }
                                    if (!isSelected) {
                                        IconButton(
                                            onClick = { profileToDelete = profile }
                                        ) {
                                            Text("🗑️", fontSize = 16.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Button(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("➕ 새 가족 프로필 추가")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("닫기")
            }
        }
    )

    // 새 프로필 추가 서브 다이얼로그 (이름만 입력받고 유니크 ID는 자동 생성)
    if (showAddDialog) {
        var newNameInput by remember { mutableStateOf("") }
        var isChecking by remember { mutableStateOf(false) }
        var errorMessage by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { if (!isChecking) showAddDialog = false },
            title = { Text("새 가족 프로필 추가", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "추가할 가족 또는 본인의 프로필 이름을 입력하세요.\n고유 ID는 시스템에서 자동으로 생성되어 안전하게 관리됩니다.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = newNameInput,
                        onValueChange = { 
                            newNameInput = it
                            errorMessage = ""
                        },
                        label = { Text("프로필 이름 (예: 와이프, 나래, 둘째)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    if (errorMessage.isNotEmpty()) {
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !isChecking,
                    onClick = {
                        val cleanName = newNameInput.trim()
                        if (cleanName.isEmpty()) {
                            errorMessage = "프로필 이름을 입력해주세요."
                            return@Button
                        }

                        isChecking = true
                        fun createUniqueProfile() {
                            val generatedId = "user_" + java.util.UUID.randomUUID().toString().replace("-", "").take(8).lowercase()
                            viewModel.checkUserIdExists(generatedId) { exists ->
                                if (exists) {
                                    createUniqueProfile()
                                } else {
                                    isChecking = false
                                    viewModel.setProfileName(generatedId, cleanName)
                                    viewModel.setUserId(generatedId)
                                    prefs.edit().putString("LastUsedUserId", generatedId).apply()
                                    Toast.makeText(context, "'${cleanName}' 프로필이 생성되었습니다. 증권사 계좌를 등록해주세요.", Toast.LENGTH_LONG).show()
                                    showAddDialog = false
                                    onDismiss()
                                }
                            }
                        }
                        createUniqueProfile()
                    }
                ) {
                    if (isChecking) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text("생성")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isChecking,
                    onClick = { showAddDialog = false }
                ) {
                    Text("취소")
                }
            }
        )
    }

    // 프로필 이름 수정 서브 다이얼로그
    profileToEdit?.let { target ->
        var editedName by remember(target.id) { mutableStateOf(target.name) }
        var isSaving by remember { mutableStateOf(false) }
        var editError by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { if (!isSaving) profileToEdit = null },
            title = { Text("프로필 이름 수정", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("변경할 프로필 이름을 입력하세요.", fontSize = 13.sp)

                    OutlinedTextField(
                        value = editedName,
                        onValueChange = {
                            editedName = it
                            editError = ""
                        },
                        label = { Text("프로필 이름") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    if (editError.isNotEmpty()) {
                        Text(
                            text = editError,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !isSaving,
                    onClick = {
                        val cleanName = editedName.trim()
                        if (cleanName.isEmpty()) {
                            editError = "프로필 이름을 입력해주세요."
                            return@Button
                        }
                        isSaving = true
                        viewModel.setProfileName(target.id, cleanName)
                        Toast.makeText(context, "프로필 이름이 '${cleanName}'(으)로 변경되었습니다.", Toast.LENGTH_SHORT).show()
                        isSaving = false
                        profileToEdit = null
                    }
                ) {
                    Text("저장")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isSaving,
                    onClick = { profileToEdit = null }
                ) {
                    Text("취소")
                }
            }
        )
    }

    // 프로필 삭제 확인 다이얼로그
    profileToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { profileToDelete = null },
            title = { Text("프로필 삭제 확인", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error) },
            text = {
                Text("'${target.name} (ID: ${target.id})' 프로필과 해당 데이터베이스 노드를 완전히 삭제하시겠습니까?\n이 작업은 되돌릴 수 없습니다.")
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        val idToDelete = target.id
                        viewModel.deleteProfile(idToDelete) { success, msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            if (success) {
                                profileToDelete = null
                            }
                        }
                    }
                ) {
                    Text("삭제", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { profileToDelete = null }) {
                    Text("취소")
                }
            }
        )
    }

    pendingUnlockProfile?.let { target ->
        ProfilePinDialog(
            profile = target,
            onDismiss = { pendingUnlockProfile = null },
            onSuccess = {
                viewModel.setUserId(target.id)
                prefs.edit().putString("LastUsedUserId", target.id).apply()
                pendingUnlockProfile = null
                onDismiss()
            }
        )
    }
}
