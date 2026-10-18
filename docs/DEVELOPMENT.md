# Development

## Setup

Requirements: JDK 17; Android SDK platform 35 and build tools 35.0.0; network access for the initial Gradle/Maven downloads. The wrapper pins Gradle 8.13 with its distribution checksum. Android Studio is optional. Minimum device API is 28.

Clone the repository; if access is restricted, use your authorized GitHub account. Set `ANDROID_HOME`, or create an ignored `local.properties` containing `sdk.dir=/path/to/android/sdk` (use forward slashes for Windows paths). Set `JAVA_HOME` to JDK 17.

```sh
bash ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Windows: `./gradlew.bat` runs the same tasks, or use `build-apk.ps1`. Successful builds produce `app/build/outputs/apk/debug/app-debug.apk`. The Windows script defaults to an optimized maintainer-signed release build when the local signing setup is available; `-DebugBuild` selects a debuggable build. Follow [release signing](RELEASING.md) for exact signing and artifact rules.

Debug builds made on different machines normally have different signing certificates. They cannot replace each other's installed APKs. Use the maintainer's prerelease for compatible updates; do not uninstall or clear user data to work around a certificate mismatch.

## Project map

| Location | Responsibility |
| --- | --- |
| `app/src/main/java/it/ayngallery` | Kotlin app, Compose views, display lifecycle, catalog, players, editors and widgets |
| `app/src/main/res` | Strings, icons, themes, widget layouts and runtime audio |
| `app/src/test/java/it/ayngallery` | JVM/Robolectric regression and native rendering tests |
| `app/src/test/resources/media` | Synthetic audio/video fixtures available in a fresh clone |
| `localization` and `scripts/localization.py` | Translation coverage and source revision checks |
| `docs` | Maintained product and contributor documentation |
| `licenses`, `assets/audio` | Third-party notices and audio provenance |

Room stores stable media/source identifiers, membership and file-operation state. Folder access uses Android document grants. Gallery preferences keep each section's layout independently; individual album/video-folder media views have their own grid/list and density choices. The music player is shared between panels, notifications and widgets. Editing exports copies. File operations journal progress and verify copies before source deletion.

The network explorer uses signed SMB2/3 shares and the existing operation journal for verified transfers. Live-server interoperability and the remaining limitations are described in [known issues](KNOWN_ISSUES.md).

## Checks

Run the full command before changing shared catalog, display, player or file-operation behavior. Focused tests can speed up iteration, for example:

```sh
bash ./gradlew :app:testDebugUnitTest --tests it.ayngallery.TouchRenderTest
python scripts/localization.py
```

All required media fixtures are tracked. Output in `qa`, `dist` and build directories is disposable local evidence and stays ignored. Resource tests do not require Python; the translation maintenance script does.

GitHub Actions runs the same verification from a clean checkout on Ubuntu, with read-only repository permissions. It does not install on devices, create releases, upload APKs or access signing keys. It retains verification reports for seven days; their visibility follows repository access. Every push and manual dispatch runs all tests across four balanced class shards, independently from lint and the optimized build. The final verify check requires all five jobs to pass. For a local shard use `./gradlew :app:testDebugUnitTest -PtestShard=0/4` (indices 0 through 3 cover the complete suite once). Remaining verification limits are listed in [known issues](KNOWN_ISSUES.md).

## Documentation screenshots

```sh
bash ./gradlew :app:testDebugUnitTest --tests it.ayngallery.DocumentationScreenshotTest
```

This captures actual Compose screens with original illustrated fixtures and invented media metadata. Files are written to `qa/rendered/documentation-*.png` and remain ignored until deliberately selected for documentation. These fixtures are useful for repeatable layout review; they are not the current public showcase.

The README uses maintainer-supplied Thor captures in `assets/showcase`, preserving both screens and the original media files. The asset inventory and YouTube link are in [showcase notes](../assets/showcase/README.md). When changing documentation, verify local links, image loading and desktop/mobile layout; do not add raw debug captures, private device identifiers, credentials or original media files to Git.

## Physical Thor checks

Use your own Thor with USB debugging explicitly enabled and authorized. Record installed version, display state and logs before updating. Install an update in place only after checking the certificate. Keep data, permissions and selected roots intact.

Verify lower browsing/upper preview and swapped roles; A/B/X and stick navigation after touching preview; Home/Recents transitions on both displays; game overlay coexistence; SD-card disconnect/reconnect; GIF looping; file-provider copies; editor previews and widget sizing. Include launcher/version in results. A passing runner does not establish a hardware result.

Optimized local builds, physical performance measurements and Baseline Profile generation are described in [Performance verification](PERFORMANCE.md).


## Physical GIF memory stress test

The opt-in `GifStressInstrumentation` runner uses linked GIFs, or temporarily creates forty synthetic GIFs in a writable linked folder if none exist. It exercises the real gallery at limits four and forty, samples Java/native/PSS memory, removes its own fixtures and restores preferences. Keep the device open and idle; preserve a preference backup before an unattended stress run. It never modifies existing media. The test APK is separate from the application.

```sh
./gradlew :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e seconds 90 it.ayngallery.test/it.ayngallery.GifStressInstrumentation
```

After signing-key migration, sign the test APK with `sign-apk.ps1` before installing it, just like the target debug APK. Results are printed by instrumentation and stored in the target app’s private `files/gif-stress-result.json`. A clean run tests a bounded workload, not every possible GIF or long-term memory behavior.
