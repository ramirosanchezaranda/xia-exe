import java.security.MessageDigest
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

// Con -PtargetOs=windows se usan las librerías nativas de Windows aunque se
// compile en otro sistema (para armar la versión portable desde Linux).
val targetWindows = providers.gradleProperty("targetOs").orNull == "windows"

dependencies {
    implementation(project(":windows"))

    implementation(if (targetWindows) compose.desktop.windows_x64 else compose.desktop.currentOs)
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

// ---------------------------------------------------------------------------
// Versión portable para Windows: un .zip con Java incluido y un lanzador.
// No necesita instalarse ni compilarse en Windows:
//   ./gradlew :desktop:portableWindowsZip -PtargetOs=windows
// ---------------------------------------------------------------------------

val jreVersion = "17.0.16+8"
// SHA-256 publicado por Adoptium para ese zip.
val jreSha256 = "d35b05f4832215d8877d0dbf15c6370c854d7d5b812f890a9c0db8ad412a6bf2"
val jreZip = layout.buildDirectory.file("jre/temurin-jre-$jreVersion-windows-x64.zip")

val downloadWindowsJre by tasks.registering {
    group = "distribution"
    description = "Descarga el Java (Temurin JRE) para Windows x64"
    outputs.file(jreZip)
    doLast {
        val file = jreZip.get().asFile
        fun sha256(f: File) = MessageDigest.getInstance("SHA-256")
            .digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        if (file.exists() && sha256(file) == jreSha256) return@doLast
        file.parentFile.mkdirs()
        val tag = jreVersion.replace("+", "%2B")
        val name = "OpenJDK17U-jre_x64_windows_hotspot_${jreVersion.replace('+', '_')}.zip"
        val url = "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-$tag/$name"
        logger.lifecycle("Descargando $url")
        uri(url).toURL().openStream().use { input -> file.outputStream().use { output -> input.copyTo(output) } }
        check(sha256(file) == jreSha256) {
            file.delete()
            "El Java descargado no coincide con el checksum de Adoptium"
        }
    }
}

tasks.register<Zip>("portableWindowsZip") {
    group = "distribution"
    description = "Arma ControlXIA portable para Windows (zip con Java incluido)"
    dependsOn(tasks.named("jar"), downloadWindowsJre)
    doFirst {
        check(targetWindows) { "Corré la tarea con -PtargetOs=windows" }
    }
    archiveFileName.set("ControlXIA-$appVersion-windows-portable.zip")
    destinationDirectory.set(layout.buildDirectory.dir("portable"))
    into("ControlXIA") {
        from(tasks.named("jar")) { into("lib") }
        from(configurations.named("runtimeClasspath")) { into("lib") }
        from(project.file("src/portable"))
    }
    from(jreZip.map { zipTree(it) }) {
        // El zip trae una carpeta "jdk-17…-jre/": queda como ControlXIA/runtime/.
        eachFile { path = path.replaceFirst(Regex("^[^/]+/"), "ControlXIA/runtime/") }
    }
    includeEmptyDirs = false
}
