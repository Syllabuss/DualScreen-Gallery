# Rebuilding with a modified MP3 library

Every distributed APK must be accompanied, at the same download location, by the
`DSGallery-VERSION-relinking.zip` produced from the exact sources used to build that APK.
Do not rely on access to a private GitHub repository or only a source URL. The kit
contains the complete application build sources, Gradle wrapper, first-party terms,
third-party notices and the unmodified jump3r 1.0.5 source archive. It deliberately
contains no private key, password, local.properties, user media or device logs.

First-party source is GPL-3.0-only; third-party components retain their licenses.
Recipients can modify and rebuild the application and replace the LGPL MP3
library. The maintainer signing key is neither included nor required.

## Recipient instructions

1. Extract the kit. Install JDK 17 and Android SDK 35. Set `ANDROID_HOME` or create
   your own `local.properties` containing `sdk.dir=...`.
2. Run `./gradlew :app:assembleDebug` (`gradlew.bat` on Windows). The wrapper and
   normal build dependencies are downloaded from their configured repositories.
   The default debug key belongs to your machine; maintainer signing is unnecessary.
3. To change jump3r, extract `third_party/jump3r/jump3r-1.0.5-sources.jar`. Place its
   Java sources in `app/src/main/java`, retaining package names. Omit
   `de/sciss/jump3r/lowlevel/LameEncoder.java`, the unused desktop Java Sound adapter
   (Android has no `javax.sound` API); the complete original source remains in the
   archive, and the core `mp3`/`mpg` sources used by this application are rebuilt. In
   `app/build.gradle.kts`, remove the runtime line
   `implementation("de.sciss:jump3r:1.0.5")` so the modified source replaces the
   dependency. Keep the library's license and notices.
4. Run `./gradlew :app:assembleDebug` again. This recompiles the combined application
   against your modified library, rather than attempting to patch optimized DEX.
   Alternatively replace that runtime dependency with your own compiled library JAR.
5. Your APK is in `app/build/outputs/apk/debug`. Its signing key differs from the
   maintainer's key: test it on a separate Android user/device, or first back up
   your preferences and media before choosing to uninstall an existing installation.

For distribution by the maintainer, run `:app:lgplRelinkingKit`, publish the kit next
to the matching APK, and retain both files and their checksums together. If jump3r
is changed, include the actual changed source and update its notices before shipping.
The kit is an application reconstruction distribution, not a standalone audio pack.
