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
    // Whisper local (whisper.cpp) para transcribir mejor sin internet.
    implementation("io.github.givimad:whisper-jni:1.7.1")
    // Subida de audio a Whisper en la nube (Groq, OpenAI).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.test {
    useJUnitPlatform()
}
