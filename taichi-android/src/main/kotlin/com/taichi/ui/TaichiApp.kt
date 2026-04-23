package com.taichi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
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
    History("History", Icons.AutoMirrored.Filled.List),
    Settings("Settings", Icons.Default.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaichiApp(vm: TaichiViewModel = viewModel()) {
    var selectedTab by remember { mutableStateOf(Tab.Dashboard) }

    Scaffold(
        containerColor = TaichiBackground,
        bottomBar = {
            NavigationBar(containerColor = TaichiSurface, contentColor = TaichiOnSurface) {
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
            modifier = Modifier.fillMaxSize().background(TaichiBackground),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Portfolio value + PnL
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

            // Stat pills: Cash | Long | Short
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatPill("Cash", "$${formatMoney(state.cash)}", Modifier.weight(1f))
                    StatPill("Long", "$${formatMoney(state.longPositionsValue)}", Modifier.weight(1f), TaichiProfit)
                    StatPill("Short", formatShortPnl(state.shortUnrealizedPnl), Modifier.weight(1f), TaichiLoss)
                }
            }

            // Exposure bar
            item {
                val longVal = state.longPositionsValue
                val shortVal = kotlin.math.abs(state.shortUnrealizedPnl).coerceAtLeast(
                    state.shortPositions.sumOf { it.notional }
                )
                val total = longVal + shortVal
                val longFraction = if (total > 0) (longVal / total).toFloat() else 1f

                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Net Delta", color = TaichiOnSurfaceVariant, fontSize = 12.sp)
                        val netPct = if (state.totalValue > 0)
                            (longVal - shortVal) / state.totalValue * 100 else 0.0
                        val label = when {
                            netPct > 10 -> "+${"%.0f".format(netPct)}% long"
                            netPct < -10 -> "${"%.0f".format(netPct)}% short"
                            else -> "~neutral"
                        }
                        Text(label, color = TaichiOnSurfaceVariant, fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                    ) {
                        if (longFraction > 0.01f) {
                            Box(
                                modifier = Modifier
                                    .weight(longFraction.coerceAtLeast(0.01f))
                                    .fillMaxHeight()
                                    .background(TaichiProfit)
                            )
                        }
                        if (longFraction < 0.99f) {
                            Box(
                                modifier = Modifier
                                    .weight((1f - longFraction).coerceAtLeast(0.01f))
                                    .fillMaxHeight()
                                    .background(TaichiLoss)
                            )
                        }
                    }
                }
            }

            // Liquidation alerts
            if (state.liquidationEvents.isNotEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = TaichiLoss.copy(alpha = 0.15f)),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("LIQUIDATION", color = TaichiLoss, fontSize = 13.sp,
                                fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            state.liquidationEvents.forEach { event ->
                                Text(event, color = TaichiLoss, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            // Long positions
            if (state.longPositions.isNotEmpty()) {
                item { SectionLabel("LONG POSITIONS") }
                items(state.longPositions, key = { "long-${it.symbol}" }) { pos ->
                    LongPositionCard(pos)
                }
            }

            // Short positions
            if (state.shortPositions.isNotEmpty()) {
                item { SectionLabel("SHORT POSITIONS") }
                items(state.shortPositions, key = { "short-${it.symbol}" }) { pos ->
                    ShortPositionCard(pos)
                }
            }

            // Empty state
            if (state.longPositions.isEmpty() && state.shortPositions.isEmpty() && !state.isLoading) {
                item {
                    Text(
                        text = "No open positions",
                        color = TaichiOnSurfaceVariant,
                        fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            state.error?.let { err ->
                item { Text(err, color = TaichiLoss, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp)) }
            }
        }
    }
}

@Composable
private fun StatPill(label: String, value: String, modifier: Modifier = Modifier, accentColor: Color? = null) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(TaichiSurface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = accentColor ?: TaichiOnSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(2.dp))
        Text(value, color = TaichiOnSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Column {
        Text(
            text = text,
            color = TaichiOnSurfaceVariant,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
        HorizontalDivider(color = TaichiOutline)
    }
}

@Composable
private fun SideBadge(side: String) {
    val (text, color) = when (side) {
        "long" -> "LONG" to TaichiProfit
        "short" -> "SHORT" to TaichiLoss
        else -> side.uppercase() to TaichiOnSurfaceVariant
    }
    Text(
        text = text,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun LongPositionCard(pos: LongPositionUi) {
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
                    Text(pos.symbol, color = TaichiOnSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(8.dp))
                    SideBadge("long")
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("$pnlSign$${formatMoney(pos.unrealizedPnl)}", color = pnlColor,
                        fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text("$pnlSign${"%.2f".format(pos.unrealizedPnlPct)}%", color = pnlColor,
                        fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LabelValue("Qty", formatQuantity(pos.quantity))
                LabelValue("Entry", "$${formatPrice(pos.entryPrice)}")
                LabelValue("Current", "$${formatPrice(pos.currentPrice)}")
                LabelValue("Value", "$${formatMoney(pos.marketValue)}")
            }
        }
    }
}

@Composable
private fun ShortPositionCard(pos: ShortPositionUi) {
    val pnlColor = if (pos.unrealizedPnl >= 0) TaichiProfit else TaichiLoss
    val pnlSign = if (pos.unrealizedPnl >= 0) "+" else ""

    // Liquidation distance color
    val liqColor = when {
        pos.liqDistancePct == null -> TaichiOnSurfaceVariant
        pos.liqDistancePct < 25 -> TaichiLoss
        pos.liqDistancePct < 50 -> TaichiWarning
        else -> TaichiProfit
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = TaichiSurface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: symbol + badge + PnL
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(pos.symbol, color = TaichiOnSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(8.dp))
                    SideBadge("short")
                    if (pos.leverage > 1.0) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "${"%.0f".format(pos.leverage)}x",
                            color = TaichiWarning, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(TaichiWarning.copy(alpha = 0.15f))
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("$pnlSign$${formatMoney(pos.unrealizedPnl)}", color = pnlColor,
                        fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text("$pnlSign${"%.2f".format(pos.unrealizedPnlPct)}%", color = pnlColor,
                        fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Price + quantity row
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LabelValue("Qty", formatQuantity(pos.quantity))
                LabelValue("Entry", "$${formatPrice(pos.entryPrice)}")
                LabelValue("Current", "$${formatPrice(pos.currentPrice)}")
                LabelValue("Notional", "$${formatMoney(pos.notional)}")
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Liquidation gauge
            if (pos.liquidationPrice != null && pos.liqDistancePct != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Liq: $${formatPrice(pos.liquidationPrice)}", color = liqColor,
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    Text("${"%.1f".format(pos.liqDistancePct)}% away", color = liqColor,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(4.dp))
                // Gauge bar: fill = how close to liquidation (inverted: more filled = closer = worse)
                val fillFraction = (1.0 - (pos.liqDistancePct / 200.0).coerceIn(0.0, 1.0)).toFloat()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(TaichiOutline)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fillFraction)
                            .background(liqColor)
                    )
                }
            }

            // Funding + collateral
            if (pos.cumulativeFunding > 0.001 || pos.collateralLocked > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Funding: -$${formatMoney(pos.cumulativeFunding)}", color = TaichiOnSurfaceVariant,
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text("Collateral: $${formatMoney(pos.collateralLocked)}", color = TaichiOnSurfaceVariant,
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    Column {
        Text(label, color = TaichiOnSurfaceVariant, fontSize = 11.sp)
        Text(value, color = TaichiOnSurface, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

// ── History ────────────────────────────────────────────────────────────

@Composable
private fun HistoryScreen(vm: TaichiViewModel) {
    val state by vm.history.collectAsState()
    LaunchedEffect(Unit) { vm.refreshHistory() }

    if (state.isLoading) {
        Box(modifier = Modifier.fillMaxSize().background(TaichiBackground), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = TaichiAccent)
        }
        return
    }

    if (state.trades.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().background(TaichiBackground), contentAlignment = Alignment.Center) {
            Text("No trades yet", color = TaichiOnSurfaceVariant, fontSize = 16.sp)
        }
        return
    }

    val grouped = state.trades.groupBy { trade ->
        try {
            Instant.parse(trade.executedAt).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        } catch (_: Exception) { trade.executedAt.take(10) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(TaichiBackground),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        grouped.forEach { (date, trades) ->
            item {
                Text(date, color = TaichiOnSurfaceVariant, fontSize = 13.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            }
            items(trades, key = { it.id }) { trade -> TradeCard(trade) }
        }
    }
}

@Composable
private fun TradeCard(trade: TradeUi) {
    val (badgeText, badgeColor) = when (trade.action.lowercase()) {
        "buy" -> "BUY" to TaichiProfit
        "sell" -> "SELL" to TaichiWarning
        "short_sell" -> "SHORT" to TaichiLoss
        "cover" -> "COVER" to TaichiAccent
        "liquidation" -> "LIQUIDATION" to TaichiLoss
        else -> trade.action.uppercase() to TaichiOnSurfaceVariant
    }

    val timeStr = try {
        Instant.parse(trade.executedAt).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    } catch (_: Exception) { trade.executedAt }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = TaichiSurface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = badgeText, color = badgeColor, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(badgeColor.copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(trade.symbol, color = TaichiOnSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "${formatQuantity(trade.quantity)} @ $${formatPrice(trade.price)}",
                        color = TaichiOnSurfaceVariant, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                val netColor = when {
                    trade.action.lowercase() in listOf("sell", "cover", "liquidation") ->
                        if (trade.netAmount > 0) TaichiProfit else TaichiLoss
                    else -> TaichiOnSurfaceVariant
                }
                val netSign = if (trade.netAmount > 0) "+" else ""
                Text("$netSign$${formatMoney(trade.netAmount)}", color = netColor,
                    fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text("fee: $${formatMoney(trade.fee)}", color = TaichiOnSurfaceVariant,
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text(timeStr, color = TaichiOnSurfaceVariant, fontSize = 11.sp)
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
        modifier = Modifier.fillMaxSize().background(TaichiBackground),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SectionHeader("MCP Provider") }
        item {
            Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = TaichiSurface)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    InfoRow("Authority", "com.taichi.mcp")
                    InfoRow("Namespace", "taichi")
                    InfoRow("Tools", "13")
                }
            }
        }

        item { SectionHeader("API Key Status") }
        item {
            Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = TaichiSurface)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    state.keyStatus.forEach { (name, configured) ->
                        val color = if (configured) TaichiProfit else TaichiWarning
                        val label = if (configured) "configured" else "not set"
                        val icon = if (configured) "\u25CF" else "\u25CB"
                        Text("$icon  $name -- $label", color = color, fontSize = 14.sp,
                            modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
            }
        }

        item { SectionHeader("API Keys") }
        item { KeyField("CryptoPanic Token", "Free at cryptopanic.com/developers/api/keys",
            state.cryptoPanicToken) { vm.updateCryptoPanicToken(it) } }
        item { KeyField("Reddit Client ID", "From reddit.com/prefs/apps (optional)",
            state.redditClientId) { vm.updateRedditClientId(it) } }
        item { KeyField("Reddit Client Secret", "From the same Reddit app page",
            state.redditClientSecret) { vm.updateRedditClientSecret(it) } }
        item { KeyField("CoinGecko API Key", "Optional -- free tier works without key",
            state.coinGeckoApiKey) { vm.updateCoinGeckoApiKey(it) } }

        item {
            Button(
                onClick = { vm.saveKeys() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = TaichiAccent, contentColor = Color.White),
                shape = RoundedCornerShape(12.dp),
            ) { Text("Save Keys", fontWeight = FontWeight.Bold) }
            state.saveMessage?.let {
                Text(it, color = TaichiProfit, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)); SectionHeader("Portfolio") }
        item {
            Button(
                onClick = { vm.showResetDialog() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = TaichiLoss.copy(alpha = 0.15f), contentColor = TaichiLoss),
                shape = RoundedCornerShape(12.dp),
            ) { Text("Reset Portfolio", fontWeight = FontWeight.Bold) }
        }
    }

    if (state.showResetDialog) {
        AlertDialog(
            onDismissRequest = { vm.dismissResetDialog() },
            containerColor = TaichiSurface,
            titleContentColor = TaichiOnSurface,
            textContentColor = TaichiOnSurfaceVariant,
            title = { Text("Reset Portfolio?") },
            text = { Text("This will delete all positions, trade history, and reset cash to \$10,000. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = { vm.resetPortfolio() },
                    colors = ButtonDefaults.textButtonColors(contentColor = TaichiLoss),
                ) { Text("Reset", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(
                    onClick = { vm.dismissResetDialog() },
                    colors = ButtonDefaults.textButtonColors(contentColor = TaichiOnSurfaceVariant),
                ) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, color = TaichiOnSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TaichiOnSurfaceVariant, fontSize = 14.sp)
        Text(value, color = TaichiOnSurface, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun KeyField(label: String, hint: String, value: String, onValueChange: (String) -> Unit) {
    Column {
        Text(label, color = TaichiOnSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(hint, color = TaichiOnSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
        OutlinedTextField(
            value = value, onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = TaichiOnSurface),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = TaichiAccent, unfocusedBorderColor = TaichiOutline,
                cursorColor = TaichiAccent,
                focusedContainerColor = TaichiSurfaceVariant, unfocusedContainerColor = TaichiSurfaceVariant),
            shape = RoundedCornerShape(8.dp),
        )
    }
}

// ── Formatting helpers ─────────────────────────────────────────────────

private fun formatMoney(value: Double): String = "%,.2f".format(value)
private fun formatShortPnl(value: Double): String {
    val sign = if (value >= 0) "+" else ""
    return "$sign$${formatMoney(value)}"
}
private fun formatPrice(value: Double): String = when {
    value >= 1.0 -> "%,.2f".format(value)
    value >= 0.01 -> "%.4f".format(value)
    else -> "%.6f".format(value)
}
private fun formatQuantity(value: Double): String = when {
    value >= 1.0 -> "%,.4f".format(value)
    value >= 0.0001 -> "%.6f".format(value)
    else -> "%.8f".format(value)
}
