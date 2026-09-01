package com.example.nospam.ui.screens

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
//    Text("Settings Screen Content", modifier = modifier)
    val context = LocalContext.current

    // 1. Create the launcher to handle the result of the intent
    val smsRoleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Success: The user granted your app the Default SMS role
            println("Default SMS role granted!")
        } else {
            // Denied: The user refused or dismissed the dialog
            println("Default SMS role denied.")
        }
    }

    Column(modifier = modifier) {
        Text("Home Screen Content")

        // 2. Trigger your function via a UI action (like a button)
        Button(onClick = {
            println("//// Clicked ///")
            requestDefaultSmsRole(context, smsRoleLauncher)
        }) {
            Text("Set as Default SMS App")
        }
    }
}

fun requestDefaultSmsRole(context: Context, launcher: ActivityResultLauncher<Intent>) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val roleManager = context.getSystemService(RoleManager::class.java)
        if (!roleManager.isRoleHeld(RoleManager.ROLE_SMS)) {
            val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
            launcher.launch(intent)
        }
    } else {
        val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
            putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
        }
        launcher.launch(intent)
    }
}
