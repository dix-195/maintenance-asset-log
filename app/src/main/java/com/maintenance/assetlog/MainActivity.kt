package com.maintenance.assetlog

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maintenance.assetlog.presentation.LogViewModel
import com.maintenance.assetlog.presentation.MaintenanceApp

class MainActivity : FragmentActivity() {
    private val model: LogViewModel by viewModels()
    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {
        model.finishExport(it)
    }
    private var unlocked by mutableStateOf(false)
    private var authenticationMessage by mutableStateOf("")
    private var pendingRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL
        receive(intent)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            val settings by model.settings.collectAsStateWithLifecycle()
            SideEffect {
                if (settings?.locked != false) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            val dark = when (settings?.theme) {
                "داكن" -> true
                "فاتح" -> false
                else -> isSystemInDarkTheme()
            }
            SideEffect {
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(colorScheme = if (dark) maintenanceDark else maintenanceLight) {
                    Surface(Modifier.fillMaxSize()) {
                        when {
                            settings == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                            settings?.locked == true && !unlocked -> Column(
                                Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally
                            ) {
                                Text("سجل الصيانة والمتابعة الدورية", style = MaterialTheme.typography.headlineSmall)
                                Button(onClick = { authenticate { unlocked = true } }) { Text("فتح التطبيق") }
                                if (authenticationMessage.isNotEmpty()) Text(authenticationMessage)
                            }
                            else -> MaintenanceApp(model, checkNotNull(settings), pendingRoute, { pendingRoute = null },
                                { authenticate { unlocked = true; it() } }, ::startExport)
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(intent)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) unlocked = false
    }

    private fun startExport(password: String) {
        model.prepareExport(password)
        exportFile.launch("maintenance-backup.mal")
    }

    private fun receive(intent: Intent) {
        if (intent.action == "com.maintenance.assetlog.OPEN") {
            pendingRoute = intent.data?.path?.removePrefix("/")?.takeIf { it.matches(Regex("(asset|maintenance)/[0-9]+")) }
        }
    }

    private fun authenticate(success: () -> Unit) {
        val authenticators = if (android.os.Build.VERSION.SDK_INT >= 30) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    authenticationMessage = ""
                    success()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    authenticationMessage = "تعذرت المصادقة. تحقق من إعداد قفل الجهاز."
                    android.widget.Toast.makeText(this@MainActivity, authenticationMessage, android.widget.Toast.LENGTH_SHORT).show()
                }
            })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("تأكيد هوية المستخدم")
            .setAllowedAuthenticators(authenticators).build())
    }
}

private val maintenanceLight = lightColorScheme(
    primary = Color(0xFF00695C), onPrimary = Color.White,
    primaryContainer = Color(0xFFB9F3E4), onPrimaryContainer = Color(0xFF00201A),
    surface = Color(0xFFF7FAF8), onSurface = Color(0xFF17201E)
)

private val maintenanceDark = darkColorScheme(
    primary = Color(0xFF85D7C5), onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005045), onPrimaryContainer = Color(0xFFB9F3E4),
    surface = Color(0xFF101714), onSurface = Color(0xFFDDE4E0)
)
