package it.ayngallery

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class AlbumProtectionState(val protected: Set<String> = emptySet(), val unlocked: Set<String> = emptySet())

/** Only access inside this app is protected; original documents and their grants are untouched. */
class AlbumProtection(context: Context, private val marker: suspend (Set<String>, Boolean) -> Set<String> = { _, _ -> emptySet() }) {
    companion object { const val MARKER = ".ayngallery-album-lock.json" }
    private val prefs = context.getSharedPreferences("album_protection", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    @Volatile private var generation = 0L
    @Volatile var markerRevision = 0L
        private set
    val markerIds get() = prefs.getStringSet("marked", emptySet())!!.toSet()
    val state = MutableStateFlow(AlbumProtectionState(prefs.getStringSet("albums", emptySet())!!.toSet()))
    val configured get() = prefs.contains("hash")
    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)
    private fun valid(pin: String) = pin.length == 4 && pin.all { it in '0'..'9' }
    fun waitSeconds(): Int = ((prefs.getLong("retry_at", 0) - System.currentTimeMillis()).coerceIn(0, 30_000) + 999).div(1000).toInt()
    private fun verify(pin: String): Boolean {
        if (!valid(pin) || waitSeconds() > 0 || !configured) return false
        val matches = MessageDigest.isEqual(hash(pin, decode(prefs.getString("salt", "")!!)), decode(prefs.getString("hash", "")!!))
        val failures = if (matches) 0 else prefs.getInt("failures", 0) + 1
        check(prefs.edit().putInt("failures", failures).putLong("retry_at", if (failures >= 5) System.currentTimeMillis() + 30_000 else 0).commit())
        return matches
    }
    suspend fun protect(ids: Set<String>, pin: String): Boolean = withContext(Dispatchers.IO) { mutex.withLock {
        require(ids.isNotEmpty() && valid(pin))
        if (configured) { if (!verify(pin)) return@withLock false }
        else {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            check(prefs.edit().putString("salt", encode(salt)).putString("hash", encode(hash(pin, salt))).commit())
        }
        val marked = marker(ids, true)
        val next = state.value.protected + ids
        check(prefs.edit().putStringSet("albums", next).putStringSet("marked", prefs.getStringSet("marked", emptySet())!! + marked).commit())
        state.value = AlbumProtectionState(next)
        markerRevision++
        true
    } }
    suspend fun unlock(pin: String): Boolean {
        val started = generation
        return withContext(Dispatchers.IO) { mutex.withLock {
        if (!verify(pin)) return@withLock false
        synchronized(this@AlbumProtection) {
            if (started != generation) false
            else { state.value = state.value.copy(unlocked = state.value.protected); true }
        }
    } } }
    suspend fun unprotect(ids: Set<String>, pin: String): Boolean = withContext(Dispatchers.IO) { mutex.withLock {
        if (!verify(pin)) return@withLock false
        marker(ids, false)
        val next = state.value.protected - ids
        check(prefs.edit().putStringSet("albums", next).putStringSet("marked", prefs.getStringSet("marked", emptySet())!! - ids).commit())
        state.value = AlbumProtectionState(next, state.value.unlocked - ids)
        markerRevision++
        true
    } }
    suspend fun configure(pin: String, previous: String? = null): Boolean = withContext(Dispatchers.IO) { mutex.withLock {
        require(valid(pin))
        if (configured && (previous == null || !verify(previous))) return@withLock false
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        check(prefs.edit().putString("salt", encode(salt)).putString("hash", encode(hash(pin, salt))).putInt("failures", 0).putLong("retry_at", 0).commit())
        lock()
        true
    } }
    suspend fun authorize(pin: String): Boolean = withContext(Dispatchers.IO) { mutex.withLock { verify(pin) } }
    /** Only complete, authorized directory reads can remove a deleted marker. Failed reads retain protection. */
    suspend fun observeMarkers(observed: Map<String, Boolean>, expectedRevision: Long = markerRevision) = mutex.withLock {
        if (expectedRevision != markerRevision) return@withLock
        if (observed.isEmpty()) return@withLock
        val present = observed.filterValues { it }.keys
        val marked = prefs.getStringSet("marked", emptySet())!!.toSet()
        val removed = observed.filterValues { !it }.keys.intersect(marked)
        val next = (state.value.protected + present) - removed
        val nextMarked = (marked + present) - removed
        if (next == state.value.protected && nextMarked == marked) return@withLock
        check(prefs.edit().putStringSet("albums", next).putStringSet("marked", nextMarked).commit())
        synchronized(this) { state.value = AlbumProtectionState(next, state.value.unlocked.intersect(next)) }
    }
    @Synchronized fun lock() { generation++; state.value = state.value.copy(unlocked = emptySet()) }
}
