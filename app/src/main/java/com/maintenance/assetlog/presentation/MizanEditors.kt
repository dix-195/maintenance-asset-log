package com.maintenance.assetlog.presentation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import com.maintenance.assetlog.core.Settings
import com.maintenance.assetlog.data.*
import com.maintenance.assetlog.domain.recurrenceChoices
import com.maintenance.assetlog.domain.outstanding
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class OperationDraft(val sectionId: String, val operation: Operation? = null, val service: Service? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OperationSheet(
    draft: OperationDraft, sections: List<Section>, vehicles: List<AssetItem>,
    settings: Settings, model: LogViewModel, close: () -> Unit,
    knownLocations: List<String> = emptyList()
) {
    val original = draft.operation
    val template = draft.service
    val today = LocalDate.now().toString()
    var section by rememberSaveable { mutableStateOf(original?.sectionId ?: draft.sectionId) }
    var title by rememberSaveable { mutableStateOf(original?.title ?: template?.name.orEmpty()) }
    var amount by rememberSaveable { mutableStateOf((original?.amount ?: template?.amount)?.let(::money).orEmpty()) }
    var currency by rememberSaveable { mutableStateOf(original?.currency ?: settings.currency) }
    var state by rememberSaveable {
        mutableStateOf(original?.state ?: if (section == HOUSE || section == MOBILE) PaymentState.DUE else PaymentState.PAID)
    }
    var date by rememberSaveable { mutableStateOf(original?.date ?: today) }
    var due by rememberSaveable { mutableStateOf(original?.dueOn ?: template?.date.orEmpty()) }
    var endsOn by rememberSaveable { mutableStateOf(original?.endsOn.orEmpty()) }
    var paidOn by rememberSaveable { mutableStateOf(original?.paidOn?.ifBlank { today } ?: today) }
    var cycle by rememberSaveable { mutableStateOf(original?.recurrence ?: template?.recurrence.orEmpty()) }
    var days by rememberSaveable { mutableStateOf(original?.reminderDays?.toString().orEmpty()) }
    var notify by rememberSaveable { mutableStateOf(original?.remind ?: template?.remind ?: true) }
    var subject by rememberSaveable { mutableStateOf(original?.subject.orEmpty()) }
    var provider by rememberSaveable { mutableStateOf(original?.provider.orEmpty()) }
    var account by rememberSaveable { mutableStateOf(original?.account.orEmpty()) }
    var method by rememberSaveable { mutableStateOf(original?.method.orEmpty()) }
    var notes by rememberSaveable { mutableStateOf(original?.notes ?: template?.notes.orEmpty()) }
    var vehicle by rememberSaveable { mutableStateOf(original?.vehicleId) }
    var km by rememberSaveable { mutableStateOf(original?.odometer?.toString().orEmpty()) }
    var nextKm by rememberSaveable { mutableStateOf(original?.nextKm?.toString().orEmpty()) }
    var attachment by remember { mutableStateOf(original?.attachment) }
    var pendingAttachment by remember { mutableStateOf<ByteArray?>(null) }
    var more by rememberSaveable { mutableStateOf(original != null) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    val busy by model.busy.collectAsState()
    val feedback by model.feedback.collectAsState()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) model.perform(error = "تعذرت قراءة صورة الفاتورة.", done = {
            attachment = pendingAttachment; pendingAttachment = null
        }) { pendingAttachment = thumbnail(context, uri) }
    }
    val image by produceState<android.graphics.Bitmap?>(null, attachment) {
        value = withContext(Dispatchers.Default) {
            attachment?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
        }
    }

    ModalBottomSheet(onDismissRequest = { if (!busy) close() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Page {
            item { Heading(if (original == null) "إضافة عملية" else "تعديل العملية") }
            item {
                Select(sections.find { it.id == section }?.name ?: "اختر القسم",
                    sections.filter { !it.archived || it.id == original?.sectionId }.map { it.id to it.name }) {
                    if (section == CAR && it != CAR) { vehicle = null; km = ""; nextKm = "" }
                    section = it
                }
            }
            item { Field("اسم العملية أو التصنيف", title) { title = it } }
            item { Field("المبلغ", amount, true) { amount = it } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Select(state.label, PaymentState.entries.map { it to it.label }) { state = it }
                    if (original?.outstanding() == true && state != PaymentState.PAID) {
                        TextButton(onClick = { state = PaymentState.PAID; paidOn = today }) { Text("تسجيل الدفع") }
                    }
                }
            }
            item { Field(if (section == MOBILE) "بداية الاشتراك (YYYY-MM-DD)" else "التاريخ (YYYY-MM-DD)", date) { date = it } }
            if (section == MOBILE) item { Field("انتهاء الباقة (YYYY-MM-DD)", endsOn) { endsOn = it } }
            item { Field(if (section == MOBILE) "موعد التجديد (YYYY-MM-DD)" else if (section == CAR && state == PaymentState.PAID && original?.previousId == null) "موعد الخدمة القادم (YYYY-MM-DD)"
                else "تاريخ الاستحقاق (YYYY-MM-DD)", due) { due = it } }
            if (state == PaymentState.PAID) item { Field("تاريخ الدفع (YYYY-MM-DD)", paidOn) { paidOn = it } }
            if (section == CAR) {
                item {
                    Select(vehicles.find { it.id == vehicle }?.name ?: "المركبة، اختياري",
                        listOf(null to "دون مركبة") + vehicles.filter { !it.archived || it.id == vehicle }.map { it.id to it.name }) {
                        vehicle = it
                    }
                }
                if (vehicle != null) {
                    item { Field("قراءة العداد عند العملية (كم)", km, true) { km = it } }
                    item { Field("عداد الاستحقاق (كم)", nextKm, true) { nextKm = it } }
                }
            }
            if (section == HOUSE) item {
                Field("المنزل أو الموقع", subject) { subject = it }
                if (knownLocations.isNotEmpty()) Select("المواقع المسجلة", knownLocations.map { it to it }) { subject = it }
            }
            if (section == MOBILE) {
                item { Field("مزود الخدمة", provider) { provider = it } }
                item { Field("رقم الجوال أو الحساب، اختياري", account) { account = it } }
            }
            item { RecurrencePicker(cycle) { cycle = it } }
            item { TextButton(onClick = { more = !more }) { Text(if (more) "إخفاء التفاصيل" else "تفاصيل إضافية") } }
            item {
                val millis = if (settings.reduceMotion) 0 else 200
                AnimatedVisibility(more, enter = fadeIn(tween(millis)), exit = fadeOut(tween(millis))) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (section == CAR) Field("مقدم الخدمة أو الورشة", provider) { provider = it }
                        Field("رمز العملة", currency) { currency = it }
                        Field("طريقة الدفع", method) { method = it }
                        Field("ملاحظات", notes) { notes = it }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Switch(notify, { notify = it })
                            Text("تذكير بالموعد", Modifier.padding(top = 12.dp))
                        }
                        if (notify) Field("التذكير قبل الموعد بالأيام، اختياري", days, true) { days = it }
                        Row {
                            OutlinedButton(enabled = !busy, onClick = { picker.launch("image/*") }) { Text("صورة فاتورة") }
                            if (attachment != null) TextButton(onClick = { attachment = null }) { Text("إزالة") }
                        }
                        image?.let { Image(it.asImageBitmap(), "صورة الفاتورة", Modifier.fillMaxWidth().height(160.dp)) }
                    }
                }
            }
            item {
                feedback?.takeIf { it.startsWith("تعذر") }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(enabled = !busy && title.isNotBlank(), onClick = {
                    model.change({
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        close()
                    }) {
                        val row = original ?: Operation(sectionId = section, title = title, amount = 0, currency = currency, state = state, date = today)
                        save(row.copy(sectionId = section, title = title.trim(), amount = checkNotNull(moneyInput(amount)),
                            currency = currency.trim().uppercase(java.util.Locale.ROOT), state = state,
                            date = dateInput(date), dueOn = dateInput(due), paidOn = if (state == PaymentState.PAID) dateInput(paidOn) else "",
                            endsOn = dateInput(endsOn), recurrence = cycle, reminderDays = numberInput(days)?.let(Math::toIntExact), remind = notify,
                            subject = subject.trim(), provider = provider.trim(), account = account.trim(), method = method.trim(),
                            notes = notes.trim(), attachment = attachment, vehicleId = vehicle,
                            odometer = vehicle?.let { numberInput(km) }, nextKm = vehicle?.let { numberInput(nextKm) }))
                    }
                }) { Text("حفظ العملية") }
                if (original != null) TextButton(enabled = !busy, onClick = { deleting = true }) { Text("حذف العملية") }
            }
        }
    }
    if (deleting && original != null) Confirm("حذف العملية", "ستُحذف العملية من السجل.", { deleting = false }) {
        model.change(close) { ledger.deleteOperation(original.id) }
    }
}

@Composable
private fun RecurrencePicker(cycle: String, change: (String) -> Unit) {
    val option = cycle.substringBefore(':')
    Select(recurrenceChoices.getValue(option), recurrenceChoices.entries.map { it.key to it.value }) {
        change(if (it == "D") "D:30" else it)
    }
    if (option == "D") {
        Field("التكرار كل (أيام)", cycle.substringAfter(':'), true) { change("D:" + normalizeNumber(it)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SectionSheet(original: Section?, model: LogViewModel, close: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(original?.name.orEmpty()) }
    var icon by rememberSaveable { mutableStateOf(original?.icon ?: "عام") }
    var color by rememberSaveable { mutableStateOf(original?.color ?: 0xFF5A7D6C) }
    var description by rememberSaveable { mutableStateOf(original?.description.orEmpty()) }
    var archive by rememberSaveable { mutableStateOf(false) }
    val busy by model.busy.collectAsState()
    val feedback by model.feedback.collectAsState()
    ModalBottomSheet(onDismissRequest = { if (!busy) close() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Page {
            item { Heading(if (original == null) "قسم جديد" else "تعديل القسم") }
            item { Field("اسم القسم", name) { name = it } }
            item { Select("الأيقونة · $icon", listOf("عام", "سيارة", "منزل", "جوال", "مصروفات").map { it to it }) { icon = it } }
            item {
                val colors = listOf(0xFF5A7D6C to "أخضر", 0xFF78644F to "رملي", 0xFF536F77 to "أزرق", 0xFF7B647C to "بنفسجي")
                Select("اللون · ${colors.firstOrNull { it.first == color }?.second.orEmpty()}", colors) { color = it }
            }
            item { Field("الوصف، اختياري", description) { description = it } }
            item {
                feedback?.takeIf { it.startsWith("تعذر") }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(enabled = !busy && name.isNotBlank(), onClick = {
                    model.change(close) {
                        save((original ?: Section(name = name)).copy(name = name.trim(), icon = icon, color = color, description = description.trim()))
                    }
                }) { Text("حفظ القسم") }
                if (original != null) TextButton(enabled = !busy, onClick = { archive = true }) {
                    Text(if (original.archived) "استعادة القسم" else "إزالة من الرئيسية")
                }
            }
        }
    }
    if (archive && original != null) Confirm(if (original.archived) "استعادة القسم" else "أرشفة القسم",
        "تبقى العمليات السابقة ضمن التقارير والسجل.", { archive = false }) {
        model.change(close) { ledger.archive(original.id, !original.archived) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ServiceSheet(section: Section, original: Service?, model: LogViewModel, close: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(original?.name.orEmpty()) }
    var type by rememberSaveable { mutableStateOf(original?.type.orEmpty()) }
    var amount by rememberSaveable { mutableStateOf(original?.amount?.let(::money).orEmpty()) }
    var date by rememberSaveable { mutableStateOf(original?.date.orEmpty()) }
    var cycle by rememberSaveable { mutableStateOf(original?.recurrence.orEmpty()) }
    var notify by rememberSaveable { mutableStateOf(original?.remind ?: true) }
    var notes by rememberSaveable { mutableStateOf(original?.notes.orEmpty()) }
    var delete by rememberSaveable { mutableStateOf(false) }
    val busy by model.busy.collectAsState()
    val feedback by model.feedback.collectAsState()
    ModalBottomSheet(onDismissRequest = { if (!busy) close() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Page {
            item { Heading(if (original == null) "خدمة جديدة" else "تعديل الخدمة") }
            item { Text(section.name) }
            item { Field("اسم الخدمة", name) { name = it } }
            item { Field("نوع الخدمة", type) { type = it } }
            item { Field("المبلغ الافتراضي، اختياري", amount, true) { amount = it } }
            item { Field("الموعد الافتراضي (YYYY-MM-DD)", date) { date = it } }
            item { RecurrencePicker(cycle) { cycle = it } }
            item { Row { Switch(notify, { notify = it }); Text("تذكير بالموعد", Modifier.padding(12.dp)) } }
            item { Field("ملاحظات", notes) { notes = it } }
            item {
                feedback?.takeIf { it.startsWith("تعذر") }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(enabled = !busy && name.isNotBlank(), onClick = {
                    model.change(close) {
                        save((original ?: Service(sectionId = section.id, name = name)).copy(name = name.trim(), type = type.trim(),
                            amount = moneyInput(amount), date = dateInput(date), recurrence = cycle, remind = notify, notes = notes.trim()))
                    }
                }) { Text("حفظ الخدمة") }
                if (original != null) TextButton(onClick = { delete = true }) { Text("حذف الخدمة") }
            }
        }
    }
    if (delete && original != null) Confirm("حذف قالب الخدمة", "تبقى العمليات التي سجلتها سابقًا.", { delete = false }) {
        model.change(close) { ledger.deleteService(original.id) }
    }
}
