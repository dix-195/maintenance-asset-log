package com.maintenance.assetlog.presentation

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.maintenance.assetlog.BuildConfig
import com.maintenance.assetlog.core.Settings
import java.util.Locale

@Composable
internal fun SettingsScreen(model: LogViewModel, settings: Settings, authenticate: (() -> Unit) -> Unit, export: (String) -> Unit) {
    var days by rememberSaveable(settings.days) { mutableStateOf(settings.days.toString()) }
    var currency by rememberSaveable(settings.currency) { mutableStateOf(settings.currency) }
    var name by rememberSaveable(settings.userName) { mutableStateOf(settings.userName) }
    var budget by rememberSaveable(settings.budget) { mutableStateOf(settings.budget?.let(::money).orEmpty()) }
    var invalid by remember { mutableStateOf(false) }
    var delete by rememberSaveable { mutableStateOf(false) }
    var restoreUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        model.refreshReminders()
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { restoreUri = it }
    Editor {
        Heading("حسابك")
        Field("اسمك، اختياري", name) { name = it }
        Field("الميزانية الشهرية (${settings.currency})", budget, true) { budget = it; invalid = false }
        Heading("المظهر")
        Toggle("تقليل الحركة", settings.reduceMotion) { model.saveSettings(settings.copy(reduceMotion = it)) }
        Choice(settings.theme, listOf("النظام", "فاتح", "داكن")) { model.saveSettings(settings.copy(theme = it)) }
        Heading("التنبيهات")
        Toggle("التنبيهات المحلية", settings.notifications) { enabled ->
            model.saveSettings(settings.copy(notifications = enabled))
            if (enabled && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        Field("أيام التذكير المسبق", days, true) { days = it; invalid = false }
        Field("العملة الافتراضية", currency) { currency = it; invalid = false }
        if (invalid) Text("أدخل عدد أيام من ٠ إلى ٣٦٥ ورمز عملة من ثلاثة أحرف وميزانية موجبة عند تحديدها.", color = MaterialTheme.colorScheme.error)
        Button(onClick = {
            val parsedDays = normalizeNumber(days).toIntOrNull()
            val code = currency.trim().uppercase(Locale.ROOT)
            val amount = runCatching { moneyInput(budget) }
            invalid = amount.isFailure || amount.getOrNull()?.let { it <= 0 } == true || parsedDays == null || parsedDays !in 0..365 || !code.matches(Regex("[A-Z]{3}"))
            if (!invalid && parsedDays != null) model.saveSettings(settings.copy(days = parsedDays, currency = code, userName = name.trim().take(60), budget = amount.getOrNull()))
        }) { Text("حفظ التفضيلات") }
        Heading("الخصوصية والبيانات")
        Toggle("قفل التطبيق ببصمة أو بقفل الجهاز", settings.locked) { locked ->
            authenticate { model.saveSettings(settings.copy(locked = locked)) }
        }
        OutlinedButton(onClick = { password = ""; exporting = true }) { Text("إنشاء نسخة احتياطية مشفرة") }
        OutlinedButton(onClick = { password = ""; open.launch(arrayOf("*/*")) }) { Text("اختيار نسخة للاستعادة") }
        TextButton(onClick = { delete = true }) { Text("حذف جميع البيانات") }
        Text("الإصدار ${BuildConfig.VERSION_NAME}")
    }
    if (delete) Confirm("حذف جميع البيانات", "ستُحذف المركبات والعمليات والقطع والأقسام المخصصة من هذا الجهاز.", { delete = false }) {
        model.change(done = { delete = false }) { clearAll() }
    }
    if (exporting || restoreUri != null) {
        val backup = exporting
        val dismiss = { exporting = false; restoreUri = null; password = "" }
        AlertDialog(onDismissRequest = dismiss, title = { Text(if (backup) "حماية النسخة الاحتياطية" else "فتح النسخة المشفرة") },
            text = {
                OutlinedTextField(password, { if (it.length <= 256) password = it },
                    label = { Text(if (backup) "كلمة المرور (٨ أحرف على الأقل)" else "كلمة المرور") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            }, confirmButton = {
                TextButton(enabled = if (backup) password.length >= 8 else password.isNotEmpty(), onClick = {
                    if (backup) {
                        val secret = password
                        dismiss()
                        export(secret)
                    } else {
                        val uri = restoreUri
                        val secret = password
                        dismiss()
                        uri?.let { model.inspect(it, secret) }
                    }
                }) { Text(if (backup) "اختيار مكان الحفظ" else "فحص النسخة") }
            }, dismissButton = { TextButton(onClick = dismiss) { Text("إلغاء") } })
    }
}

@Composable
private fun Toggle(text: String, selected: Boolean, toggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f))
        Switch(selected, toggle)
    }
}
