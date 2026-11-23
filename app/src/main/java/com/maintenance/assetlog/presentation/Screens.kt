package com.maintenance.assetlog.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import com.maintenance.assetlog.core.Settings
import com.maintenance.assetlog.data.*
import com.maintenance.assetlog.domain.Due
import com.maintenance.assetlog.domain.Priority
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val tabs = listOf(
    Triple("home", "الرئيسية", Icons.Default.Home),
    Triple("assets", "الأصول", Icons.AutoMirrored.Filled.List),
    Triple("history", "سجل الصيانة", Icons.Default.DateRange),
    Triple("settings", "الإعدادات", Icons.Default.Settings)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaintenanceApp(
    model: LogViewModel, settings: Settings, pendingRoute: String?,
    routeHandled: () -> Unit, authenticate: (() -> Unit) -> Unit, export: (String) -> Unit
) {
    val repo by model.repository.collectAsStateWithLifecycle()
    val feedback by model.feedback.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val prepared by model.prepared.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val nav = rememberNavController()
    val current by nav.currentBackStackEntryAsState()
    val route = current?.destination?.route ?: "home"
    LaunchedEffect(feedback) {
        feedback?.let { snack.showSnackbar(it); model.consumeFeedback() }
    }
    if (repo == null) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator()
            else Text("تعذر فتح قاعدة البيانات المشفرة. بقيت البيانات محفوظة.")
        }
        return
    }
    val queries = checkNotNull(repo).queries
    val assets by remember(queries) { queries.assets() }.collectAsStateWithLifecycle(emptyList())
    LaunchedEffect(pendingRoute) {
        pendingRoute?.let { nav.navigate(it); routeHandled() }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(title = { Text(tabs.find { it.first == route }?.second ?: "سجل الصيانة") },
                navigationIcon = {
                    if (tabs.none { it.first == route }) {
                        IconButton(onClick = { nav.popBackStack() }, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") }
                    }
                })
        },
        bottomBar = {
            NavigationBar {
                tabs.forEach { (path, label, icon) ->
                    NavigationBarItem(selected = route == path, enabled = !busy, onClick = {
                        nav.navigate(path) { popUpTo("home"); launchSingleTop = true }
                    }, icon = { Icon(icon, null) }, label = { Text(label) })
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            NavHost(nav, startDestination = "home") {
                composable("home") {
                    Home(queries, assets, settings, { nav.navigate("asset-edit/0") },
                        { nav.navigate("log-edit/0/0/0") }, { nav.navigate("maintenance/$it") })
                }
                composable("assets") {
                    Assets(assets, { nav.navigate("asset-edit/0") }, { nav.navigate("asset/$it") })
                }
                composable("history") {
                    History(queries, assets, { nav.navigate("log-edit/0/0/0") }, { nav.navigate("maintenance/$it") })
                }
                composable("asset/{id}") { entry ->
                    val id = entry.number("id")
                    AssetDetails(queries, id, model, { nav.popBackStack() }, { nav.navigate("asset-edit/$id") },
                        { nav.navigate("log-edit/0/$id/0") }, { nav.navigate("maintenance/$it") })
                }
                composable("asset-edit/{id}") { entry ->
                    val id = entry.number("id")
                    if (id == 0L) AssetEditor(Asset(name = "", category = ""), model) { nav.popBackStack() }
                    else {
                        val asset by remember(id) { queries.asset(id) }.collectAsStateWithLifecycle(null)
                        asset?.let { AssetEditor(it, model) { nav.popBackStack() } }
                    }
                }
                composable("maintenance/{id}") { entry ->
                    val id = entry.number("id")
                    MaintenanceDetails(queries, assets, id, settings, model, { nav.popBackStack() },
                        { nav.navigate("log-edit/$id/0/0") },
                        { nav.navigate("log-edit/0/${it.assetId}/$id") })
                }
                composable("log-edit/{id}/{asset}/{repeat}") { entry ->
                    val id = entry.number("id")
                    val repeatId = entry.number("repeat")
                    if (id == 0L && repeatId == 0L) {
                        MaintenanceEditor(null, false, entry.number("asset"), assets, settings, model) { nav.popBackStack() }
                    } else {
                        val previous by remember(id, repeatId) {
                            queries.maintenance(if (id != 0L) id else repeatId)
                        }.collectAsStateWithLifecycle(null)
                        previous?.let {
                            MaintenanceEditor(it, repeatId != 0L, it.assetId, assets, settings, model) { nav.popBackStack() }
                        }
                    }
                }
                composable("settings") { SettingsScreen(model, settings, authenticate, export) }
            }
        }
    }
    prepared?.let { snapshot ->
        Confirm("استعادة البيانات",
            "ستُستبدل البيانات الحالية بـ ${snapshot.assets.size} أصل و${snapshot.history.size} سجل صيانة.",
            model::discardBackup, model::restore)
    }
}

private fun androidx.navigation.NavBackStackEntry.number(key: String) =
    arguments?.getString(key)?.toLongOrNull() ?: 0L

@Composable
private fun Home(
    queries: LogDao, assets: List<AssetItem>, settings: Settings,
    addAsset: () -> Unit, addLog: () -> Unit, openLog: (Long) -> Unit
) {
    val cycles by remember { queries.cycles() }.collectAsStateWithLifecycle(emptyList())
    val recent by remember { queries.history(5) }.collectAsStateWithLifecycle(emptyList())
    val totals by remember { queries.totals() }.collectAsStateWithLifecycle(emptyList())
    val byId = remember(assets) { assets.associateBy { it.id } }
    val today = currentDate()
    val upcoming by produceState(emptyList<Pair<Maintenance, Due>>(), cycles, byId, today, settings.days) {
        value = withContext(Dispatchers.Default) {
            cycles.map { it to it.due(today, byId[it.assetId]?.odometer, settings.days) }
                .sortedWith(compareBy({ it.second.priority }, { it.second.days ?: Long.MAX_VALUE }, { it.second.kilometers ?: Long.MAX_VALUE }))
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("نظرة عامة", style = MaterialTheme.typography.headlineMedium) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric("الأصول", assets.size.toString(), Modifier.weight(1f))
                Metric("تحتاج صيانة قريبًا", upcoming.filter { it.second.priority in listOf(Priority.SOON, Priority.TODAY) }.distinctBy { it.first.assetId }.size.toString(), Modifier.weight(1f))
                Metric("الصيانات المتأخرة", upcoming.count { it.second.priority == Priority.LATE }.toString(), Modifier.weight(1f))
            }
        }
        item { Metric("إجمالي تكاليف الصيانة", totals.costText(), Modifier.fillMaxWidth()) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = addAsset) { Text("إضافة أصل") }
                OutlinedButton(onClick = addLog) { Text("تسجيل صيانة") }
            }
        }
        item { Heading("الصيانات القادمة") }
        if (upcoming.isEmpty()) item { Text("لا توجد دورات صيانة نشطة") }
        items(upcoming.take(20), key = { "cycle-${it.first.id}" }) { (log, due) ->
            Entry(log.type, byId[log.assetId]?.name.orEmpty(), { openLog(log.id) }) {
                DueLabel(due)
            }
        }
        item { Heading("آخر عمليات الصيانة") }
        if (recent.isEmpty()) item { Text("لا توجد عمليات مسجلة") }
        items(recent, key = { "recent-${it.id}" }) { log ->
            Entry(log.type, "${byId[log.assetId]?.name.orEmpty()} · ${log.date}", { openLog(log.id) })
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge)
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Assets(assets: List<AssetItem>, add: () -> Unit, open: (Long) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf("الكل") }
    var category by rememberSaveable { mutableStateOf("الكل") }
    var sort by rememberSaveable { mutableStateOf("الاسم") }
    val results by produceState(emptyList<AssetItem>(), assets, search, status, category, sort) {
        value = withContext(Dispatchers.Default) {
            val matching = assets.filter {
                (status == "الكل" || it.archived == (status == "مؤرشف")) &&
                    (category == "الكل" || category == it.category) &&
                    (it.name.contains(search, true) || it.category.contains(search, true))
            }
            if (sort == "الأحدث") matching.sortedByDescending(AssetItem::id) else matching
        }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Button(onClick = add) { Text("إضافة أصل") } }
        item { Field("بحث عن أصل", search) { search = it } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Choice(status, listOf("الكل", "نشط", "مؤرشف")) { status = it }
                Choice("التصنيف: $category", listOf("الكل") + assets.map { it.category }.distinct().sorted()) { category = it }
                Choice(sort, listOf("الاسم", "الأحدث")) { sort = it }
            }
        }
        if (results.isEmpty()) item { Text("لا توجد أصول مطابقة") }
        items(results, key = AssetItem::id) {
            Entry(it.name, "${it.category} · ${if (it.archived) "مؤرشف" else "نشط"}", { open(it.id) })
        }
    }
}

@Composable
private fun History(queries: LogDao, assets: List<AssetItem>, add: () -> Unit, open: (Long) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf("") }
    var limit by rememberSaveable { mutableIntStateOf(50) }
    val names = remember(assets) { assets.associate { it.id to it.name } }
    val logs by remember(limit, search, date, type, names) {
        queries.filteredHistory(search.trim(), date.trim(), type, limit)
    }.collectAsStateWithLifecycle(emptyList())
    val types by remember { queries.types() }.collectAsStateWithLifecycle(emptyList())
    val totals by remember(search, date, type, names) {
        queries.filteredTotals(search.trim(), date.trim(), type)
    }.collectAsStateWithLifecycle(emptyList())
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Button(onClick = add) { Text("تسجيل صيانة") } }
        item { Field("بحث بالأصل أو القطعة أو الملاحظات", search) { search = it } }
        item { Field("تصفية بالتاريخ (سنة أو شهر أو يوم)", date) { date = it } }
        item { Choice(type.ifEmpty { "كل الأنواع" }, listOf("كل الأنواع") + types) { type = it.takeUnless { it == "كل الأنواع" }.orEmpty() } }
        item { Text("المجموع: ${totals.costText()}") }
        if (logs.isEmpty()) item { Text("لا توجد سجلات مطابقة") }
        items(logs, key = Maintenance::id) {
            Entry(it.type, "${names[it.assetId].orEmpty()} · ${it.date} · ${money(it.cost)} ${it.currency}", { open(it.id) })
        }
        if (logs.size == limit) item { TextButton(onClick = { limit += 50 }) { Text("عرض المزيد") } }
    }
}

@Composable
internal fun Heading(text: String) = Text(text, style = MaterialTheme.typography.titleLarge)

@Composable
internal fun Entry(title: String, subtitle: String, onClick: () -> Unit, more: @Composable () -> Unit = {}) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium)
            more()
        }
    }
}

@Composable
internal fun Field(label: String, value: String, numeric: Boolean = false, change: (String) -> Unit) {
    OutlinedTextField(value, { if (it.length <= 2000) change(it) }, Modifier.fillMaxWidth(),
        label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text))
}

@Composable
internal fun Choice(label: String, values: List<String>, choose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(label) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            values.forEach { value ->
                DropdownMenuItem(text = { Text(value) }, onClick = { expanded = false; choose(value) })
            }
        }
    }
}

@Composable
internal fun Confirm(title: String, message: String, dismiss: () -> Unit, accept: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = accept) { Text("تأكيد") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("إلغاء") } })
}

@Composable
internal fun DueLabel(due: Due) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val color = when (due.priority) {
        Priority.LATE -> MaterialTheme.colorScheme.error
        Priority.TODAY, Priority.SOON -> androidx.compose.ui.graphics.Color(if (dark) 0xFFFFB86B else 0xFF8B4600)
        Priority.GOOD -> androidx.compose.ui.graphics.Color(if (dark) 0xFF84DB9C else 0xFF156B36)
        Priority.NONE -> return Text("دورة غير متاحة", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val remaining = listOfNotNull(
        due.days?.let { if (it < 0) "تجاوز الموعد بـ ${-it} يوم" else "$it يوم متبقٍ" },
        due.kilometers?.let { if (it < 0) "تجاوز العداد بـ ${-it} كم" else "$it كم متبقٍ" }
    ).joinToString(" · ")
    Text("${due.priority.label} · $remaining", color = color)
}

internal fun money(cents: Long) = BigDecimal.valueOf(cents, 2).toPlainString()
internal fun List<CostTotal>.costText() = joinToString(" · ") { "${money(it.total)} ${it.currency}" }.ifEmpty { "٠" }

@Composable
internal fun currentDate(): LocalDate {
    val date by produceState(LocalDate.now()) {
        while (true) {
            delay(30_000)
            value = LocalDate.now()
        }
    }
    return date
}
