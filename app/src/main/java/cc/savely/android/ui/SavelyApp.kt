package cc.savely.android.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import cc.savely.android.BuildConfig
import cc.savely.android.data.AndroidSessionStore
import cc.savely.android.data.BackendConfig
import cc.savely.android.data.SupabaseApi
import cc.savely.android.finance.FinanceScreen
import cc.savely.android.modules.ModuleScreen
import cc.savely.android.release.AppVersion
import cc.savely.android.release.PublishedRelease
import cc.savely.android.release.ReleaseRepository
import cc.savely.android.release.ReleaseScreen
import kotlinx.coroutines.launch

data class Destination(val route: String, val label: String, val permission: String? = null)

val destinations = listOf(
    Destination("dashboard", "Dashboard"), Destination("transactions", "Transazioni"),
    Destination("budget", "Budget"), Destination("categories", "Categorie"),
    Destination("recurring", "Ricorrenze"), Destination("charts", "Grafici"),
    Destination("portfolio", "Portafoglio"), Destination("net-worth", "Patrimonio"),
    Destination("insights", "Insights"), Destination("poker", "Poker", "poker"),
    Destination("fire", "FIRE", "fire"), Destination("fumo", "Fumo", "fumo"),
    Destination("tcg", "TCG", "tcg"), Destination("libreria", "Libreria", "libreria"),
    Destination("couple", "Coppia", "couple_expenses"), Destination("admin", "Admin", "admin"),
    Destination("settings", "Impostazioni"),
    Destination("app-release", "Versione e aggiornamenti"),
)

internal fun canNavigate(route: String, permissions: Map<String, Boolean>): Boolean =
    route == "modules" || destinations.any { it.route == route && (it.permission == null || permissions[it.permission] == true) }

@Composable
fun SavelyApp() {
    val context = LocalContext.current.applicationContext
    val preferences = remember { context.getSharedPreferences("appearance", Context.MODE_PRIVATE) }
    var theme by rememberSaveable { mutableStateOf(preferences.getString("theme", "dark") ?: "dark") }
    var legalDocument by rememberSaveable { mutableStateOf<String?>(null) }
    SavelyTheme(theme) {
        Surface(Modifier.fillMaxSize()) {
            val api = remember { runCatching {
                SupabaseApi(BackendConfig(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY, BuildConfig.WEB_URL), AndroidSessionStore(context))
            } }
            if (api.isFailure) {
                Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Savely", style = MaterialTheme.typography.displayMedium)
                    Text("Questa build non contiene una configurazione valida. Installa l’APK configurato del progetto.")
                }
            } else {
                val model: AppViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        @Suppress("UNCHECKED_CAST") return AppViewModel(api.getOrThrow()) as T
                    }
                })
                val state by model.state.collectAsStateWithLifecycle()
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                    if (state.initialized && state.session != null && state.mfaFactor == null) model.restore()
                }
                when {
                    legalDocument != null -> {
                        BackHandler { legalDocument = null }
                        LegalScreen(legalDocument!!) { legalDocument = null }
                    }
                    !state.initialized -> LoadingPane()
                    state.session == null -> Scaffold { padding ->
                        Surface(Modifier.padding(padding)) {
                            AuthScreen(state, model::signIn, model::signUp, model::resetPassword, model::restore) { legalDocument = it }
                        }
                    }
                    state.mfaFactor != null -> Scaffold { padding -> Surface(Modifier.padding(padding)) {
                        MfaChallengeScreen(state, model::verifyMfa, model::signOut)
                    } }
                    else -> key(state.session?.userId) {
                        MainShell(model, state, theme, { value -> theme = value; preferences.edit().putString("theme", value).apply() }, { legalDocument = it })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainShell(model: AppViewModel, state: AppState, theme: String, onTheme: (String) -> Unit, onLegal: (String) -> Unit,
    releaseRepository: ReleaseRepository = remember { ReleaseRepository() }) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: "dashboard"
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val allowed = destinations.filter { it.permission == null || state.permissions[it.permission] == true }
    val accessAuditWarning by model.api.accessAuditWarning.collectAsStateWithLifecycle()
    var latestRelease by remember { mutableStateOf<PublishedRelease?>(null) }
    var releaseChecked by remember { mutableStateOf(false) }
    // One public read per signed-in app session; failed checks never interrupt finance operations.
    LaunchedEffect(Unit) {
        try { latestRelease = releaseRepository.latest(); releaseChecked = true }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { /* Manual retry remains available on the version screen. */ }
    }
    fun navigate(target: String) {
        nav.navigate(target) { popUpTo("dashboard") { saveState = true }; launchSingleTop = true; restoreState = true }
    }
    BackHandler(drawer.isOpen) { scope.launch { drawer.close() } }
    LaunchedEffect(route, state.permissions) {
        if (!canNavigate(route, state.permissions)) navigate("dashboard")
    }
    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        ModalDrawerSheet(Modifier.widthIn(max = 340.dp).verticalScroll(rememberScrollState())) {
            Text("Savely", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(24.dp))
            Text(state.session?.email ?: "", modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            allowed.forEach { destination ->
                NavigationDrawerItem(label = { Text(destination.label) }, selected = route == destination.route,
                    onClick = { navigate(destination.route); scope.launch { drawer.close() } },
                    modifier = Modifier.padding(horizontal = 12.dp))
            }
            TextButton(model::signOut, enabled = !state.busy, modifier = Modifier.padding(16.dp)) { Text("Esci dall’account") }
        }
    }) {
        Scaffold(topBar = {
            TopAppBar(title = { Text(allowed.firstOrNull { it.route == route }?.label ?: "Savely") },
                navigationIcon = { IconButton({ scope.launch { drawer.open() } }) { Icon(Icons.Outlined.Menu, "Apri menu") } })
        }, bottomBar = {
            NavigationBar {
                listOf("dashboard", "transactions", "modules", "settings").forEach { target ->
                    val label = when (target) { "dashboard" -> "Panoramica"; "transactions" -> "Movimenti"; "modules" -> "Moduli"; else -> "Account" }
                    val icon = when (target) { "dashboard" -> Icons.Outlined.Dashboard; "transactions" -> Icons.AutoMirrored.Outlined.ListAlt;
                        "modules" -> Icons.Outlined.Widgets; else -> Icons.Outlined.Settings }
                    NavigationBarItem(selected = route == target, onClick = { navigate(target) },
                        icon = { Icon(icon, null) }, label = { Text(label) })
                }
            }
        }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (accessAuditWarning != null) {
                    Text(accessAuditWarning!!, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    TextButton(model.api::dismissAccessAuditWarning) { Text("Chiudi avviso") }
                }
                if (latestRelease?.version?.let { remote -> AppVersion.parse(BuildConfig.VERSION_NAME)?.let { remote > it } } == true) {
                    TextButton({ navigate("app-release") }, modifier = Modifier.fillMaxWidth()) { Text("Aggiornamento disponibile: ${latestRelease!!.tag}") }
                }
                if (state.error != null) {
                    Text(state.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                    TextButton(model::restore) { Text("Riprova") }
                }
                NavHost(navController = nav, startDestination = "dashboard", modifier = Modifier.weight(1f)) {
                    destinations.forEach { destination -> composable(destination.route) {
                        if (destination.permission != null && state.permissions[destination.permission] != true) {
                            Text("Non hai accesso a questa sezione.", modifier = Modifier.padding(24.dp))
                        } else when (destination.route) {
                            "settings" -> SettingsScreen(model.api, state.session!!, theme, onTheme, model::signOut, onLegal)
                            "app-release" -> ReleaseScreen(releaseRepository, latestRelease, releaseChecked) { latestRelease = it; releaseChecked = true }
                            "couple" -> CoupleScreen(model.api)
                            "admin" -> AdminScreen(model.api)
                            "fire", "poker", "fumo", "tcg", "libreria" -> ModuleScreen(model.api, destination.route)
                            else -> FinanceScreen(model.api, destination.route, accountKey = state.session?.userId.orEmpty())
                        }
                    } }
                    composable("modules") {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                            Text("Il tuo Savely", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(8.dp))
                            allowed.filter { it.route !in listOf("dashboard", "transactions", "settings") }.forEach { destination ->
                                ListItem(headlineContent = { Text(destination.label) }, trailingContent = {
                                    TextButton({ navigate(destination.route) }) { Text("Apri") }
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LoadingPane() {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
        CircularProgressIndicator()
        Text("Caricamento…", modifier = Modifier.padding(16.dp))
    }
}
