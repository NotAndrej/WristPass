package com.notandrej.wristpass.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.CompactButton
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

@Composable
fun VaultScreen(vm: VaultViewModel) {
    val state = rememberScalingLazyListState()
    val visible = vm.filteredItems

    Box(Modifier.fillMaxSize()) {
        ScalingLazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 22.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Vault · ${visible.size}",
                        style = MaterialTheme.typography.title3,
                        color = MaterialTheme.colors.primary,
                    )
                    CompactButton(onClick = { vm.stage = Stage.SETTINGS }) { Text("⚙") }
                }
            }

            item {
                WatchField(
                    value = vm.query,
                    onValueChange = { vm.query = it },
                    label = "Search",
                    placeholder = "name or username",
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            vm.error?.let { message ->
                item {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.caption1,
                        color = MaterialTheme.colors.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (visible.isEmpty()) {
                item {
                    Text(
                        text = if (vm.items.isEmpty()) "No logins in this vault" else "No matches",
                        style = MaterialTheme.typography.body1,
                        color = MaterialTheme.colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
            }

            items(count = visible.size, key = { visible[it].id }) { index ->
                val item = visible[index]
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                ) {
                    FieldRow(
                        label = if (item.hasTotp) "🔑 ${item.name}" else item.name,
                        value = item.username ?: "—",
                        onClick = { vm.select(item) },
                    )
                }
            }
        }
    }
}
