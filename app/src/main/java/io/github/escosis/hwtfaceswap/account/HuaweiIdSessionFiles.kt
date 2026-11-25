package io.github.escosis.hwtfaceswap.account

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** No Android/WebView initialization in the parent. Only the reserved suffix trees are website data. */
internal object HuaweiIdSessionFiles {
    const val PREFIX = "wristmirror_huawei_id_"
    private const val LOCK_NAME = "huawei-id-webview.lock"
    private val dataName = Regex("app_webview_${PREFIX}[0-9a-f]{32}")
    private val cacheName = Regex("webview_${PREFIX}[0-9a-f]{32}")

    fun suffix(request: String): String {
        require(HuaweiIdWebPolicy.validRequest(request))
        return PREFIX + request.replace("-", "")
    }

    class Lease internal constructor(private val file: RandomAccessFile, private val lock: FileLock) : Closeable {
        override fun close() { try { lock.release() } finally { file.close() } }
    }

    /** An immutable lock filename; deleting it would allow two different lock inodes to coexist. */
    fun tryLease(noBackup: File): Lease? {
        if (!noBackup.isDirectory && !noBackup.mkdirs()) return null
        val target = File(noBackup, LOCK_NAME)
        if (target.canonicalFile.parentFile != noBackup.canonicalFile) return null
        val file = RandomAccessFile(target, "rw")
        try {
            val lock = try { file.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            if (lock == null) { file.close(); return null }
            return Lease(file, lock)
        } catch (failure: Exception) { file.close(); throw failure }
    }

    fun dataPath(dataRoot: File, request: String) = File(dataRoot, "app_webview_" + suffix(request))

    /** Probe before loading any website. An unrecognized provider layout never gets a login session. */
    fun hasExpectedDataPath(dataRoot: File, request: String): Boolean {
        val path = dataPath(dataRoot, request)
        return path.isDirectory && path.canonicalFile == File(dataRoot.canonicalFile, path.name)
    }

    fun hasExpectedLayout(dataRoot: File, cacheRoot: File, request: String): Boolean {
        val cache = File(cacheRoot, "webview_" + suffix(request))
        return hasExpectedDataPath(dataRoot, request) && cache.isDirectory &&
            cache.canonicalFile == File(cacheRoot.canonicalFile, cache.name)
    }

    /** Caller must own the process lease. Both scopes are attempted; links are never followed outside a tree. */
    fun purge(dataRoot: File, cacheRoot: File): Boolean {
        val dataCleared = runCatching { purgeRoot(dataRoot, dataName) }.getOrDefault(false)
        val cacheCleared = runCatching { purgeRoot(cacheRoot, cacheName) }.getOrDefault(false)
        return dataCleared && cacheCleared
    }

    private fun purgeRoot(root: File, names: Regex): Boolean {
        if (!root.isDirectory) return false
        val children = root.listFiles() ?: return false
        for (child in children) {
            if (!names.matches(child.name)) continue
            if (child.canonicalFile != File(root.canonicalFile, child.name)) return false
            Files.walkFileTree(child.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file); return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                    if (error != null) throw error
                    Files.delete(dir); return FileVisitResult.CONTINUE
                }
            })
            if (Files.exists(child.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return false
        }
        return root.listFiles()?.none { names.matches(it.name) } == true
    }
}
