package com.maintenance.assetlog.presentation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.maintenance.assetlog.core.Settings
import com.maintenance.assetlog.data.*
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

@Composable
internal fun Editor(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(contentPadding = PaddingValues(16.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
            }
        }
    }
}

@Composable
internal fun AssetEditor(asset: Asset, model: LogViewModel, leave: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(asset.name) }
    var category by rememberSaveable { mutableStateOf(asset.category) }
    var maker by rememberSaveable { mutableStateOf(asset.maker) }
    var deviceModel by rememberSaveable { mutableStateOf(asset.model) }
    var serial by rememberSaveable { mutableStateOf(asset.serial) }
    var purchased by rememberSaveable { mutableStateOf(asset.purchased) }
    var price by rememberSaveable { mutableStateOf(asset.price?.let(::money).orEmpty()) }
    var specs by rememberSaveable { mutableStateOf(asset.specs) }
    var notes by rememberSaveable { mutableStateOf(asset.notes) }
    var odometer by rememberSaveable { mutableStateOf(asset.odometer?.toString().orEmpty()) }
    var correction by rememberSaveable { mutableStateOf(false) }
    var photo by remember { mutableStateOf(asset.photo) }
    val context = LocalContext.current
    var encoded by remember { mutableStateOf<ByteArray?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            model.perform("تعذر قراءة الصورة.", done = { photo = encoded; encoded = null }) {
                encoded = thumbnail(context, it)
            }
        }
    }
    Editor {
        Heading(if (asset.id == 0L) "أصل جديد" else "تعديل الأصل")
        Field("اسم الأصل", name) { name = it }
        Field("التصنيف", category) { category = it }
        Field("الشركة المصنعة", maker) { maker = it }
        Field("الموديل", deviceModel) { deviceModel = it }
        Field("الرقم التسلسلي", serial) { serial = it }
        Field("تاريخ الشراء (YYYY-MM-DD)", purchased) { purchased = it }
        Field("سعر الشراء", price, true) { price = it }
        Field("المواصفات الفنية", specs) { specs = it }
        Field("ملاحظات", notes) { notes = it }
        Field("العداد الحالي (كم)", odometer, true) { odometer = it }
        if (asset.odometer != null) {
            Row { Checkbox(correction, { correction = it }); Text("أؤكد تصحيح قراءة العداد عند خفضها أو مسحها", Modifier.padding(top = 12.dp)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pick.launch("image/*") }) { Text(if (photo == null) "اختيار صورة" else "استبدال الصورة") }
            if (photo != null) TextButton(onClick = { photo = null }) { Text("إزالة الصورة") }
        }
        Button(onClick = {
            model.change(leave) {
                save(asset.copy(name = name.trim(), category = category.trim(), maker = maker.trim(),
                    model = deviceModel.trim(), serial = serial.trim(), purchased = dateInput(purchased),
                    price = moneyInput(price), specs = specs.trim(), notes = notes.trim(), photo = photo,
                    odometer = numberInput(odometer)), correction)
            }
        }) { Text("حفظ الأصل") }
    }
}

private fun thumbnail(context: android.content.Context, uri: Uri): ByteArray {
    val reader = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    checkNotNull(reader.openInputStream(uri)).use { BitmapFactory.decodeStream(it, null, bounds) }
    require(bounds.outWidth > 0 && bounds.outHeight > 0)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 700) sample *= 2
    val image = checkNotNull(reader.openInputStream(uri)).use {
        checkNotNull(BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }))
    }
    return try {
        ByteArrayOutputStream().use { output ->
            check(image.compress(Bitmap.CompressFormat.JPEG, 80, output))
            output.toByteArray()
        }
    } finally {
        image.recycle()
    }
}

@Composable
internal fun MaintenanceEditor(
    previous: Maintenance?, repeating: Boolean, assetId: Long, assets: List<AssetItem>,
    settings: Settings, model: LogViewModel, leave: () -> Unit
) {
    var selected by rememberSaveable { mutableLongStateOf(assetId) }
    var type by rememberSaveable { mutableStateOf(previous?.type.orEmpty()) }
    var part by rememberSaveable { mutableStateOf(previous?.part.orEmpty()) }
    var date by rememberSaveable { mutableStateOf(if (!repeating && previous != null) previous.date else LocalDate.now().toString()) }
    var odometer by rememberSaveable { mutableStateOf((if (repeating) assets.find { it.id == assetId }?.odometer else previous?.odometer)?.toString().orEmpty()) }
    var cost by rememberSaveable { mutableStateOf(if (repeating) "" else previous?.cost?.let(::money).orEmpty()) }
    var currency by rememberSaveable { mutableStateOf(previous?.currency ?: settings.currency) }
    var technician by rememberSaveable { mutableStateOf(previous?.technician.orEmpty()) }
    var notes by rememberSaveable { mutableStateOf(if (repeating) "" else previous?.notes.orEmpty()) }
    var months by rememberSaveable { mutableStateOf(previous?.months?.toString().orEmpty()) }
    var interval by rememberSaveable { mutableStateOf(previous?.intervalKm?.toString().orEmpty()) }
    var next by rememberSaveable { mutableStateOf(if (repeating) "" else previous?.explicitNext.orEmpty()) }
    var status by rememberSaveable { mutableStateOf(if (repeating) "مكتملة" else previous?.status ?: "مكتملة") }
    val available = assets.filter { !it.archived || it.id == assetId }
    Editor {
        Heading(if (previous != null && !repeating) "تعديل الصيانة" else "تسجيل صيانة")
        if (available.isEmpty()) Text("أضف أصلًا نشطًا قبل تسجيل الصيانة")
        var expanded by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = previous == null && available.isNotEmpty()) {
                Text(available.firstOrNull { it.id == selected }?.name ?: "اختر الأصل")
            }
            DropdownMenu(expanded, { expanded = false }) {
                available.forEach { asset ->
                    DropdownMenuItem(text = { Text("${asset.name} · ${asset.category} (#${asset.id})") }, onClick = {
                        selected = asset.id
                        expanded = false
                    })
                }
            }
        }
        Choice("أنواع الصيانة", listOf("صيانة دورية", "تغيير قطعة", "إصلاح عطل", "فحص", "تنظيف")) { type = it }
        Field("نوع الصيانة أو اسم مخصص", type) { type = it }
        Field("القطعة المستبدلة", part) { part = it }
        Field("تاريخ الصيانة (YYYY-MM-DD)", date) { date = it }
        Field("قراءة العداد وقت الصيانة (كم)", odometer, true) { odometer = it }
        Field("التكلفة", cost, true) { cost = it }
        Field("رمز العملة", currency) { currency = it }
        Field("مركز الصيانة أو الفني", technician) { technician = it }
        Field("الملاحظات الفنية", notes) { notes = it }
        Choice(status, listOf("مكتملة", "قيد التنفيذ")) { status = it }
        Heading("الدورة التالية")
        Field("التكرار بالأشهر", months, true) { months = it }
        Field("التكرار بالكيلومترات", interval, true) { interval = it }
        Field("موعد محدد اختياري (YYYY-MM-DD)", next) { next = it }
        Button(enabled = selected > 0, onClick = {
            model.change(leave) {
                val completed = dateInput(date)
                require(!repeating || previous == null || LocalDate.parse(completed) >= LocalDate.parse(previous.date))
                save(Maintenance(id = if (repeating) 0 else previous?.id ?: 0,
                    assetId = selected, type = type.trim(), part = part.trim(), date = completed,
                    odometer = numberInput(odometer), cost = moneyInput(cost) ?: 0,
                    currency = currency.trim().uppercase(Locale.ROOT), technician = technician.trim(),
                    notes = notes.trim(), months = numberInput(months)?.let(Math::toIntExact),
                    intervalKm = numberInput(interval), explicitNext = dateInput(next),
                    cycle = previous?.cycle ?: UUID.randomUUID().toString(), status = status))
            }
        }) { Text("حفظ الصيانة") }
    }
}

internal fun normalizeNumber(value: String) = value.trim().map { char ->
    when {
        char == '٫' -> '.'
        char.isDigit() -> ('0'.code + char.digitToInt()).toChar()
        else -> char
    }
}.joinToString("")
internal fun numberInput(value: String) = normalizeNumber(value).takeIf(String::isNotEmpty)?.toLong()
private val moneyPattern = Regex("[0-9]{1,11}(\\.[0-9]{1,2})?")
internal fun moneyInput(value: String) = normalizeNumber(value).takeIf(String::isNotEmpty)?.let {
    require(moneyPattern.matches(it))
    BigDecimal(it).movePointRight(2).longValueExact()
}
internal fun dateInput(value: String) = normalizeNumber(value).asDate()?.toString().orEmpty()
