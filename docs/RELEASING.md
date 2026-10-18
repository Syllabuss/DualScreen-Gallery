# Release signing and publishing

The maintainer’s local signing identity is configured outside Git in `%USERPROFILE%/.android/ds-gallery-signing`. It contains encrypted legacy/release keystores, their v3 certificate lineage, a Windows-user-protected password and private recovery instructions. Never commit or upload that folder. Keep an offline private backup of the whole folder; its recovery instructions allow restoration on another machine. The original development identity is retained only as the predecessor in the signing lineage.

The new RSA-3072 release certificate is valid for 40 years. Rotation targets Android 9 (API 28), the app’s minimum supported version, using the [Android apksigner rotation procedure](https://developer.android.com/tools/apksigner#examples). An upgrade from the original development signature to the release key was tested on Thor without uninstalling or losing preferences. The package ID remains `it.ayngallery`.

## Build locally

Use JDK 17 and Android SDK/build tools 35.0.0:

```powershell
.\build-apk.ps1 -JavaHome 'C:\path\to\jdk-17'
```

On the maintainer’s configured machine this builds the optimized release APK, signs it with the private identity and lineage, verifies the signature and writes checksums in `dist`. `-DebugBuild` produces a debuggable APK signed with that same identity so it can update the installed app. Do not install raw Gradle debug outputs after migration: their default development signature is not the new leaf signing key.

For an already verified local build, sign without repeating the full test suite:

```powershell
.\sign-apk.ps1 -InputApk app/build/outputs/apk/release/app-release-unsigned.apk -OutputApk dist/DSGallery-version.apk -JavaHome 'C:\path\to\jdk-17'
```

The helper retrieves its password through Windows DPAPI, passes it through a temporary environment variable, clears that variable afterward, and verifies the signed APK. The password and keys are never printed by the helper. Independent contributors and CI do not have the private identity; their debug/benchmark APKs are development artifacts and are not compatible maintainer updates.

## Current preparation

The first beta is **0.1.0-beta.123** (code **209**) on `main`. DualScreen-Gallery starts with one source snapshot; the previous repository's development history is not part of this repository. The maintainer confirmed the seven Thor checks below. Publish the signed APK, its SHA-256 and matching LGPL reconstruction kit only after the release commit passes GitHub Actions.

## Thor acceptance

Accepted baseline: **0.1.0-beta.121**, version code **207**, installed in place on **2026-10-18**. All seven checks were confirmed by the maintainer on October 18, 2026 after the beta.121 update.

- [x] Existing roots, preferences and favorites are preserved.
- [x] The launcher offers exactly Icon Player, Compact Player and Photo frame. Replace obsolete Coin/legacy-square instances if still present.
- [x] Compact Player works at 1×1: centered cover, unchanged maximum size when rotating, tap to play/pause. Also check Compact at 3×1 and Icon at 2×2.
- [x] Photo frame can shrink to 1×1; check one photo, a GIF and an album slideshow, including access to configuration.
- [x] Selecting another favorite song updates its preview without interrupting the current song.
- [x] A stationary album hold opens options; a drag reorders without opening them. Two albums retain independent view/size choices.
- [x] TV → Music → close PiP leaves the Music preview intact. Adding a TV favorite is immediate; removal asks for confirmation.

PiP return and lid reopening were already reported working on Thor. A new stress run of those functions is not required for this candidate; record any regression encountered during normal use. Require green CI for the release commit before publishing.

## Before publishing

1. Commit the intended source/version and update release notes and known limitations.
2. Pass tests, lint, translation checks and relevant physical-device checks.
3. Build the actual signed release APK, inspect its version and signature, and install it in place on Thor.
4. Preserve the generated SHA-256 alongside the unchanged APK.
5. Generate `:app:lgplRelinkingKit` from the exact APK sources. Verify a clean rebuild and attach `dist/DSGallery-VERSION-relinking.zip` and its checksum beside every APK; see [relinking instructions](LGPL_RELINKING.md). Publish the requested GitHub release only after final acceptance. Development versions remain prereleases.
6. For future replacements, verify the new release and its assets before retiring the previous public release. The pre-publication beta cleanup described above has already been completed.

CI builds verify source and create reports; they do not contain private signing keys or publish signed releases. Never solve a signature mismatch by uninstalling or clearing user data.
