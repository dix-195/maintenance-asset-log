package com.maintenance.assetlog.presentation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.maintenance.assetlog.domain.paidItems
import com.maintenance.assetlog.domain.Priority
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val tabs = listOf(
    Triple("home", "الرئيسية", Icons.Default.Home),
    Triple("operations", "العمليات", Icons.AutoMirrored.Filled.List),
    Triple("add", "إضافة", Icons.Default.Add),
    Triple("reports", "التقارير", Icons.Default.DateRange),
    Triple("more", "المزيد", Icons.Default.Settings)
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
            if (busy) Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(4) {
                    Surface(Modifier.fillMaxWidth().height(if (it == 0) 112.dp else 58.dp),
                        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {}
                }
            }
            else Text("تعذر فتح قاعدة البيانات المشفرة. بقيت البيانات محفوظة.")
        }
        return
    }
    val repository = checkNotNull(repo)
    val queries = repository.queries
    val ledger = repository.ledger
    val sections by remember(ledger) { ledger.sections() }.collectAsStateWithLifecycle(emptyList())
    val services by remember(ledger) { ledger.services() }.collectAsStateWithLifecycle(emptyList())
    val operations by remember(ledger) { ledger.operations() }.collectAsStateWithLifecycle(emptyList())
    val legacy by remember(queries) { queries.allHistory() }.collectAsStateWithLifecycle(emptyList())
    val legacyDue by remember(queries) { queries.cycles() }.collectAsStateWithLifecycle(emptyList())
    val assets by remember(queries) { queries.assets() }.collectAsStateWithLifecycle(emptyList())
    val paid by produceState(emptyList<com.maintenance.assetlog.domain.PaidItem>(), operations, legacy, assets) {
        value = withContext(Dispatchers.Default) { paidItems(operations, legacy, assets) }
    }
    val locations = remember(operations) { operations.filter { it.sectionId == HOUSE }.map { it.subject }
        .filter { it.isNotBlank() }.distinct().sorted() }
    var draft by remember { mutableStateOf<OperationDraft?>(null) }
    var editingSection by rememberSaveable { mutableStateOf<String?>(null) }
    var editingService by remember { mutableStateOf<Pair<Section, Service?>?>(null) }
    val add = {
        val section = sections.firstOrNull { it.id == PERSONAL && !it.archived }
            ?: sections.firstOrNull { !it.archived }
        if (section != null) draft = OperationDraft(section.id) else editingSection = ""
    }
    LaunchedEffect(pendingRoute) {
        pendingRoute?.let { nav.navigate(it); routeHandled() }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            if (route == "home" || route == "operations") FloatingActionButton(onClick = add) {
                Icon(Icons.Default.Add, "إضافة عملية سريعة")
            }
        },
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(title = { Text(if (route == "home") "ميزان" else tabs.find { it.first == route }?.second ?: "ميزان") },
                navigationIcon = {
                    if (tabs.none { it.first == route }) {
                        IconButton(onClick = { nav.popBackStack() }, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") }
                    }
                })
        },
        bottomBar = {
            Surface(shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), shadowElevation = 3.dp) {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                tabs.forEach { (path, label, icon) ->
                    NavigationBarItem(selected = route == path, enabled = !busy, onClick = {
                        if (path == "add") add()
                        else nav.navigate(path) { popUpTo("home"); launchSingleTop = true }
                    }, icon = {
                        val size by androidx.compose.animation.core.animateDpAsState(
                            if (route == path) 25.dp else 22.dp,
                            tween(if (settings.reduceMotion) 0 else 220), label = "nav-icon"
                        )
                        Icon(icon, null, Modifier.size(size))
                    }, label = { Text(label, maxLines = 1) })
                }
            }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            val motion = !settings.reduceMotion
            NavHost(nav, startDestination = "home",
                enterTransition = { if (motion) fadeIn(tween(220)) + slideInHorizontally(tween(220)) { -it / 12 } else EnterTransition.None },
                exitTransition = { if (motion) fadeOut(tween(180)) else ExitTransition.None },
                popEnterTransition = { if (motion) fadeIn(tween(220)) else EnterTransition.None },
                popExitTransition = { if (motion) fadeOut(tween(220)) + slideOutHorizontally(tween(220)) { -it / 12 } else ExitTransition.None }
            ) {
                composable("home") {
                    Dashboard(settings, sections, operations, paid, assets, legacyDue,
                        { nav.navigate("section/$it") }, { editingSection = "" },
                        { nav.navigate("operation/$it") }, { nav.navigate("maintenance/$it") })
                }
                composable("operations") {
                    OperationsScreen(sections, operations, paid.filter { it.legacyId != null },
                        { nav.navigate("operation/$it") }, { nav.navigate("maintenance/$it") })
                }
                composable("reports") { ReportsScreen(sections, paid, settings) }
                composable("more") {
                    Page {
                        item { Entry("الإعدادات", "التفضيلات والنسخ الاحتياطي", { nav.navigate("settings") }) }
                        item { Entry("المركبات والأجهزة", "إدارة المركبات والسجلات المرتبطة بها", { nav.navigate("assets") }) }
                        item { Heading("إدارة الأقسام") }
                        item { TextButton(onClick = { editingSection = "" }) { Text("إضافة قسم جديد") } }
                        items(sections, key = Section::id) { section ->
                            Entry(section.name, if (section.archived) "مؤرشف" else "نشط", { editingSection = section.id })
                        }
                    }
                }
                composable("section/{key}") { entry ->
                    sections.find { it.id == entry.arguments?.getString("key") }?.let { section ->
                        SectionScreen(section, services.filter { it.sectionId == section.id }, operations.filter { it.sectionId == section.id },
                            { draft = OperationDraft(section.id, service = it) }, { editingSection = section.id },
                            { editingService = section to it }, { nav.navigate("operation/$it") },
                            { nav.navigate("assets") }, { nav.navigate("history") })
                    }
                }
                composable("operation/{key}") { entry ->
                    val key = entry.arguments?.getString("key").orEmpty()
                    val operation by remember(ledger, key) { ledger.operation(key) }.collectAsStateWithLifecycle(null)
                    val row = operation
                    if (row != null) {
                        OperationSheet(OperationDraft(row.sectionId, row), sections, assets, settings, model, { nav.popBackStack() }, locations)
                    } else {
                        Page { item { Text("العملية غير متاحة.") } }
                    }
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
    draft?.let { OperationSheet(it, sections, assets, settings, model, { draft = null }, locations) }
    editingSection?.let { id -> SectionSheet(sections.find { it.id == id }, model) { editingSection = null } }
    editingService?.let { (section, service) -> ServiceSheet(section, service, model) { editingService = null } }
    prepared?.let { snapshot ->
        Confirm("استعادة البيانات",
            "ستُستبدل البيانات الحالية بـ ${snapshot.operations.size} عملية جديدة و${snapshot.history.size} عملية صيانة سابقة.",
            model::discardBackup, model::restore)
    }
}

private fun androidx.navigation.NavBackStackEntry.number(key: String) =
    arguments?.getString(key)?.toLongOrNull() ?: 0L

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
        item { Button(onClick = add) { Text("إضافة مركبة أو جهاز") } }
        item { Field("بحث عن مركبة أو جهاز", search) { search = it } }
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
        item { Field("بحث بالمركبة أو الجهاز أو القطعة أو الملاحظات", search) { search = it } }
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
