pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // Compose Desktop usa algunos artefactos androidx publicados solo acá.
        google()
    }
}

rootProject.name = "controlxia-desktop"

// brain: cerebro común (LLM, tools, memoria) en Kotlin/JVM puro, sin
// dependencias de Android ni de Windows. Pensado para que la app de Android
// lo consuma también (hito M0).
include(":brain")
// windows: capa de plataforma (voz, acciones de Windows, configuración,
// orquestación), en Kotlin/JVM sin UI para poder testearla aparte.
include(":windows")
// desktop: la app (Compose Desktop: bandeja, indicador flotante, ventanas).
include(":desktop")
