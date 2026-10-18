package it.ayngallery

import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28], application = android.app.Application::class)
class ExplorerDirectoryRecoveryTest {
    private val parent = ExplorerLocation("smb:fixture/share", "parent")
    private val op = FileOperation("job", "legacy", "NETWORK_MKDIR", "source", "smb:old/share", "", "Album",
        "vnd.android.document/directory", 0, 0, parent.directoryId, parent.providerId, "Album", true, "job")
    @Test fun publishedFileIsRecoveredAfterRenameEvenBeforeItsUriWasJournaled() = runBlocking {
        val digest = FileDigest(3, "verified")
        val pending = op.copy(kind = "NETWORK_COPY", finalName = "song.mp3", recipe = "{\"publicationName\":\"song.mp3\"}")
        var recorded: FileOperation? = null
        val result = recoverNetworkPublication(pending, digest, { name -> assertEquals("song.mp3", name); "target/song.mp3" to digest }, { recorded = it })
        assertEquals("target/song.mp3", result!!.first)
        assertEquals("PUBLISHED", recorded!!.phase)
        assertEquals(result.first, recorded!!.publishedUri)
        assertNull(recoverNetworkPublication(recorded!!, digest, { error("Already journaled") }, {}))
    }
    @Test fun unrelatedOrChangedDestinationNeverAuthorizesRecoveryOrSourceDeletion() = runBlocking {
        val digest = FileDigest(3, "verified")
        assertNull(recoverNetworkPublication(op, digest, { error("No publication intent") }, {}))
        val pending = op.copy(kind = "NETWORK_MOVE", finalName = "song.mp3", recipe = "{\"publicationName\":\"song.mp3\"}")
        var saved = false
        assertTrue(runCatching { recoverNetworkPublication(pending, digest, { "target" to FileDigest(3, "changed") }, { saved = true }) }.isFailure)
        assertFalse(saved)
        assertNull(recoverNetworkPublication(pending, digest, { null }, {}))
    }
    @Test fun createdFolderIsJournaledAndRetryReusesItWithoutCreatingAnother() = runBlocking {
        val entries = mutableListOf<ExplorerEntry>()
        var creates = 0
        val records = mutableListOf<FileOperation>()
        val create: suspend (String) -> ExplorerLocation = { name ->
            creates++; entries += ExplorerEntry(parent, "parent/Album", name, true, null, null, true, true)
            ExplorerLocation(parent.providerId, "parent/Album")
        }
        // Publication can precede a process death: retry must reconcile the existing folder.
        create("Album")
        val result = recoverNetworkDirectory(op, { entries }, create, { records += it })
        assertEquals("parent/Album", result.directoryId); assertEquals(1, creates)
        assertEquals("COMPLETED", records.single().phase)
        recoverNetworkDirectory(records.single(), { entries }, create, {})
        assertEquals(1, creates)
    }
    @Test fun fileCollisionOrChangedPublishedIdentityNeverCreatesOrCompletes() = runBlocking {
        for (entry in listOf(ExplorerEntry(parent, "file", "Album", false, 1, 1, true, true),
            ExplorerEntry(parent, "other", "Album", true, null, null, true, true))) {
            var created = false; var saved = false
            val result = runCatching { recoverNetworkDirectory(op.copy(publishedUri = "expected"), { listOf(entry) },
                { created = true; parent }, { saved = true }) }
            assertTrue(result.isFailure); assertFalse(created); assertFalse(saved)
        }
    }
    @Test fun missingUnpublishedFolderIsCreatedButMissingPublishedFolderIsNotReplaced() = runBlocking {
        var saved: FileOperation? = null
        val result = recoverNetworkDirectory(op, { emptyList() }, { ExplorerLocation(parent.providerId, "new") }, { saved = it })
        assertEquals("new", result.directoryId); assertEquals("new", saved!!.publishedUri)
        assertTrue(runCatching { recoverNetworkDirectory(op.copy(publishedUri = "gone"), { emptyList() }, { error("Must not create") }, {}) }.isFailure)
    }
}
