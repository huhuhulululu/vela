package vela.app.infra

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import vela.core.base.model.Prefs

/**
 * filesDir 下 Vela 目录里的 json 原子写：先把完整内容同步到同目录临时文件，再原子替换目标。
 * 写到一半失败时目标文件保持原样，临时文件不留下。
 */
object AtomicFile {
    const val DIRECTORY = "Vela"

    fun resolve(filesDir: File, name: String): File {
        require(name.isNotEmpty() && name != "." && name != "..") { "非法文件名" }
        require('/' !in name && '\\' !in name && '\u0000' !in name) { "非法文件名" }
        return File(File(filesDir, DIRECTORY), name)
    }

    fun write(
        target: File,
        bytes: ByteArray,
        writeTemp: (File, ByteArray) -> Unit = ::writeTempFile,
    ) {
        val parent = target.parentFile ?: throw IOException("缺少父目录")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("无法创建目录")
        val temporary = File(parent, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            writeTemp(temporary, bytes)
            moveIntoPlace(temporary, target)
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    /** 超过 [limit] 或文件不存在时返回 null，不抛出。 */
    fun read(target: File, limit: Int = Prefs.maxFileBytes): ByteArray? {
        if (!target.isFile) return null
        if (target.length() > limit.toLong()) return null
        return target.readBytes()
    }

    private fun writeTempFile(temporary: File, bytes: ByteArray) {
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }

    private fun moveIntoPlace(temporary: File, target: File) {
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            if (!temporary.renameTo(target)) throw IOException("原子替换失败")
        }
    }
}
