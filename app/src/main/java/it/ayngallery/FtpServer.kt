package it.ayngallery

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.runBlocking
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.ConnectionConfigFactory
import org.apache.ftpserver.DataConnectionConfigurationFactory
import org.apache.ftpserver.command.Command
import org.apache.ftpserver.command.CommandFactoryFactory
import org.apache.ftpserver.ftplet.*
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.UsernamePasswordAuthentication
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.net.InetAddress
import java.security.MessageDigest

/** Passive FTP over a single SAF grant. Lifetime is owned by the foreground sharing service. */
internal class FtpServer(context: Context, private val files: FileOperations, tree: Uri,
                         private val address: InetAddress, private val port: Int,
                         private val password: String, private val afterUpload: (String) -> Unit = {}) {
    private val shared = FtpSharedFolder(context, tree)
    private var server: org.apache.ftpserver.FtpServer? = null
    @Volatile private var stopping = false
    fun start() {
        check(server == null)
        stopping = false
        require(!address.isAnyLocalAddress && !address.isMulticastAddress && port in 1024..65535 && password.length >= 12)
        val user = BaseUser().apply {
            name = USER; homeDirectory = "/"; setEnabled(true); maxIdleTime = 120
            authorities = listOf(WritePermission(), ConcurrentLoginPermission(2, 2))
        }
        val factory = FtpServerFactory().apply {
            userManager = object : UserManager {
                override fun getUserByName(name: String?) = user.takeIf { name == USER }
                override fun getAllUserNames() = arrayOf(USER)
                override fun doesExist(name: String?) = name == USER
                override fun getAdminName() = USER
                override fun isAdmin(name: String?) = false
                override fun save(value: User?) = throw UnsupportedOperationException()
                override fun delete(name: String?) = throw UnsupportedOperationException()
                override fun authenticate(authentication: Authentication?): User {
                    val supplied = authentication as? UsernamePasswordAuthentication ?: throw AuthenticationFailedException()
                    if (supplied.username != USER || !MessageDigest.isEqual(password.toByteArray(Charsets.UTF_8),
                            supplied.password.orEmpty().toByteArray(Charsets.UTF_8))) throw AuthenticationFailedException()
                    return user
                }
            }
            fileSystem = FileSystemFactory { View(shared, files) }
            connectionConfig = ConnectionConfigFactory().apply {
                maxLogins = 2; maxAnonymousLogins = 0; isAnonymousLoginEnabled = false; maxLoginFailures = 3
            }.createConnectionConfig()
            val data = DataConnectionConfigurationFactory().apply {
                isActiveEnabled = false; isPassiveIpCheck = true; passiveAddress = address.hostAddress; idleTime = 30
            }.createDataConnectionConfiguration()
            addListener("default", ListenerFactory().apply {
                this.port = this@FtpServer.port; serverAddress = address.hostAddress; dataConnectionConfiguration = data
            }.createListener())
            commandFactory = CommandFactoryFactory().apply {
                addCommand("STOR", receive)
                val unsupported = Command { session, _, _ -> session.write(DefaultFtpReply(502, "Command not supported")) }
                // Never append, overwrite, delete or remotely rename user files.
                listOf("APPE", "STOU", "DELE", "RMD", "RNFR", "RNTO").forEach { addCommand(it, unsupported) }
            }.createCommandFactory()
        }
        val opened = factory.createServer()
        try { opened.start(); server = opened } catch (error: Exception) { opened.stop(); throw error }
    }
    fun stop() { stopping = true; server?.stop(); server = null }
    private val receive = Command { session, _, request ->
        var operation: String? = null
        try {
            if (!session.isLoggedIn) { session.write(DefaultFtpReply(530, "Log in first")); return@Command }
            if (!request.hasArgument() || session.fileOffset != 0L) {
                session.write(DefaultFtpReply(550, "Choose a new file; upload resume is not supported")); return@Command
            }
            val path = session.fileSystemView.getFile(request.argument).absolutePath
            val (parent, name) = shared.destination(path)
            if (shared.children(parent).any { it.name.equals(name, true) }) {
                session.write(DefaultFtpReply(550, "File already exists")); return@Command
            }
            session.write(DefaultFtpReply(150, "Opening data connection")).awaitUninterruptibly(10_000)
            val data = session.dataConnection.openConnection()
            val (id, output) = runBlocking { files.beginFtpReceive(parent, name) }
            operation = id
            output.use { data.transferFromClient(session.ftpletSession, it) }
            check(!stopping) { "Sharing stopped during transfer" }
            // A successful EOF alone is not enough: acknowledge only the verified provider copy.
            runBlocking { files.finishFtpReceive(id) }
            runCatching { afterUpload(name) }
            session.write(DefaultFtpReply(226, "Transfer verified and complete"))
        } catch (_: Exception) {
            operation?.let { runCatching { runBlocking { files.abortFtpReceive(it) } } }
            session.write(DefaultFtpReply(550, "Transfer failed; no existing file was overwritten"))
        } finally {
            session.resetState(); session.dataConnection.closeDataConnection()
        }
    }
    private class View(private val shared: FtpSharedFolder, private val files: FileOperations) : FileSystemView {
        private var working = "/"
        override fun getHomeDirectory(): FtpFile = Entry(shared, files, "/")
        override fun getWorkingDirectory(): FtpFile = Entry(shared, files, working)
        override fun getFile(path: String): FtpFile = Entry(shared, files, "/" + FtpSharedFolder.components(path, working).joinToString("/"))
        override fun changeWorkingDirectory(path: String): Boolean {
            val entry = getFile(path)
            if (!entry.isDirectory) return false
            working = entry.absolutePath; return true
        }
        override fun isRandomAccessible() = false
        override fun dispose() = Unit
    }
    private class Entry(private val shared: FtpSharedFolder, private val files: FileOperations, private val path: String,
                        private val listedInfo: DocumentInfo? = null) : FtpFile {
        private val metadata by lazy { listedInfo ?: runCatching { shared.resolve(path) }.getOrNull() }
        private fun info() = metadata
        override fun getAbsolutePath() = path
        override fun getName() = path.substringAfterLast('/').ifEmpty { "/" }
        override fun isHidden() = false
        override fun doesExist() = info() != null
        override fun isDirectory() = info()?.mime == DocumentsContract.Document.MIME_TYPE_DIR
        override fun isFile() = info()?.let { it.mime != DocumentsContract.Document.MIME_TYPE_DIR } == true
        override fun isReadable() = doesExist()
        override fun isWritable() = !doesExist() && runCatching { shared.destination(path) }.isSuccess
        override fun isRemovable() = false
        override fun getOwnerName() = USER
        override fun getGroupName() = USER
        override fun getLinkCount() = if (isDirectory) 2 else 1
        override fun getLastModified() = info()?.modified ?: 0L
        override fun setLastModified(time: Long) = false
        override fun getSize() = info()?.size?.coerceAtLeast(0) ?: 0L
        override fun getPhysicalFile(): Any = path
        override fun mkdir(): Boolean = runCatching {
            val (parent, name) = shared.destination(path)
            runBlocking { files.createFtpDirectory(parent, name) }
            true
        }.getOrDefault(false)
        override fun delete() = false
        override fun move(destination: FtpFile?) = false
        override fun listFiles(): List<FtpFile>? {
            val folder = info()?.takeIf { it.mime == DocumentsContract.Document.MIME_TYPE_DIR } ?: return null
            return shared.children(folder.uri).sortedBy { it.name }.map { Entry(shared, files, path.trimEnd('/') + "/" + it.name, it) }
        }
        override fun createOutputStream(offset: Long): OutputStream = throw IOException("Use verified STOR")
        override fun createInputStream(offset: Long): InputStream {
            if (offset != 0L) throw IOException("Download resume is not supported")
            val file = shared.resolve(path)
            if (file.mime == DocumentsContract.Document.MIME_TYPE_DIR) throw IOException("Not a file")
            return shared.documents.input(file.uri)
        }
    }
    companion object { const val USER = "dsgallery" }
}
