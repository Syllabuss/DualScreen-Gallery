package it.ayngallery.performance

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val profile = BaselineProfileRule()
    @Test fun startupAndNavigation() = profile.collect(
        packageName = "it.ayngallery", includeInStartupProfile = true
    ) {
        pressHome()
        startActivityAndWait()
        repeat(6) { device.pressDPadDown(); Thread.sleep(150) }
    }
}
