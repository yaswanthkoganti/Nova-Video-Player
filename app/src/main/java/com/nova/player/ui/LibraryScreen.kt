package com.nova.player.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.nova.player.data.*
import com.nova.player.engine.fmtTime

private const val ALL = "All videos"
private val SORTS = listOf("Name", "Newest", "Largest", "Longest")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LibraryScreen(onOpenSettings: () -> Unit, onPlay: (Uri) -> Unit) {
    val ctx = LocalContext.current
    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
    else Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var videos by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    LaunchedEffect(granted) { if (granted) videos = MediaRepo.load(ctx) else permLauncher.launch(perm) }

    val statesFlow = remember { NovaDb.get(ctx).dao().allStates() }
    val states by statesFlow.collectAsState(emptyList())
    val progress = remember(states) {
        states.associate { it.uri to if (it.durationMs > 0) it.positionMs.toFloat() / it.durationMs else 0f }
    }

    var folder by remember { mutableStateOf<String?>(null) }
    var sort by remember { mutableIntStateOf(1) }
    var sortMenu by remember { mutableStateOf(false) }
    var showStream by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(onPlay) }
    BackHandler(folder != null) { folder = null }

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(modifier = Modifier.nestedScroll(scroll.nestedScrollConnection), topBar = {
        LargeFlexibleTopAppBar(
            title = { Text(folder ?: "Nova Player") },
            subtitle = { Text("${videos.size} videos") },
            scrollBehavior = scroll,
            actions = {
                IconButton({ showStream = true }) { Icon(Icons.Default.Link, "Network stream") }
                IconButton({ picker.launch(arrayOf("video/*", "application/octet-stream")) }) { Icon(Icons.Default.FolderOpen, "Open file") }
                Box {
                    IconButton({ sortMenu = true }) { Icon(Icons.Default.Sort, "Sort") }
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        SORTS.forEachIndexed { i, n ->
                            DropdownMenuItem(text = { Text(n) }, onClick = { sort = i; sortMenu = false })
                        }
                    }
                }
                IconButton(onOpenSettings) { Icon(Icons.Default.Settings, "Settings") }
            }
        )
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                !granted -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Allow access to your videos")
                    Spacer(Modifier.height(12.dp))
                    Button({ permLauncher.launch(perm) }) { Text("Grant permission") }
                }
                folder == null -> {
                    val groups = videos.groupBy { it.folder }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
                    LazyColumn {
                        item {
                            ListItem(
                                headlineContent = { Text(ALL) },
                                supportingContent = { Text("${videos.size} videos") },
                                leadingContent = { Icon(Icons.Default.VideoLibrary, null) },
                                modifier = Modifier.clickable { folder = ALL },
                            )
                        }
                        items(groups.entries.toList()) { (name, list) ->
                            ListItem(
                                headlineContent = { Text(name) },
                                supportingContent = { Text("${list.size} videos") },
                                leadingContent = { Icon(Icons.Default.Folder, null) },
                                modifier = Modifier.clickable { folder = name },
                            )
                        }
                    }
                }
                else -> {
                    val base = if (folder == ALL) videos else videos.filter { it.folder == folder }
                    val list = when (sort) {
                        0 -> base.sortedWith(compareBy(Natural) { it.title })
                        2 -> base.sortedByDescending { it.size }
                        3 -> base.sortedByDescending { it.durationMs }
                        else -> base.sortedByDescending { it.added }
                    }
                    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(list, key = { it.id }) { v ->
                            val p = progress[v.uri.toString()]
                            Row(
                                Modifier.fillMaxWidth().clickable { onPlay(v.uri) }.padding(horizontal = 16.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.width(128.dp).height(72.dp).clip(RoundedCornerShape(8.dp))) {
                                    AsyncImage(v.uri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    Text(
                                        fmtTime(v.durationMs),
                                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                    if (p != null && p > 0.01f) LinearProgressIndicator(
                                        progress = { p.coerceIn(0f, 1f) },
                                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(v.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        buildString {
                                            if (v.height > 0) append("${v.height}p · ")
                                            append(Formatter.formatShortFileSize(ctx, v.size))
                                            if (p == null) append(" · NEW")
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showStream) {
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showStream = false },
            title = { Text("Network stream") },
            text = {
                OutlinedTextField(url, { url = it }, singleLine = true, placeholder = { Text("http://, https://, rtsp://, .m3u8") })
            },
            confirmButton = {
                TextButton({ if (url.isNotBlank()) { onPlay(Uri.parse(url.trim())); showStream = false } }) { Text("Play") }
            },
            dismissButton = { TextButton({ showStream = false }) { Text("Cancel") } },
        )
    }
}
