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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CompactButton
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

@Composable
fun LoginScreen(vm: VaultViewModel) {
    val state = rememberScalingLazyListState()
    Box(Modifier.fillMaxSize()) {
        ScalingLazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 26.dp),
        ) {
            item {
                Text(
                    text = "WristPass",
                    style = MaterialTheme.typography.title1,
                    color = MaterialTheme.colors.primary,
                )
            }
            item {
                Text(
                    text = if (vm.hasSavedSession) "Saved session — enter your master password to unlock"
                    else "Sign in to your Bitwarden or Vaultwarden account",
                    style = MaterialTheme.typography.caption1,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                )
            }

            item {
                WatchField(
                    value = vm.server,
                    onValueChange = { vm.server = it },
                    label = "Server URL",
                    placeholder = "https://vault.bitwarden.com",
                    keyboardType = KeyboardType.Uri,
                )
            }
            item {
                WatchField(
                    value = vm.email,
                    onValueChange = { vm.email = it },
                    label = "Email",
                    placeholder = "you@example.com",
                    keyboardType = KeyboardType.Email,
                )
            }
            item {
                WatchField(
                    value = vm.masterPassword,
                    onValueChange = { vm.masterPassword = it },
                    label = "Master password",
                    keyboardType = KeyboardType.Password,
                    imeAction = if (vm.needsTwoFactor) ImeAction.Next else ImeAction.Done,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }

            if (vm.needsTwoFactor) {
                item {
                    WatchField(
                        value = vm.twoFactorCode,
                        onValueChange = { vm.twoFactorCode = it },
                        label = "Two-factor code",
                        placeholder = "6-digit code",
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    )
                }
            }

            vm.kdfSummary?.let { summary ->
                item {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            vm.error?.let { message ->
                item {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.caption1,
                        color = MaterialTheme.colors.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    )
                }
            }
            vm.status?.let { message ->
                item {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.caption1,
                        color = MaterialTheme.colors.secondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item {
                Button(
                    onClick = { vm.unlock() },
                    enabled = vm.canSubmit,
                    colors = ButtonDefaults.primaryButtonColors(),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    Text(if (vm.loading) "Working…" else "Unlock vault")
                }
            }

            item {
                CompactButton(
                    onClick = { vm.stage = Stage.SETTINGS },
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    Text("Settings")
                }
            }
        }
    }
}
