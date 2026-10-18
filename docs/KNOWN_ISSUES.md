# Known issues and verification limits

Current application build: **0.1.0-beta.124**, code **210**, on **main**, dated **2026-10-18**. This is the first beta snapshot in DualScreen-Gallery; the previous development history is not included.

## Device coverage

DS Gallery is developed and tested on **AYN Thor**. Other dual-screen Android devices have not been physically tested. Single-screen mode is experimental. Automated display-policy tests do not replace device testing of launchers, hinge events and controller firmware.

Recent Thor checks covered returning both panels to iiSU, one Gallery card in Recents, software sleep/wake and a physical lid cycle, controller navigation, background music, video replacement, PiP return to Settings, and explicit access to Download/shared storage. These checks do not establish behavior on other firmware or devices. The eight-page tutorial was inspected at 800×600 and 800×480, including its folder recap and controller controls.

## Open limitations

- **Video PiP and lid reopening:** reported working in the maintainer’s latest Thor checks on October 16, including the previously reported return animation. These are no longer treated as reproduced open blockers; behavior on other devices remains unverified.
- **GIF memory:** the older heap-exhaustion report was not reproduced in the latest Thor stress test. Forty synthetic 640×360, 48-frame GIFs completed two 90-second phases, with preview changes and category switching. Peak Java use was 135.0 MiB at requested limit four and 218.9 MiB at forty, against a 256 MiB heap. Restored settings reduced it to 103.2 MiB. Peak process PSS was 635.5 MiB. Forty therefore leaves less headroom and is not certified for larger GIFs; the default stays four. No test media or folders remained afterward.
- **Malformed audio:** one supplied MP3 declares about 3:05 in its Xing header but contains about 2:53 of readable audio. Indexed seeking avoids the previous out-of-range error; the player cannot recover missing audio. The declared duration can remain visible until indexing completes.
- **Duplicate finder:** experimental. Review matches before confirming any operation; visual similarity alone is not proof of duplicate file contents.
- **Album locks:** PIN access is an interface safeguard, not file encryption. Other file managers can still access the source files.
- **Online TV:** experimental and disabled by default. User-provided playlists do not guarantee a working or licensed stream; no catalogs are bundled. HTTPS-only HLS, geography, DRM and segment-size limits may exclude sources; long-duration live/network stress tests are not yet certified.
- **Network sharing:** FTP is intended for a trusted local network and does not encrypt transfers. Only the folder explicitly shared is exposed. Android storage and background-execution restrictions still apply.

## Current verification

Beta.124 corrects music previews: selecting a different song while playing keeps the current track visible until activation; previous/next follows the new track, including buffering, and paused browsing follows selection. All 915 JVM tests, lint and the optimized build passed locally before the release-version update. The final commit must also pass GitHub Actions before publication.

The optimized beta.122 APK and matching LGPL reconstruction kit build locally; the APK is signed with the maintainer's dedicated identity. Beta.122 was installed in place on Thor on October 18 without clearing data. Favorites → TV was checked with touch and L1 while video continued in PiP; TV remained selected. The maintainer confirmed all seven widget/preview acceptance checks on October 18; see [release checks](RELEASING.md#thor-acceptance). Beta.122 adds the local-video current-frame cover shortcut, translated into every supported language. A full 913-test local run passed 912 checks; after synchronizing the PIN-change confirmation step, that test and ten related import, localization and cover checks passed together.

The Favorites → TV regression is covered by navigation tests: restoring a panel preserves TV, and controller handlers refresh when TV visibility changes. The beta.121 candidate adds these fixes; all 12 targeted navigation and player checks passed locally.

Current automated results are available in [GitHub Actions](https://github.com/Syllabuss/DualScreen-Gallery/actions). All test groups, lint and the optimized build must pass for the release commit.

The editor rendering regression was rerun twice with Compose-managed asynchronous waits; both local runs passed. This runner-only synchronization change does not alter the installed beta.120 APK.

The public showcase now contains real dual-screen album, music, photo/GIF, favorite and video/TV captures, an example alongside a separate game, the widget GIF and a YouTube demonstration. Documentation assets are not bundled in the APK.

## Earlier verification

Beta.115 passed all 901 JVM tests and debug lint with zero errors. Mixed favorites
now share explicit controller focus acquisition, size-aware GIF budgeting, compact
list sizing and sort reset behavior. TV stars have independent addition dates;
manual channel saves no longer mark them as favorites. SMB publication hash reads
check cancellation between chunks. Complete CI verification is split into four
balanced test jobs plus an optimized build/lint job; the final verify gate requires
every job to pass. No GitHub release was published.


Beta.110 was installed on the Thor without clearing data. Both first-use TV notices,
the empty personal-source browser and the upper-screen playlist dialog were checked;
TV was returned to its previous disabled setting. Personal-source persistence, M3U
parsing and cross-source search are covered by offline fixtures, not a claim that
every external provider works. The full JVM run completed 894 tests: 891 passed;
three outdated theme/catalog/revision expectations were corrected and their focused
reruns passed. Lint reports zero errors. The optimized release APK builds, and a
separate extracted LGPL kit rebuilt successfully using jump3r Java sources instead
of its dependency JAR. No GitHub release was published.

Beta.95 was installed on Thor and exercised as an optimized, signed release: choosing Download, starting the FTP listener, authenticating from a separate PC over LAN, listing the shared root, and uploading/downloading a new 41-byte text fixture all succeeded; round-trip bytes matched. The release keeps MINA’s reflectively constructed NioProcessor constructor. Five shared-storage/upload-limit tests and lint passed before this packaging-only fix.

Beta.93 passed 25 onboarding/gesture/cache tests, followed by a successful rerun of the nine gesture tests including migration of stock directions and preservation of custom mappings. Lint and the optimized release build passed; the signed update was installed on Thor without resetting preferences or onboarding.

Beta.92 layout/theme changes passed all 153 TouchRenderTest and TvIntegrationTest checks, debug lint and the optimized release build. The signed update was installed on Thor in place, preserving user data. Physical review of the revised controls remains separate from these automated checks.

Beta.91 adds bounded FTP staging (8 GiB per file, with 128 MiB internal headroom), MINA 2.2.9, an explicit first-use TV acknowledgement, a 20-second TV startup deadline, and favorite feedback shared by hearts and stars. The full suite executed 890 tests; 889 passed initially and the new deadline test required enabling TV in its fixture. Its corrected focused rerun passed, alongside the acknowledgement test. The final 53 FTP/file-operation tests passed, as did lint and the optimized release build. A clean extraction rebuilt successfully with the included jump3r Java sources replacing its JAR. Beta.91 was installed on Thor without clearing user data. Distribution now requires the matching LGPL reconstruction kit beside every APK.

During beta.90 integration, the complete 856-test suite passed. The final 19 targeted TV checks cover controller navigation, R3 view changes, persistent reordering, decoding and saving custom artwork, preserving drafts after Android picker results, and confirmation before replacing a local video. Thor checks covered live playback/overlays, channel-switch gating, Settings access, thumbnail import/save and long-press reorder. Original preferences, channel order and covers were restored afterward.

Beta.89 TV navigation/search changes passed 13 targeted tests, including default category placement, saved-channel stepping and keyboard-mode restoration. The compact browser and category placement were inspected on Thor. Existing device preferences and saved channels were preserved.

The IPTV integration passed the complete 850-test JVM suite. After the final preview/PiP correction, 11 focused tests also passed; debug/release builds and lint completed with zero errors. The signed optimized beta.88 APK was installed on Thor without clearing data. Catalog browsing, live HLS, Home PiP and return to the preview were physically checked. User preferences were restored and test channel records removed. This includes isolated channel storage, legacy preference migration, validated DNS/redirect policies, bounded M3U parsing and live-only command restrictions. Source availability, geography and broadcaster restrictions remain outside the app’s control.

Beta.86 fixes controller confirmation on video folders. Folder opening by touch and controller passed targeted regression tests; the debug APK was installed on Thor without clearing data.

Beta.85 was installed on Thor without clearing data. The full suite executed 837 tests: 836 passed, and one album-toolbar test needed to wait for the asynchronous listing before inspecting its button. After correcting that test wait, all five targeted settings/defaults/toolbar checks passed. Lint reports zero errors; debug and optimized APKs build successfully. This does not claim to repeat all physical media and hinge stress tests on this version.

## Build and release

All 2078 text keys are translated across the supported languages. Automated checks cover catalog visibility, document-provider isolation, non-destructive file operations, preference persistence, controller routing, GIF handling, onboarding and media playback state.

The release APK disables debugging and enables resource/code shrinking. It uses a dedicated private signing key with a v3 lineage from the original development certificate, targeting key rotation from Android 9 onward. An in-place Thor upgrade succeeded and preserved preferences. Signing material and its private recovery instructions live outside the repository. The old beta.66 GitHub release was removed on October 18 at the maintainer's request. Public packaging, tag and release publication remain pending until after the merge to `main`; no replacement release has been published.

## Publication checks

- Online TV now contains no built-in source directory. Personal import/search are
  fixture-tested and the first-use dialogs were checked on Thor; provider-specific
  and long-duration streaming behavior remain beta verification limits.
- SMB enumeration is verified on the Thor after the optimized event-bus constructor
  fix. The user subsequently reported a successful transfer. Byte-for-byte verification
  is pending identification of its destination; no repeated transfer is required
  merely to reproduce the earlier generic error. Network operations remain beta.
- Modern UI Vol.1 explicitly permits use in applications and creative projects.
  Its supplied terms prohibit redistributing the original library as a standalone
  sound pack. The repository contains the converted runtime cues used by the app,
  not the original pack; preserve the supplier terms and attribution. Any separate
  sound-pack redistribution needs its own review. Audio assets are not GPL code.
- Pixabay audio retains its own content license and provenance. Do not offer the
  source sound as a standalone download or represent it as GPL-licensed audio.
