package com.maintenance.assetlog.presentation

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maintenance.assetlog.core.*
import com.maintenance.assetlog.data.LogRepository
import com.maintenance.assetlog.data.Snapshot
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class LogViewModel(private val app: Application) : AndroidViewModel(app) {
    private val preferences = Preferences(app)
    val settings = preferences.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val _repository = MutableStateFlow<LogRepository?>(null)
    val repository = _repository.asStateFlow()
    private val _feedback = MutableStateFlow<String?>(null)
    val feedback = _feedback.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _prepared = MutableStateFlow<Snapshot?>(null)
    val prepared = _prepared.asStateFlow()

    init {
        perform("تعذر فتح قاعدة البيانات المشفرة. بقيت البيانات محفوظة.") {
            val repository = EntryPointAccessors.fromApplication(app, ReminderDependencies::class.java).repository().get()
            repository.ensureCatalog()
            withContext(Dispatchers.Main) { _repository.value = repository }
        }
    }

    fun consumeFeedback() { _feedback.value = null }

    fun perform(
        error: String = "تعذر إتمام العملية. تحقق من القيم المدخلة.",
        done: () -> Unit = {},
        operation: suspend () -> Unit
    ) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { operation() }
                done()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _feedback.value = error
            } finally {
                _busy.value = false
            }
        }
    }

    fun change(done: () -> Unit = {}, operation: suspend LogRepository.() -> Unit) =
        perform(done = done) {
            checkNotNull(repository.value).operation()
            scheduleReminders(app, preferences.settings.first().notifications)
        }

    fun saveSettings(value: Settings) = perform(done = { _feedback.value = "حُفظت الإعدادات" }) {
        preferences.save(value)
        scheduleReminders(app, value.notifications)
    }

    fun refreshReminders() = viewModelScope.launch {
        scheduleReminders(app, preferences.settings.first().notifications)
    }

    private var exportPassword: CharArray? = null

    fun prepareExport(password: String) {
        exportPassword?.fill('\u0000')
        exportPassword = password.toCharArray()
    }

    fun finishExport(uri: Uri?) {
        val chars = exportPassword ?: return
        exportPassword = null
        if (uri == null || busy.value) {
            chars.fill('\u0000')
            return
        }
        perform(error = "تعذر حفظ النسخة الاحتياطية.", done = { _feedback.value = "حُفظت النسخة المشفرة" }) {
            try {
                val snapshot = checkNotNull(repository.value).snapshot()
                checkNotNull(app.contentResolver.openOutputStream(uri, "wt")).use { Backup.write(snapshot, chars, it) }
            } finally {
                chars.fill('\u0000')
            }
        }
    }

    override fun onCleared() {
        exportPassword?.fill('\u0000')
        super.onCleared()
    }

    fun inspect(uri: Uri, password: String) = perform("الملف غير صالح أو كلمة المرور غير صحيحة.") {
        val chars = password.toCharArray()
        try {
            _prepared.value = checkNotNull(app.contentResolver.openInputStream(uri)).use { Backup.read(it, chars) }
        } finally {
            chars.fill('\u0000')
        }
    }

    fun discardBackup() { _prepared.value = null }

    fun restore() {
        val snapshot = prepared.value ?: return
        change(done = { discardBackup(); _feedback.value = "اكتملت الاستعادة" }) { restore(snapshot) }
    }
}
