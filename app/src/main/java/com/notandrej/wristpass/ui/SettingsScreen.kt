package com.notandrej.wristpass.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.CompactButton
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text

@Composable
fun SettingsScreen(vm: VaultViewModel) {
    val state = rememberScalingLazyListState()
    Box(Modifier.fillMaxSize()) {
        ScalingLazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 24.dp),
        ) {
            item {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.title2,
                    color = MaterialTheme.colors.primary,
                )
            }

            item {
                Text(
                    text = "Allow invalid TLS certificate (self-hosted only)",
                    style = MaterialTheme.typography.body1,
                    color = MaterialTheme.colors.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
            item {
                Switch(
                    checked = vm.allowInvalidTls,
                    onCheckedChange = { vm.updateAllowInvalidTls(it) },
                )
            }
            if (vm.allowInvalidTls) {
                item {
                    Text(
                        text = "⚠ Certificate checks are OFF for this server. " +
                            "Traffic can be intercepted; only use this for a self-hosted " +
                            "instance with a self-signed certificate you control.",
                        style = MaterialTheme.typography.caption1,
                        color = MaterialTheme.colors.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                    )
                }
            } else {
                item {
                    Text(
                        text = "Certificates are validated normally.",
                        style = MaterialTheme.typography.caption1,
                        color = MaterialTheme.colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (vm.stage == Stage.VAULT) {
                item {
                    CompactButton(onClick = { vm.lock() }) { Text("Lock now") }
                }
            }
            item {
                CompactButton(onClick = { vm.signOut() }) { Text("Sign out (forget token)") }
            }
            item {
                CompactButton(
                    onClick = { vm.stage = if (vm.stage == Stage.SETTINGS && vm.items.isNotEmpty()) Stage.VAULT else Stage.LOGIN },
                ) { Text("Back") }
            }
        }
    }
}
