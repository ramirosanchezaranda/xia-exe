package com.controlxia.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.Alignment

private enum class Section(val title: String) {
    ASSISTANT(Texts.SECTION_ASSISTANT),
    LLM(Texts.SECTION_LLM),
    VOICE(Texts.SECTION_VOICE),
    PERMISSIONS(Texts.SECTION_PERMISSIONS),
    HISTORY(Texts.SECTION_HISTORY),
    PRIVACY(Texts.SECTION_PRIVACY),
}

/** Ventana de configuración (RF-20), con la misma estructura que Android. */
@Composable
fun SettingsWindow(app: AppContext, onClose: () -> Unit) {
    val state = rememberWindowState(size = DpSize(900.dp, 680.dp), position = WindowPosition.Aligned(Alignment.Center))
    var section by remember { mutableStateOf(Section.ASSISTANT) }
    Window(onCloseRequest = onClose, state = state, title = Texts.SETTINGS_TITLE) {
        XiaTheme {
            Surface(Modifier.fillMaxSize(), color = Paper) {
                Row(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.width(220.dp).fillMaxHeight()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(vertical = 24.dp),
                    ) {
                        Text(
                            Texts.APP.uppercase(),
                            style = MaterialTheme.typography.labelLarge,
                            color = Accent,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                        Section.values().forEach { s ->
                            val selected = s == section
                            Box(
                                Modifier.fillMaxWidth()
                                    .clickable { section = s }
                                    .background(if (selected) Paper else MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                            ) {
                                Text(
                                    s.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (selected) Ink else Muted,
                                )
                            }
                        }
                    }
                    Column(
                        Modifier.weight(1f).fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 32.dp, vertical = 24.dp),
                    ) {
                        Text(section.title, style = MaterialTheme.typography.headlineMedium)
                        when (section) {
                            Section.ASSISTANT -> AssistantSection(app)
                            Section.LLM -> LlmSection(app)
                            Section.VOICE -> VoiceSection(app)
                            Section.PERMISSIONS -> PermissionsSection(app)
                            Section.HISTORY -> HistorySection(app)
                            Section.PRIVACY -> PrivacySection(app)
                        }
                    }
                }
            }
        }
    }
}
