package com.my.autotradingapp

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.firebase.messaging.FirebaseMessaging
import com.my.autotradingapp.ui.TradingViewModel
import com.my.autotradingapp.ui.theme.AutoTradingAppTheme
import com.my.autotradingapp.data.UserProfile
import com.my.autotradingapp.ui.components.NotificationListDialog
import com.my.autotradingapp.ui.components.ProfileManagementDialog
import com.my.autotradingapp.ui.components.ProfileLockScreen
import com.my.autotradingapp.ui.components.ProfilePinDialog
import com.my.autotradingapp.utils.ProfileSecurityManager
import com.my.autotradingapp.ui.screens.StockScreen
import com.my.autotradingapp.ui.screens.CryptoScreen
import com.my.autotradingapp.ui.screens.LaoreScreen
import com.my.autotradingapp.ui.screens.QuantSwingScreen
import com.my.autotradingapp.ui.screens.setup.StockAccountSetupScreen
import com.my.autotradingapp.ui.screens.setup.CryptoAccountSetupScreen

fun formatIsoTimeKst(isoTime: String): String {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val zdt = java.time.ZonedDateTime.parse(isoTime)
            val kstZdt = zdt.withZoneSameInstant(java.time.ZoneId.of("Asia/Seoul"))
            val formatter = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
            return kstZdt.format(formatter)
        }
    } catch(e: Exception) {}
    
    return isoTime.replace(Regex("T"), " ").replace(Regex("\\.\\d+.*|\\+.*|Z"), "")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AutoTradingAppTheme {
                MainContent()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainContent(viewModel: TradingViewModel = viewModel()) {
    var selectedTab by remember { mutableIntStateOf(0) }
    
    val currentUserId by viewModel.userId.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    val currentProfileName by viewModel.currentProfileName.collectAsState()
    val notifications by viewModel.notifications.collectAsState()
    var showNotificationDialog by remember { mutableStateOf(false) }
    var showProfileManagementDialog by remember { mutableStateOf(false) }
    val unreadCount = notifications.count { !it.read }

    var unlockedProfiles by remember { mutableStateOf(setOf<String>()) }
    var pendingProfileToUnlock by remember { mutableStateOf<UserProfile?>(null) }
    
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("TradingApp", android.content.Context.MODE_PRIVATE)

    val currentProfile = profiles.find { it.id == currentUserId }
    val isCurrentUnlocked = remember(currentUserId, currentProfile?.pin, unlockedProfiles) {
        val pin = currentProfile?.pin ?: ""
        pin.isEmpty() || unlockedProfiles.contains(currentUserId) || ProfileSecurityManager.isProfileUnlocked(context, currentUserId, pin)
    }
    
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted -> }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            if (permission != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val lastUserId = prefs.getString("LastUsedUserId", "")
        val targetId = if (lastUserId.isNullOrEmpty() || lastUserId == "test_user") "user_main" else lastUserId
        if (currentUserId != targetId) {
            viewModel.setUserId(targetId)
        }
        if (lastUserId == "test_user" || lastUserId.isNullOrEmpty()) {
            prefs.edit().putString("LastUsedUserId", targetId).apply()
        }
    }

    var showStockSetup by remember { mutableStateOf(false) }
    var showCryptoSetup by remember { mutableStateOf(false) }

    LaunchedEffect(currentUserId) {
        showStockSetup = false
        showCryptoSetup = false
        if (currentUserId.isNotEmpty()) {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    viewModel.saveFcmToken(task.result)
                }
            }
        }
    }
    
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text("자동매매 Pro", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = "활성: $currentProfileName ($currentUserId)",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        Box(modifier = Modifier.padding(end = 2.dp)) {
                            IconButton(onClick = { showNotificationDialog = true }) {
                                Text("🔔", fontSize = 20.sp)
                            }
                            if (unreadCount > 0) {
                                Badge(
                                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp),
                                    containerColor = MaterialTheme.colorScheme.error
                                ) {
                                    Text(unreadCount.toString(), color = Color.White)
                                }
                            }
                        }
                        IconButton(onClick = {
                            if (selectedTab == 1) {
                                showCryptoSetup = true
                            } else {
                                showStockSetup = true
                            }
                        }) {
                            Text("⚙️", fontSize = 20.sp)
                        }
                    }
                )

                // Profile Switcher Chips Bar (가족 계정 간 원클릭 전환)
                if (profiles.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            items(profiles) { profile ->
                                val isSelected = profile.id == currentUserId
                                val isUnlocked = profile.pin.isEmpty() ||
                                    unlockedProfiles.contains(profile.id) ||
                                    ProfileSecurityManager.isProfileUnlocked(context, profile.id, profile.pin)
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        if (!isSelected) {
                                            if (isUnlocked) {
                                                showStockSetup = false
                                                showCryptoSetup = false
                                                viewModel.setUserId(profile.id)
                                                prefs.edit().putString("LastUsedUserId", profile.id).apply()
                                            } else {
                                                pendingProfileToUnlock = profile
                                            }
                                        }
                                    },
                                    label = {
                                        Text(
                                            text = if (profile.pin.isNotEmpty() && !isUnlocked) "🔒 ${profile.name}" else "${profile.icon} ${profile.name}",
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                )
                            }
                            item {
                                AssistChip(
                                    onClick = { showProfileManagementDialog = true },
                                    label = { Text("➕ 관리", fontSize = 12.sp) }
                                )
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                NavigationBarItem(
                    icon = { Text("📈", fontSize = 24.sp) },
                    label = { Text("주식") },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 }
                )
                NavigationBarItem(
                    icon = { Text("🪙", fontSize = 24.sp) },
                    label = { Text("코인") },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 }
                )
                NavigationBarItem(
                    icon = { Text("♾️", fontSize = 24.sp) },
                    label = { Text("무한매수") },
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 }
                )
                NavigationBarItem(
                    icon = { Text("⚡", fontSize = 24.sp) },
                    label = { Text("퀀트스윙") },
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
                .fillMaxSize()
        ) {
            if (!isCurrentUnlocked && currentProfile != null) {
                ProfileLockScreen(
                    profile = currentProfile,
                    onUnlockClick = {
                        pendingProfileToUnlock = currentProfile
                    },
                    onSwitchProfileClick = {
                        showProfileManagementDialog = true
                    }
                )
            } else {
                when (selectedTab) {
                    0 -> {
                        if (showStockSetup) {
                            StockAccountSetupScreen(
                                onDismiss = { showStockSetup = false },
                                viewModel = viewModel
                            )
                        } else {
                            StockScreen(
                                viewModel = viewModel,
                                onSettingsClick = { showStockSetup = true }
                            )
                        }
                    }
                    1 -> {
                        if (showCryptoSetup) {
                            CryptoAccountSetupScreen(
                                onDismiss = { showCryptoSetup = false },
                                viewModel = viewModel
                            )
                        } else {
                            CryptoScreen(
                                viewModel = viewModel,
                                onSettingsClick = { showCryptoSetup = true }
                            )
                        }
                    }
                    2 -> {
                        LaoreScreen(viewModel = viewModel)
                    }
                    3 -> {
                        QuantSwingScreen(viewModel = viewModel)
                    }
                }
            }
        }
    }

    if (showNotificationDialog) {
        NotificationListDialog(
            notifications = notifications,
            onDismiss = { showNotificationDialog = false },
            onNotificationClick = { key -> viewModel.markNotificationRead(key) },
            onClearAll = { viewModel.clearNotifications() }
        )
    }

    if (showProfileManagementDialog) {
        ProfileManagementDialog(
            viewModel = viewModel,
            onDismiss = { showProfileManagementDialog = false }
        )
    }

    pendingProfileToUnlock?.let { target ->
        ProfilePinDialog(
            profile = target,
            onDismiss = { pendingProfileToUnlock = null },
            onSuccess = {
                val targetId = target.id
                unlockedProfiles = unlockedProfiles + targetId
                if (currentUserId != targetId) {
                    showStockSetup = false
                    showCryptoSetup = false
                    viewModel.setUserId(targetId)
                    prefs.edit().putString("LastUsedUserId", targetId).apply()
                }
                pendingProfileToUnlock = null
            }
        )
    }
}

