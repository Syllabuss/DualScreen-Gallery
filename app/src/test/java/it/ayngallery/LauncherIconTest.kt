package it.ayngallery

import android.content.ComponentName
import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class)
class LauncherIconTest {
    @Test fun switchingIconsKeepsExactlyOneLauncherAndRemembersChoice() {
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        val pm = app.packageManager
        assertEquals("LIGHT", LauncherIcon.current(app))
        assertEquals(R.drawable.app_icon_light, app.applicationInfo.icon)
        assertNotNull(pm.getLaunchIntentForPackage(app.packageName))
        listOf("LIGHT", "DARK").forEach { selected ->
            LauncherIcon.select(app, selected)
            assertEquals(selected, LauncherIcon.current(app))
            val launcher = pm.getLaunchIntentForPackage(app.packageName)
            assertNotNull(launcher)
            assertEquals(if (selected == "DARK") R.drawable.app_icon_dark else R.drawable.app_icon_light,
                pm.getActivityInfo(launcher!!.component!!, 0).icon)
            assertEquals("${app.packageName}.Launcher${if (selected == "DARK") "Dark" else "Light"}", launcher!!.component!!.className)
            listOf("LIGHT", "DARK").forEach { key ->
                assertEquals(if (selected == key) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    pm.getComponentEnabledSetting(ComponentName(app, "${app.packageName}.Launcher${if (key == "DARK") "Dark" else "Light"}")))
            }
        }
    }
}
