package com.maintenance.assetlog.presentation

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maintenance.assetlog.core.Settings
import com.maintenance.assetlog.data.*
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AssetDetails(
    queries: LogDao, id: Long, model: LogViewModel, leave: () -> Unit,
    edit: () -> Unit, addLog: () -> Unit, openLog: (Long) -> Unit
) {
    val asset by remember(id) { queries.asset(id) }.collectAsStateWithLifecycle(null)
    var limit by rememberSaveable { mutableIntStateOf(30) }
    val logs by remember(id, limit) { queries.assetHistory(id, limit) }.collectAsStateWithLifecycle(emptyList())
    val parts by remember(id) { queries.parts(id) }.collectAsStateWithLifecycle(emptyList())
    val totals by remember(id) { queries.assetTotals(id) }.collectAsStateWithLifecycle(emptyList())
    var deleting by rememberSaveable { mutableStateOf(false) }
    var editingPart by remember { mutableStateOf<Part?>(null) }
    var removingPart by remember { mutableStateOf<Part?>(null) }
    val item = asset
    if (item == null) {
        Text("المركبة أو الجهاز غير متاح", Modifier.padding(16.dp))
        return
    }
    val image by produceState<android.graphics.Bitmap?>(null, item.photo) {
        value = withContext(Dispatchers.Default) { item.photo?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Heading(item.name)
            Text("${item.category} · ${if (item.archived) "مؤرشف" else "نشط"}")
        }
        image?.let { bitmap -> item { Image(bitmap.asImageBitmap(), "صورة المركبة أو الجهاز", Modifier.fillMaxWidth().heightIn(max = 240.dp)) } }
        item {
            Information("الشركة المصنعة" to item.maker, "الموديل" to item.model,
                "سنة الصنع" to item.manufactureYear?.toString().orEmpty(), "اللوحة" to item.plate.orEmpty(), "الرقم التسلسلي" to item.serial,
                "تاريخ الشراء" to item.purchased, "سعر الشراء" to item.price?.let(::money).orEmpty(),
                "المواصفات" to item.specs, "العداد (كم)" to item.odometer?.toString().orEmpty(), "الملاحظات" to item.notes,
                "تكاليف الصيانة" to totals.costText())
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = edit) { Text("تعديل المركبة أو الجهاز") }
                OutlinedButton(onClick = { model.change { queries.archive(id, !item.archived) } }) {
                    Text(if (item.archived) "إعادة تفعيل" else "أرشفة")
                }
                TextButton(onClick = { deleting = true }) { Text("حذف") }
            }
        }
        item {
            Heading("عمليات الصيانة")
            TextButton(onClick = addLog) { Text("تسجيل صيانة") }
        }
        if (logs.isEmpty()) item { Text("لا توجد عمليات صيانة") }
        items(logs, key = { "log-${it.id}" }) {
            Entry(it.type, "${it.date} · ${money(it.cost)} ${it.currency}", { openLog(it.id) })
        }
        if (logs.size == limit) item { TextButton(onClick = { limit += 30 }) { Text("عرض المزيد") } }
        item {
            Heading("القطع والبطاريات")
            TextButton(onClick = { editingPart = Part(assetId = id, name = "") }) { Text("إضافة قطعة") }
        }
        if (parts.isEmpty()) item { Text("لا توجد قطع مرتبطة") }
        items(parts, key = { "part-${it.id}" }) { part ->
            Entry(part.name, part.notes, { editingPart = part }) {
                Information("نهاية الضمان" to part.warranty, "انتهاء الصلاحية" to part.expires)
                TextButton(onClick = { removingPart = part }) { Text("حذف القطعة") }
            }
        }
    }
    if (deleting) Confirm("حذف المركبة أو الجهاز", "سيُحذف المركبة أو الجهاز وسجل صيانته وقطعه.", { deleting = false }) {
        model.change(leave) { queries.deleteAsset(id) }
    }
    removingPart?.let { part ->
        Confirm("حذف القطعة", "هل تريد حذف «${part.name}»؟", { removingPart = null }) {
            model.change(done = { removingPart = null }) { queries.deletePart(part.id) }
        }
    }
    editingPart?.let { PartEditor(it, model) { editingPart = null } }
}

@Composable
private fun PartEditor(part: Part, model: LogViewModel, leave: () -> Unit) {
    var name by remember { mutableStateOf(part.name) }
    var warranty by remember { mutableStateOf(part.warranty) }
    var expires by remember { mutableStateOf(part.expires) }
    var notes by remember { mutableStateOf(part.notes) }
    AlertDialog(onDismissRequest = leave, title = { Text("بيانات القطعة") }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Field("اسم القطعة أو البطارية", name) { name = it } }
            item { Field("نهاية الضمان (YYYY-MM-DD)", warranty) { warranty = it } }
            item { Field("انتهاء الصلاحية (YYYY-MM-DD)", expires) { expires = it } }
            item { Field("ملاحظات", notes) { notes = it } }
        }
    }, confirmButton = {
        TextButton(onClick = {
            model.change(leave) { save(part.copy(name = name.trim(), warranty = dateInput(warranty), expires = dateInput(expires), notes = notes.trim())) }
        }) { Text("حفظ") }
    }, dismissButton = { TextButton(onClick = leave) { Text("إلغاء") } })
}

@Composable
internal fun MaintenanceDetails(
    queries: LogDao, assets: List<AssetItem>, id: Long, settings: Settings, model: LogViewModel,
    leave: () -> Unit, edit: () -> Unit, repeat: (Maintenance) -> Unit
) {
    val log by remember(id) { queries.maintenance(id) }.collectAsStateWithLifecycle(null)
    var deleting by rememberSaveable { mutableStateOf(false) }
    val entry = log
    if (entry == null) {
        Text("عمليات الصيانة غير متاح", Modifier.padding(16.dp))
        return
    }
    val asset = assets.firstOrNull { it.id == entry.assetId }
    Editor {
        Heading(entry.type)
        Text(asset?.name.orEmpty())
        DueLabel(entry.due(currentDate(), asset?.odometer, settings.days))
        Information("تاريخ الصيانة" to entry.date, "التكلفة" to "${money(entry.cost)} ${entry.currency}",
            "قراءة العداد (كم)" to entry.odometer?.toString().orEmpty(), "القطعة" to entry.part,
            "الفني" to entry.technician, "الملاحظات" to entry.notes, "الحالة" to entry.status,
            "الموعد التالي" to (entry.explicitNext.asDate() ?: com.maintenance.assetlog.domain.nextDate(LocalDate.parse(entry.date), entry.months))?.toString().orEmpty(),
            "العداد التالي (كم)" to com.maintenance.assetlog.domain.nextOdometer(entry.odometer, entry.intervalKm)?.toString().orEmpty())
        Button(onClick = edit) { Text("تعديل الصيانة") }
        if (asset?.archived == false) OutlinedButton(onClick = { repeat(entry) }) { Text("تسجيل إتمام دورة جديدة") }
        TextButton(onClick = { deleting = true }) { Text("حذف السجل") }
    }
    if (deleting) Confirm("حذف عمليات الصيانة", "هل تريد حذف هذه العملية؟", { deleting = false }) {
        model.change(leave) { queries.deleteMaintenance(id) }
    }
}

@Composable
private fun Information(vararg fields: Pair<String, String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        fields.filter { it.second.isNotBlank() }.forEach { (label, value) -> Text("$label: $value") }
    }
}
