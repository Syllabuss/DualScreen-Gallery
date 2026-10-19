# Known issues and verification limits

Latest published build: **0.1.0-beta.125**, code **211**, on **main**, dated **2026-10-19**. Public beta with single-screen support.

## Device coverage

DS Gallery was designed around the maintainer’s **AYN Thor**. It also supports single-screen Android devices; recent phone checks used a Samsung SM-S948B running Android 16. Other dual-screen Android devices have not been physically tested. Automated display-policy tests do not replace device testing of launchers, hinge events and controller firmware.

Recent Thor checks covered returning both panels to iiSU, one Gallery card in Recents, software sleep/wake and a physical lid cycle, controller navigation, background music, video replacement, PiP return to Settings, and explicit access to Download/shared storage. These checks do not establish behavior on other firmware or devices. The eight-page tutorial was inspected at 800×600 and 800×480, including its folder recap and controller controls.

The single-screen update was installed in place on Thor on October 19, preserving app data. The maintainer’s regression checks on that build are still pending. Phone checks covered settings list layout, the landscape toggle in both directions, folder selection including Download, converted-GIF swipes and filename display, and the empty-folder setting.

## Open limitations

- **Video PiP and lid reopening:** reported working in the maintainer’s latest Thor checks on October 16, including the previously reported return animation. These are no longer treated as reproduced open blockers; behavior on other devices remains unverified.
- **Single-screen preview after rotation:** one isolated case showed an old animation with a different media title and blocked swipes. It was captured but not reliably reproduced after restarting; further investigation is deferred.
- **GIF memory:** the older heap-exhaustion report was not reproduced in the latest Thor stress test. Forty synthetic 640×360, 48-frame GIFs completed two 90-second phases, with preview changes and category switching. Peak Java use was 135.0 MiB at requested limit four and 218.9 MiB at forty, against a 256 MiB heap. Restored settings reduced it to 103.2 MiB. Peak process PSS was 635.5 MiB. Forty therefore leaves less headroom and is not certified for larger GIFs; the default stays four. No test media or folders remained afterward.
- **Malformed audio:** one supplied MP3 declares about 3:05 in its Xing header but contains about 2:53 of readable audio. Indexed seeking avoids the previous out-of-range error; the player cannot recover missing audio. The declared duration can remain visible until indexing completes.
- **Duplicate finder:** experimental. Review matches before confirming any operation; visual similarity alone is not proof of duplicate file contents.
- **Album locks:** PIN access is an interface safeguard, not file encryption. Other file managers can still access the source files.
- **Online TV:** experimental and disabled by default. User-provided playlists do not guarantee a working or licensed stream; no catalogs are bundled. HTTPS-only HLS, geography, DRM and segment-size limits may exclude sources; long-duration live/network stress tests are not yet certified.
- **Network sharing:** FTP is intended for a trusted local network and does not encrypt transfers. Only the folder explicitly shared is exposed. Android storage and background-execution restrictions still apply.

## Release verification

The [beta.124 release commit passed all four test groups, build, lint and translation checks](https://github.com/Syllabuss/DualScreen-Gallery/actions/runs/39161231202). The suite contains 915 JVM tests. Automated tests cover state and rendering; they do not certify every device, codec or external server.

The signed APK was installed in place on Thor without clearing data. Its four uploaded assets were checked against local SHA-256 digests. The matching reconstruction kit rebuilt successfully from an extracted copy, including a build replacing the jump3r dependency with its Java sources.

The maintainer confirmed widget sizing, controller navigation, album actions, independent album views and TV/Music preview behavior. PiP return, settings preservation and lid reopening were also checked on Thor. Onboarding was exercised from a reset installation.

The single-screen changes passed 922 JVM tests, lint and an optimized release build. The final orientation-toggle adjustment then passed 14 targeted tests, lint and an optimized release build, plus an on-phone on/off check. These local checks are separate from the published beta.124 CI result above.

## Distribution and third-party assets

The release APK disables debugging and enables code/resource shrinking. Private signing material is kept outside Git. Always update compatible installations in place; see [release instructions](RELEASING.md).

Modern UI Vol.1 and Pixabay audio retain their supplier terms and attribution. Runtime cues are part of the application, not a standalone sound pack. Screenshots and showcase media are documentation examples and are not bundled in the APK. See [third-party notices](../THIRD_PARTY_NOTICES.md).
