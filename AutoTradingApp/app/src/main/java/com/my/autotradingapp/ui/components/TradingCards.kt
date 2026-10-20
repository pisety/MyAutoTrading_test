package com.my.autotradingapp.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.my.autotradingapp.data.HoldingStock
import java.util.Locale

// Common Colors
val BuyColor = Color(0xFFE53935)
val SellColor = Color(0xFF1E88E5)
val NeutralColor = Color(0xFF757575)

@Composable
fun HoldingStockItem(
    symbol: String,
    stock: HoldingStock,
    isOverseas: Boolean,
    isCrypto: Boolean = false,
    onStopLossClick: () -> Unit,
    onBuyClick: () -> Unit,
    onSellClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Column: Name & Quantity
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stock.name.ifEmpty { symbol },
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    if (isCrypto) {
                        Text(
                            text = "${formatCryptoQuantity(stock.quantity)}개",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        val intQty = stock.quantity.toInt()
                        Text(
                            text = "${intQty}주",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                // Right Column: Price & Yield
                Column(horizontalAlignment = Alignment.End) {
                    val priceStr = formatPrice(stock.currentPrice, isOverseas, isCrypto)
                    Text(
                        text = priceStr,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                    
                    val yieldColor = if (stock.yield > 0) BuyColor else if (stock.yield < 0) SellColor else NeutralColor
                    val yieldSign = if (stock.yield > 0) "+" else ""
                    Text(
                        text = "$yieldSign${String.format(Locale.US, "%.2f", stock.yield)}%",
                        color = yieldColor,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    
                    val evalAmount = stock.currentPrice * stock.quantity
                    val evalStr = formatPrice(evalAmount, isOverseas, isCrypto)
                    Text(
                        text = evalStr,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(12.dp))
            
            // Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onBuyClick,
                    colors = ButtonDefaults.buttonColors(containerColor = BuyColor.copy(alpha = 0.1f), contentColor = BuyColor),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("매수", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = onSellClick,
                    colors = ButtonDefaults.buttonColors(containerColor = SellColor.copy(alpha = 0.1f), contentColor = SellColor),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("매도", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onStopLossClick,
                    modifier = Modifier.weight(1f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text("손절", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
fun StockItem(
    symbol: String,
    name: String,
    price: Double,
    changeRate: Double? = null,
    isOverseas: Boolean,
    onBuyClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                
                val priceStr = if (price > 0) formatPrice(price, isOverseas, false) else "로딩중..."
                
                if (price > 0 && changeRate != null) {
                    val sign = if (changeRate > 0) "+" else ""
                    val color = if (changeRate > 0) BuyColor else if (changeRate < 0) SellColor else NeutralColor
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = priceStr, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "($sign${String.format(Locale.US, "%.2f", changeRate)}%)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = color,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else {
                    Text(text = priceStr, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }
            Button(
                onClick = onBuyClick,
                colors = ButtonDefaults.buttonColors(containerColor = BuyColor),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("매수", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

fun formatPrice(amount: Double, isOverseas: Boolean, isCrypto: Boolean): String {
    return if (isCrypto) {
        "₩${String.format(Locale.US, "%,.0f", amount)}"
    } else if (isOverseas) {
        "$${String.format(Locale.US, "%.2f", amount)}"
    } else {
        "₩${String.format(Locale.US, "%,.0f", amount)}"
    }
}

fun formatCryptoQuantity(qty: Double): String {
    val s = String.format(Locale.US, "%.8f", qty)
    return s.trimEnd('0').trimEnd('.')
}
