package com.taichi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taichi.ui.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class Tab(val label: String, val icon: ImageVector) {
    Dashboard("Dashboard", Icons.Default.Home),
    History("History", Icons.Default.List),
    Settings("Settings", Icons.Default.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaichiApp(vm: TaichiViewModel = viewModel()) {
    var selectedTab by remember { mutableStateOf(Tab.Dashboard) }

    Scaffold(
        containerColor = TaichiBackground,
        bottomBar = {
            NavigationBar(
                containerColor = TaichiSurface,
                contentColor = TaichiOnSurface,
            ) {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = TaichiAccent,
                            selectedTextColor = TaichiAccent,
                            unselectedIconColor = TaichiOnSurfaceVariant,
                            unselectedTextColor = TaichiOnSurfaceVariant,
                            indicatorColor = TaichiAccent.copy(alpha = 0.12f),
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (selectedTab) {
                Tab.Dashboard -> DashboardScreen(vm)
                Tab.History -> HistoryScreen(vm)
                Tab.Settings -> SettingsScreen(vm)
            }
        }
    }
}

// ── Dashboard ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardScreen(vm: TaichiViewModel) {
    val state by vm.portfolio.collectAsState()

    PullToRefreshBox(
        isRefreshing = state.isLoading,
        onRefresh = { vm.refreshPortfolio() },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(TaichiBackground),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Portfolio value
            item {
                Column(modifier = Modifier.padding(bottom = 8.dp)) {
                    Text(
                        text = "$${formatMoney(state.totalValue)}",
                        color = TaichiOnSurface,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                    val pnlColor = if (state.totalPnl >= 0) TaichiProfit else TaichiLoss
                    val pnlSign = if (state.totalPnl >= 0) "+" else ""
                    Text(
                        text = "$pnlSign$${formatMoney(state.totalPnl)} ($pnlSign${"%.2f".format(state.totalPnlPct)}%)",
                        color = pnlColor,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            // Stat pills
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatPill(
                        label = "Cash",
                        value = "$${formatMoney(state.cash)}",
                        modifier = Modifier.weight(1f),
                    )
                    StatPill(
                        label = "Long Exposure",
                        value = "$${formatMoney(state.positionsValue)}",
                        modifier = Modifier.weight(1f),
                    )
                    StatPill(
                        label = "Trades",
                        value = "${state.totalTrades}",
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // Positions header
            item {
                Text(
                    text = "POSITIONS",
                    color = TaichiOnSurfaceVariant,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                HorizontalDivider(color = TaichiOutline)
            }

            if (state.positions.isEmpty() && !state.isLoading) {
                item {
                    Text(
                        text = "No open positions",
                        color = TaichiOnSurfaceVariant,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            items(state.positions, key = { it.symbol }) { position ->
                PositionCard(position)
            }

            // Error
            state.error?.let { err ->
                item {
                    Text(
                        text = err,
                        color = TaichiLoss,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatPill(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(TaichiSurface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            color = TaichiOnSurfaceVariant,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            color = TaichiOnSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun PositionCard(pos: PositionUi) {
    val pnlColor = if (pos.unrealizedPnl >= 0) TaichiProfit else TaichiLoss
    val pnlSign = if (pos.unrealizedPnl >= 0) "+" else ""

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = TaichiSurface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = pos.symbol,
                        color = TaichiOnSurface,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "LONG",
                        color = TaichiProfit,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(TaichiProfit.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "$pnlSign$${formatMoney(pos.unrealizedPnl)}",
                        color = pnlColor,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = "$pnlSign${"%.2f".format(pos.unrealizedPnlPct)}%",
                        color = pnlColor,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                LabelValue("Qty", formatQuantity(pos.quantity))
                LabelValue("Entry", "$${formatPrice(pos.entryPrice)}")
                LabelValue("Current", "$${formatPrice(pos.currentPrice)}")
                LabelValue("Value", "$${formatMoney(pos.marketValue)}")
            }
        }
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    Column {
        Text(
            text = label,
            color = TaichiOnSurfaceVariant,
            fontSize = 11.sp,
        )
        Text(
            text = value,
            color = TaichiOnSurface,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

// ── History ────────────────────────────────────────────────────────────

@Composable
private fun HistoryScreen(vm: TaichiViewModel) {
    val state by vm.history.collectAsState()

    LaunchedEffect(Unit) { vm.refreshHistory() }

    if (state.isLoading) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(TaichiBackground),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = TaichiAccent)
        }
        return
    }

    if (state.trades.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(TaichiBackground),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "No trades yet",
                color = TaichiOnSurfaceVariant,
                fontSize = 16.sp,
            )
        }
        return
    }

    // Group trades by date
    val grouped = state.trades.groupBy { trade ->
        try {
            val instant = Instant.parse(trade.executedAt)
            instant.atZone(ZoneId.systemDefault()).toLocalDate().toString()
        } catch (_: Exception) {
            trade.executedAt.take(10)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(TaichiBackground),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        grouped.forEach { (date, trades) ->
            item {
                Text(
                    text = date,
                    color = TaichiOnSurfaceVariant,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }
            items(trades, key = { it.id }) { trade ->
                TradeCard(trade)
            }
        }
    }
}

@Composable
private fun TradeCard(trade: TradeUi) {
    val (badgeText, badgeColor) = when (trade.action.lowercase()) {
        "buy" -> "BUY" to TaichiProfit
        "sell" -> "SELL" to TaichiWarning
        "short" -> "SHORT" to TaichiLoss
        "cover" -> "COVER" to TaichiAccent
        else -> trade.action.uppercase() to TaichiOnSurfaceVariant
    }

    val timeStr = try {
        val instant = Instant.parse(trade.executedAt)
        instant.atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    } catch (_: Exception) {
        trade.executedAt
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = TaichiSurface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = badgeText,
                    color = badgeColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(badgeColor.copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = trade.symbol,
                        color = TaichiOnSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "${formatQuantity(trade.quantity)} @ $${formatPrice(trade.price)}",
                        color = TaichiOnSurfaceVariant,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                val netColor = when {
                    trade.action.lowercase() == "sell" || trade.action.lowercase() == "cover" -> {
                        if (trade.netAmount > 0) TaichiProfit else TaichiLoss
                    }
                    else -> TaichiOnSurfaceVariant
                }
                val netSign = if (trade.netAmount > 0) "+" else ""
                Text(
                    text = "$netSign$${formatMoney(trade.netAmount)}",
                    color = netColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = "fee: $${formatMoney(trade.fee)}",
                    color = TaichiOnSurfaceVariant,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = timeStr,
                    color = TaichiOnSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

// ── Settings ───────────────────────────────────────────────────────────

@Composable
private fun SettingsScreen(vm: TaichiViewModel) {
    val state by vm.settings.collectAsState()

    state.saveMessage?.let { msg ->
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(2000)
            vm.clearSaveMessage()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(TaichiBackground),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // MCP info
        item {
            SectionHeader("MCP Provider")
        }
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = TaichiSurface),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    InfoRow("Authority", "com.taichi.mcp")
                    InfoRow("Namespace", "taichi")
                    InfoRow("Tools", "12")
                }
            }
        }

        // API key status
        item {
            SectionHeader("API Key Status")
        }
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = TaichiSurface),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    state.keyStatus.forEach { (name, configured) ->
                        val color = if (configured) TaichiProfit else TaichiWarning
                        val label = if (configured) "configured" else "not set"
                        val icon = if (configured) "\u25CF" else "\u25CB"
                        Row(modifier = Modifier.padding(vertical = 2.dp)) {
                            Text(
                                text = "$icon  $name -- $label",
                                color = color,
                                fontSize = 14.sp,
                            )
                        }
                    }
                }
            }
        }

        // API key fields
        item {
            SectionHeader("API Keys")
        }
        item {
            KeyField(
                label = "CryptoPanic Token",
                hint = "Free at cryptopanic.com/developers/api/keys",
                value = state.cryptoPanicToken,
                onValueChange = { vm.updateCryptoPanicToken(it) },
            )
        }
        item {
            KeyField(
                label = "Reddit Client ID",
                hint = "From reddit.com/prefs/apps (optional)",
                value = state.redditClientId,
                onValueChange = { vm.updateRedditClientId(it) },
            )
        }
        item {
            KeyField(
                label = "Reddit Client Secret",
                hint = "From the same Reddit app page",
                value = state.redditClientSecret,
                onValueChange = { vm.updateRedditClientSecret(it) },
            )
        }
        item {
            KeyField(
                label = "CoinGecko API Key",
                hint = "Optional -- free tier works without key",
                value = state.coinGeckoApiKey,
                onValueChange = { vm.updateCoinGeckoApiKey(it) },
            )
        }

        // Save button
        item {
            Button(
                onClick = { vm.saveKeys() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = TaichiAccent,
                    contentColor = Color.White,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Save Keys", fontWeight = FontWeight.Bold)
            }
            state.saveMessage?.let { msg ->
                Text(
                    text = msg,
                    color = TaichiProfit,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        // Reset portfolio
        item {
            Spacer(modifier = Modifier.height(16.dp))
            SectionHeader("Portfolio")
        }
        item {
            Button(
                onClick = { vm.showResetDialog() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = TaichiLoss.copy(alpha = 0.15f),
                    contentColor = TaichiLoss,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Reset Portfolio", fontWeight = FontWeight.Bold)
            }
        }
    }

    // Reset confirmation dialog
    if (state.showResetDialog) {
        AlertDialog(
            onDismissRequest = { vm.dismissResetDialog() },
            containerColor = TaichiSurface,
            titleContentColor = TaichiOnSurface,
            textContentColor = TaichiOnSurfaceVariant,
            title = { Text("Reset Portfolio?") },
            text = {
                Text("This will delete all positions, trade history, and reset cash to \$10,000. This cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.resetPortfolio() },
                    colors = ButtonDefaults.textButtonColors(contentColor = TaichiLoss),
                ) {
                    Text("Reset", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { vm.dismissResetDialog() },
                    colors = ButtonDefaults.textButtonColors(contentColor = TaichiOnSurfaceVariant),
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        color = TaichiOnSurface,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, color = TaichiOnSurfaceVariant, fontSize = 14.sp)
        Text(
            text = value,
            color = TaichiOnSurface,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun KeyField(
    label: String,
    hint: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    Column {
        Text(
            text = label,
            color = TaichiOnSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = hint,
            color = TaichiOnSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                color = TaichiOnSurface,
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = TaichiAccent,
                unfocusedBorderColor = TaichiOutline,
                cursorColor = TaichiAccent,
                focusedContainerColor = TaichiSurfaceVariant,
                unfocusedContainerColor = TaichiSurfaceVariant,
            ),
            shape = RoundedCornerShape(8.dp),
        )
    }
}

// ── Formatting helpers ─────────────────────────────────────────────────

private fun formatMoney(value: Double): String = "%,.2f".format(value)

private fun formatPrice(value: Double): String {
    return when {
        value >= 1.0 -> "%,.2f".format(value)
        value >= 0.01 -> "%.4f".format(value)
        else -> "%.6f".format(value)
    }
}

private fun formatQuantity(value: Double): String {
    return when {
        value >= 1.0 -> "%,.4f".format(value)
        value >= 0.0001 -> "%.6f".format(value)
        else -> "%.8f".format(value)
    }
}
