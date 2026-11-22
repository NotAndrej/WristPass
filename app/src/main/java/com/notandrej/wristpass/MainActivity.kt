package com.notandrej.wristpass

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.wear.compose.material.MaterialTheme
import com.notandrej.wristpass.ui.ItemDetailScreen
import com.notandrej.wristpass.ui.LoginScreen
import com.notandrej.wristpass.ui.SettingsScreen
import com.notandrej.wristpass.ui.Stage
import com.notandrej.wristpass.ui.VaultScreen
import com.notandrej.wristpass.ui.VaultViewModel
import com.notandrej.wristpass.ui.clearClipboard
import com.notandrej.wristpass.ui.theme.WristPassTheme

class MainActivity : ComponentActivity() {

    private val viewModel: VaultViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WristPassTheme {
                WristPassApp(viewModel)
            }
        }
        if (BuildConfig.DEBUG) {
            viewModel.debugAutoLogin(
                intent.getStringExtra("wp_server"),
                intent.getStringExtra("wp_email"),
                intent.getStringExtra("wp_password"),
                intent.getStringExtra("wp_2fa"),
            )
        }
    }

    /**
     * Re-lock whenever the app leaves the foreground: opening the watch face,
     * another app, or simply the screen going off all drop the in-memory keys
     * and every decrypted field. The token survives, so unlocking again only
     * needs the master password.
     */
    override fun onStop() {
        super.onStop()
        viewModel.lock()
        clearClipboard(this)
    }
}

@Composable
private fun WristPassApp(vm: VaultViewModel) {
    MaterialTheme {
        val selected = vm.selected
        when {
            selected != null -> ItemDetailScreen(vm, selected)
            vm.stage == Stage.LOGIN -> LoginScreen(vm)
            vm.stage == Stage.VAULT -> VaultScreen(vm)
            else -> SettingsScreen(vm)
        }
    }
}
