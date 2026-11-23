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
                                Text("ميزان", style = MaterialTheme.typography.headlineSmall)
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
        exportFile.launch("mizan-backup.mal")
    }

    private fun receive(intent: Intent) {
        if (intent.action == "com.maintenance.assetlog.OPEN") {
            pendingRoute = intent.data?.path?.removePrefix("/")?.takeIf { it.matches(Regex("(asset|maintenance)/[0-9]+|operation/[a-f0-9-]{36}")) }
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
    background = Color(0xFFF7F8F5), surface = Color.White,
    surfaceVariant = Color(0xFFEEF1EC), primary = Color(0xFF5A7D6C),
    secondary = Color(0xFFC89D7C), onBackground = Color(0xFF242B27),
    onSurface = Color(0xFF242B27), onSurfaceVariant = Color(0xFF6B7570),
    onPrimary = Color.White, onSecondary = Color(0xFF242B27),
    primaryContainer = Color(0xFFDCE9DF), onPrimaryContainer = Color(0xFF242B27)
)

private val maintenanceDark = darkColorScheme(
    background = Color(0xFF171D19), surface = Color(0xFF202823),
    surfaceVariant = Color(0xFF303A34), primary = Color(0xFFA8C6B4),
    secondary = Color(0xFFDCB79A), onBackground = Color(0xFFE5ECE6),
    onSurface = Color(0xFFE5ECE6), onSurfaceVariant = Color(0xFFBCC7BF),
    onPrimary = Color(0xFF23392D), onSecondary = Color(0xFF36271C),
    primaryContainer = Color(0xFF30483B), onPrimaryContainer = Color(0xFFE1EEE5)
)
