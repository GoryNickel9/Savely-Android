package cc.savely.android.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
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
import cc.savely.android.R
import cc.savely.android.data.AndroidAuthLinkStore
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
import kotlinx.coroutines.flow.filterNotNull

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
fun SavelyApp(authCallbacks: AuthCallbackInbox? = null) {
    val context = LocalContext.current.applicationContext
    val preferences = remember { context.getSharedPreferences("appearance", Context.MODE_PRIVATE) }
    var theme by rememberSaveable { mutableStateOf(preferences.getString("theme", "dark") ?: "dark") }
    var legalDocument by rememberSaveable { mutableStateOf<String?>(null) }
    LocalizationProvider {
        SavelyTheme(theme) {
            Surface(Modifier.fillMaxSize()) {
                val api = remember { runCatching {
                    SupabaseApi(BackendConfig(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY, BuildConfig.WEB_URL), AndroidSessionStore(context),
                        authLinks = AndroidAuthLinkStore(context))
                } }
                if (api.isFailure) {
                    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(localized("Savely"), style = MaterialTheme.typography.displayMedium)
                        Text(localized("Questa build non contiene una configurazione valida. Installa l’APK configurato del progetto."))
                    }
                } else {
                    val model: AppViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            @Suppress("UNCHECKED_CAST") return AppViewModel(api.getOrThrow()) as T
                        }
                    })
                    val state by model.state.collectAsStateWithLifecycle()
                    LaunchedEffect(model, authCallbacks) {
                        val inbox = authCallbacks ?: return@LaunchedEffect
                        inbox.events.filterNotNull().collect { event ->
                            legalDocument = null
                            model.handleAuthCallback(event)
                            inbox.consume(event.id)
                        }
                    }
                    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                        if (state.initialized && state.session != null && state.mfaFactor == null && !state.recovery) model.restore()
                    }
                    when {
                        legalDocument != null -> {
                            BackHandler { legalDocument = null }
                            LegalScreen(legalDocument!!) { legalDocument = null }
                        }
                        !state.initialized -> LoadingPane()
                        state.mfaFactor != null -> Scaffold { padding -> Surface(Modifier.padding(padding)) {
                            MfaChallengeScreen(state, model::verifyMfa, model::signOut)
                        } }
                        state.recovery -> Scaffold { padding -> Surface(Modifier.padding(padding)) {
                            PasswordRecoveryScreen(state, model::completePasswordRecovery, model::cancelPasswordRecovery)
                        } }
                        state.session == null -> Scaffold { padding ->
                            Surface(Modifier.padding(padding)) {
                                AuthScreen(state, model::signIn, model::signUp, model::resetPassword, model::restore) { legalDocument = it }
                            }
                        }
                        else -> key(state.session?.userId) {
                            MainShell(model, state, theme, { value -> theme = value; preferences.edit().putString("theme", value).apply() }, { legalDocument = it })
                        }
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
        if (!canNavigate(target, state.permissions)) return
        nav.navigate(target) { popUpTo("dashboard") { saveState = true }; launchSingleTop = true; restoreState = true }
    }
    BackHandler(drawer.isOpen) { scope.launch { drawer.close() } }
    LaunchedEffect(route, state.permissions) {
        if (!canNavigate(route, state.permissions)) navigate("dashboard")
    }
    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        ModalDrawerSheet(Modifier.widthIn(max = 340.dp).verticalScroll(rememberScrollState())) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.savely_brand), contentDescription = null, modifier = Modifier.size(48.dp))
                Text(localized("Savely"), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 12.dp))
            }
            Text(state.session?.email ?: "", modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            SavelyDrawerItem("Moduli", route == "modules") { navigate("modules"); scope.launch { drawer.close() } }
            allowed.forEach { destination ->
                SavelyDrawerItem(destination.label, route == destination.route) {
                    navigate(destination.route); scope.launch { drawer.close() }
                }
            }
            TextButton(model::signOut, enabled = !state.busy, modifier = Modifier.padding(16.dp)) { Text(localized("Esci dall’account")) }
        }
    }) {
        Scaffold(topBar = {
            SavelyTopBar(if (route == "modules") "Moduli" else allowed.firstOrNull { it.route == route }?.label ?: "Savely") {
                scope.launch { drawer.open() }
            }
        }, bottomBar = {
            SavelyBottomNavigation(route, ::navigate) { scope.launch { drawer.open() } }
        }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (accessAuditWarning != null) {
                    Text(localized(accessAuditWarning!!), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    TextButton(model.api::dismissAccessAuditWarning) { Text(localized("Chiudi avviso")) }
                }
                if (latestRelease?.version?.let { remote -> AppVersion.parse(BuildConfig.VERSION_NAME)?.let { remote > it } } == true) {
                    TextButton({ navigate("app-release") }, modifier = Modifier.fillMaxWidth()) { Text(localized("Aggiornamento disponibile: {{value0}}", "value0" to (latestRelease!!.tag))) }
                }
                if (state.error != null) {
                    Text(localized(state.error), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                    TextButton(model::restore) { Text(localized("Riprova")) }
                }
                NavHost(navController = nav, startDestination = "dashboard", modifier = Modifier.weight(1f)) {
                    destinations.forEach { destination -> composable(destination.route) {
                        if (destination.permission != null && state.permissions[destination.permission] != true) {
                            Text(localized("Non hai accesso a questa sezione."), modifier = Modifier.padding(24.dp))
                        } else when (destination.route) {
                            "settings" -> SettingsScreen(model.api, state.session!!, theme, onTheme, model::signOut, onLegal)
                            "app-release" -> ReleaseScreen(releaseRepository, latestRelease, releaseChecked) { latestRelease = it; releaseChecked = true }
                            "couple" -> CoupleScreen(model.api)
                            "admin" -> AdminScreen(model.api)
                            "fire", "poker", "fumo", "tcg", "libreria" -> ModuleScreen(model.api, destination.route)
                            else -> FinanceScreen(model.api, destination.route, accountKey = state.session?.userId.orEmpty(), onNavigate = ::navigate)
                        }
                    } }
                    composable("modules") {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                            Text(localized("Il tuo Savely"), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(8.dp))
                            allowed.filter { it.route !in listOf("dashboard", "transactions", "settings") }.forEach { destination ->
                                ListItem(headlineContent = { Text(localized(destination.label)) }, trailingContent = {
                                    TextButton({ navigate(destination.route) }) { Text(localized("Apri")) }
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
internal fun SavelyDrawerItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface) {
        Text(localized(label), style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected, role = Role.Tab, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 16.dp))
    }
}

@Composable
internal fun SavelyTopBar(title: String, onOpenMenu: () -> Unit) {
    Surface {
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).heightIn(min = 64.dp).padding(end = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onOpenMenu, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Menu, localized("Apri menu")) }
            Text(localized(title), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp).testTag("screen-title"))
        }
    }
}

@Composable
internal fun SavelyBottomNavigation(route: String, onNavigate: (String) -> Unit, onMore: () -> Unit) {
    val primary = listOf("dashboard", "transactions", "budget", "settings")
    Surface(shadowElevation = 2.dp) {
        // Content determines height: translated labels and the system font scale may wrap.
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)
            .height(IntrinsicSize.Min).selectableGroup()) {
            (primary + "more").forEach { target ->
                val label = when (target) { "dashboard" -> "Dashboard"; "transactions" -> "Transazioni";
                    "budget" -> "Budget"; "settings" -> "Impostazioni";
                    // The existing "Altro" translation is "Other" for uncategorized user data.
                    else -> if (LocalAppLanguage.current == AppLanguage.ENGLISH) "More" else "Altro" }
                val icon = when (target) { "dashboard" -> Icons.Outlined.Dashboard; "transactions" -> Icons.Outlined.SwapHoriz;
                    "budget" -> Icons.Outlined.Savings; "settings" -> Icons.Outlined.Settings; else -> Icons.Outlined.Menu }
                val selected = if (target == "more") route !in primary else route == target
                val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                Column(Modifier.weight(1f).fillMaxHeight().heightIn(min = 64.dp)
                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                    .selectable(selected, role = Role.Tab, onClick = { if (target == "more") onMore() else onNavigate(target) })
                    .testTag("nav-$target").padding(horizontal = 4.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(icon, contentDescription = null, tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else color,
                        modifier = Modifier.size(24.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(localized(label), style = MaterialTheme.typography.labelMedium,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else color,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.testTag("nav-label-$target"))
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
        Text(localized("Caricamento…"), modifier = Modifier.padding(16.dp))
    }
}
