import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(project(":brain"))

    // Acceso a Win32: atajo global, teclado/mouse, credenciales, registro.
    api("net.java.dev.jna:jna-platform:5.14.0")
    // Palabra de activación (Picovoice). Mismo AccessKey que en Android.
    implementation("ai.picovoice:porcupine-java:3.0.5")
    // Voz a texto offline (y palabra de activación sin cuenta).
    implementation("com.alphacephei:vosk:0.3.45")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.test {
    useJUnitPlatform()
}
