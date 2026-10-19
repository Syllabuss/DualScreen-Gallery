# Architectural cleanup — October 19, 2026

This pass improves the existing application without changing its UI, persistent formats or feature set. It starts from `8a437ab`, after the first public beta, on the separate `codex/architectural-cleanup` branch. The beta.124 release is not replaced by this work.

## Baseline and priorities

The review followed startup, display ownership, controller callbacks, catalog access, playback, file journaling and network publication through the eight requested files and their callers. The initial codebase had 160 production Kotlin files and 915 JVM tests. Large files were a symptom, not a reason to split every component.

The [baseline CI run](https://github.com/Syllabuss/DualScreen-Gallery/actions/runs/39165317752) passed build/lint and three test groups. `CollectionRenderTest` failed while disposing a Compose dialog: `LifecycleRegistry.removeObserver` ran on Room's transaction executor. The stack passes through the Compose test frame clock and its unconfined continuation interceptor, rather than an application call to `removeObserver`.

Work was ordered as follows:

1. Make the collection test's scheduling deterministic without bypassing lifecycle thread checks.
2. Separate existing responsibilities where their ownership is already clear.
3. Make the file journal's phases and controller routing easier to follow.
4. Consolidate real playback/publication duplication and clarify resource ownership.
5. Run focused checks, the complete suite, localization, lint and an optimized build; verify the branch in CI before proposing integration.

## Implemented changes

| Area | Before | After and preserved contract |
| --- | --- | --- |
| Collection rendering test | The default test dispatcher could resume dialog disposal on a Room callback thread; activity cleanup only happened on success. | An explicit `StandardTestDispatcher` queues composition work on the rule's scheduler. The test waits for the asynchronous collection page and destroys its activity in `finally`. Both orientations retain the complete create/rename/cover/remove checks and original-file assertions. |
| Application startup | `GalleryApp.kt` mixed dependency initialization with the entire display/navigation session. | The existing `GallerySession` and its session types live together in `GallerySession.kt`. Class names, initialization order, saved keys and lifecycle behavior are unchanged. |
| Library and settings | `GallerySections.kt` combined source/album screens, media actions, theme editing and playback settings. | The existing preference screen and its private editors live in `PreferencesScreen.kt`. Album/media actions stay together. The forwarding-only `PreferenceSwitch` wrapper is removed in favor of the existing `GallerySettingSwitch`. |
| File journal | `execute` embedded creation/copy/hash verification, publication and source deletion in one 228-line coordinator. | `execute` is 146 lines and delegates to three private phases: `stageVerifiedCopy`, `publishVerifiedCopy`, `deleteVerifiedOriginal`. The execution mutex, dispatch/recovery branches and failure handling remain in the coordinator. No phase names or persisted fields change. |
| Controller focus | Nested branches repeated the action-to-direction mapping and obscured priority between settings, library and album navigation. | Guard returns preserve the original priority, with one direction mapping and explicit sound/footer handling. `PanelActivity` computes its repeated network-browser condition once. |
| Preview transitions | A long expression hid the priority of TV, settings, frame, video, comparison, player and image scenes. | An ordered `when` retains the same transition keys and priority, with explicit scene fields. No animation or control layout changes. |
| Music state | Coordinator and service constructed the same long positional snapshot separately. | A private `Player.playbackSnapshot` uses named fields. The coordinator still passes its cached queue and distinguishes buffering from pause; widget snapshots retain their previous defaults and error text. |
| GIF ownership | The viewer mixed decoder setup, bookmark restoration and disposal with presentation; its disposal path called `close` twice. | `rememberGifPlayback` keeps those responsibilities beside the decoder and closes it once in `finally`. Frame bookmarks, limits, pause behavior and UI retention during fades are preserved. |
| Network publication | A deeply nested expression repeated destination digest reads and cancellation checks. | Guard returns separate recovered, already-published, local and remote destinations. One local digest function keeps cancellation in every read. Durable name reservation, verified hashes, abort handling and source-preservation rules remain intact. |
| Document preview | Worker IO independently changed text, PDF page count and bitmap Compose state. Cache-directory creation also ran before the IO block. | Decoding returns one `ExplorerDocumentPreview`, published by the caller after IO completes. A failed PDF render recycles its unpublished bitmap. Cache-directory creation now runs in the existing IO block. ZIP/path, size, scope and cache limits are retained. |

The extra private functions express transaction phases or own a real resource; no repository/service interfaces, dependency-injection framework, replacement state store or new runtime library was introduced. No tests were added or disabled.

## Size and complexity context

The following are navigation aids, not a quality score. Extracted files retain the original classes/functions; the application is not smaller merely because their locations changed.

| File | Baseline lines | Lines after this pass |
| --- | ---: | ---: |
| `GalleryHome.kt` | 1,215 | 1,256 |
| `GallerySections.kt` | 1,257 | 754 |
| `FileOperations.kt` | 1,191 | 1,214 |
| `GalleryApp.kt` | 1,036 | 156 |
| `ExplorerSession.kt` | 590 | 612 |
| `MediaViewer.kt` | 1,338 | 1,317 |
| `Music.kt` | 727 | 753 |
| `PanelActivity.kt` | 660 | 662 |

`GallerySession.kt` contains 889 lines and `PreferencesScreen.kt` 527. Total code has not been minimized: named arguments and explicit branches deliberately add readable lines. More useful differences are one playback mapping instead of two, one focus-direction mapping instead of two, one decoder cleanup path instead of two, and explicit journal phase boundaries inside the same lock.

## Verification

Focused file/import/trash/FTP checks passed after extracting the journal phases. Focused network, music, GIF and widget checks passed after consolidating publication and playback state. The collection tests passed in both landscape and small portrait configurations with the explicit test dispatcher. Localization verification reports 2,079 translated keys per supported locale and no pending translations.

Both complete local runs passed all 915 tests (116 test classes), with no failures, errors or skipped tests. The final run covers the last scene-key readability change and also passes `lintDebug`, `assembleBenchmark` (including R8) and `lgplRelinkingKit`. The runtime/test changes are committed through `803b03c`; this report is a subsequent documentation-only commit. No retry policy, test exclusion, artificial sleep, lifecycle-check suppression or security-validation bypass is introduced.

[Branch CI run 39242861591](https://github.com/Syllabuss/DualScreen-Gallery/actions/runs/39242861591), on that exact code commit, passed all four test shards, build/lint, translation verification, the LGPL kit and the final `verify` job. The previously failing collection shard passed without rerunning the job.

Lint is not warning-free. The baseline CI report contains 571 warnings and two hints; the first local report contains 569 warnings and two hints, with no added issue categories. One difference is the environment-dependent Android Gradle Plugin version advisory, so this is not presented as two code defects fixed. Most remaining findings concern unused resources and KTX suggestions. Resources are not deleted wholesale during this pass: localization, reflective lookup and device-specific screens need to be considered before removing them.

## Remaining risks and follow-up

- `GalleryHome` still coordinates many scene and controller decisions; `GallerySession` still owns several kinds of transient state. Extracting them into many callbacks or a new global state store would move, rather than resolve, the coupling. A later change should address one state owner at a time with paired-display tests.
- The album drag implementation combines geometry, focus and pointer ownership. It was not rewritten: the geometry model already has dedicated tests, and a new implementation would risk the large/small tile and controller behavior.
- Journal operation/phase strings remain part of the recovery contract. An enum/schema migration or splitting the execution mutex would be a separate change requiring migration and interrupted-operation evidence. Conservative hashing, identity checks and non-cancellable journal writes are intentionally retained.
- PiP/lid handling, compatibility branches and protected document-provider checks were not removed because they look defensive. They cover device behavior the JVM cannot establish.
- Other Robolectric UI tests still use the default Compose dispatcher. This pass fixes the demonstrated collection scheduler failure; migrating every test rule in one sweep would change unrelated timing assumptions. Future failures should be traced to their actual dispatcher/fixture before changing them.
- Explorer shutdown uses synchronous remote cleanup. Moving shutdown or changing socket ownership needs dedicated interruption/teardown tests; it is not folded into a presentation refactor.
- No physical Thor was connected during this pass. Dual-screen startup/swap, iiSU Home/lid return, controller/touch focus, GIF pause/restoration, audio/video coexistence and FTP/SMB interruption/recovery need a physical smoke check before merging. Existing JVM fixtures cover these policies but do not certify firmware, codecs or a live server.
- No performance improvement in milliseconds, battery use or peak memory is claimed without device measurements. The concrete changes remove duplicated cleanup/mapping and a small amount of UI-thread disk work; they are not a replacement for profiling.

## Device acceptance checks

Before merging, use the existing installation and selected folders; resetting the app would hide compatibility problems.

1. Navigate library, albums and settings with controller and touch, then swap panels twice and check selection and focus.
2. Pause a GIF on a frame, leave its preview and return; check its frame, strip and zoom behavior. Also leave the preview while it is playing.
3. Play music, switch tracks with L2/R2, and check preview, widget and notification state. Check video coexistence and the existing pause/resume rules.
4. Exercise manual and navigation-triggered PiP, Home/iiSU return, lid close/reopen and both panel assignments.
5. Copy a disposable file in each direction over FTP/SMB, interrupt one transfer, then resume or cancel it. Confirm the original and completed copy before deleting any fixture.
6. Open a text file and a multipage PDF from the network browser, change pages and close the preview while it is loading.
