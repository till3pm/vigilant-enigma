package com.example.androidpaperserver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.androidpaperserver.data.ServerRepository
import com.example.androidpaperserver.model.ModrinthProject
import com.example.androidpaperserver.model.ServerInstance
import com.example.androidpaperserver.network.ModrinthApi
import com.example.androidpaperserver.network.PaperApi
import com.example.androidpaperserver.server.RuntimeManager
import com.example.androidpaperserver.server.PluginManager
import com.example.androidpaperserver.server.ServerFiles
import com.example.androidpaperserver.server.ServerForegroundService
import com.example.androidpaperserver.util.BackupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        setContent { PaperServerApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaperServerApp() {
    val context = LocalContext.current
    val repository = remember { ServerRepository(context) }
    val paperApi = remember { PaperApi() }
    val modrinth = remember { ModrinthApi() }
    val runtime = remember { RuntimeManager(context) }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
    var servers by remember { mutableStateOf(repository.list()) }
    var selected by remember { mutableStateOf<ServerInstance?>(null) }
    var versions by remember { mutableStateOf<List<String>>(emptyList()) }
    var showCreate by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }
    var showOnboarding by remember { mutableStateOf(!context.getSharedPreferences("prefs", 0).getBoolean("onboarded", false)) }
    var accent by remember { mutableStateOf(context.getSharedPreferences("prefs", 0).getString("accent", "Warm cream") ?: "Warm cream") }

    val colors = mapOf("Warm cream" to 0xFFEADFC8, "Ivory" to 0xFFF2EBDD, "Muted beige" to 0xFFD9CBB9, "Soft sage" to 0xFFC8D2C0, "Dusty rose" to 0xFFD9B8B4, "Muted lavender" to 0xFFC8BDD4)
    val accentColor = androidx.compose.ui.graphics.Color(colors[accent] ?: colors.getValue("Warm cream"))
    val scheme = if (androidx.compose.foundation.isSystemInDarkTheme()) darkColorScheme(primary = accentColor, secondary = accentColor) else lightColorScheme(primary = accentColor, secondary = accentColor)

    MaterialTheme(colorScheme = scheme) {
        Scaffold(
            topBar = { TopAppBar(title = { Text("Paper Server Manager") }) },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(tab == 0, { tab = 0 }, icon = { Icon(Icons.Default.Dns, null) }, label = { Text("Servers") })
                    NavigationBarItem(tab == 1, { tab = 1 }, icon = { Icon(Icons.Default.Extension, null) }, label = { Text("Plugins") })
                    NavigationBarItem(tab == 2, { tab = 2 }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
                }
            }
        ) { padding ->
            when (tab) {
                0 -> ServersScreen(Modifier.padding(padding), servers, selected, { selected = it }, { showCreate = true }, context)
                1 -> PluginsScreen(Modifier.padding(padding), servers, selected, { selected = it }, modrinth, repository, context)
                else -> SettingsScreen(Modifier.padding(padding), accent, { value -> accent = value; context.getSharedPreferences("prefs", 0).edit().putString("accent", value).apply() }, runtime)
            }
        }

        if (showOnboarding) {
            AccentOnboarding(accent, { accent = it }, { 
                context.getSharedPreferences("prefs", 0).edit().putBoolean("onboarded", true).putString("accent", accent).apply(); showOnboarding = false
            }, colors.keys.toList())
        }

        createError?.let { error ->
            AlertDialog(onDismissRequest = { createError = null }, confirmButton = { TextButton(onClick = { createError = null }) { Text("OK") } }, title = { Text("Could not create server") }, text = { Text(error) })
        }

        if (showCreate) {
            LaunchedEffect(Unit) { if (versions.isEmpty()) versions = runCatching { paperApi.versions() }.getOrDefault(emptyList()) }
            CreateServerDialog(versions, { showCreate = false }, { draft, eula ->
                showCreate = false
                scope.launch {
                    var createdDir: File? = null
                    runCatching {
                        val build = paperApi.latestBuild(draft.minecraftVersion)
                        val server = draft.copy(id = UUID.randomUUID().toString().replace("-", "").take(16), paperBuild = build.build, eulaAccepted = eula, createdAt = System.currentTimeMillis())
                        val dir = repository.directory(server.id)
                        createdDir = dir
                        ServerFiles.initialize(dir, server)
                        paperApi.download(build, File(dir, "paper.jar")) { _, _ -> }
                        repository.save(server)
                        servers = repository.list()
                        selected = server
                        showCreate = false
                    }.onFailure {
                        createdDir?.deleteRecursively()
                        createError = "Server creation failed: ${it.message}"
                    }
                }
            })
        }

        selected?.let { server ->
            ServerDetailDialog(server, repository, context, { 
                scope.launch { delay(500); servers = repository.list(); selected = repository.get(server.id) }
            }, { selected = null })
        }
    }
}

@Composable
private fun ServersScreen(modifier: Modifier, servers: List<ServerInstance>, selected: ServerInstance?, onSelect: (ServerInstance) -> Unit, onAdd: () -> Unit, context: android.content.Context) {
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Your servers", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            FilledTonalButton(onClick = onAdd) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Add server") }
        }
        Spacer(Modifier.height(12.dp))
        if (servers.isEmpty()) {
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp)) { Text("No servers yet", style = MaterialTheme.typography.titleMedium); Text("Create a Paper server to get started.") } }
        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(servers, key = { it.id }) { server ->
                Card(onClick = { onSelect(server) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Dns, null, Modifier.size(36.dp)); Spacer(Modifier.width(14.dp));
                        Column(Modifier.weight(1f)) { Text(server.name, fontWeight = FontWeight.SemiBold); Text("Minecraft ${server.minecraftVersion} · Paper ${server.paperBuild}"); Text("Port ${server.port}") }
                        StatusDot(server.id, context)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusDot(id: String, context: android.content.Context) {
    var running by remember { mutableStateOf(false) }
    LaunchedEffect(id) { while (true) { val log = File(ServerRepository(context).directory(id), "logs/manager-console.log"); running = withContext(Dispatchers.IO) { File(ServerRepository(context).directory(id), "manager-state.txt").takeIf { it.exists() }?.readText()?.trim() == "RUNNING" }; delay(2500) } }
    Text(if (running) "Running" else "Stopped", color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CreateServerDialog(versions: List<String>, onDismiss: () -> Unit, onCreate: (ServerInstance, Boolean) -> Unit) {
    var name by remember { mutableStateOf("My Paper Server") }; var version by remember(versions) { mutableStateOf(versions.firstOrNull() ?: "1.21.11") }
    var minMem by remember { mutableStateOf("1024") }; var maxMem by remember { mutableStateOf("2048") }; var port by remember { mutableStateOf("25565") }
    var maxPlayers by remember { mutableStateOf("20") }; var viewDistance by remember { mutableStateOf("10") }; var simDistance by remember { mutableStateOf("10") }; var motd by remember { mutableStateOf("A Paper server") }
    var eula by remember { mutableStateOf(false) }; var busy by remember { mutableStateOf(false) }
    val valid = name.isNotBlank() && (minMem.toIntOrNull() ?: 0) > 0 && (maxMem.toIntOrNull() ?: 0) >= (minMem.toIntOrNull() ?: Int.MAX_VALUE) && (port.toIntOrNull() ?: 0) in 1..65535 && (maxPlayers.toIntOrNull() ?: 0) in 1..1000 && (viewDistance.toIntOrNull() ?: 0) in 2..32 && (simDistance.toIntOrNull() ?: 0) in 2..32
    AlertDialog(onDismissRequest = onDismiss, confirmButton = { Button(enabled = !busy && valid && eula && versions.isNotEmpty(), onClick = { busy = true; onCreate(ServerInstance("", name.trim(), version, 0, minMem.toIntOrNull() ?: 1024, maxMem.toIntOrNull() ?: 2048, port.toIntOrNull() ?: 25565, maxPlayers.toIntOrNull() ?: 20, viewDistance.toIntOrNull() ?: 10, simDistance.toIntOrNull() ?: 10, motd, eula, 0), eula) }) { Text(if (busy) "Installing…" else "Create and install") } }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } }, title = { Text("Create Paper server") }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Server name") }) }
            item { VersionDropdown(version, versions, { version = it }) }
            item { OutlinedTextField(minMem, { minMem = it }, label = { Text("Initial memory (MB)") }) }
            item { OutlinedTextField(maxMem, { maxMem = it }, label = { Text("Maximum memory (MB)") }) }
            item { OutlinedTextField(port, { port = it }, label = { Text("Server port") }) }
            item { OutlinedTextField(maxPlayers, { maxPlayers = it }, label = { Text("Maximum players") }) }
            item { OutlinedTextField(viewDistance, { viewDistance = it }, label = { Text("View distance") }) }
            item { OutlinedTextField(simDistance, { simDistance = it }, label = { Text("Simulation distance") }) }
            item { OutlinedTextField(motd, { motd = it }, label = { Text("MOTD") }) }
            item { Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(eula, { eula = it }); Text("I accept the Minecraft EULA") } }
            item { if (!valid) Text("Check the name, memory, port, player count, and distance values.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            item { Text("Paper is downloaded from PaperMC's official API. A compatible Java runtime must be installed in Settings before starting the server.", style = MaterialTheme.typography.bodySmall) }
        }
    })
}

@Composable
private fun VersionDropdown(value: String, versions: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box { OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("Minecraft version: $value"); Spacer(Modifier.weight(1f)); Icon(Icons.Default.ArrowDropDown, null) }; DropdownMenu(expanded, { expanded = false }) { versions.take(30).forEach { v -> DropdownMenuItem({ Text(v) }, { onSelect(v); expanded = false }) } } }
}

@Composable
private fun ServerDetailDialog(server: ServerInstance, repository: ServerRepository, context: android.content.Context, refresh: () -> Unit, close: () -> Unit) {
    var page by remember { mutableStateOf(0) }; var command by remember { mutableStateOf("") }; var console by remember { mutableStateOf("") }; var message by remember { mutableStateOf<String?>(null) }; var props by remember { mutableStateOf<Map<String, String>>(emptyMap()) }; var confirmDelete by remember { mutableStateOf(false) }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { val state = File(repository.directory(server.id), "manager-state.txt").takeIf { it.exists() }?.readText()?.trim(); if (state == "RUNNING") message = "Stop the server before restoring a backup." else { runCatching { val temp = File.createTempFile("restore-", ".zip", context.cacheDir); context.contentResolver.openInputStream(it)!!.use { input -> temp.outputStream().use { output -> input.copyTo(output) } }; BackupManager.restore(temp, repository.directory(server.id)); temp.delete(); message = "Backup restored" }.onFailure { message = "Restore failed: ${it.message}" } } } }; var paperFile by remember { mutableStateOf("config/paper-global.yml") }; var paperText by remember { mutableStateOf("") }
    LaunchedEffect(page) { if (page == 0) while (true) { console = withContext(Dispatchers.IO) { runCatching { File(repository.directory(server.id), "logs/manager-console.log").takeIf { it.exists() }?.readLines()?.takeLast(350)?.joinToString("\n") ?: "" }.getOrDefault("") }; delay(1000) } }
    AlertDialog(onDismissRequest = close, confirmButton = {}, title = { Text(server.name) }, text = {
        Column(Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("Console", "Config", "Paper").forEachIndexed { i, label -> FilterChip(page == i, { page = i; if (i == 1) props = ServerFiles.readProperties(File(repository.directory(server.id), "server.properties")); if (i == 2) { paperFile = "config/paper-global.yml"; paperText = File(repository.directory(server.id), paperFile).takeIf { it.exists() }?.readText() ?: "Paper configuration file will appear after the server has generated it." } }, { Text(label) }) } }
            Spacer(Modifier.height(10.dp))
            when (page) {
                0 -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { context.startService(Intent(context, ServerForegroundService::class.java).setAction(ServerForegroundService.ACTION_START).putExtra(ServerForegroundService.EXTRA_SERVER_ID, server.id)) }) { Text("Start") }
                        OutlinedButton(onClick = { context.startService(Intent(context, ServerForegroundService::class.java).setAction(ServerForegroundService.ACTION_STOP).putExtra(ServerForegroundService.EXTRA_SERVER_ID, server.id)) }) { Text("Stop") }
                        OutlinedButton(onClick = { context.startService(Intent(context, ServerForegroundService::class.java).setAction(ServerForegroundService.ACTION_RESTART).putExtra(ServerForegroundService.EXTRA_SERVER_ID, server.id)) }) { Text("Restart") }
                    }
                    Spacer(Modifier.height(8.dp)); Surface(Modifier.fillMaxWidth().height(260.dp), tonalElevation = 2.dp) { Text(console.ifBlank { "No console output yet." }, Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall) }
                    Spacer(Modifier.height(6.dp)); Row(verticalAlignment = Alignment.CenterVertically) { OutlinedTextField(command, { command = it }, Modifier.weight(1f), label = { Text("Command") }); IconButton(enabled = command.isNotBlank(), onClick = { context.startService(Intent(context, ServerForegroundService::class.java).setAction(ServerForegroundService.ACTION_COMMAND).putExtra(ServerForegroundService.EXTRA_SERVER_ID, server.id).putExtra(ServerForegroundService.EXTRA_COMMAND, command)); command = "" }) { Icon(Icons.Default.Send, "Send") } }
                }
                1 -> {
                    val keys = listOf("server-port", "max-players", "view-distance", "simulation-distance", "motd", "online-mode")
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.height(330.dp)) { keys.forEach { key -> item { var value by remember(props[key]) { mutableStateOf(props[key] ?: "") }; OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), label = { Text(key) }, singleLine = true, trailingIcon = { IconButton(onClick = { ServerFiles.updateProperty(File(repository.directory(server.id), "server.properties"), key, value); props = ServerFiles.readProperties(File(repository.directory(server.id), "server.properties")); message = "Saved" }) { Icon(Icons.Default.Save, "Save") } }) } } }
                }
                2 -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(paperFile.endsWith("paper-global.yml"), { paperFile = "config/paper-global.yml"; paperText = File(repository.directory(server.id), paperFile).takeIf { it.exists() }?.readText() ?: "" }, { Text("Global") })
                        FilterChip(paperFile.endsWith("paper-world-defaults.yml"), { paperFile = "config/paper-world-defaults.yml"; paperText = File(repository.directory(server.id), paperFile).takeIf { it.exists() }?.readText() ?: "" }, { Text("World defaults") })
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(paperText, { paperText = it }, Modifier.fillMaxWidth().height(300.dp), label = { Text(paperFile) })
                    Spacer(Modifier.height(6.dp))
                    Button(onClick = { val f = File(repository.directory(server.id), paperFile); f.parentFile?.mkdirs(); f.writeText(paperText); message = "Saved $paperFile" }) { Text("Save Paper file") }
                    Text("This raw editor writes the selected Paper YAML file directly and does not maintain a second shadow configuration model.", style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(8.dp)); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { val backup = File(repository.directory(server.id), "../backups/${server.id}-${System.currentTimeMillis()}.zip").canonicalFile; BackupManager.create(repository.directory(server.id), backup); message = "Backup created" }) { Text("Backup") }
                OutlinedButton(onClick = { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("Restore") }
                TextButton(onClick = { confirmDelete = true }) { Text("Delete") }; TextButton(onClick = close) { Text("Close") }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    })
    if (confirmDelete) {
        AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete server?") }, text = { Text("This permanently removes the server directory, worlds, plugins, logs, and configuration from app storage.") }, confirmButton = { Button(onClick = { val state = File(repository.directory(server.id), "manager-state.txt").takeIf { it.exists() }?.readText()?.trim(); if (state == "RUNNING") message = "Stop the server before deleting it." else { repository.delete(server); confirmDelete = false; close(); refresh() } }) { Text("Delete permanently") } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
    }
}

@Composable
private fun PluginsScreen(modifier: Modifier, servers: List<ServerInstance>, selected: ServerInstance?, onSelect: (ServerInstance) -> Unit, api: ModrinthApi, repository: ServerRepository, context: android.content.Context) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ModrinthProject>>(emptyList()) }
    var offset by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var installed by remember { mutableStateOf(emptyList<PluginManager.Record>()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(selected?.id) { installed = selected?.let { PluginManager(context, repository.directory(it.id)).records() } ?: emptyList() }
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text("Modrinth plugins", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text("Only projects filtered as Paper plugins for the selected Minecraft version are shown. Plugins execute third-party code.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp)); ServerPicker(servers, selected, onSelect)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), label = { Text("Search") })
            IconButton(enabled = !loading && query.isNotBlank() && selected != null, onClick = {
                loading = true
                scope.launch { results = runCatching { api.search(query, selected!!.minecraftVersion, 0) }.getOrDefault(emptyList()); offset = 20; loading = false }
            }) { Icon(Icons.Default.Search, "Search") }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (installed.isNotEmpty()) {
            Spacer(Modifier.height(8.dp)); Text("Installed", style = MaterialTheme.typography.titleMedium)
            installed.forEach { record ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(record.fileName); Text(record.versionNumber, style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = { selected?.let { s -> PluginManager(context, repository.directory(s.id)).remove(record); installed = PluginManager(context, repository.directory(s.id)).records(); status = "Removed ${record.fileName}" } }) { Text("Remove") }
                    TextButton(onClick = {
                        selected?.let { s -> scope.launch { status = "Updating ${record.fileName}…"; val latest = runCatching { api.versions(record.projectId) }.getOrDefault(emptyList()).firstOrNull { s.minecraftVersion in it.gameVersions && "paper" in it.loaders }; if (latest == null) status = "No compatible update found." else { val target = File(repository.directory(s.id), "plugins/${latest.fileName}"); runCatching { api.download(latest.fileUrl, target, latest.sha512) }.onSuccess { PluginManager(context, repository.directory(s.id)).remove(record); PluginManager(context, repository.directory(s.id)).record(record.projectId, latest); installed = PluginManager(context, repository.directory(s.id)).records(); status = "Updated ${latest.fileName}" }.onFailure { status = "Update failed: ${it.message}" } } } }
                    }) { Text("Update") }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            items(results, key = { it.id }) { p ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(p.title, style = MaterialTheme.typography.titleMedium)
                        Text(p.description)
                        Text("${p.downloads} downloads", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(6.dp))
                        Button(enabled = selected != null, onClick = {
                            val s = selected ?: return@Button
                            scope.launch {
                                status = "Checking release…"
                                val v = runCatching { api.versions(p.id) }.getOrDefault(emptyList()).firstOrNull { s.minecraftVersion in it.gameVersions && "paper" in it.loaders }
                                if (v == null) status = "No compatible Paper release found."
                                else {
                                    val target = File(repository.directory(s.id), "plugins/${v.fileName}")
                                    runCatching { api.download(v.fileUrl, target, v.sha512) { _, _ -> }; PluginManager(context, repository.directory(s.id)).record(p.id, v); installed = PluginManager(context, repository.directory(s.id)).records() }.onSuccess { status = if (v.dependencies.isEmpty()) "Installed ${v.fileName}" else "Installed ${v.fileName}; dependency projects should be reviewed: ${v.dependencies.joinToString()}" }.onFailure { status = "Install failed: ${it.message}" }
                                }
                            }
                        }) { Text("Install") }
                    }
                }
            }
        }
        if (results.isNotEmpty() && selected != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(enabled = !loading, onClick = {
                loading = true
                scope.launch { val more = runCatching { api.search(query, selected!!.minecraftVersion, offset) }.getOrDefault(emptyList()); results = (results + more).distinctBy { it.id }; offset += 20; loading = false }
            }, modifier = Modifier.fillMaxWidth()) { Text("Load more") }
        }
    }
}

@Composable
private fun ServerPicker(servers: List<ServerInstance>, selected: ServerInstance?, onSelect: (ServerInstance) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box { OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(selected?.let { "Server: ${it.name}" } ?: "Select a server"); Spacer(Modifier.weight(1f)); Icon(Icons.Default.ArrowDropDown, null) }; DropdownMenu(expanded, { expanded = false }) { servers.forEach { s -> DropdownMenuItem({ Text(s.name) }, { onSelect(s); expanded = false }) } } }
}

@Composable
private fun SettingsScreen(modifier: Modifier, accent: String, setAccent: (String) -> Unit, runtime: RuntimeManager) {
    val context = LocalContext.current; var runtimeVersion by remember { mutableStateOf(runtime.javaMajor()) }; var status by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { status = runCatching { context.contentResolver.openInputStream(it)!!.use { input -> runtime.importZip(input) }; runtimeVersion = runtime.javaMajor(); "Java $runtimeVersion imported" }.getOrElse { "Runtime error: ${it.message}" } } }
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp)); Text("Creamy accent", style = MaterialTheme.typography.titleMedium)
        Text("The app follows the system light/dark appearance.", style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(8.dp))
        listOf("Warm cream", "Ivory", "Muted beige", "Soft sage", "Dusty rose", "Muted lavender").forEach { value -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { RadioButton(accent == value, { setAccent(value) }); Text(value) } }
        HorizontalDivider(Modifier.padding(vertical = 12.dp)); Text("Java runtime", style = MaterialTheme.typography.titleMedium); Text(runtimeVersion?.let { "Detected Java $it" } ?: "No compatible Java runtime installed", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp)); Button(onClick = { launcher.launch(arrayOf("application/zip", "application/octet-stream")) }) { Icon(Icons.Default.UploadFile, null); Spacer(Modifier.width(6.dp)); Text("Import Android-compatible JRE ZIP") }
        Text("The runtime must contain bin/java and be built for the device ABI. Paper's Java requirement depends on Minecraft version; Paper documents Java 21 for 1.20–1.21.11 and Java 25 for 26.1+.", style = MaterialTheme.typography.bodySmall)
        status?.let { Text(it) }
    }
}

@Composable
private fun AccentOnboarding(value: String, onChange: (String) -> Unit, finish: () -> Unit, options: List<String>) {
    AlertDialog(onDismissRequest = {}, confirmButton = { Button(onClick = finish) { Text("Continue") } }, title = { Text("Choose your accent") }, text = { Column { Text("Pick a calm creamy accent. You can change it later in Settings."); Spacer(Modifier.height(8.dp)); options.forEach { Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(value == it, { onChange(it) }); Text(it) } } } })
}
