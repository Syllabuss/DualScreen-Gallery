# Performance verification

DS Gallery has three relevant build variants:

- `debug`: development and functional rendering checks.
- `benchmark`: non-debuggable R8/resource-shrunk app, signed with the local development key so existing development installations can be updated without losing data.
- `profile`: non-debuggable, unminified target for collecting readable Baseline Profile rules.

`release` is optimized but does not contain a repository signing credential. Use a stable private production key before distributing a production release. Never uninstall or erase user data to bypass a certificate mismatch.

`build-apk.ps1` produces the optimized local APK by default; use `-DebugBuild` for a development APK. CI checks both debug and optimized app assembly. Macrobenchmark dependencies belong only to the separate `performance` test APK.

## Physical-device runs

Use a free, unlocked Thor with USB debugging authorized. Performance runs cold-start/stop the app, so pause user playback first. Preserve the installed app's certificate and document grants. Do not use uninstall, clear-data, factory reset or synthetic fixtures in the user's media folders.

Build the targets and instrumentation APKs:

```sh
./gradlew :app:assembleBenchmark :app:assembleProfile :performance:assembleBenchmark :performance:assembleProfile
```

Measure an optimized target:

```sh
./gradlew :performance:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=it.ayngallery.performance.StartupBenchmark
```

Collect a readable profile using the unminified target (Android 13+ recommended):

```sh
./gradlew :performance:connectedProfileAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=it.ayngallery.performance.BaselineProfileGenerator
```

The tests generate local benchmark reports, traces and profile text in Gradle's output folders. Review the collected profile, place the generated baseline rules at `app/src/main/baseline-prof.txt` and rebuild the optimized target. Compare before/after; do not commit an invented profile or a trace containing user information. The app already receives library profiles, but an app-specific profile must be generated from real runs.

The generator covers startup and controller navigation. Use an already configured test library for meaningful media-grid measurements; a first-run tutorial is a different workload and must be reported separately. Neither command is run automatically by CI without a device.

## Workloads

Record cold/warm launch, frame-time distribution, dropped frames, CPU, allocation rate and peak process memory. Repeat with:

- 1,000 and 10,000 media, internal storage and SD, warm/cold thumbnail cache.
- Grid/list, sorting, album density changes, two displays and one covered display.
- 4, 12 and up to 40 visible GIFs of different dimensions, frame seeking and pause.
- 0, 1 and 4 music widgets, including a compact 1×1 widget; Home, gallery, game and screen off.
- A large copy/export/trash operation while browsing and toggling a favorite on another file.
- SMB transfer while navigating, cancelling and disconnecting; interrupted recovery.

GIF animation has both the user's count cap and a per-panel rendering-buffer estimate. It is a guard, not a guarantee about all native decoder allocations; real memory peaks still require device profiling.

Bars use smaller bitmaps, quantized change detection and at most ten image updates per second. Pulse remains a lightweight scale update. Host effect updates are suspended while both gallery panels cover the Home; Android does not provide reliable visibility for individual launcher widgets, so behavior while a different app covers the Home still needs device measurement. No launcher polling has been added.

Cache accounting covers only app-owned derived artwork. Recovery, explicit cache clearing and failed atomic writes invalidate it. Frame-selection previews use a separate bounded 4 MiB cache; chosen video covers remain in the normal artwork cache.

## Review validation

Functional tests check latest favorite preservation during an in-flight move, independent catalog access, compact-widget transport, GIF animation limits and warm cache accounting. These do not certify a frame rate or battery improvement. Generated APKs and signed credentials stay outside source control.
