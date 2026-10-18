package it.ayngallery

import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

internal data class ExplorerShareAddress(val host: String, val share: String) {
    companion object {
        fun parse(text: String): ExplorerShareAddress {
            val value = text.trim().removePrefix("smb://").replace('\\', '/').trim('/')
            val parts = value.split('/')
            require(parts.size == 2 && parts.all { it.isNotBlank() && it != "." && it != ".." &&
                it.none { c -> c.isISOControl() || c in "?#@" } })
            require(parts[0].none { it.isWhitespace() || it == ':' })
            return ExplorerShareAddress(parts[0], parts[1])
        }
    }
}

/** Remote document IDs are accepted only after server enumeration. */
internal class ExplorerSmbFolder(address: ExplorerShareAddress, user: String, password: CharArray) : AutoCloseable {
    private val client = SMBClient(SmbConfig.builder().withSigningRequired(true).withDfsEnabled(false)
        .withTimeout(5, TimeUnit.SECONDS).withSoTimeout(5, TimeUnit.SECONDS)
        .withBufferSize(64 * 1024).withSocketFactory(BoundedSmbSockets()).build())
    private var share: DiskShare? = null
    private val providerId = "smb:${address.host}/${address.share}"
    private val known = java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply { add("") }
    private val files = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    val root = ExplorerLocation(providerId, "")

    init {
        try {
            val domain = user.substringBefore('\\', "")
            val account = user.substringAfter('\\')
            require(account.isNotBlank())
            val connection = client.connect(address.host)
            val session = connection.authenticate(AuthenticationContext(account, password, domain))
            share = session.connectShare(address.share) as? DiskShare ?: error("Not a disk share")
        } catch (failure: Exception) { client.close(); throw failure }
        finally { password.fill('\u0000') }
    }

    fun list(location: ExplorerLocation): List<ExplorerEntry> {
        require(location.providerId == providerId && location.directoryId in known)
        val disk = checkNotNull(share)
        val result = disk.list(location.directoryId).filter { it.fileName !in setOf(".", "..") && !it.fileName.startsWith(".ayngallery-") && it.fileAttributes and 0x400L == 0L }
        require(result.size <= 10_000)
        return result.map { info ->
            val name = info.fileName
            require(name.isNotBlank() && name.none { it == '/' || it == '\\' || it.isISOControl() })
            val path = listOf(location.directoryId, name).filter(String::isNotEmpty).joinToString("\\")
            val directory = info.fileAttributes and 0x10L != 0L
            if (directory) known += path else files += path
            ExplorerEntry(location, path, name, directory, info.endOfFile.takeUnless { directory },
                info.lastWriteTime.toEpochMillis(), readable = true, writable = info.fileAttributes and 1L == 0L)
        }
    }
    fun resolveDirectory(id: String): ExplorerLocation {
        var at = root
        if (id in known) return ExplorerLocation(providerId, id)
        if (id.isEmpty()) return at
        val parts = id.split('\\')
        require(parts.size <= 32 && parts.all { it.isNotBlank() && it !in setOf(".", "..") })
        for (part in parts) {
            val child = list(at).single { it.directory && it.name == part }
            at = ExplorerLocation(at.providerId, child.id)
        }
        return at
    }

    fun directory(parent: ExplorerLocation, name: String): ExplorerLocation {
        require(parent.providerId == providerId && parent.directoryId in known); FileNames.validateBase(name)
        val path = listOf(parent.directoryId, name).filter(String::isNotEmpty).joinToString("\\")
        checkNotNull(share).mkdir(path); known += path
        return ExplorerLocation(providerId, path)
    }
    fun openRead(entry: ExplorerEntry): com.hierynomus.smbj.share.File {
        require(entry.location.providerId == providerId && entry.id in files)
        val opened = checkNotNull(share).openFile(entry.id, setOf(com.hierynomus.msdtyp.AccessMask.GENERIC_READ), null,
            setOf(com.hierynomus.mssmb2.SMB2ShareAccess.FILE_SHARE_READ), com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_OPEN,
            setOf(com.hierynomus.mssmb2.SMB2CreateOptions.FILE_NON_DIRECTORY_FILE))
        try {
            val info = opened.fileInformation
            check(info.standardInformation.endOfFile == entry.size && info.basicInformation.lastWriteTime.toEpochMillis() == entry.modified)
            return opened
        } catch (failure: Exception) { opened.close(); throw failure }
    }
    fun stat(entry: ExplorerEntry): ExplorerEntry {
        require(entry.location.providerId == providerId && entry.id in files)
        val info = checkNotNull(share).getFileInformation(entry.id)
        return entry.copy(size = info.standardInformation.endOfFile, modified = info.basicInformation.lastWriteTime.toEpochMillis())
    }
    fun <T> read(id: String, action: (java.io.InputStream) -> T): T {
        require(id in files)
        return checkNotNull(share).openFile(id, setOf(com.hierynomus.msdtyp.AccessMask.GENERIC_READ), null,
            com.hierynomus.mssmb2.SMB2ShareAccess.ALL, com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_OPEN,
            setOf(com.hierynomus.mssmb2.SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)).use { file -> file.inputStream.use(action) }
    }
    fun create(parent: ExplorerLocation, name: String, resume: Boolean = false): String {
        require(parent.providerId == providerId && parent.directoryId in known)
        require(name.isNotBlank() && name.none { it in "/\\" || it.isISOControl() })
        val path = listOf(parent.directoryId, name).filter(String::isNotEmpty).joinToString("\\")
        if (resume && name.matches(Regex("\\.ayngallery-[a-f0-9-]{36}\\.part")) && checkNotNull(share).fileExists(path)) { files += path; return path }
        checkNotNull(share).openFile(path, setOf(com.hierynomus.msdtyp.AccessMask.GENERIC_WRITE), null,
            com.hierynomus.mssmb2.SMB2ShareAccess.ALL, com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_CREATE,
            setOf(com.hierynomus.mssmb2.SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)).close()
        files += path
        return path
    }
    fun <T> write(id: String, action: (java.io.OutputStream) -> T): T {
        require(id in files && id.substringAfterLast('\\').matches(Regex("\\.ayngallery-[a-f0-9-]{36}\\.part")))
        return checkNotNull(share).openFile(id, setOf(com.hierynomus.msdtyp.AccessMask.GENERIC_WRITE), null,
            com.hierynomus.mssmb2.SMB2ShareAccess.ALL, com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_OVERWRITE,
            setOf(com.hierynomus.mssmb2.SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)).use { file -> file.outputStream.use(action) }
    }
    fun publish(id: String, parent: ExplorerLocation, name: String): String {
        require(id in files && parent.providerId == providerId && parent.directoryId in known)
        FileNames.validateBase(name)
        val path = listOf(parent.directoryId, name).filter(String::isNotEmpty).joinToString("\\")
        checkNotNull(share).openFile(id, setOf(com.hierynomus.msdtyp.AccessMask.DELETE), null,
            com.hierynomus.mssmb2.SMB2ShareAccess.ALL, com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_OPEN,
            setOf(com.hierynomus.mssmb2.SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)).use { it.rename(path, false) }
        files -= id; files += path
        return path
    }
    fun deleteVerified(entry: ExplorerEntry, expected: FileDigest) {
        require(entry.location.providerId == providerId && entry.id in files)
        // Hold a handle that denies concurrent writers/deleters until the verified deletion commits.
        checkNotNull(share).openFile(entry.id, setOf(com.hierynomus.msdtyp.AccessMask.GENERIC_READ, com.hierynomus.msdtyp.AccessMask.DELETE), null,
            setOf(com.hierynomus.mssmb2.SMB2ShareAccess.FILE_SHARE_READ), com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_OPEN,
            setOf(com.hierynomus.mssmb2.SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)).use { file ->
            val info = file.fileInformation
            check(info.standardInformation.endOfFile == entry.size && info.basicInformation.lastWriteTime.toEpochMillis() == entry.modified)
            check(file.inputStream.use { networkDigest(it) } == expected)
            file.deleteOnClose()
        }
        files -= entry.id
    }

    override fun close() { try { share?.close() } finally { share = null; client.close() } }
}

/** Socket connect has its own deadline, separate from SMB read/transact deadlines. */
private class BoundedSmbSockets : SocketFactory() {
    override fun createSocket() = Socket()
    private fun connect(host: String, port: Int, local: InetAddress? = null, localPort: Int = 0): Socket {
        val socket = Socket()
        try {
            if (local != null) socket.bind(InetSocketAddress(local, localPort))
            socket.connect(InetSocketAddress(host, port), 5_000)
            socket.soTimeout = 5_000
            return socket
        } catch (failure: Exception) { socket.close(); throw failure }
    }
    override fun createSocket(host: String, port: Int) = connect(host, port)
    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int) = connect(host, port, local, localPort)
    override fun createSocket(host: InetAddress, port: Int) = connect(host.hostAddress!!, port)
    override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int) = connect(host.hostAddress!!, port, local, localPort)
}
