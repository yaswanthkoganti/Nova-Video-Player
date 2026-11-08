package com.nova.player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.nova.player.data.Settings
import com.nova.player.data.SettingsRepo
import com.nova.player.ui.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = SettingsRepo(applicationContext)
        setContent {
            val st by repo.flow.collectAsState(Settings())
            NovaTheme(st.theme) {
                var screen by rememberSaveable { mutableIntStateOf(0) } // 0 library, 1 network, 2 settings
                if (screen == 2) {
                    BackHandler { screen = 0 }
                    SettingsScreen(repo, st) { screen = 0 }
                } else {
                    Scaffold(bottomBar = {
                        NavigationBar {
                            NavigationBarItem(screen == 0, { screen = 0 }, { Icon(Icons.Default.VideoLibrary, null) }, label = { Text("Library") })
                            NavigationBarItem(screen == 1, { screen = 1 }, { Icon(Icons.Default.Lan, null) }, label = { Text("Network") })
                        }
                    }) { pad ->
                        Box(Modifier.padding(bottom = pad.calculateBottomPadding())) {
                            if (screen == 0) LibraryScreen(onOpenSettings = { screen = 2 }, onPlay = ::play)
                            else NetworkScreen(onOpenSettings = { screen = 2 }, onPlay = ::play)
                        }
                    }
                }
            }
        }
    }

    private fun play(uri: Uri) = startActivity(Intent(this, PlayerActivity::class.java).setData(uri))
}
