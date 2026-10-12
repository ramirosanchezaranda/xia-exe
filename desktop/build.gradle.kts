import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

val appVersion = "0.1.0"

dependencies {
    implementation(project(":windows"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "com.controlxia.desktop.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8", "-Xmx512m")

        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "ControlXIA"
            packageVersion = appVersion
            description = "Asistente de voz para Windows"
            vendor = "ControlXIA"
            // Runtime completo: más pesado, pero evita que falte un módulo del JDK
            // (sonido, TLS, JNA) en la PC del usuario.
            includeAllModules = true

            windows {
                // Instalación por usuario: no pide permisos de administrador (RNF-05).
                perUserInstall = true
                dirChooser = true
                menu = true
                menuGroup = "ControlXIA"
                shortcut = true
                // Fijo para que las versiones nuevas actualicen la instalada.
                upgradeUuid = "6f1b8a52-3c1e-4d4b-9a0e-2f8d7c5b1e43"
                iconFile.set(project.file("src/main/resources/icons/controlxia.ico"))
            }
        }

        buildTypes.release.proguard {
            isEnabled.set(false)
        }
    }
}

/**
 * Firma opcional del instalador (para distribuir a otros usuarios). Requiere
 * signtool.exe (Windows SDK) y un certificado de firma de código. Se activa
 * solo si están definidas las variables de entorno; ver docs/FIRMA.md.
 */
tasks.register<Exec>("signExe") {
    group = "distribution"
    description = "Firma el instalador .exe con signtool (opcional)"
    dependsOn("packageExe")
    val certFile = System.getenv("CONTROLXIA_SIGN_PFX")
    val certPass = System.getenv("CONTROLXIA_SIGN_PASSWORD")
    onlyIf { certFile != null && certPass != null }
    doFirst {
        val exe = layout.buildDirectory.dir("compose/binaries/main/exe").get().asFile
            .listFiles { f -> f.extension == "exe" }?.firstOrNull()
            ?: error("No se encontró el instalador. Corré packageExe primero.")
        commandLine(
            System.getenv("CONTROLXIA_SIGNTOOL") ?: "signtool",
            "sign", "/fd", "SHA256", "/f", certFile, "/p", certPass,
            "/tr", "http://timestamp.digicert.com", "/td", "SHA256",
            exe.absolutePath,
        )
    }
}
