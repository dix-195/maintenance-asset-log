package com.maintenance.assetlog.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.maintenance.assetlog.core.Settings
import com.maintenance.assetlog.data.*
import com.maintenance.assetlog.domain.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Dashboard(
    settings: Settings, sections: List<Section>, operations: List<Operation>,
    paid: List<PaidItem>, assets: List<AssetItem>, legacyDue: List<Maintenance>,
    openSection: (String) -> Unit, addSection: () -> Unit, openOperation: (String) -> Unit,
    openLegacy: (Long) -> Unit
) {
    val today = currentDate()
    val monthly = remember(paid, today) { paid.inMonth(YearMonth.from(today)) }
    val total = monthly.filter { it.currency == settings.currency }.sumOf { it.amount }
    val outstanding = operations.filter(Operation::outstanding).sortedBy { it.dueOn.ifBlank { it.endsOn.ifBlank { "9999" } } }
    val vehicleKm = assets.associate { it.id to it.odometer }
    val upcoming = outstanding.filter { reminderLabel(it, today, vehicleKm[it.vehicleId]) != null }
    val legacyUpcoming = legacyDue.mapNotNull { entry ->
        val due = entry.due(today, vehicleKm[entry.assetId], settings.days)
        if (due.priority in listOf(Priority.LATE, Priority.TODAY, Priority.SOON)) entry to due else null
    }.sortedBy { it.second.priority }
    var entered by rememberSaveable { mutableStateOf(false) }
    var visible by remember { mutableStateOf(entered || settings.reduceMotion) }
    LaunchedEffect(Unit) { visible = true; entered = true }
    Page {
        item {
            Text(settings.userName.takeIf { it.isNotBlank() }?.let { "أهلًا، $it" } ?: "أهلًا بك في ميزان",
                style = MaterialTheme.typography.headlineSmall)
            Text("كل تفاصيلك في توازن", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("مصروفات الشهر الحالي", style = MaterialTheme.typography.titleMedium)
                    val reveal by animateFloatAsState(if (visible) 1f else 0f,
                        tween(if (settings.reduceMotion) 0 else 280), label = "total")
                    val shownTotal = if (settings.reduceMotion || reveal == 1f) total else (total * reveal).toLong()
                    Text("${money(shownTotal)} ${settings.currency}", style = MaterialTheme.typography.headlineMedium)
                    val other = monthly.filterNot { it.currency == settings.currency }
                    if (other.isNotEmpty()) Text(totalsText(other, settings.currency))
                    settings.budget?.let { budget ->
                        if (budget > 0) {
                            val ratio = total.toDouble() / budget
                            LinearProgressIndicator(progress = { ratio.coerceIn(0.0, 1.0).toFloat() },
                                modifier = Modifier.fillMaxWidth())
                            Text("المتبقي ${money(budget - total)} ${settings.currency} · ${(ratio * 100).toLong()}٪")
                            if (ratio >= .8) Text(if (ratio >= 1) "بلغت حد الميزانية الشهرية" else "اقتربت من حد الميزانية")
                        }
                    }
                    Text("${upcoming.size + legacyUpcoming.size} التزام قادم أو متأخر · ${outstanding.count { it.state == PaymentState.DUE || it.dueOn.asDate()?.isBefore(today) == true }} فاتورة غير مسددة")
                }
            }
        }
        item { Heading("أقسامك") }
        val visibleSections = sections.filterNot { it.archived }
            .sortedBy { defaultSections.indexOfFirst { preset -> preset.id == it.id }.let { n -> if (n < 0) Int.MAX_VALUE else n } }
        items(visibleSections, key = Section::id) { section ->
            val delay = (visibleSections.indexOf(section) * 20).coerceAtMost(100)
            AnimatedVisibility(visible,
                enter = if (settings.reduceMotion) fadeIn(tween(0)) else fadeIn(tween(240, delay)) +
                    slideInVertically(tween(240, delay)) { it / 5 } + scaleIn(tween(240, delay), initialScale = .97f)) {
                SectionCard(section, settings.reduceMotion) { openSection(section.id) }
            }
        }
        item {
            OutlinedButton(onClick = addSection, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null)
                Text("إضافة قسم جديد", Modifier.padding(start = 8.dp))
            }
        }
        item { Heading("الالتزامات القادمة") }
        if (upcoming.isEmpty() && legacyUpcoming.isEmpty()) item { Text("لا توجد التزامات مجدولة.") }
        items(upcoming.take(4), key = { "due-${it.id}" }) { row ->
            Entry(row.title, reminderLabel(row, today, vehicleKm[row.vehicleId]).orEmpty(), { openOperation(row.id) })
        }
        items(legacyUpcoming.take((4 - upcoming.size).coerceAtLeast(0)), key = { "legacy-due-${it.first.id}" }) { (row, due) ->
            Entry(row.type, "الصيانة · ${due.priority.label}", { openLegacy(row.id) })
        }
        item { Heading("آخر الدفعات") }
        if (paid.isEmpty()) item { Text("ستظهر دفعاتك هنا بعد تسجيلها.") }
        items(paid.sortedByDescending { it.date }.take(4), key = { "paid-${it.id}" }) { row ->
            Entry(row.title, "${money(row.amount)} ${row.currency} · ${row.date}",
                { row.legacyId?.let(openLegacy) ?: openOperation(row.id) })
        }
        if (monthly.any { it.currency == settings.currency && it.amount > 0 }) {
            item {
                Heading("توزيع مصروفات الشهر")
                Breakdown(monthly, sections, settings.currency, settings.reduceMotion)
            }
        }
    }
}

@Composable
private fun SectionCard(section: Section, reduced: Boolean, open: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && !reduced) .98f else 1f, tween(180), label = "section press")
    Card(onClick = open, modifier = Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale },
        shape = RoundedCornerShape(22.dp), interactionSource = interaction,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = RoundedCornerShape(14.dp), color = Color(section.color).copy(alpha = .16f)) {
                Icon(sectionIcon(section.icon), null, Modifier.padding(12.dp), tint = MaterialTheme.colorScheme.onSurface)
            }
            Column(Modifier.weight(1f)) {
                Text(section.name, style = MaterialTheme.typography.titleMedium)
                if (section.description.isNotBlank()) Text(section.description, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun sectionIcon(icon: String): ImageVector = when (icon) {
    "منزل" -> Icons.Default.Home
    "جوال" -> Icons.Default.Phone
    "مصروفات" -> Icons.Default.Star
    "سيارة" -> Icons.Default.Build
    else -> Icons.Default.FavoriteBorder
}

@Composable
internal fun SectionScreen(
    section: Section, templates: List<Service>, operations: List<Operation>,
    addOperation: (Service?) -> Unit, editSection: () -> Unit, editService: (Service?) -> Unit,
    openOperation: (String) -> Unit, manageVehicles: () -> Unit, legacyHistory: () -> Unit
) {
    Page {
        item {
            Heading(section.name)
            if (section.description.isNotBlank()) Text(section.description)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { addOperation(null) }) { Text("إضافة عملية") }
                TextButton(onClick = editSection) { Text("تعديل القسم") }
            }
            if (section.id == CAR) {
                TextButton(onClick = manageVehicles) { Text("إدارة المركبات") }
                TextButton(onClick = legacyHistory) { Text("عمليات الصيانة السابقة") }
            }
        }
        if (templates.isNotEmpty() || section.id != PERSONAL) {
            item {
                Heading("الخدمات")
                TextButton(onClick = { editService(null) }) { Text("إضافة خدمة") }
            }
            items(templates, key = Service::id) { service ->
                Entry(service.name, service.type.ifBlank { "قالب خدمة" }, { addOperation(service) }) {
                    TextButton(onClick = { editService(service) }) { Text("تعديل الخدمة") }
                }
            }
        }
        item { Heading("العمليات") }
        if (operations.isEmpty()) item { Text("لا توجد عمليات في هذا القسم.") }
        items(operations, key = Operation::id) { operation ->
            Entry(operation.title, operationLabel(operation), { openOperation(operation.id) })
        }
    }
}

@Composable
internal fun OperationsScreen(
    sections: List<Section>, operations: List<Operation>, legacy: List<PaidItem>,
    open: (String) -> Unit, openLegacy: (Long) -> Unit
) {
    var search by rememberSaveable { mutableStateOf("") }
    var month by rememberSaveable { mutableStateOf("") }
    var sectionId by rememberSaveable { mutableStateOf("") }
    var state by rememberSaveable { mutableStateOf("") }
    val names = sections.associate { it.id to it.name }
    val rows = operations.filter {
        (sectionId.isEmpty() || sectionId == it.sectionId) && (state.isEmpty() || state == it.state.name) &&
            (it.title.contains(search, true) || it.notes.contains(search, true) || it.subject.contains(search, true)) &&
            it.paidOn.ifBlank { it.dueOn.ifBlank { it.date } }.startsWith(normalizeNumber(month))
    }
    val old = legacy.filter {
        (sectionId.isEmpty() || sectionId == it.sectionId) && (state.isEmpty() || state == PaymentState.PAID.name) &&
            it.title.contains(search, true) && it.date.toString().startsWith(normalizeNumber(month))
    }
    Page {
        item { Field("ابحث في العمليات", search) { search = it } }
        item { Field("التاريخ أو الشهر (YYYY-MM)", month) { month = it } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Select(names[sectionId] ?: "كل الأقسام", listOf("" to "كل الأقسام") + sections.map { it.id to it.name }) { sectionId = it }
                Select(PaymentState.entries.find { it.name == state }?.label ?: "كل الحالات",
                    listOf("" to "كل الحالات") + PaymentState.entries.map { it.name to it.label }) { state = it }
            }
        }
        if (rows.isEmpty() && old.isEmpty()) item { Text("لا توجد عمليات مطابقة.") }
        val combined = (rows.map { Triple(it.paidOn.ifBlank { it.dueOn.ifBlank { it.date } }, it.id, it) } +
            old.map { Triple(it.date.toString(), it.id, it) }).sortedByDescending { it.first }
        items(combined, key = { it.second }) { (_, _, item) ->
            when (item) {
                is Operation -> Entry(item.title, "${names[item.sectionId].orEmpty()} · ${operationLabel(item)}", { open(item.id) })
                is PaidItem -> Entry(item.title, "${names[item.sectionId] ?: "السجلات السابقة"} · ${money(item.amount)} ${item.currency} · ${item.date}",
                    { item.legacyId?.let(openLegacy) })
            }
        }
    }
}

@Composable
internal fun ReportsScreen(sections: List<Section>, paid: List<PaidItem>, settings: Settings) {
    val today = currentDate()
    var selectedMonth by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    var selectedSection by rememberSaveable { mutableStateOf("") }
    val month = YearMonth.parse(selectedMonth)
    val filtered = paid.filter { selectedSection.isEmpty() || it.sectionId == selectedSection }
    val monthly = filtered.inMonth(month)
    val week = today.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
    Page {
        item {
            Heading("تقارير المصروفات")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { selectedMonth = month.minusMonths(1).toString() }) { Text("السابق") }
                Text(selectedMonth)
                TextButton(onClick = { selectedMonth = month.plusMonths(1).toString() }) { Text("التالي") }
            }
            Select(sections.find { it.id == selectedSection }?.name ?: "كل الأقسام",
                listOf("" to "كل الأقسام") + sections.map { it.id to it.name }) { selectedSection = it }
        }
        item {
            Text("اليوم · ${totalsText(filtered.filter { it.date == today }, settings.currency)}")
            Text("هذا الأسبوع · ${totalsText(filtered.filter { it.date in week..today }, settings.currency)}")
            Text("الشهر المختار · ${totalsText(monthly, settings.currency)}")
            Text("الشهر السابق · ${totalsText(filtered.inMonth(month.minusMonths(1)), settings.currency)}")
        }
        item {
            Heading("التوزيع حسب القسم · ${settings.currency}")
            Breakdown(monthly, sections, settings.currency, settings.reduceMotion) {
                selectedSection = if (selectedSection == it) "" else it
            }
        }
    }
}

@Composable
private fun Breakdown(items: List<PaidItem>, sections: List<Section>, currency: String, reduced: Boolean, select: ((String) -> Unit)? = null) {
    val totals = items.filter { it.currency == currency && it.amount > 0 }.groupBy { it.sectionId }
        .mapValues { (_, rows) -> rows.sumOf { it.amount } }
    val total = totals.values.sum()
    if (total == 0L) Text("لا توجد دفعات بهذه العملة.")
    totals.entries.sortedByDescending { it.value }.forEach { (id, amount) ->
        val section = sections.find { it.id == id }
        val progress by animateFloatAsState(amount.toFloat() / total,
            tween(if (reduced) 0 else 280), label = "distribution")
        Column(Modifier.fillMaxWidth().then(if (select != null) Modifier.clickable { select(id) } else Modifier).padding(vertical = 8.dp)) {
            Text("${section?.name ?: "قسم محفوظ"} · ${money(amount)}")
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun Page(content: LazyListScope.() -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp), content = content)
}

@Composable
internal fun <T> Select(label: String, choices: List<Pair<T, String>>, choose: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(label) }
        DropdownMenu(expanded, { expanded = false }) {
            choices.forEach { (value, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; choose(value) })
            }
        }
    }
}

private fun totalsText(rows: List<PaidItem>, currency: String) = rows.groupBy { it.currency }.entries.sortedBy { it.key }
    .joinToString(" · ") { (code, values) -> "${money(values.sumOf { it.amount })} $code" }
    .ifBlank { "0.00 $currency" }

internal fun operationLabel(operation: Operation) =
    "${money(operation.amount)} ${operation.currency} · ${operation.state.label} · ${operation.paidOn.ifBlank { operation.dueOn.ifBlank { operation.date } }}"

private fun reminderLabel(operation: Operation, today: LocalDate, currentKm: Long?): String? {
    val date = operation.dueOn.ifBlank { operation.endsOn }.asDate()
    val km = operation.nextKm
    val dateText = date?.let { if (it < today) "متأخر منذ $it" else "الاستحقاق $it" }
    val kmText = km?.let { target ->
        if (currentKm != null && currentKm >= target) "بلغت قراءة العداد $target كم" else "عند $target كم"
    }
    return listOfNotNull(dateText, kmText).joinToString(" · ").ifBlank { null }
}
