package it.ayngallery.performance

import androidx.benchmark.macro.*
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()
    @Test fun coldStartAndControllerNavigation() = benchmark.measureRepeated(
        packageName = "it.ayngallery",
        metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.UseIfAvailable),
        startupMode = StartupMode.COLD,
        iterations = 5,
        setupBlock = { pressHome() }
    ) {
        startActivityAndWait()
        repeat(6) { device.pressDPadDown(); Thread.sleep(150) }
    }
}
