package com.notandrej.wristpass.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.CompactButton
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.notandrej.wristpass.crypto.Totp
import com.notandrej.wristpass.data.VaultItem
import kotlinx.coroutines.delay

@Composable
fun ItemDetailScreen(vm: VaultViewModel, item: VaultItem) {
    val context = LocalContext.current
    val state = rememberScalingLazyListState()

    var copiedField by remember { mutableStateOf<String?>(null) }
    var passwordRevealed by remember { mutableStateOf(false) }
    var nowSeconds by remember { mutableStateOf(System.currentTimeMillis() / 1000) }

    // Live TOTP: tick once a second while this screen is showing.
    LaunchedEffect(item.id) {
        while (true) {
            nowSeconds = System.currentTimeMillis() / 1000
            delay(1000)
        }
    }
    LaunchedEffect(copiedField) {
        if (copiedField != null) {
            delay(2000)
            copiedField = null
        }
    }

    val totpParams = remember(item.id) { vm.totpParams(item) }
    val totpCode = totpParams?.let { Totp.code(it, nowSeconds) }
    val totpRemaining = totpParams?.let { Totp.secondsRemaining(it, nowSeconds) } ?: 0
    val totpFraction = totpParams?.let { totpRemaining.toFloat() / it.period } ?: 0f
    val totpAccent = if (totpRemaining <= 5 && totpCode != null) MaterialTheme.colors.error
    else MaterialTheme.colors.secondary

    fun copy(field: String, value: String?) {
        if (value.isNullOrBlank()) return
        copySecretToClipboard(context, "WristPass · ${item.name}", value)
        copiedField = field
    }

    Box(Modifier.fillMaxSize()) {
        ScalingLazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 22.dp),
        ) {
            item {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.title3,
                    color = MaterialTheme.colors.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                FieldRow(
                    label = "Username",
                    value = item.username ?: "—",
                    onClick = { copy("username", item.username) },
                )
            }

            item {
                FieldRow(
                    label = "Password",
                    value = if (passwordRevealed) (item.password ?: "—") else "••••••••",
                    onClick = { copy("password", item.password) },
                )
            }

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompactButton(onClick = { passwordRevealed = !passwordRevealed }) {
                        Text(if (passwordRevealed) "Hide" else "Reveal")
                    }
                    CompactButton(onClick = { copy("password", item.password) }) { Text("Copy pw") }
                }
            }

            if (totpCode != null) {
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Column {
                            Text(
                                text = "TOTP",
                                style = MaterialTheme.typography.caption1,
                                color = MaterialTheme.colors.onSurfaceVariant,
                            )
                            Text(
                                text = totpCode.chunked(3).joinToString(" "),
                                style = MaterialTheme.typography.title2,
                                color = totpAccent,
                                modifier = Modifier.clickable { copy("totp", totpCode) },
                            )
                            Spacer(Modifier.height(6.dp))
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color.White.copy(alpha = 0.12f)),
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(totpFraction.coerceIn(0f, 1f))
                                        .fillMaxHeight()
                                        .background(totpAccent),
                                )
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "${totpRemaining}s left · tap code to copy",
                                style = MaterialTheme.typography.caption2,
                                color = MaterialTheme.colors.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                FieldRow(
                    label = "Website",
                    value = item.uri ?: "—",
                    onClick = { copy("uri", item.uri) },
                )
            }

            copiedField?.let { field ->
                item {
                    Text(
                        text = "$field copied",
                        style = MaterialTheme.typography.caption1,
                        color = MaterialTheme.colors.secondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    CompactButton(onClick = { vm.closeDetail() }) { Text("Back") }
                    CompactButton(onClick = { vm.lock() }) { Text("Lock") }
                }
            }
        }
    }
}
