package it.ayngallery

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.SavedStateHandle
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class, qualifiers = "it")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class LibraryBackupRenderTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp
    @After fun close() { app.onTerminate() }
    @Test fun removedBackupCannotBeOpenedFromOldSavedNavigation() {
        val restored = GalleryWindowModel(app, SavedStateHandle(mapOf("tab" to "BACKUP")))
        assertEquals("ALBUM", restored.route.value.tab)
        restored.go("BACKUP")
        assertEquals("SETTINGS", restored.route.value.tab)
    }
    @Test fun settingsHaveNoLibraryBackupAction() {
        Shadows.shadowOf(app).declareComponentUnbindable(android.content.ComponentName(app, MusicService::class.java))
        try {
            val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
            val intent = Shadows.shadowOf(launcher).nextStartedActivity
            val panel = Robolectric.buildActivity(MainPanelActivity::class.java, intent)
            try {
                panel.setup().visible(); panel.get().windowModel.go("SETTINGS")
                compose.waitForIdle()
                compose.waitUntil(5000) {
                    Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(25))
                    compose.onAllNodesWithTag("settings-list").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("settings-list").assertExists()
                compose.onNodeWithTag("settings-library-backup").assertDoesNotExist()
                compose.onNodeWithTag("export-library").assertDoesNotExist()
            } finally { panel.pause().stop().destroy() }
        } finally { /* Application resources are released by the fixture. */ }
    }
}
