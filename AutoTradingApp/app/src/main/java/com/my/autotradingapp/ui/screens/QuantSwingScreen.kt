package com.my.autotradingapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.my.autotradingapp.data.DualMomentumStatus
import com.my.autotradingapp.data.DualMomentumTrade
import com.my.autotradingapp.ui.TradingViewModel

@Composable
fun QuantSwingScreen(viewModel: TradingViewModel) {
    val settings by viewModel.dualMomentumSettings.collectAsState()
    val status by viewModel.dualMomentumStatus.collectAsState()
    val trades by viewModel.dualMomentumTrades.collectAsState()
    val stockAccountData by viewModel.stockAccountData.collectAsState()
    val currentUserId by viewModel.userId.collectAsState()
    
    val activeAccId = stockAccountData.activeAccountId
    val activeAcc = stockAccountData.savedAccounts[activeAccId]
    
    val isRealAccount = activeAcc != null && !activeAcc.isMock
    val isMockOverseas = activeAcc != null && activeAcc.isMock && activeAcc.isOverseas
    val isQuantSwingAllowed = (currentUserId == "pisety" && isMockOverseas)
    
    val accountDisplay = if (activeAcc != null) {
        val mockText = if (activeAcc.isMock) "모의" else "실전"
        val osText = if (activeAcc.isOverseas) "해외" else "국내"
        "${activeAcc.broker} ($mockText $osText) ${activeAcc.accountNumber}"
    } else {
        "선택된 계좌 없음"
    }

    var showEmergencyDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Header Card: Strategy & Auto Trading Switch
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("⚡ 퀀트 스윙 v2.1", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    color = when {
                                        isRealAccount -> Color(0xFFC62828)
                                        isQuantSwingAllowed && settings.enabled -> Color(0xFF1B5E20)
                                        else -> Color(0xFF555555)
                                    },
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = when {
                                            isRealAccount -> "실전 계좌 비활성 🔒"
                                            !isQuantSwingAllowed -> "모의 해외 전용 ⚪"
                                            settings.enabled -> "가동 중 🟢"
                                            else -> "정지됨 ⚪"
                                        },
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = "SOXL / SOXS 단일 계좌 상호배타 스윙",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isQuantSwingAllowed && settings.enabled,
                            enabled = isQuantSwingAllowed,
                            onCheckedChange = { if (isQuantSwingAllowed) viewModel.toggleDualMomentum(it) }
                        )
                    }

                    if (!isQuantSwingAllowed) {
                        Spacer(modifier = Modifier.height(10.dp))
                        val (bannerBg, bannerFg, bannerText) = if (isRealAccount) {
                            Triple(
                                Color(0xFFFFEBEE),
                                Color(0xFFC62828),
                                "🛑 [실전 계좌 절대 차단] 국내/해외 모든 실전 계좌는 퀀트 스윙 자동매매가 영구적으로 차단되어 있으며 활성화할 수 없습니다. 퀀트 스윙을 가동하려면 모의 해외 계좌(한국투자_MOCK_OS)로 전환하세요."
                            )
                        } else {
                            Triple(
                                Color(0xFFFFF3CD),
                                Color(0xFF856404),
                                "ℹ️ 퀀트 스윙은 [pisety]의 [한국투자_MOCK_OS] (해외 모의투자) 전용 전략입니다. 실전 계좌 및 타 프로필 보호를 위해 비활성화되어 있습니다."
                            )
                        }
                        Surface(
                            color = bannerBg,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = bannerText,
                                color = bannerFg,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("선택 계좌", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(accountDisplay, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }

                    if (isQuantSwingAllowed && status.lastUpdated.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("마지막 갱신", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                            Text(status.lastUpdated, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }

        // 2. Market Regime & Macro Indicator Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                border = CardDefaults.outlinedCardBorder()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("🌐 반도체 시장 국면 (SOXX Macro)", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("200MA 레짐", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val isBull = status.regime.uppercase().contains("BULL")
                            Text(
                                text = if (isBull) "🐂 상승장 (BULL)" else "🐻 하락장 (BEAR)",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isBull) Color(0xFFE53935) else Color(0xFF1E88E5)
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("SOXX RSI(14)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = String.format("%.1f", status.soxxRsi),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "💡 신규 진입 필터: SOXL은 RSI < 65일 때만 허용, SOXS는 RSI > 38일 때만 허용",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        }

        // 3. Current Active Position Card
        item {
            ActivePositionCard(status = status)
        }

        // 4. Entry Tier Progress Card
        item {
            EntryTierProgressCard(status = status)
        }

        // 5. Emergency Action Button
        item {
            if (status.shares > 0.0) {
                Button(
                    onClick = { showEmergencyDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("🚨 비상 시장가 전량 청산", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }

        // 6. Trade History Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("📜 최근 체결 내역", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("총 ${trades.size}건", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
            }
        }

        // 7. Trade History Items
        if (trades.isEmpty()) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("아직 발생한 체결 내역이 없습니다.", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        } else {
            items(trades) { trade ->
                TradeHistoryItemCard(trade = trade)
            }
        }
    }

    if (showEmergencyDialog) {
        AlertDialog(
            onDismissRequest = { showEmergencyDialog = false },
            title = { Text("비상 전량 청산 확인") },
            text = { Text("현재 보유 중인 포지션을 즉시 시장가로 전량 매도하시겠습니까? 이 작업은 즉시 실행됩니다.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.requestDualMomentumEmergencyClose()
                        showEmergencyDialog = false
                    }
                ) {
                    Text("청산 실행", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmergencyDialog = false }) {
                    Text("취소")
                }
            }
        )
    }
}

@Composable
fun ActivePositionCard(status: DualMomentumStatus) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("📊 실시간 포지션", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                
                val sym = status.activeSymbol.ifEmpty {
                    if (status.activeTicker == 1) "SOXL" else if (status.activeTicker == 2) "SOXS" else "NONE"
                }
                
                if (status.shares > 0.0 && sym != "NONE") {
                    Surface(
                        color = if (sym == "SOXL") Color(0xFFC62828) else Color(0xFF1565C0),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = if (sym == "SOXL") "🚀 SOXL (3x Bull)" else "🛡️ SOXS (3x Bear)",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "🔍 관망 중 (포지션 없음)",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (status.shares > 0.0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("보유 수량", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${status.shares.toInt()} 주", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("평균 단가", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("$${String.format("%.2f", status.avgPrice)}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("현재가", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("$${String.format("%.2f", status.currentPrice)}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("평가 손익 (수익률)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val isPlus = status.profitLossRate >= 0
                        val color = if (isPlus) Color(0xFFD32F2F) else Color(0xFF1976D2)
                        Text(
                            text = "${if (isPlus) "+" else ""}${String.format("%.2f", status.profitLossRate)}% ($${String.format("%.2f", status.profitLossAmount)})",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = color
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("보유 일수", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${status.daysHeld}일차", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                // Price Targets
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("익절 목표가 (TP)", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                        Text("$${String.format("%.2f", status.tpTargetPrice)}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF2E7D32))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("트레일링 스탑", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                        Text("$${String.format("%.2f", status.tpTrailingStop)}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("손절선 (SL -5%)", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                        Text("$${String.format("%.2f", status.slPrice)}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFC62828))
                    }
                }
            } else {
                Text(
                    text = "현재 진입 신호를 대기하고 있습니다.\nSOXL 또는 SOXS 중 먼저 신호가 발생하는 1종목으로 집중 진입합니다.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
fun EntryTierProgressCard(status: DualMomentumStatus) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("🪜 당일 분할 매수 현황 (4-Tier Entry)", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(10.dp))

            val levels = status.boughtLevels.ifEmpty { listOf(false, false, false, false) }
            val tierLabels = listOf(
                "1차 눌림 (-2% / -3%)",
                "2차 눌림 (-3% / -4%)",
                "3차 C1 서킷 (-9%)",
                "4차 C1 서킷 (-10%)"
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                tierLabels.forEachIndexed { idx, label ->
                    val isBought = if (idx < levels.size) levels[idx] else false
                    Surface(
                        modifier = Modifier.weight(1f),
                        color = if (isBought) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(8.dp),
                        border = if (isBought) null else CardDefaults.outlinedCardBorder()
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "${idx + 1}단계",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isBought) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isBought) "체결됨 ✅" else "대기 ⏳",
                                fontSize = 10.sp,
                                color = if (isBought) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TradeHistoryItemCard(trade: DualMomentumTrade) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val isBuy = trade.action.uppercase() == "BUY"
                    Surface(
                        color = if (isBuy) Color(0xFFC62828) else Color(0xFF2E7D32),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = if (isBuy) "매수" else "매도",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(trade.symbol, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("${trade.quantity.toInt()}주", fontSize = 13.sp)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${trade.datetime} • ${trade.reason}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text("$${String.format("%.2f", trade.price)}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (trade.pnlRate != 0.0f) {
                    val isPlus = trade.pnlRate > 0
                    Text(
                        text = "${if (isPlus) "+" else ""}${String.format("%.2f", trade.pnlRate)}%",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isPlus) Color(0xFFD32F2F) else Color(0xFF1976D2)
                    )
                }
            }
        }
    }
}
