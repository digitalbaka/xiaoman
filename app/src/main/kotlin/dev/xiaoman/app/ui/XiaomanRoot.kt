package dev.xiaoman.app.ui

import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.AppLink
import dev.xiaoman.app.R
import dev.xiaoman.app.speech.Speaker

enum class Tab(@StringRes val label: Int, val icon: ImageVector) {
    CHAT(R.string.nav_chat, Icons.AutoMirrored.Filled.Chat),
    SKILLS(R.string.nav_skills, Icons.Filled.Extension),
    MEMORY(R.string.nav_memory, Icons.Filled.Psychology),
    SETTINGS(R.string.nav_settings, Icons.Filled.Settings),
}

@Composable
fun XiaomanRoot(graph: AppGraph) {
    var tab by rememberSaveable { mutableStateOf(Tab.CHAT) }
    // On any tab other than chat the first back press only returns to chat; a second press exits,
    // so a stray tap on Settings cannot close the app.
    BackHandler(enabled = tab != Tab.CHAT) { tab = Tab.CHAT }
    val context = LocalContext.current
    val speaker = remember { Speaker(context) }
    DisposableEffect(speaker) { onDispose { speaker.shutdown() } }
    val pendingLink by graph.pendingLink.collectAsStateWithLifecycle()
    LaunchedEffect(pendingLink) {
        when (val link = pendingLink) {
            is AppLink.ChatAdd -> {
                if (graph.session.openExternalChat(link.type.key, link.type.label, link.id)) {
                    tab = Tab.CHAT
                } else {
                    Toast.makeText(context, context.getString(R.string.link_busy), Toast.LENGTH_SHORT).show()
                }
                graph.pendingLink.value = null
            }
            is AppLink.AlarmSet, is AppLink.PostDetail -> tab = Tab.CHAT
            null -> Unit
        }
    }
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    Scaffold(
        bottomBar = {
            // The bar used to vanish in a single frame while the IME animates; sliding it out keeps
            // the bar's height (and the content padding derived from it) changing frame by frame.
            AnimatedVisibility(
                visible = !keyboardOpen,
                enter = slideInVertically(tween(220)) { it } + fadeIn(tween(220)),
                exit = slideOutVertically(tween(160)) { it } + fadeOut(tween(160)),
            ) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(stringResource(t.label), style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            when (tab) {
                Tab.CHAT -> ChatScreen(graph, speaker = speaker, onOpenSettings = { tab = Tab.SETTINGS })
                Tab.SKILLS -> SkillsScreen(graph)
                Tab.MEMORY -> MemoryScreen(graph)
                Tab.SETTINGS -> SettingsScreen(graph)
            }
        }
    }
    (pendingLink as? AppLink.AlarmSet)?.let { request ->
        AlarmSetupDialog(
            request = request,
            onDismiss = { graph.pendingLink.value = null },
            onConfirm = { label, atMillis ->
                graph.reminderScheduler.schedule(label, atMillis)
                Toast.makeText(
                    context,
                    context.getString(R.string.alarm_scheduled, label, formatTime(atMillis)),
                    Toast.LENGTH_SHORT,
                ).show()
                graph.pendingLink.value = null
            },
        )
    }
    (pendingLink as? AppLink.PostDetail)?.let { link ->
        PostDetailDialog(id = link.id, onDismiss = { graph.pendingLink.value = null })
    }
}
