package com.controlxia.windows.config

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.io.File
import java.util.Properties

/** Guarda secretos (API keys, AccessKey de Picovoice) fuera del archivo de configuración. */
interface SecretStore {
    fun get(key: String): String
    fun set(key: String, value: String)

    companion object {
        /** Credential Manager en Windows; archivo local en otros sistemas (solo desarrollo). */
        fun create(): SecretStore =
            if (AppPaths.isWindows) WindowsCredentialStore() else FileSecretStore(File(AppPaths.dataDir, "secrets.properties"))

        fun llmKey(type: com.controlxia.brain.llm.LlmProviderType) = "llm.${type.name}"
        const val PORCUPINE_ACCESS_KEY = "porcupine.accessKey"
        const val STT_CLOUD_KEY = "stt.cloud"
    }
}

/**
 * Administrador de credenciales de Windows (D6): las claves quedan cifradas
 * por el sistema con la cuenta del usuario y se ven en "Credenciales de
 * Windows" como ControlXIA/…
 */
class WindowsCredentialStore : SecretStore {

    override fun get(key: String): String {
        val ref = PointerByReference()
        if (!CredApi.INSTANCE.CredReadW(WString(target(key)), CRED_TYPE_GENERIC, 0, ref)) return ""
        return try {
            val cred = Credential(ref.value)
            if (cred.CredentialBlobSize == 0 || cred.CredentialBlob == null) ""
            else String(cred.CredentialBlob!!.getByteArray(0, cred.CredentialBlobSize), Charsets.UTF_8)
        } finally {
            CredApi.INSTANCE.CredFree(ref.value)
        }
    }

    override fun set(key: String, value: String) {
        if (value.isEmpty()) {
            CredApi.INSTANCE.CredDeleteW(WString(target(key)), CRED_TYPE_GENERIC, 0)
            return
        }
        val bytes = value.toByteArray(Charsets.UTF_8)
        val blob = Memory(bytes.size.toLong()).apply { write(0, bytes, 0, bytes.size) }
        val cred = Credential().apply {
            Type = CRED_TYPE_GENERIC
            TargetName = WString(target(key))
            CredentialBlobSize = bytes.size
            CredentialBlob = blob
            Persist = CRED_PERSIST_LOCAL_MACHINE
            UserName = WString("ControlXIA")
        }
        if (!CredApi.INSTANCE.CredWriteW(cred, 0)) {
            throw IllegalStateException("No se pudo guardar la credencial (error ${Native.getLastError()})")
        }
    }

    private fun target(key: String) = "ControlXIA/$key"

    @Suppress("PropertyName")
    @Structure.FieldOrder(
        "Flags", "Type", "TargetName", "Comment", "LastWritten", "CredentialBlobSize",
        "CredentialBlob", "Persist", "AttributeCount", "Attributes", "TargetAlias", "UserName",
    )
    class Credential : Structure {
        @JvmField var Flags: Int = 0
        @JvmField var Type: Int = 0
        @JvmField var TargetName: WString? = null
        @JvmField var Comment: WString? = null
        @JvmField var LastWritten: WinBase.FILETIME = WinBase.FILETIME()
        @JvmField var CredentialBlobSize: Int = 0
        @JvmField var CredentialBlob: Pointer? = null
        @JvmField var Persist: Int = 0
        @JvmField var AttributeCount: Int = 0
        @JvmField var Attributes: Pointer? = null
        @JvmField var TargetAlias: WString? = null
        @JvmField var UserName: WString? = null

        constructor() : super()
        constructor(p: Pointer) : super(p) { read() }
    }

    @Suppress("FunctionName")
    interface CredApi : StdCallLibrary {
        fun CredWriteW(credential: Credential, flags: Int): Boolean
        fun CredReadW(target: WString, type: Int, flags: Int, credential: PointerByReference): Boolean
        fun CredDeleteW(target: WString, type: Int, flags: Int): Boolean
        fun CredFree(buffer: Pointer)

        companion object {
            val INSTANCE: CredApi = Native.load("Advapi32", CredApi::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
    }

    private companion object {
        const val CRED_TYPE_GENERIC = 1
        const val CRED_PERSIST_LOCAL_MACHINE = 2
    }
}

/** Respaldo para desarrollo fuera de Windows. No cifra: no usar en producción. */
class FileSecretStore(private val file: File) : SecretStore {
    private val props = Properties().apply { if (file.exists()) file.inputStream().use { load(it) } }

    override fun get(key: String): String = props.getProperty(key).orEmpty()

    @Synchronized
    override fun set(key: String, value: String) {
        if (value.isEmpty()) props.remove(key) else props.setProperty(key, value)
        file.outputStream().use { props.store(it, "ControlXIA (solo desarrollo)") }
        runCatching {
            file.setReadable(false, false); file.setReadable(true, true)
            file.setWritable(false, false); file.setWritable(true, true)
        }
    }
}
