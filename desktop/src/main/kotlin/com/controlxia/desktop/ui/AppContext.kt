package com.controlxia.desktop.ui

import com.controlxia.windows.assistant.AssistantController
import com.controlxia.windows.config.HistoryStore
import com.controlxia.windows.config.SecretStore
import com.controlxia.windows.config.SettingsRepository

/** Lo que necesitan las pantallas, en un solo lugar. */
class AppContext(
    val settings: SettingsRepository,
    val secrets: SecretStore,
    val history: HistoryStore,
    val controller: AssistantController,
)
