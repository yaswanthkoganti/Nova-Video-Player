package com.nova.player.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nova.player.net.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NetworkScreen(onOpenSettings: () -> Unit, onPlay: (Uri) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Network") },
                subtitle = { Text("DLNA / UPnP · SMB shares") },
                scrollBehavior = scroll,
                actions = { IconButton(onOpenSettings) { Icon(Icons.Default.Settings, "Settings") } },
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad)) {
            PrimaryTabRow(tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("DLNA") })
                Tab(tab == 1, { tab = 1 }, text = { Text("SMB") })
            }
            if (tab == 0) DlnaTab(onPlay) else SmbTab(onPlay)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DlnaTab(onPlay: (Uri) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf(emptyList<DlnaDevice>()) }
    var scanning by remember { mutableStateOf(false) }
    var dev by remember { mutableStateOf<DlnaDevice?>(null) }
    var stack by remember { mutableStateOf(listOf("0")) }
    var entries by remember { mutableStateOf(emptyList<DlnaEntry>()) }
    var err by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(dev, stack) {
        val d = dev ?: return@LaunchedEffect
        runCatching { Dlna.browse(d, stack.last()) }
            .onSuccess { entries = it; err = null }
            .onFailure { err = it.message }
    }
    BackHandler(dev != null) { if (stack.size > 1) stack = stack.dropLast(1) else dev = null }

    Column(Modifier.fillMaxSize()) {
        if (dev == null) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Button({
                    scanning = true
                    scope.launch { devices = runCatching { Dlna.discover(ctx) }.getOrDefault(emptyList()); scanning = false }
                }) { Text("Scan network") }
                if (scanning) { Spacer(Modifier.width(16.dp)); LoadingIndicator(Modifier.size(36.dp)) }
            }
            if (!scanning && devices.isEmpty()) Text("No servers yet. Make sure your phone is on the same Wi-Fi.", Modifier.padding(horizontal = 16.dp))
            LazyColumn {
                items(devices) { d ->
                    ListItem(
                        headlineContent = { Text(d.name) },
                        leadingContent = { Icon(Icons.Default.Dns, null) },
                        modifier = Modifier.clickable { dev = d; stack = listOf("0") },
                    )
                }
            }
        } else {
            err?.let { Text("Error: $it", Modifier.padding(16.dp)) }
            LazyColumn {
                items(entries) { e ->
                    ListItem(
                        headlineContent = { Text(e.title) },
                        leadingContent = { Icon(if (e.isContainer) Icons.Default.Folder else Icons.Default.PlayCircle, null) },
                        modifier = Modifier.clickable {
                            if (e.isContainer) stack = stack + e.id else e.url?.let { onPlay(Uri.parse(it)) }
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SmbTab(onPlay: (Uri) -> Unit) {
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var stack by remember { mutableStateOf(emptyList<String>()) }
    var entries by remember { mutableStateOf(emptyList<SmbEntry>()) }
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    fun open(url: String, push: Boolean = true) {
        loading = true; err = null
        scope.launch {
            runCatching { Smb.list(url, user, pass) }
                .onSuccess { entries = it; if (push) stack = stack + url }
                .onFailure { err = it.message ?: "Connection failed" }
            loading = false
        }
    }
    BackHandler(stack.isNotEmpty()) {
        stack = stack.dropLast(1)
        if (stack.isEmpty()) entries = emptyList() else open(stack.last(), push = false)
    }

    if (stack.isEmpty()) {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(host, { host = it }, label = { Text("Server (e.g. 192.168.1.10)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(user, { user = it }, label = { Text("Username (blank = guest)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button({ if (host.isNotBlank()) open("smb://${host.trim()}/") }) { Text("Connect") }
                if (loading) { Spacer(Modifier.width(16.dp)); LoadingIndicator(Modifier.size(36.dp)) }
            }
            err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("SMB files play with the libVLC engine.", style = MaterialTheme.typography.bodySmall)
        }
    } else {
        Column {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            err?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            LazyColumn {
                items(entries) { e ->
                    ListItem(
                        headlineContent = { Text(e.name) },
                        leadingContent = { Icon(if (e.isDir) Icons.Default.Folder else Icons.Default.PlayCircle, null) },
                        modifier = Modifier.clickable {
                            if (e.isDir) open(e.url) else onPlay(Uri.parse(Smb.playUrl(e.url, user, pass)))
                        },
                    )
                }
            }
        }
    }
}
