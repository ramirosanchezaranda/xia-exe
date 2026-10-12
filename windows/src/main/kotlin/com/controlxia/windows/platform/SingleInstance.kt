package com.controlxia.windows.platform

import com.controlxia.windows.config.AppPaths
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock

/** Evita dos asistentes escuchando a la vez: un lock de archivo por usuario. */
object SingleInstance {
    private var channel: FileChannel? = null
    private var lock: FileLock? = null

    fun acquire(): Boolean {
        val file = File(AppPaths.dataDir, "instance.lock")
        channel = RandomAccessFile(file, "rw").channel
        lock = runCatching { channel!!.tryLock() }.getOrNull()
        return lock != null
    }
}
