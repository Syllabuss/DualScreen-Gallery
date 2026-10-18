package it.ayngallery

import org.junit.Assert.*
import org.junit.Test

class ExplorerShareAddressTest {
    @Test fun windowsAndSmbAddressesIdentifyExactlyOneShare() {
        assertEquals(ExplorerShareAddress("PC", "Photos"), ExplorerShareAddress.parse("\\\\PC\\Photos"))
        assertEquals(ExplorerShareAddress("192.168.1.5", "My Photos"), ExplorerShareAddress.parse("smb://192.168.1.5/My Photos"))
    }
    @Test fun credentialsTraversalPortsAndSubfoldersAreNotAcceptedAsShares() {
        for (raw in listOf("PC", "smb://user:password@PC/Photos", "PC/Photos/../../other", "PC/..", "PC/Photos/Child", "PC:445/Photos", "PC/Photos\u0000"))
            assertTrue(raw, runCatching { ExplorerShareAddress.parse(raw) }.isFailure)
    }
}
