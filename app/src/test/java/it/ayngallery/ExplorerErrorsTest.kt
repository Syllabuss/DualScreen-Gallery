package it.ayngallery

import org.junit.Assert.assertEquals
import org.junit.Test
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.mssmb2.SMB2MessageCommandCode

class ExplorerErrorsTest {
    @Test fun wrappedTransportAndSmbFailuresKeepTheirSpecificGuidance() {
        assertEquals(R.string.network_host_error, explorerConnectionError(java.io.IOException(java.net.UnknownHostException())))
        assertEquals(R.string.network_timeout_error, explorerConnectionError(java.io.IOException(java.net.SocketTimeoutException())))
        assertEquals(R.string.network_offline_error, explorerConnectionError(java.net.ConnectException()))
        assertEquals(R.string.network_auth_error, explorerConnectionError(SMBApiException(0xC000006DL, SMB2MessageCommandCode.SMB2_SESSION_SETUP, null)))
        assertEquals(R.string.network_share_error, explorerConnectionError(SMBApiException(0xC00000CCL, SMB2MessageCommandCode.SMB2_TREE_CONNECT, null)))
        assertEquals(R.string.network_permission_error, explorerConnectionError(SecurityException()))
    }
}
