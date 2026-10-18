package it.ayngallery

import com.hierynomus.mssmb2.SMBApiException

internal fun explorerConnectionError(failure: Throwable): Int {
    val causes = generateSequence(failure) { it.cause }.take(12).toList()
    val status = causes.filterIsInstance<SMBApiException>().firstOrNull()?.statusCode
    return when {
        status in setOf(0xC000006DL, 0xC000006AL, 0xC0000064L, 0xC0000072L, 0xC0000234L) -> R.string.network_auth_error
        status == 0xC00000CCL -> R.string.network_share_error
        status == 0xC0000022L || causes.any { it is SecurityException } -> R.string.network_permission_error
        causes.any { it is java.net.UnknownHostException } -> R.string.network_host_error
        causes.any { it is java.net.SocketTimeoutException || it is java.util.concurrent.TimeoutException } -> R.string.network_timeout_error
        causes.any { it is java.net.ConnectException || it is java.net.NoRouteToHostException || it is java.net.SocketException } -> R.string.network_offline_error
        else -> R.string.explorer_remote_error
    }
}

/** Diagnostic types, protocol codes and call sites only: no paths or credentials. */
internal fun logExplorerFailure(failure: Throwable, phase: String) {
    val types = generateSequence(failure) { it.cause }.take(8).joinToString(" -> ") { cause ->
        cause.javaClass.name + ((cause as? SMBApiException)?.statusCode?.let { ":0x${it.toString(16)}" } ?: "")
    }
    val sites = failure.stackTrace.take(6).joinToString(" <- ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
    android.util.Log.w("DSGalleryTransfer", "$phase: $types; $sites")
}
