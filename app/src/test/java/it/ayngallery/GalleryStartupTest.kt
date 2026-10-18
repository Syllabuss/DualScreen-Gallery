package it.ayngallery

import android.content.Intent
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class, qualifiers = "it")
@LooperMode(LooperMode.Mode.PAUSED)
class GalleryStartupTest {
    @Test fun newModelStartsOnChosenCategoryAndFallsBackWhenItIsHidden() {
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        kotlinx.coroutines.runBlocking { app.preferences.update { it.copy(defaultSection = "MUSIC") } }
        assertEquals("MUSIC", GalleryWindowModel(app, SavedStateHandle()).route.value.tab)
        kotlinx.coroutines.runBlocking { app.preferences.update { it.copy(disabledCategories = setOf("MUSIC", "ALBUM")) } }
        assertEquals("PHOTO", GalleryWindowModel(app, SavedStateHandle()).route.value.tab)
    }

    @Before fun reduceMotionForLifecycleChecks() {
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        kotlinx.coroutines.runBlocking { app.preferences.update { it.copy(reducedMotion = true) } }
    }
    @Test fun homeRelocksAlbumsWithoutChangingTheirPersistedProtection() {
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        kotlinx.coroutines.runBlocking {
            assertTrue(app.catalog.protection.configure("1234"))
            app.catalog.protection.observeMarkers(mapOf("private" to true))
            assertTrue(app.catalog.protection.unlock("1234"))
        }
        assertEquals(setOf("private"), app.catalog.protection.state.value.unlocked)
        app.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey"))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(app.catalog.protection.state.value.unlocked.isEmpty())
        assertEquals(setOf("private"), app.catalog.protection.state.value.protected)
    }
    @Test fun companionPanelIsExcludedFromRecentTasks() {
        val app = RuntimeEnvironment.getApplication()
        val info = app.packageManager.getActivityInfo(
            android.content.ComponentName(app, OtherPanelActivity::class.java), 0
        )
        assertTrue(info.flags and android.content.pm.ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
    }

    @Test fun thorMainTaskLaunchesAboveAndTheExcludedCompanionBrowsesBelow() {
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        shadowOf(app).declareComponentUnbindable(android.content.ComponentName(app, MusicService::class.java))
        org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build::class.java, "MODEL", "AYN Thor")
        org.robolectric.shadows.ShadowDisplayManager.changeDisplay(0, "w1920dp-h1080dp-land-mdpi")
        org.robolectric.shadows.ShadowDisplayManager.addDisplay("w1240dp-h1080dp-land-mdpi")
        app.session.refreshDisplays()
        val (lower, upper) = app.session.thorPair!!
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        val mainIntent = shadowOf(launcher).nextStartedActivity
        assertEquals(MainPanelActivity::class.java.name, mainIntent.component!!.className)
        assertEquals(upper.key, mainIntent.getStringExtra("display_key"))
        assertEquals(1, app.session.state.anchorSlot)
        assertTrue(app.session.state.swapped)
        val main = Robolectric.buildActivity(MainPanelActivity::class.java, mainIntent).setup().visible()
        try {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(800))
            val companion = shadowOf(main.get()).nextStartedActivity
            assertEquals(OtherPanelActivity::class.java.name, companion.component!!.className)
            assertEquals(lower.key, companion.getStringExtra("display_key"))
            assertTrue(companion.flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS != 0)
        } finally { main.pause().stop().destroy() }
    }
    @Test fun latestUsageResumeDistinguishesLauncherFromAnotherForegroundApp() {
        assertEquals("com.iisulauncher", latestResumedPackage(listOf(
            ForegroundUsageEvent("it.ayngallery", true),
            ForegroundUsageEvent("it.ayngallery", false),
            ForegroundUsageEvent("com.iisulauncher", true)
        )))
        assertEquals("com.example.game", latestResumedPackage(listOf(
            ForegroundUsageEvent("com.iisulauncher", true),
            ForegroundUsageEvent("com.example.game", true),
            ForegroundUsageEvent("com.example.game", false)
        )))
        assertNull(latestResumedPackage(emptyList()))
    }

    @Test fun delayedCompanionWaitsForLauncherAndHomeCancelsPendingOpen() {
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        shadowOf(app).declareComponentUnbindable(android.content.ComponentName(app, MusicService::class.java))
        org.robolectric.shadows.ShadowDisplayManager.addDisplay("w800dp-h600dp")
        app.session.refreshDisplays()
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        val launches = listOf(shadowOf(launcher).nextStartedActivity, shadowOf(launcher).nextStartedActivity)
        val controller = Robolectric.buildActivity(MainPanelActivity::class.java,
            launches.single { it.component!!.className == MainPanelActivity::class.java.name }).setup().visible()
        val panel = controller.get()
        val other = app.session.screens.single { it.key != panel.screenKey }
        try {
            app.session.deferStartupCompanion(panel.screenKey, other.key, 1)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
            assertNull(shadowOf(panel).nextStartedActivity)
            app.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey"))
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
            assertNull(shadowOf(panel).nextStartedActivity)
            app.session.deferStartupCompanion(panel.screenKey, other.key, 1)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(800))
            val opened = shadowOf(panel).nextStartedActivity
            assertEquals(other.key, opened.getStringExtra("display_key"))
            assertEquals(OtherPanelActivity::class.java.name, opened.component!!.className)
            assertNull(shadowOf(panel).nextStartedActivity)
        } finally { controller.pause().stop().destroy() }
    }
    @org.robolectric.annotation.Implements(android.app.Activity::class)
    class HomeTaskShadow : org.robolectric.shadows.ShadowActivity() {
        var moved = false
        @org.robolectric.annotation.Implementation
        override fun moveTaskToBack(nonRoot: Boolean): Boolean { moved = true; return true }
    }
    @Config(shadows = [HomeTaskShadow::class])
    @Test fun sleepingDuringTutorialDoesNotMoveTheGalleryBehindTheLauncher() {
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        shadowOf(app).declareComponentUnbindable(android.content.ComponentName(app, MusicService::class.java))
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        val controller = Robolectric.buildActivity(MainPanelActivity::class.java, shadowOf(launcher).nextStartedActivity).setup().visible()
        val power = shadowOf(app.getSystemService(android.os.PowerManager::class.java))
        try {
            app.session.tutorialInProgress = true
            power.setIsInteractive(false)
            controller.pause().stop()
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
            assertFalse(org.robolectric.shadow.api.Shadow.extract<HomeTaskShadow>(controller.get()).moved)
            assertTrue(app.session.tutorialInProgress)
            assertFalse(app.preferences.tutorialCompleted.value)
        } finally { power.setIsInteractive(true); controller.destroy() }
    }
    @Config(shadows = [HomeTaskShadow::class])
    @Test fun delayedStopWhileWakingDoesNotBecomeAHomeRequest() {
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        shadowOf(app).declareComponentUnbindable(android.content.ComponentName(app, MusicService::class.java))
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        val panel = Robolectric.buildActivity(MainPanelActivity::class.java, shadowOf(launcher).nextStartedActivity).setup().visible()
        try {
            app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
            shadowOf(Looper.getMainLooper()).idle()
            panel.pause().stop()
            // Power is already interactive, but the app's start callback has not arrived yet.
            shadowOf(app.getSystemService(android.os.PowerManager::class.java)).setIsInteractive(true)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
            val task = org.robolectric.shadow.api.Shadow.extract<HomeTaskShadow>(panel.get())
            assertFalse(task.moved)
            panel.start().resume().visible()
            app.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey"))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(task.moved)
        } finally { panel.destroy() }
    }
    @org.robolectric.annotation.Config(shadows = [HomeTaskShadow::class])
    @Test fun homeDismissesBothPanelsButAppCoverAndRecentsDoNot() {
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        shadowOf(app).declareComponentUnbindable(android.content.ComponentName(app, MusicService::class.java))
        org.robolectric.shadows.ShadowDisplayManager.addDisplay("w800dp-h600dp")
        app.session.refreshDisplays()
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        val launches = listOf(shadowOf(launcher).nextStartedActivity, shadowOf(launcher).nextStartedActivity)
        val intent = launches.single { it.component!!.className == MainPanelActivity::class.java.name }
        val main = Robolectric.buildActivity(MainPanelActivity::class.java, intent).setup().visible()
        val other = Robolectric.buildActivity(OtherPanelActivity::class.java, Intent(intent).apply { removeExtra("ticket") }).setup().visible()
        try {
            main.get().windowModel.go("ALBUM", album = "retained-folder")
            val state = app.session.state
            // Starting a different app stops only the covered panel.
            other.pause().stop()
            assertFalse(org.robolectric.shadow.api.Shadow.extract<HomeTaskShadow>(main.get()).moved)
            for (reason in listOf("recentapps", "assist", "gestureNav", "")) {
                app.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", reason))
                shadowOf(Looper.getMainLooper()).idle()
                assertFalse(org.robolectric.shadow.api.Shadow.extract<HomeTaskShadow>(main.get()).moved)
            }
            app.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey"))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(org.robolectric.shadow.api.Shadow.extract<HomeTaskShadow>(main.get()).moved)
            assertTrue(org.robolectric.shadow.api.Shadow.extract<HomeTaskShadow>(other.get()).moved)
            assertFalse(main.get().isFinishing)
            assertFalse(other.get().isFinishing)
            assertEquals("retained-folder", main.get().windowModel.route.value.album)
            assertEquals(state, app.session.state)
            assertTrue(app.getSharedPreferences("display_safety_v1", 0).getBoolean("pair_resume_pending", false))
            app.session.restorePairAfterRecentReturn(main.get())
            val restored = shadowOf(main.get()).nextStartedActivity
            assertEquals(OtherPanelActivity::class.java.name, restored.component!!.className)
            assertEquals(app.session.screens.single { it.key != main.get().screenKey }.key, restored.getStringExtra("display_key"))
            assertFalse(app.getSharedPreferences("display_safety_v1", 0).getBoolean("pair_resume_pending", true))
            app.session.restorePairAfterRecentReturn(main.get())
            assertNull(shadowOf(main.get()).nextStartedActivity)
        } finally { other.destroy(); main.pause().stop().destroy() }
    }
    @org.robolectric.annotation.Config(shadows = [HomeTaskShadow::class])
    @Test fun iiSuStyleHomeBackgroundsBothOnlyAfterBothPanelsStop() {
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        shadowOf(app).declareComponentUnbindable(android.content.ComponentName(app, MusicService::class.java))
        org.robolectric.shadows.ShadowDisplayManager.addDisplay("w800dp-h600dp")
        app.session.refreshDisplays()
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        val intents = listOf(shadowOf(launcher).nextStartedActivity, shadowOf(launcher).nextStartedActivity)
        val lowerIntent = intents.first { it.component!!.className == MainPanelActivity::class.java.name }
        val lower = Robolectric.buildActivity(MainPanelActivity::class.java, lowerIntent).setup().visible()
        val upper = Robolectric.buildActivity(OtherPanelActivity::class.java, Intent(lowerIntent).apply { removeExtra("ticket") }).setup().visible()
        try {
            val second = app.session.screens.first { it.key != lower.get().screenKey }.key
            org.robolectric.util.ReflectionHelpers.setField(upper.get(), "screenKey", second)
            org.robolectric.util.ReflectionHelpers.getField<MutableSet<String>>(app.session, "allowed").add(second)
            lower.pause().stop() // Another app covers just this display; partner remains foreground.
            shadowOf(Looper.getMainLooper()).idleFor(1, java.util.concurrent.TimeUnit.SECONDS)
            assertFalse(org.robolectric.shadow.api.Shadow.extract<HomeTaskShadow>(lower.get()).moved)
            assertFalse(org.robolectric.shadow.api.Shadow.extract<HomeTaskShadow>(upper.get()).moved)
            upper.pause().stop() // A launcher gesture stops the final panel without the homekey broadcast.
            shadowOf(Looper.getMainLooper()).idleFor(499, java.util.concurrent.TimeUnit.MILLISECONDS)
            assertFalse(org.robolectric.shadow.api.Shadow.extract<HomeTaskShadow>(lower.get()).moved)
            shadowOf(Looper.getMainLooper()).idleFor(1, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(org.robolectric.shadow.api.Shadow.extract<HomeTaskShadow>(lower.get()).moved)
            assertTrue(org.robolectric.shadow.api.Shadow.extract<HomeTaskShadow>(upper.get()).moved)
        } finally { upper.pause().stop().destroy(); lower.pause().stop().destroy() }
    }
    @Test fun pairedSelectionRestoresPreviewAndNavigationClaimsOnlyOneLibrary() {
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        shadowOf(app).declareComponentUnbindable(android.content.ComponentName(app, MusicService::class.java))
        org.robolectric.shadows.ShadowDisplayManager.addDisplay("w800dp-h600dp")
        app.session.refreshDisplays()
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        val launches = listOf(shadowOf(launcher).nextStartedActivity, shadowOf(launcher).nextStartedActivity)
        val mainIntent = launches.single { it.component!!.className == MainPanelActivity::class.java.name }
        val main = Robolectric.buildActivity(MainPanelActivity::class.java, mainIntent).setup().visible()
        // Robolectric creates Activity windows on display 0. Model the already accepted partner's display;
        // real launch placement remains covered separately by ticket tests and Thor checks.
        val other = Robolectric.buildActivity(OtherPanelActivity::class.java, Intent(mainIntent).apply { removeExtra("ticket") }).setup().visible()
        try {
            val second = app.session.screens.first { it.key != main.get().screenKey }.key
            org.robolectric.util.ReflectionHelpers.setField(other.get(), "screenKey", second)
            val allowed = org.robolectric.util.ReflectionHelpers.getField<MutableSet<String>>(app.session, "allowed")
            allowed.add(second)
            assertTrue(app.session.canSwap())
            main.get().windowModel.go("ALBUM", album = "folder")
            other.get().windowModel.go("SETTINGS")
            app.session.selectMedia("photo")
            assertFalse(other.get().windowModel.route.value.forceLibrary)
            assertNull(other.get().windowModel.route.value.viewer)
            assertEquals("folder", main.get().windowModel.route.value.album)
            other.get().windowModel.go("SETTINGS")
            app.session.selectMedia("photo") // Same ID must also restore the preview.
            assertFalse(other.get().windowModel.route.value.forceLibrary)
            other.get().windowModel.go("PHOTO")
            assertTrue(app.session.state.swapped)
            assertFalse(main.get().windowModel.route.value.forceLibrary)
            assertTrue(other.get().windowModel.route.value.forceLibrary)
            main.get().windowModel.go("SETTINGS")
            main.get().onBackPressedDispatcher.onBackPressed()
            assertFalse(main.get().windowModel.route.value.forceLibrary)
            app.session.swap()
            assertFalse(app.session.state.swapped)
            main.get().windowModel.go("ALBUM", album = "folder")
            main.get().windowModel.search("retained")
            val route = main.get().windowModel.route.value
            app.session.openSettings(main.get())
            assertEquals(main.get().slot, app.session.settingsOwner)
            main.pause().stop() // Native picker temporarily covers the controls panel.
            assertFalse(app.session.canSwap())
            assertEquals(main.get().slot, app.session.settingsOwner)
            assertNotNull(app.session.settingsDraft)
            main.start().resume().visible()
            app.session.settingsSection = "DEVICE"
            main.get().windowModel.go("DISPLAY")
            app.session.selectMedia("other-photo")
            assertFalse(app.session.state.swapped)
            assertEquals("DISPLAY", main.get().windowModel.route.value.tab)
            other.get().onBackPressedDispatcher.onBackPressed()
            assertEquals("SETTINGS_DEVICE", main.get().windowModel.route.value.tab)
            other.get().onBackPressedDispatcher.onBackPressed()
            assertEquals("SETTINGS_HUB", main.get().windowModel.route.value.tab)
            other.get().onBackPressedDispatcher.onBackPressed()
            assertNull(app.session.settingsOwner)
            assertEquals(route, main.get().windowModel.route.value)
            assertEquals("retained", main.get().windowModel.query.value)
            assertFalse(app.session.state.swapped)
        } finally { other.pause().stop().destroy(); main.pause().stop().destroy() }
    }
    @Test fun editorsKeepControlsOnTheLowerDisplayWhenLibraryRolesAreSwapped() {
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        shadowOf(app).declareComponentUnbindable(android.content.ComponentName(app, MusicService::class.java))
        org.robolectric.shadows.ShadowDisplayManager.addDisplay("w800dp-h600dp")
        app.session.refreshDisplays()
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        val launches = listOf(shadowOf(launcher).nextStartedActivity, shadowOf(launcher).nextStartedActivity)
        val intent = launches.single { it.component!!.className == MainPanelActivity::class.java.name }
        val upper = Robolectric.buildActivity(MainPanelActivity::class.java, intent).setup().visible()
        val lower = Robolectric.buildActivity(OtherPanelActivity::class.java, Intent(intent).apply { removeExtra("ticket") }).setup().visible()
        try {
            val second = app.session.screens.single { it.key != upper.get().screenKey }.key
            org.robolectric.util.ReflectionHelpers.setField(lower.get(), "screenKey", second)
            org.robolectric.util.ReflectionHelpers.getField<MutableSet<String>>(app.session, "allowed").add(second)
            app.session.swap()
            val target = MediaEntity("video", "video", "content://fixture/video", "folder", "clip.mp4", "video/mp4", "VIDEO", 100, 1)
            app.session.openMediaEditor(upper.get(), ExternalRequest(ExternalAction.OPEN, listOf(ExternalTarget.from(target))).encode())
            assertEquals(lower.get().slot, app.session.mediaEditor!!.ownerSlot)
            assertSame(lower.get(), app.session.controllerTarget(upper.get()))
            val recipe = VideoRecipe(6000, 2125, 4000)
            app.session.updateMediaEditorRecipe(recipe.encode())
            app.session.updateMediaEditorPreview(position = 3000, seek = true)
            assertEquals(recipe, VideoRecipe.decode(app.session.mediaEditor!!.recipe!!))
            assertEquals(3000L, app.session.mediaEditor!!.previewPosition)
            val seek = app.session.mediaEditor!!.seekRevision
            app.session.updateMediaEditorPreview(position = 3100)
            assertEquals("Progress updates must not re-seek the player", seek, app.session.mediaEditor!!.seekRevision)
            app.session.closeMediaEditor(); assertNull(app.session.mediaEditor)
        } finally { lower.pause().stop().destroy(); upper.pause().stop().destroy() }
    }
    @Test fun photoFrameRestoresTheDisplayOrderThatWasActiveBeforeItStarted() {
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        shadowOf(app).declareComponentUnbindable(android.content.ComponentName(app, MusicService::class.java))
        org.robolectric.shadows.ShadowDisplayManager.addDisplay("w800dp-h600dp")
        app.session.refreshDisplays()
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        val intents = listOf(shadowOf(launcher).nextStartedActivity, shadowOf(launcher).nextStartedActivity)
        val upper = Robolectric.buildActivity(MainPanelActivity::class.java,
            intents.single { it.component!!.className == MainPanelActivity::class.java.name }).setup().visible()
        val lower = Robolectric.buildActivity(OtherPanelActivity::class.java,
            intents.single { it.component!!.className == OtherPanelActivity::class.java.name }).setup().visible()
        try {
            val second = app.session.screens.single { it.key != upper.get().screenKey }.key
            org.robolectric.util.ReflectionHelpers.setField(lower.get(), "screenKey", second)
            org.robolectric.util.ReflectionHelpers.getField<MutableSet<String>>(app.session, "allowed").add(second)
            org.robolectric.util.ReflectionHelpers.getField<MutableMap<Int, java.lang.ref.WeakReference<PanelActivity>>>(app.session, "panels")
                .apply { put(0, java.lang.ref.WeakReference(upper.get())); put(1, java.lang.ref.WeakReference(lower.get())) }
            org.robolectric.util.ReflectionHelpers.getField<MutableSet<Int>>(app.session, "started").addAll(setOf(0, 1))
            assertTrue(app.session.canSwap())
            app.session.swap()
            assertTrue(app.session.state.swapped)
            app.session.libraryModel()!!.startFrame()
            assertEquals("FRAME", app.session.libraryModel()!!.route.value.tab)
            assertTrue("PhotoFrame keeps the existing display roles during setup and playback", app.session.state.swapped)
            app.session.libraryModel()!!.go("PHOTO")
            assertEquals("PHOTO", app.session.libraryModel()!!.route.value.tab)
            assertTrue("Leaving PhotoFrame restores the user's original screen arrangement", app.session.state.swapped)
        } finally { lower.pause().stop().destroy(); upper.pause().stop().destroy() }
    }
    @Test fun automaticDualDefaultPreservesExplicitOffAndRejectsCorruptCessionState() {
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        org.robolectric.shadows.ShadowDisplayManager.addDisplay("w800dp-h600dp")
        val prefs = app.getSharedPreferences("display_safety_v1", 0)
        fun other(): String? {
            val session = GallerySession(app)
            return try { session.startupOther(session.screens.first { it.id == 0 }.key) } finally { session.close() }
        }
        assertNotNull(other())
        prefs.edit().putStringSet("startup_pair", emptySet()).commit()
        assertNull(other())
        prefs.edit().remove("startup_pair").putString("ceded", "invalid fixture").commit()
        assertNull(other())
    }
    @After fun release() { (RuntimeEnvironment.getApplication() as GalleryApp).onTerminate() }
    @Test fun playerShortcutDoesNotAskForPhotoPermissionAndAndroidSettingsRemainOnCurrentDisplay() {
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        app.getSharedPreferences("device_gallery_v1", 0).edit().putBoolean("asked", false).commit()
        shadowOf(app).declareComponentUnbindable(android.content.ComponentName(app, MusicService::class.java))
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java, Intent("it.ayngallery.OPEN_PLAYER")).create().get()
        val initial = shadowOf(launcher).nextStartedActivity
        assertEquals(initial.component, shadowOf(launcher).nextStartedActivityForResult.intent.component)
        val controller = Robolectric.buildActivity(MainPanelActivity::class.java, initial).setup().visible()
        assertFalse(app.getSharedPreferences("device_gallery_v1", 0).getBoolean("asked", false))
        assertNull(shadowOf(controller.get()).nextStartedActivityForResult)
        controller.get().devicePermissionSettings()
        val settings = shadowOf(controller.get()).nextStartedActivityForResult
        assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, settings.intent.action)
        assertEquals("package:it.ayngallery", settings.intent.data.toString())
        assertEquals(0, settings.options.getInt("android.activity.launchDisplayId"))
        assertNull(shadowOf(controller.get()).nextStartedActivityForResult)
        controller.pause().stop().destroy()
    }
    @Test fun startupNeverRequestsGlobalVisualPermissionEvenAfterRotation() {
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        app.getSharedPreferences("device_gallery_v1", 0).edit().putBoolean("asked", false).commit()
        shadowOf(app).declareComponentUnbindable(android.content.ComponentName(app, MusicService::class.java))
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        val initial = shadowOf(launcher).nextStartedActivity
        // ShadowActivity keeps ordinary starts in its result queue too; consume the launcher entry.
        assertEquals(initial.component, shadowOf(launcher).nextStartedActivityForResult.intent.component)
        val controller = Robolectric.buildActivity(MainPanelActivity::class.java, initial).setup().visible()
        assertFalse(app.getSharedPreferences("device_gallery_v1", 0).getBoolean("asked", false))
        assertNull(shadowOf(controller.get()).nextStartedActivityForResult)
        controller.recreate()
        assertNull(shadowOf(controller.get()).nextStartedActivityForResult)
        controller.pause().stop().destroy()
    }
    @Test fun ordinaryLauncherOpensExactlyOneAuthorizedPanel() {
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        val shadow = shadowOf(launcher)
        val intent = shadow.nextStartedActivity
        assertNotNull(intent)
        assertEquals(MainPanelActivity::class.java.name, intent.component!!.className)
        assertFalse(intent.getStringExtra("display_key").isNullOrBlank())
        assertFalse(intent.getStringExtra("ticket").isNullOrBlank())
        assertNull(shadow.nextStartedActivity)
        assertTrue(launcher.isFinishing)
    }
    @Test fun panelCreatesComposeContentAndRotationWithoutOpeningSecondTask() {
        // Robolectric does not start a bound MediaSessionService automatically.
        // Mark the service unavailable instead of its synthetic null-binder callback: this test covers UI/lifecycle,
        // not playback or MediaSession connection behavior.
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).declareComponentUnbindable(android.content.ComponentName(app, MusicService::class.java))
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        val intent = shadowOf(launcher).nextStartedActivity
        val controller = Robolectric.buildActivity(MainPanelActivity::class.java, intent).setup().visible()
        val panel = controller.get()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(panel.isFinishing)
        assertNotNull(panel.findViewById<android.view.View>(android.R.id.content))
        assertNull(shadowOf(panel).nextStartedActivity)
        controller.recreate()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(controller.get().isFinishing)
        assertNull(shadowOf(controller.get()).nextStartedActivity)
        controller.pause().stop().destroy()
    }
    @Test fun authorizedRelaunchRebindsExistingPanelToCurrentDisplayDescriptor() {
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        shadowOf(app).declareComponentUnbindable(android.content.ComponentName(app, MusicService::class.java))
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        val controller = Robolectric.buildActivity(MainPanelActivity::class.java, shadowOf(launcher).nextStartedActivity).setup().visible()
        val panel = controller.get()
        val before = panel.screenKey
        controller.pause().stop()
        // Model a surviving singleTask instance with a changed display binding. The separate
        // display-change listener deliberately closes uncertain bindings, so suppress it here.
        app.getSystemService(android.hardware.display.DisplayManager::class.java).unregisterDisplayListener(app.session)
        org.robolectric.shadows.ShadowDisplayManager.changeDisplay(0, "w900dp-h600dp")
        app.session.refreshDisplays()
        val current = app.session.current(panel)!!
        assertNotEquals(before, current.key)
        assertTrue(app.session.launch(launcher, current, panel.slot))
        controller.newIntent(shadowOf(launcher).nextStartedActivity)
        assertEquals(current.key, panel.screenKey)
        controller.start().resume().visible()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(app.session.events.joinToString("\n"), panel.isFinishing)
        assertTrue(panel.isStartedFlow.value)
        assertNull(shadowOf(panel).nextStartedActivity)
        val report = app.session.report(panel)
        assertTrue(report.contains("stato valido=true"))
        assertTrue(report.contains("READ_EXTERNAL_STORAGE:"))
        assertTrue(report.contains("Galleria Android:"))
        assertTrue(report.contains("Scansione: in corso="))
        controller.pause().stop().destroy()
    }
    @Test fun unexpectedDisplayRelaunchIsRejectedAndDoesNotRestartPanel() {
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        shadowOf(app).declareComponentUnbindable(android.content.ComponentName(app, MusicService::class.java))
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        val controller = Robolectric.buildActivity(MainPanelActivity::class.java, shadowOf(launcher).nextStartedActivity).setup().visible()
        val panel = controller.get()
        val bound = panel.screenKey
        controller.pause().stop()
        controller.newIntent(Intent(panel, MainPanelActivity::class.java).putExtra("display_key", "unexpected-display").putExtra("ticket", "invalid"))
        assertTrue(panel.isFinishing)
        assertEquals(bound, panel.screenKey)
        assertTrue(app.session.error!!.contains("display richiesto=unexpected-display"))
        controller.start()
        assertFalse(panel.isStartedFlow.value)
        assertNull(shadowOf(panel).nextStartedActivity)
        controller.stop().destroy()
    }
    @Test fun swappingLibraryKeepsFolderGridSearchAndBackHistory() {
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        val lower = GalleryWindowModel(app, SavedStateHandle())
        val upper = GalleryWindowModel(app, SavedStateHandle())
        lower.go("ALBUM", album = "stable-folder"); lower.search("fixture")
        lower.albumGrid(true); lower.albumColumns(6); lower.selectAlbums(setOf("other-folder"))
        upper.takeLibraryFrom(lower)
        assertEquals(lower.route.value.copy(viewer = null, forceLibrary = false), upper.route.value)
        assertEquals("fixture", upper.query.value)
        assertTrue(upper.albumGrid.value); assertEquals(6, upper.albumColumns.value)
        assertEquals(lower.selectedAlbums.value, upper.selectedAlbums.value)
        assertTrue(upper.back()); assertTrue(upper.selectedAlbums.value.isEmpty())
        assertTrue(upper.back()); assertEquals("ALBUM", upper.route.value.tab); assertNull(upper.route.value.album)
    }
    @Test fun galleryViewsPersistAcrossFreshStoresAndModels() {
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        val model = GalleryWindowModel(app, SavedStateHandle())
        model.albumGrid(true); model.albumColumns(6)
        model.go("PHOTO"); model.mediaGrid(false); model.mediaColumns(5)
        model.go("ALBUM", album = "pictures"); model.mediaGrid(true); model.mediaColumns(2)
        model.albumAnchor = "pictures"
        model.go("VIDEO"); assertTrue(model.mediaGrid("VIDEO").value); assertEquals(4, model.mediaColumns("VIDEO").value)
        assertNull(model.albumAnchor); model.albumAnchor = "movies"
        model.go("ALBUM"); assertEquals("pictures", model.albumAnchor)
        model.go("VIDEO"); assertEquals("movies", model.albumAnchor)
        model.mediaGrid(false); model.mediaColumns(4)
        model.go("MUSIC"); assertFalse(model.mediaGrid("MUSIC").value); assertEquals(4, model.mediaColumns("MUSIC").value)
        model.mediaGrid(false); model.mediaColumns(7)
        val fresh = PreferenceStore(app)
        assertTrue(fresh.albumGrid.value); assertEquals(6, fresh.albumColumns.value)
        assertFalse(fresh.mediaGrid("PHOTO").value); assertEquals(5, fresh.mediaColumns("PHOTO").value)
        assertTrue(fresh.mediaGrid("ALBUM", "pictures").value); assertEquals(2, fresh.mediaColumns("ALBUM", "pictures").value)
        assertFalse(fresh.mediaGrid("VIDEO").value); assertEquals(4, fresh.mediaColumns("VIDEO").value)
        assertFalse(fresh.mediaGrid("MUSIC").value); assertEquals(7, fresh.mediaColumns("MUSIC").value)
        val restarted = GalleryWindowModel(app, SavedStateHandle())
        assertEquals(6, restarted.albumColumns.value)
        assertFalse(restarted.mediaGrid("PHOTO").value)
        assertFalse(restarted.mediaGrid("VIDEO").value)
        assertFalse(restarted.mediaGrid("MUSIC").value)
    }
    @Test fun albumsAreTheDefaultAndColumnCountIsBoundedAcrossRecreation() {
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        val saved = SavedStateHandle()
        val model = GalleryWindowModel(app, saved)
        assertEquals("ALBUM", model.route.value.tab)
        assertEquals(3, model.albumColumns.value)
        model.albumColumns(9); assertEquals(8, model.albumColumns.value)
        model.albumColumns(1); assertEquals(2, model.albumColumns.value)
        model.albumGrid(true); model.selectAlbums(setOf("stable-folder"))
        assertTrue(model.back()); assertTrue(model.selectedAlbums.value.isEmpty())
        assertEquals(2, GalleryWindowModel(app, saved).albumColumns.value)
        assertTrue(GalleryWindowModel(app, saved).albumGrid.value)
    }
    @Test fun restoringPanelRoleClearsAnOldViewerAndSettingsWithoutLosingAlbumSelection() {
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        val model = GalleryWindowModel(app, SavedStateHandle())
        model.go("ALBUM", album = "stable-folder")
        model.open("selected-image")
        model.restorePanelRole()
        assertEquals("stable-folder", model.route.value.album)
        assertNull(model.route.value.viewer)
        assertFalse(model.route.value.forceLibrary)
        model.go("DISPLAY")
        model.restorePanelRole()
        assertEquals("PHOTO", model.route.value.tab)
        assertFalse(model.route.value.forceLibrary)
    }
    @Test fun returningFromPlayerRestoresOriginalFolderAndSearch() {
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        val saved = SavedStateHandle()
        val model = GalleryWindowModel(app, saved)
        model.go("ALBUM", album = "stable-folder")
        model.search("my query")
        model.go("PLAYER")
        assertTrue(model.back())
        assertEquals("stable-folder", model.route.value.album)
        assertEquals("my query", model.query.value)
        val recreated = GalleryWindowModel(app, saved)
        assertEquals(model.route.value, recreated.route.value)
        assertTrue(recreated.back())
        assertEquals("ALBUM", recreated.route.value.tab)
    }
}
