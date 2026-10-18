package it.ayngallery

import android.app.Service
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.Inet4Address
import java.net.InetAddress
import java.security.SecureRandom

internal data class FtpState(val tree: String? = null, val starting: Boolean = false,
    val startedAt: Long? = null, val address: String? = null, val password: String? = null, val error: String? = null) {
    val running get() = address != null
}
internal class FtpSharing(private val app: GalleryApp) {
    private val prefs = app.getSharedPreferences("ftp_share", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(FtpState(tree = prefs.getString("tree", null)))
    val state = mutable.asStateFlow()
    private val mutex = Mutex()
    private var server: FtpServer? = null
    fun choose(tree: Uri) {
        check(!state.value.running && !state.value.starting)
        FtpSharedFolder(app, tree) // Confirm the persisted read/write grant before retaining it.
        prefs.edit().putString("tree", tree.toString()).apply()
        mutable.value = FtpState(tree = tree.toString())
    }
    fun start() {
        if (state.value.running || state.value.starting) return
        mutable.value = state.value.copy(starting = true, error = null)
        try { ContextCompat.startForegroundService(app, Intent(app, FtpSharingService::class.java)) }
        catch (error: Exception) { mutable.value = state.value.copy(starting = false, error = error.message) }
    }
    fun stop() { app.stopService(Intent(app, FtpSharingService::class.java)) }
    suspend fun open() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (server != null) return@withLock
            try {
                val tree = Uri.parse(requireNotNull(state.value.tree))
                val address = localAddress()
                val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
                val password = buildString { val random = SecureRandom(); repeat(16) { append(alphabet[random.nextInt(alphabet.length)]) } }
                val opened = FtpServer(app, app.files, tree, address, PORT, password) { name ->
                    if (MediaNames.kind("application/octet-stream", name) != null) app.albums.refreshDevice(force = true)
                }
                val startedAt = System.currentTimeMillis()
                opened.start(); server = opened
                mutable.value = FtpState(tree.toString(), startedAt = startedAt, address = "ftp://${address.hostAddress}:$PORT", password = password)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.value = state.value.copy(starting = false, error = error.message) }
        }
    }
    suspend fun close() = withContext(Dispatchers.IO) {
        mutex.withLock {
            server?.stop(); server = null
            mutable.value = state.value.copy(starting = false, startedAt = null, address = null, password = null)
        }
    }
    private fun localAddress(): InetAddress {
        val networks = app.getSystemService(ConnectivityManager::class.java)
        for (network in networks.allNetworks) {
            val capabilities = networks.getNetworkCapabilities(network) ?: continue
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue
            networks.getLinkProperties(network)?.linkAddresses?.firstOrNull {
                it.address is Inet4Address && !it.address.isAnyLocalAddress && !it.address.isLoopbackAddress
            }?.let { return it.address }
        }
        error(localized(R.string.ftp_wifi_required, "Collega il Thor e l’altro dispositivo alla stessa rete Wi-Fi."))
    }
    companion object { const val PORT = 2121 }
}

class FtpSharingService : Service() {
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val app get() = application as GalleryApp
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("ftp", "FTP", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, FtpSharingService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        startForeground(431, NotificationCompat.Builder(this, "ftp").setSmallIcon(R.drawable.ph_folders)
            .setContentTitle(localized(R.string.ftp_title, "Condivisione FTP"))
            .setContentText(localized(R.string.ftp_notification, "La cartella scelta è disponibile sulla rete locale."))
            .setOngoing(true).addAction(0, localized(R.string.ftp_stop, "Ferma condivisione"), stop).build())
        jobs.launch { app.ftp.open(); if (!app.ftp.state.value.running) stopSelf() }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }
    override fun onDestroy() {
        jobs.cancel()
        // Stop listening before the service disappears; no lingering server and no automatic restart.
        app.catalog.scope.launch(Dispatchers.IO) { app.ftp.close() }
        super.onDestroy()
    }
}
