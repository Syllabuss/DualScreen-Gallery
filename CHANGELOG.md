# Changelog

## Unreleased — IPtv branch

- Allow the photo widget to shrink to a single launcher cell while retaining its existing initial size.

- Offer exactly three launcher widgets: Icon Player, Compact Player and the photo frame. Remove Coin and the legacy duplicate Icon provider. Compact Player supports 1×1 cover-only play/pause as well as its 3×1 transport layout.

- Name the music widgets Player Coin, Icon Player and Compact Player. Request a fixed one-cell Coin footprint and preserve identical cover bounds when playback starts. Record reviewed translations and stabilize the GIF-strip UI test; fail fast on unrecorded localization revisions.

- Center and bound compact music-widget motion across resize, add a coin-flip effect and make the circular button 1×1 by default. Follow system appearance and derive smoother widget bars from cover colors. Wait for asynchronous work enqueueing in the file-operation regression test.

- Preview the selected music track without replacing the playing song; preserve the Music screen when a floating TV player closes. Confirm only removal of TV favorites. Open album options after a stationary long press, and remember grid/list and density separately for each album and video folder.

- Repair mixed-favorite controller focus, GIF memory estimates, compact list sizing and sort reset; separate TV favorite timestamps from channel saves. Make SMB verification cancellable. Fix the regression test/lint gate and split complete CI verification into balanced test shards plus an optimized build/lint job.

- Cycle the four Tools pages with the mapped previous/next section controller buttons (L1/R1 by default).

- Preserve TV channel selection on return and prevent focused list items from sliding back into place during rapid touch scrolling.

- Separate saved TV channels (pin) from favorites (star), preserve TV focus on re-entry, restore bounded GIF animation in mixed favorites, reuse logos from cached personal playlists, and align the root chooser and preview focus across panel swaps.

- Show Info and permissions cache results as a transient toast. App reset now checks active/scheduled work rather than treating failed journal records as running transfers; keep the five-second confirmation delay.
- Remove bundled TV source directories; add personal HTTPS M3U playlists and bounded cross-source channel search. Add a second first-use source/rights notice without catalog recommendations. Preserve saved channels.
- License first-party source under GPL-3.0-only, retain separate branding/asset notices and matching LGPL reconstruction instructions.

- Shape Tools navigation as edge-attached rectangular tabs; simplify explorer paths and rebalance Trash toolbar spacing while retaining permanent-deletion confirmation.

- Retain SMBJ event-bus handler construction in optimized builds; frame both explorer panes and present connection errors in a lower-screen dialog dismissed on retry.

- Distribute Tools rail icons vertically; prefill example SMB connection values with a localized reminder to replace them with personal details.

- Retain unsubmitted share-address and username drafts across tab changes while clearing the password when the form closes.

- Prefill remote connection retries with the last attempted share address and username, including failed attempts; never persist the password.

- Require a fresh five-second countdown before confirming an app reset, with Cancel on the left and Reset on the right.

- Keep tutorial shoulder badges enlarged while held and change pages only on a matching, uncancelled release; ignore held-button repeats.

- Split sharing and remote browsing into separate Tools rail icons and remove the redundant mode chips; narrow the rail while retaining centered touch targets.

- Limit the FTP transfer dialog to the current sharing session and disable it when sharing stops; add a sensitive-clipboard copy button for the password.

- Show FTP transfer history in a scrollable upper-screen dialog opened from the lower-screen button; lift the sharing footer and center Tools icons in a wider rail.

- Retain MINA's reflected socket-processor constructor in optimized APKs so FTP sharing can start in release builds.
- Open the app's shared-storage provider when broad storage permission is already granted, allowing Download to be explicitly selected for FTP. Place sharing controls and connection guidance by physical display.

- Fade tutorial language changes without recreating either panel, retain the welcome greeting, and interpolate theme colors. Add shoulder-button badge feedback and correct the Purple completion icon.
- Match new default three-finger navigation to left/previous and right/next, with double-caret tutorial symbols; preserve saved custom mappings.
- Center the Tools sidebar icons and report compact cache cleanup through a short toast. Put FTP sharing controls above, its start/stop button at the bottom, and connection guidance on the other display.

- Keep theme selection live across both settings panels until Save or Discard; order base palettes Rainbow, White, Black, Purple and reveal expanded background controls with smooth scrolling.
- Align empty Favorites/TV previews, offer channel browsing through the centered add button, and default browsing to Sources beside search.
- Protect the centered video dim control from long folder titles, round the screen-swap button, and remove redundant settings shortcuts in Tools.

- Bound FTP uploads to 8 GiB each while reserving 128 MiB of internal storage; update Apache MINA to 2.2.9.
- Require explicit acknowledgement of authorized TV sources before first use; document rights reports and external network access in every supported language.
- Give live-channel startup a 20-second overall deadline so stalled streams release channel navigation.
- Pulse favorite hearts and TV stars only on addition, never on initial display or switching media.
- Generate versioned LGPL reconstruction kits with complete application/build sources, jump3r source and explicit reconstruction permissions; require them in distribution builds.
- Verify pushes on both main and IPtv and add periodic dependency update checks. Align TV/privacy guides with persistent, bounded logo caching.

- Compact the image preview options bar, use volume-aware speaker icons in music/video players, and default fresh/reset installations to top-and-bottom preview shadows and monthly photo grouping.
- Restore an opaque native PiP player with fitted video and avoid resetting its geometry during window resizing.
- Pause music and video on device sleep by default without reviving music previously interrupted by video. Add separate opt-in sleep playback settings with battery and heat guidance.
- Show the music volume slider on demand, above its speaker button, and hide it after five idle seconds.
- Suppress restored-item navigation sounds during section changes so only the section cue plays.
- Bound network staging by the declared source size and available space before writing; discard unverified partial copies without touching originals.
- Preserve natural-name, date, size, duration and added-date sorting in mixed media/TV favorites. Keep previews within the active filter and search.
- Retain TV thumbnails across launches with a seven-day/20 MiB eviction policy and atomic writes. Remove unused custom channel artwork after cancellation, replacement or channel removal.
- Wait for database-test coroutine shutdown before closing Room to prevent background database-lock errors.

- Reconcile a reserved network publication after a disconnect or process death instead of creating another copy. Journal the published destination before final verification.
- Refresh TV drag targets during edge scrolling and keep gesture ownership on the viewport. Compact channel grids to five columns, clip focus highlights and use Phosphor stars with dismissible add/remove confirmations.
- Separate downloaded channel logos from cropped custom backgrounds, use rectangular saved-channel thumbnails and four-column catalog grids. Keep focused channel names scrolling and synchronize selection/scroll after L2/R2 channel changes.
- Use yellow duotone/glowing saved-channel stars and simplify the removal confirmation. Center its prompt, place the heart beside the accented channel name, and separate Cancel/Confirm across the footer.
- Show list logos independently of custom thumbnail availability, retain both layers when present, and leave missing artwork blank. Add a compact pencil-overlay thumbnail editor and shared navigation/favorite sound cues.
- Reorder TV channels against stable original slots with a separate drag preview and proportional edge scrolling in list and grid layouts.
- Preserve manually opened PiP across category changes and show its placeholder only on the current video/channel; automatically opened PiP still returns to the gallery player.
- Credit IPTV-org catalogs and metadata alongside the existing in-app credits; keep license details in the third-party notices.
- Toggle the current live channel with A; consume B to close a paused TV player before navigating. Keep Back at the TV root when it has its own navbar entry.
- Load public channel logos remotely only for the focused channel; downsample cached tile artwork, expire it after seven unused days and include it in Clear cache.
- Synchronize TV overlay controls after channel changes, add a TV-only close button, place channel announcements at the bottom and let media previews remain visible while TV is in PiP.
- Release live TV on device sleep and offer to reconnect on return. Cancel obsolete TV confirmations when navigating elsewhere.

## 0.1.0-beta.90 — 2026-10-17 · IPtv branch

- Add controller-aware channel lists and square-grid view, R3 view switching and persistent long-press channel reordering. Derive channel-card accents from fixed-size thumbnails.
- Fix artwork decoding and return from the Android picker to the same upper-screen channel editor, preserving its draft. Show custom artwork in previews and retain channel order after editing.
- Announce channel changes at the right of the player and block repeated L2/R2 changes until the stream is ready or fails. Reopening the current channel never pauses it.
- Keep TV overlay controls hidden at startup and synchronized with the playback controls. Reuse the themed PiP placeholder and allow Settings to take priority over TV.
- Confirm replacing a playing local video with TV, and show connection failures in retryable, controller-accessible dialogs.

## 0.1.0-beta.89 — 2026-10-17 · IPtv branch

- Insert optional TV navigation after Music by default, preserving explicitly reordered categories.
- Change live channels with L2/R2 using the playback catalog or saved channel list.
- Compact catalog browsing into a category dropdown and search row; move the network notice into an info tooltip and the manual-channel dialog. Keep the navigation bar behind the keyboard while searching.

## 0.1.0-beta.88 — IPtv branch

- Add opt-in online TV, a contextual Video shortcut or optional navbar category, personal channel storage, on-demand IPTV-org catalogs, individual HLS URLs and channel name/artwork customization.
- Reuse the video coordinator and PiP for linear live playback, with seeking/queue/track controls disabled for TV and a restricted HTTPS transport for manifests, child streams, keys, catalogs and logos.
- Keep TV stars separate from local media favorites, with an optional mixed presentation. Preserve existing category order and settings exports; translate the TV interface into all supported languages.
- Open Video in folder view on fresh installations while respecting saved choices. Update media-view test fixtures to request the view they exercise explicitly.

## 0.1.0-beta.87 — 2026-10-16

- Add a copy button beside the FTP address for sharing it with a compatible mobile client.
- Prepare a private release signing identity with Android v3 certificate lineage; verify an in-place update from the existing development signature on Thor.
- Add an opt-in physical-device GIF stress runner with temporary fixtures, memory sampling and preference cleanup. A 40-GIF run completed both 90-second phases at requested limits of four and forty without a crash.


## 0.1.0-beta.86 — 2026-10-16

- Confirming a folder in Video folder view opens that folder instead of playing its selected cover video. Refresh controller routing immediately when switching between folder and media views.

## 0.1.0-beta.85 — 2026-10-16

- Refine the eight-page onboarding with page-specific headings, equal-height controller cards, compact folder previews and tooltips, a two-turn vertical-axis icon animation and theme-aware completion colors. Use the supplied welcome and previous-page sounds outside sound customization.
- Keep Interface controls editable for empty categories, including independent Favorites controls. Show screen swapping by default in new installations.
- Increase focused settings icons slightly more than their labels and link the author credit to Syllabuss on Telegram.
- Preserve the current Thor Playback defaults; the device has no saved Playback overrides.


## 0.1.0-beta.84 — 2026-10-16

- Onboarding dismisses only through Skip or completion, with centered dialogs and larger vertical margins. The other display blocks interaction and cycles multilingual greetings before a zoom/fade reveal.
- Combine folder selection and its thumbnail recap in one step, followed by a dedicated animated completion page; R1 advances Later and completion. Folder-picker results keep Albums as the destination rather than opening Media folders, and optional full-storage guidance supports Download.
- Default three-finger gestures: swipe up to swap screens, left/right for next/previous section, stationary tap to dim. Down is unassigned; existing custom bindings are preserved.
- Move video onboarding hints into controller-card tooltips and hide unassigned gestures.


## 0.1.0-beta.83 — 2026-10-16

- Reorganize onboarding into compact controller cards with L1/R1 page navigation, image-specific double-tap guidance and directional gesture illustrations.
- Show a live linked-folder count and a bounded thumbnail recap, including media-type badges and an overflow tile. Keep progress dots beside the footer actions.
- Empty Favorites keep their explanation on the preview display and show only a theme-independent magenta heart on the navigation display.


## 0.1.0-beta.82 — 2026-10-16

- Linking the same folder for images/videos and music retains both roles. Recognize the shared-storage provider and Android primary-storage provider as the same local documents, preserving favorites and memberships.
- Repair previously indexed local aliases after a successful scan, skipping pending file operations and inconsistent file versions.
- Avoid synchronous disk writes for immediate view preferences. Build catalog lookup indexes and GIF strip decoders only when requested, and index scan aliases instead of repeatedly searching the library.
- Converted GIF videos remain in Photos while retaining the video decoder; they stay excluded from Videos.
- Align regression tests with Rainbow, album grids, denser views and independent player volume.


## 0.1.0-beta.81 — 2026-10-16

- Cancel pending Home detection when the device sleeps, preventing a delayed fallback during wake-up.
- Right stick adjusts system brightness and console volume in Video and Music; images retain zoom. Explain the optional Android brightness permission.
- Open the shared-storage document-provider root directly when adding Download or another folder with full-storage access.


## 0.1.0-beta.80 — 2026-10-16

- Guided optional all-files permission and document-provider access to Download or the entire internal shared-storage root, retaining the chosen media role.
- Fresh defaults: Rainbow, Album grid, Music list and denser Photo/Video/Favorite grids; preserve saved choices. Exclude converted GIF animations from Video.

## 0.1.0-beta.79 — 2026-10-16

- Empty-library add tiles pulse gently with a stronger surrounding glow; empty Favorites use the app's magenta heart with a pulsing halo. Reduced-motion mode keeps them static.
- Folder guidance is wider, uses theme surfaces and groups its search heading with an information note. Empty media lists no longer show a zero-count sorting bar.
- The app reset confirmation opens on the details display; controller Back dismisses it without leaving Info & Permissions.
- Onboarding uses eight steps, with device-language selection, four base themes, linked-folder thumbnails and an animated final welcome. The other display stays decorative and non-interactive. L1/R1 navigate the pages; tutorial sounds remain separate from configurable interface sounds.
- Sleeping or folding the device no longer triggers the gallery's all-panels-stopped Home fallback.

## 0.1.0-beta.78 — 2026-10-16

- Empty Music previews use the same folder guidance as Photos, Videos and Albums. Favorites explain that no media have been marked yet and never suggest linking a folder.
- Empty library panels offer a single centered add-folder tile. Folder guidance explains that more folders can be added later; Cancel and Continue are keyboard/controller accessible, with Continue focused initially.
- The toolbar add-folder shortcut is hidden in empty categories, leaving the centered tile as the single entry point.

## 0.1.0-beta.77 — 2026-10-16

- Info & Permissions offers side-by-side cache cleanup and app reset above the credits. Reset requires confirmation, preserves files in linked folders and returns to onboarding on the next launch.
- Playback examples place their label near the top and illustrate the applicable controls, zoom navigator and optional GIF frame counter/strip without decoding media.
- Album and video-folder focus are independent. Media inside Albums have their own layout and zoom, separate from Photos and Videos.
- The video volume slider and left stick adjust player volume, leaving Android multimedia volume unchanged.
- Three-finger gestures accept more natural finger arrival timing and shorter swipes while rejecting conflicting finger directions and established two-finger gestures.

## 0.1.0-beta.76 — 2026-10-16

- Settings previews include the optional screen-swap control and read-only Image, Video and Music examples. No media playback is started by these examples.
- Returning from Home while using video PiP preserves the current settings page and unsaved draft.
- Starting another video asks before replacing an active video. Cancel resumes the previous video; “Do not show again” is also configurable in Playback → Video.
- Added the replacement-confirmation cue and four separate PIN-digit cues, preserving individual sound controls.
- Restored audio-language selection beside subtitles and added a separate volume control beside the video timeline.
- Three-finger downward swipes dim the touched panel without requiring playback. Existing custom downward gestures are preserved.
- System bars are hidden on gallery panels by default; Interface can enable the status bar on the upper display.

## 0.1.0-beta.75 — 2026-10-15

- Controller scrolling in Playback and Controls now reaches the full bottom of the page, including trailing explanations. The music Previous explanation includes an information icon.
- L1/R1 switch the Controls tabs; X opens the guide, with a circular shortcut badge beside its button. Unsaved changes still require a discard decision.
- Moving between the two Info & Permissions panels clears the focus on the panel being left.
- Music players have a vertical volume slider on the right, controlling only their own playback volume.
- Video audio-track selection uses the translate icon beside the elapsed time; subtitles and playback settings remain on the right.

## 0.1.0-beta.74 — 2026-10-15

- Album and video-folder dragging now share a single ordered grid layout for live preview and saved placement, including mixed 1×/2× tiles and grouped moves.
- Corrected the light launcher icon, controller navigation through Info & Permissions and focus when returning from Theme.
- Custom music-player colors are literal; artwork mode adds a full-screen cover backdrop and more distinct extracted accents. Playing grid covers pulse while list covers rotate.
- Video selection starts on the second tap; the bottom player menu contains playback order and speed, alongside separate subtitle/audio controls. Shuffle completes the folder before repeating.
- Native video controls and preview overlays hide together. Dismissing PiP retains the paused timeline, and launcher audio no longer interrupts background music.
- GIF-strip pause collapses the strip and play restores its previous width. Music overlays dock more easily at either edge, and favorite hearts use a consistent magenta glow.

## 0.1.0-beta.73 — 2026-10-15

- Back closes a paused video without leaving the current browsing folder; during playback it remains a browsing action.
- Music preselects the previous track, with the first listed song as fallback, and removes the extra note from the solid-disc placeholder.
- Added theme, cover-derived and custom music-player colors, readable gradients and saved color preferences.
- Settings categories share a border focus treatment with a gentle icon/text scale animation.

## 0.1.0-beta.72 — 2026-10-15

- Stabilized album dragging and group selection, comparison GIFs, controller sliders and settings focus restoration.
- The first controller input on the settings home highlights Interface without opening or moving to another category. Playback scrolls as one page, and credits remain reachable with the controller.
- Entering and leaving Sounds gently fades the current music out and back in, respecting subsequent user transport commands.
- Music uses frame-index seeking for MP3 streams instead of relying solely on seek tables. Invalid or truncated source files still require replacement to recover missing audio.
- Video resume expires after seven days and excludes the beginning and end. Video navigation keeps its folder, and Home/PiP return waits for the original preview panel.
- Shared three-finger panel-swap gestures remain available after role changes. Changing navigation tabs wakes a dimmed panel.
- The GIF filmstrip stays anchored; the music overlay and its dock tab leave space around it.
- Added checkerboard, tint, solid and vertical/radial gradient backgrounds for transparent previews, with color controls and portable preferences.
- Added a circular music-widget provider and refined compact widget layouts. Existing widget instances keep their providers.

## 0.1.0-beta.71 — 2026-10-14

- Optimized local build with R8/resource shrinking and a separate physical-device performance/profile test module.
- Reused scan indexes and UI derivations, avoided redundant protection copies and coalesced catalog reloads.
- File execution no longer holds the catalog writer during the full copy/export; final indexing retains current favorites and playback state.
- Reduced artwork-cache scans, added fast video cache hits and a bounded temporary frame-selection cache.
- Compensated GIF decoding time and applied an estimated animation-buffer budget alongside count limits.
- Reduced music-widget bitmap updates, reused unchanged cover buffers and added compact 1×1 cover-tap play/pause.
- SMB directory browsing can continue while transfers run; disconnect waits for cancellation before releasing shared handles.


## 0.1.0-beta.70 — 2026-10-14

- Album cover actions are available throughout the Albums section, but not in Photos.
- Flat album browsing includes populated subalbums; folder browsing retains the hierarchy.
- GIF frame controls live under Playback → Images, with dependent options shown only when enabled.
- Clarified reduced motion and controller sorting, refined dependent cover controls and settings spacing. Reduced motion affects app transitions and player effects, without changing GIF playback or widget animations.


## 0.1.0-beta.69

- Use the desktop-tower icon for Tools and add a direct Trash shortcut to the settings header.
- Replace the two settings layout buttons with one alternating list/grid toggle.
- Group Playback into Images, Video and Music tabs with shared options above and fading category changes.
- Add media action links to the matching Playback tab; album cover assignment is restricted to media opened inside Albums.


## 0.1.0-beta.68

- Add an opt-in nearest-neighbor image/GIF preview filter in media options. Standard filtering remains the default; changing the filter preserves image zoom.
- Persist the filter choice and include it in settings exports; older backups retain standard filtering.
- Resolve GIF-strip centre selection from visible frame placement.


## 0.1.0-beta.67

- Mirror and anchor comparison edge tabs; reveal a corner resize handle with the close control.
- Pause GIF-strip playback while dragging and preview the central frame at any strip width; preserve explicit frame selections.
- Keep the GIF strip and mini music player separated from the zoom navigator.
- Recover legacy network folder-creation records without overwriting files or reconstructing missing transfers.
- Confine video editor render-test effects to the UI looper; the complete Linux verification passed.


## 0.1.0-beta.66

- Move FTP into Tools alongside Trash and Duplicate Finder, reusing the existing dual-pane network screen.
- Remove the optional FTP home-navbar switch; Tools always exposes FTP without automatically starting sharing.


## 0.1.0-beta.65

- Replace the light/dark Android launcher icons and Interface previews with border-free artwork. Existing icon preference is retained.


## 0.1.0-beta.64

- Pin/unpin on the same preview preserves zoom and position; album PIN Back erases a digit before closing.
- Video folders have independent tile sizes and grid density. Album focus borders survive density changes.
- Media action menus close when changing tabs; FTP ignores global settings shortcuts.
- Cover-frame controller seeks start at one second and accelerate during continuous input. Video flow changes show brief feedback; the redundant Close icon is removed.
- Overlay dragging resumes from the last position, uses an inset at rest and resets dock/strip layout after closing the gallery.
- Settings gear and component-notice link presentation refined; playback feedback translated in every language.


## 0.1.0-beta.63

- Guided batch/ZIP recovery and specific connection errors.
- Visible album GIF cover controls, persistent video folder view and stronger action/dialog consistency.
- Shared cover-frame extraction, chosen GIF-frame export, retained docked controls and paused GIF state.
- Supplied UI sounds integrated and all ten languages aligned.
- Settings hub navigation into the partner Info card and removal of stale partner content.


## Unreleased — 0.1.0-beta.62

- Open controller-ready album/media actions on the preview display, with an Interface option for the browsing display. Start right-stick image zoom at normal size.
- Use draggable edge tabs, a collapsible cyclic GIF filmstrip, full-display comparison movement and remaining comparison capacity indicators.
- Cycle Video grid/list/folders with R3 and show nested folders inside video albums. Replace the redundant floating-playback button with video flow modes.
- Add local/SMB file previews, verified COPY/MOVE, operation progress/cancellation and bounded ZIP extraction. Directory moves preserve original folder shells. Live SMB interoperability and complete interrupted-batch recovery remain open.
- Complete the 725-test Windows run and install on Thor without clearing data. Update ten-language coverage to 1927 keys.

## Earlier development — 0.1.0-beta.61

- Separate FTP folder sharing from a read-only device explorer, with independent local/computer panes and a single-screen tab layout.
- Browse explicitly authorized Android folders and connect to signed SMB2/3 shares with session-only credentials. Display friendly errors and allow closing the computer connection.
- Preserve folder history, focus, selections and scroll positions across pane changes. Live Windows/Thor verification and file transfers remain pending.

## 0.1.0-beta.60

- Shorter first-launch guide with controller cards, vertical screen-swap arrows and gentle pop-in/pop-out transitions.
- Zoomed photo/GIF previews support left-stick panning and right-stick vertical zoom without moving library focus.
- Preview presentation options move to Interface; Playback retains media playback behavior. Info links to component notices.

## 0.1.0-beta.59

- Image preview taps now only toggle controls; swipe remains the gesture for browsing. Double-tap zoom hides the overlay.
- Wider first-launch guide with animated steps, themed icons and explanations of folder, notification and comparison permissions.

## Unreleased — 0.1.0-beta.58

- Animate GIF comparison windows on the preview display; tap to toggle Close, drag across the full display, or park at an edge and recover from a bubble.
- Park the compact music player and GIF filmstrip at either side without swiping the underlying media. Move the resize handle to the strip’s far right.
- Keep browsing focus when releasing a preview pin and clear album selection after changing tile size.
- Add an initially active butterfly control for automatic video PiP, explained in Playback settings in all ten languages.
- Use a wide, muted, exact-seeking cover-frame preview with separate time labels and a longer slider.

## 0.1.0-beta.57

- Allow photo/GIF zoom down to 50%; double-tapping a reduced image restores its fitted size before the normal zoom toggle.
- Share progressive video seeks between the left stick and double taps in the central left/right picture bands.
- Place video Close at the far right, theme the PiP return button and use distinct pin/comparison icons.

## 0.1.0-beta.56

- Follow the focused album or media in the preview, with an optional image/GIF pin and confirmation before replacing it.
- Add up to two movable, resizable image comparison windows with explicit Android overlay permission.
- Preserve the video player and position while visiting Music, FTP or settings; optionally keep playback going in Android PiP and return it to the preview automatically.
- Add an independent animated-GIF album-cover switch and translate the new controls in all ten languages.

## 0.1.0-beta.55

- Restore the paused frame's position when reopening the GIF filmstrip.
- Preserve album packing while avoiding repeated scans of occupied cells and grouping grid bands once per layout.
- Reuse provider metadata within each FTP listing; downloads and new commands still resolve current files.

## 0.1.0-beta.54

- Keep album tile sizes at 1× and 2×; small albums fill two rows alongside a large tile, with controller navigation following their positions.
- Make the GIF filmstrip compact and adjustable from its right edge. Hiding the overlay preserves paused playback; only the strip's Play control resumes it.

## 0.1.0-beta.53

- Add opt-in FTP sharing after Favorites, with a chosen folder, per-session credentials, passive connections and explicit start/stop controls.
- Journal received files and verify the destination before confirming upload success. Existing files cannot be overwritten or deleted remotely; interrupted uploads are not published.
- Add the real GIF current-frame counter and a lazy frame strip for pause, frame selection and resuming at the selected frame. The zoom navigator shows the same decoded frame.
- Persist the new interface controls and retain compatibility with older preference backups. Keep selected FTP grants and credentials outside portable exports.

## 0.1.0-beta.52

- Browse video-only folders from the Video toolbar, retaining the ordinary video list/grid inside a folder.
- Keep photo/video browsing positions separate from the global preview; enter an album at its previewed media only when that media belongs to it.
- Dismiss an open visual preview with B on the album root. Protected covers no longer override an already open visual media item.
- Cache derived video thumbnails in the existing private artwork cache, with file-version and frame-time keys and serialized decoding.
- Choose a video-cover frame by timeline position, saving only its timestamp. Allow videos as album covers and animate the focused GIF cover.
- Give album tiles three sizes, 1×, 1.5× and 2×, with controller navigation based on packed tile rows.

## 0.1.0-beta.51

- Change the stock shortcuts to Y for swapping displays and X for contextual actions. Preserve custom bindings and update the guide and video restart dialog.
- Restore the video close button and add a button for returning a floating video to the gallery.
- Restore the Video section and playing item when leaving Picture-in-Picture after browsing other categories, keeping the existing player and timeline.
- Give PIN keypad buttons a subtle press animation and align settings list arrows in a consistent target area.
- Add simultaneous GIF limits of 4, 6, 12, 20, 24, 35 and 40; use 4 by default.
- Keep the PiP fullscreen source bounds stable across native window resizing. Fullscreen return from Home can still show the Thor firmware's lateral task transition; this remains open.

## 0.1.0-beta.50

- Move available subtitle controls into the bottom video transport bar, between Picture-in-Picture and player settings; remove the top close button. B still pauses the video before returning to its preview.
- Publish the existing video decoder as an Android media session, with notification play/pause and seeking. Keep its notification separate from the music session.
- Add optional shared video audio in Playback settings so launcher music can continue alongside the video. Normal audio-focus handling remains the default.
- Serialize music widget renders and transport/animation patches on the main dispatcher, preventing concurrent host updates.
- Translate the new playback option in all ten languages and include it in settings backup.

## 0.1.0-beta.49

- Use a wide colour picker with the wheel on the left, saturation and brightness on the right, an editable HEX field and a copy-to-clipboard button.
- Keep the startup logo on the preview display; use subtle edge lighting on the browsing display instead of a second central animation.
- Repeat held horizontal video stick input every half second, preserving progressive seek steps and showing the signed jump beside the video.
- Place the floating-video button beside the player settings and improve surface handoff and native window resizing.
- Offer optional five-second image-overlay hiding, disabled by default. Changing media does not restart its timer.
- Replace the studio checker fill with thin theme-coloured edge lines that quickly fade towards the image centre.
- Keep music-only albums out of the Album section and remove the option to show them there.
- Use icon buttons for image rotation and mirroring, with crop presets calculated from each display's current logical dimensions.
- Cover the brief route/inset change when entering or leaving settings with a short fade.
- Align these changes in all ten interface languages.

## 0.1.0-beta.48

- Lock video stick input to a clearly dominant axis until it returns to centre, avoiding accidental seeks while changing volume.
- Seek once per horizontal flick: rapid same-direction flicks advance 5, 10, 10, 30, 30 and then 60 seconds. Direction changes and pauses over one second reset to 5 seconds.
- Add native Android Picture-in-Picture through the frame-corners button. The existing player continues while browsing photos, settings, the launcher or other apps; its native controls offer pause, resizing, full-screen return and close.
- Translate the new video controls and guidance in all ten interface languages.

## 0.1.0-beta.47

- Abandon pending video resume requests on page changes, D-pad/stick navigation and shoulder-tab changes, keeping A targeted at the current selection.
- Reject confirmation of a resume request whose media is no longer selected.
- Round the zoom navigator, its image and viewport outline; use the active theme colour for its border.

## 0.1.0-beta.46

- Cancel a pending video resume dialog when another media item is selected, leaving its preview ready without starting the abandoned video.
- Animate GIF thumbnails inside the zoom navigator while their preview panel is active.
- Keep the opening logo on the primary screen, with the companion screen retaining its themed fade.

## 0.1.0-beta.45

- Cycle music artwork through normal, pulse, disc with a centre hole and disc without a hole; choose the saved effect in Playback settings.
- Add a centred navigation-header moon during video playback, hidden while searching. Optional idle dimming offers 5, 10, 30 or 60 seconds and five minutes; it starts disabled and wakes on interaction.
- Keep the current video paused, with its playback position and browsing context, when opening and closing settings.
- Make the video resume actions shorter and align their controller badges and labels to the left.

## 0.1.0-beta.44

- Silence interface cues and sound previews during video playback, including buffering. Pausing or ending the video releases this mute; automatic converted-GIF loops keep navigation cues available.
- Replace the video resume footer with three aligned actions: Continue, Start over and Cancel, retaining A/Y/B controller shortcuts without a repeated controller paragraph.

## 0.1.0-beta.43

- Integrate the smaller controller-focus footer margin into the normal grid scroll and keep the intended album column during navigation.
- Animate double-tap photo zoom and open album-cover previews on double tap, respecting PIN protection.
- Blend navbar icon color, size and opacity; crossfade settings previews when cycling categories.
- Render GIF frame totals above edge shadows, alongside the rest of the hideable overlay.
- Use A to resume, Y to restart and B to cancel the video resume dialog. During video playback, B pauses first and exits on the next press.
- Keep the PIN keypad stationary during errors and add individually configurable sounds for its first three digits.
- Remove destructive album actions; style media trash actions with a red label and trash icon.
- Add a second disc cover effect without a centre hole and a distinct resting size for pulse covers.
- Make library sort controls touch-only by default, with an Interface opt-in and explanatory hints.
- Start image-detail metadata reads inside the visible details sheet to avoid a stalled loading indicator.
- Keep the Thor’s main task on the primary display so Recents can restore it without relocating it; explicitly exclude the lower companion and preserve browsing/preview roles.

## 0.1.0-beta.42

- Limit the final-row controller scroll to half the trailing padding and preserve the album navigation column in both directions.
- Keep the photo overlay deadline across media changes; A toggles it on the active photo, including the GIF frame counter.
- Add optional image file names and directional preview motion in Playback; order sections as Images, Video, Music.
- Align preview action buttons, add touch shoulder hints to settings previews and fade settings layout changes.
- Add configurable restore, PIN success/error and album reorder sounds, with a brief rejected-PIN animation.
- Let each music widget show a disc with or without its centre hole.
- Replace the opening zoom with a short themed logo dissolve after the initial layout frames.

## 0.1.0-beta.41

- Reveal the existing trailing grid padding when controller focus reaches the final row, while keeping short grids stationary.

## 0.1.0-beta.40

- Suppress prior focus outlines during group selection and use stable album slots while reordering.
- Add quick navbar reorder arrows, a saved start category and shoulder-button settings previews.
- Reuse preview edge masks and extend directional grid motion to video previews.
- Refine the themed opening reveal and compact image-background controls.
- Clarify first-run shortcuts and credit Phosphor icons in About.

## 0.1.0-beta.39

- Keep hidden image overlays hidden across navigation and fade them after five idle seconds.
- Refine settings spacing and transitions, compact gallery tiles and add directional grid preview motion.
- Keep rotating music widget covers stable across artwork updates.
- Add saved image-background position and zoom controls.
- Reveal the app with a short, bounded console-style opening animation.
- Animate video preview swipes, match media overlay shading to albums and refine PhotoFrame controls.

## 0.1.0-beta.38

- Move the PhotoFrame moon control into its header.
- Give the preview lock distinct press and drag feedback; paint the open icon before requesting the PIN.
- Keep the companion settings card at a stable size throughout its entrance fade.

## 0.1.0-beta.37

- Keep PhotoFrame previews free of navigation chrome, use the theme background, and fade back to the gallery.
- Dim the companion controls after ten seconds of PhotoFrame playback, with a moon shortcut and touch to wake.
- Explain left-stick video controls and move duplicate search below its scope choices.

## 0.1.0-beta.36

- Put duplicate results on the companion display and simplify search controls.
- Add a saved playback switch for video stick controls; B exits playback only from the Video tab.
- Highlight the active settings layout, compact Screens and backup, and keep protected albums closed until PIN confirmation.

- Separate video left-stick seeking and volume from D-pad browsing.
- Import private, validated WAV interface sounds up to six seconds, with confirmation before restoring bundled sounds.
- Match Gradient/Image swatch dimensions and align the default application and Recents icons with launcher choices.
- Preserve the current favorite across panel swaps. Use the same saved-video resume choice for touch and controller.
- Keep a playing video visible while browsing other videos; A starts the newly selected video. Swapping panels transfers the existing player and its playback position.
- Save launcher-icon changes together with Interface settings and use a compact icon selector. Settings start in grid view on new installations.
- Initially focus the newest favorite; tighten smaller favorite tiles and add an independent compact favorite list.
- Preview interface sounds one at a time, with a trace derived from each bundled sound on the companion screen.
- Move cache clearing into Trash and rename display settings to Screens and backup.
- Continue video playback across gallery transport changes, support controller play/pause and exit, and optionally dim the browsing screen.
- Guide photo-frame setup through Album/Favorites, interval and transition; keep controls on the browsing panel and the slideshow on the preview panel.
- Align Language/Theme shortcuts, compact sort-direction controls, match thumbnail corners and increase the partner Info card spacing.
- Merge album edge shadows into one mask and add an optional fading studio checkerboard border.
- Persist the music preview cover effect across section changes and app restarts.
- Route video transport buttons through the current gallery context and enable horizontal picture swipes during playback without taking over the seek bar.
- Move support and device information to the upper About panel; use the light launcher icon by default and enable photo navigation and GIF frame counts for new installations.
- Add R3 list/grid switching and move the stock photo-frame shortcut to L3. Interface hints show mapped controller buttons in circles.
- Keep reverse sorting at the start of the refreshed list, align toolbar controls, use theme accents for navigation separators and add a smaller video-list density.
- Add configurable album-preview edge shadows, rounded media thumbnails and album view/cover transitions. Hide counts on hidden or protected covers.
- Give Favorites an independent list/masonry preference and use media dimensions for mosaic tiles.
- Add newest, oldest and shuffled photo-widget sequences, retaining their timeline when the launcher recreates the widget.
- Add a scrollable controller guide for video seeking/volume, L3 and gallery shortcuts.
- Simplify PhotoFrame timing and transition controls, with a full-screen slideshow toggle that hides system bars and gallery navigation.

## 0.1.0-beta.29 — 2026-10-11

- Show the actual media count on protected album covers without unlocking files; excluded sources and trashed files stay out of the count.
- Beta.28 device smoke checks passed for paired launch/Home/return, split settings, locked startup covers, toolbar visibility, music playback and touch focus. Longer hardware checks remain open.

## 0.1.0-beta.28 — 2026-10-11

- Select a light or dark launcher icon in Interface; refreshed DS Gallery branding.
- Clearer locked-album covers, larger PIN keys, favorites previews and music focus.
- Trash previews follow the most recently deleted item and controller focus. Duplicate groups use a six-column overview.
- Paired settings separate About content and permissions; removed the game-animation option.
- All ten interface languages updated. Runtime audio uses one Ogg Vorbis format.
- Isolate native Robolectric suites on Linux and wait for query cancellation before closing Room.
- Public Beta presentation refreshed; physical-device validation remains required.

## 0.1.0-beta.27 — 2026-10-11

- Expand music pulse to 70–106% in the app and widgets.
- Add an explicit video playback exit and a resume/start-over prompt.
- Make photo, audio and video editing full-screen, with controls below and a live partner preview above. Trim handles and playback share the selected interval; saving opens Android’s folder picker and writes a new copy.
- Export a selected video’s audio as MP3; audio edits also support MP3 alongside M4A.
- Move album subfolder badges over the top of covers and add sort-direction buttons to media libraries.
- Remove custom touch-zone controls, outline custom swatches, center the preview-example overlay and browse background images through a wider five-column album picker.
- Use the selected theme’s surface color for navigation bars.

## 0.1.0-beta.26 — 2026-10-11

- Restore the real album layout, covers, toolbar, and inactive navigation bar in the live theme preview.
- Arrange custom colors in two rows of three compact cards, with a small gear on each swatch.
- Give neon and pastel palettes distinct backgrounds and accents while preserving readable text contrast.
- Keep music pulse peaks at 106% and contract to 84% between beats in both the app and widgets.
- Keep outgoing photo grids from scrolling or reordering during section fades.
- Add a subtitle selector to the video preview when supported embedded subtitle tracks are available.

## 0.1.0-beta.25 — 2026-10-11

- Keep the in-app music pulse within the album-art bounds by contracting the cover as audio energy rises.
- Restore the album cover preview when leaving an album after viewing a photo.
- Add bordered, draggable category controls to Interface settings and clarify that the icons can be activated and reordered.
- Move the custom theme to the top and separate gallery-background controls; add translucent chrome over photo backgrounds.

## 0.1.0-beta.24 — 2026-10-11

- Rename the app to DS Gallery (DualScreen Gallery) and document AYN Thor as the only tested dual-screen device.
- Show four compact theme presets per row, group neon and pastel palettes in accordions, and add two palettes to each family.
- Label settings previews as examples and remove simulated gallery controls from those previews.
- Start from the first item on cold launch unless the user enabled remembered media; keep explicit in-session selection intact.

## 0.1.0-beta.23 — 2026-10-11

- Tighten bottom navigation to the icon height with only a small vertical margin.
- Give the music pulse a stronger contraction-to-expansion range and faster response.
- Route the empty Music library through the same folder guidance dialog as other categories.

## 0.1.0-beta.22 — 2026-10-11

- Increase the audio-reactive pulse range in the app player and music widgets.
- Open folder guidance when tapping anywhere on an empty media panel, including the preview display.
- Reduce bottom navigation height while keeping the icons the same size.

## 0.1.0-beta.21 — 2026-10-11

- Make Y and Start open actions for the focused album or media item; retain Settings as the fallback when nothing is focused.
- Exit album selection after saving a cover.
- Make the music widget pulse follow bass energy with a more visible, smooth scale response.
- Add a black window launch frame and a restrained fade/settle entrance; separate the bottom navigation from the library with the theme accent.
- Refresh project overview and paired two-display documentation screenshots.

## 0.1.0-beta.20 — 2026-10-10

- Preserve the current album media and browsing position when swapping panels with X.
- Choose an album cover from its own photos; move Select all and Deselect all to the selection toolbar.
- Clamp downward music navigation to the final track and disable unavailable in-app previous/next actions.
- Remember Photo and Video previews independently of Music, matching GIF filters to each list and using a newest-item fallback or category empty state.
- Add a vertical theme gradient with adjustable end color/intensity, or a library-image background with adjustable opacity, for the browsing panel.
- Fade between preview categories and the music player; update all supported translations and portable settings compatibility.

## 0.1.0-beta.19 — 2026-10-10

- Refresh renamed album names and paths while preserving IDs, covers and hidden state.
- Release cached music-widget cover frames when a widget is deleted or its effect is disabled.
- Cache audio metadata read results, including absent tags, with a non-destructive database upgrade. Clearing caches allows a retry.
- Fix interrupted music-cover effect transitions so rapid taps return the cover upright and at its normal size.

## 0.1.0-beta.18 — 2026-10-10

- Show lightweight skeleton placeholders when Android first creates photo or music widgets and while an album photo is loading.
- Keep the last rendered widget during ordinary refreshes. Placeholders use native shapes without bitmap decoding, timers or gesture handlers.
- Validate loading layouts at minimum widget sizes and preserve launcher long-press handling.

## 0.1.0-beta.17 — 2026-10-10

- Introduce a two-step first-launch tutorial with controller shortcuts, folder permissions, progress dots and skip/back navigation. Configured libraries no longer show the empty-library welcome.
- Read and cache audio title, artist and album tags when browsing chosen music folders, without extracting cover art for the whole library.
- Avoid rebuilding the complete catalog for operation progress updates; mutation safeguards still follow deletion phases.
- Keep Trash sorted by deletion time, newest first, and label Find duplicates as experimental.
- Align tutorial and experimental-status copy in all supported languages.

## 0.1.0-beta.16 — 2026-10-10

- Reorder albums by dragging a long-pressed selection, including whole selected blocks; remember the order and place newly discovered albums first.
- Show empty albums and add an empty-album creation tile. Reusing an existing folder name preserves it without creating a duplicate.
- Offer optional hierarchical album browsing alongside the default flattened folder navigation.
- Move Trash and Find duplicates to a compact sidebar, default Trash to six columns with four-to-eight sizing, and show restore progress over media.
- Highlight favorites in deletion confirmations. Optional direct deletion remains hidden by default and uses the existing verification journal.
- Keep trash actions in the gallery and advance the preview to the preceding available media.
- Show move/copy destinations in two columns and guide creation of a destination album.
- Add Y as the remappable settings shortcut and show current controller bindings beside matching interface options.
- Use smaller covers in the widget track picker, remove its Cancel footer, improve music cover resolution, and fade in newly loaded photo widgets.
- Place GIF frame counts above the photo navigator and update the deletion cue and all supported translations.

## 0.1.0-beta.15 — 2026-10-10

- Photo minimap follows finger movement continuously and fades with preview controls, with no outer border.
- Interface settings can hide navigation categories while always retaining at least one; controller tab navigation follows the visible categories.
- Albums preview their first item in the current sort order when opened and return to their cover when closed. An optional playback setting keeps the last selected media instead.
- Theme and Language are the first links inside Interface. Sounds offers individual controls for every interface cue.
- Settings support a saved list or three-column grid layout. Commands remains above Screens and tools.
- Playback no longer exposes the legacy frame interval or artwork priority controls.
- Portable settings exports include the new preferences and retain compatibility with older versions. All supported translations are updated.

## 0.1.0-beta.14 — 2026-10-10

- Replace gallery density controls with Phosphor magnifying-glass plus/minus icons and add a direct album-folder shortcut.
- Move Trash above Info in Settings, with Trashpit and DuplicateFinder tabs.
- Add stable trash grid sizing, remaining-day badges, selection counts, a companion preview, and confirmed batch restore/delete actions.
- Reject restores whose selected trash record changed before confirmation; retain verified copies and the existing operation journal.
- Combine Info and permissions with library, collection, hidden-album and PIN links, version details, sound-source credits and Ko-fi.
- Show settings import/export controls directly inside Screens and tools → Advanced.

## 0.1.0-beta.13 — 2026-10-10

- Tap the larger, bordered zoom navigator to center the photo on that point; hide it with preview overlays.
- Rename Appearance to Theme in every supported language.
- Restore controller focus when entering custom theme controls and add visible focus indicators to discard confirmations.
- Remove the music toolbar Play button and its visibility setting.
- Disable category tool toggles when no corresponding media are available and explain the empty settings preview in every supported language.

## 0.1.0-beta.12 — 2026-10-10

- Keep the bottom navigation bar touch-only; shoulder buttons change tabs in the music library too.
- Keep favorites mosaic tile sizes stable while thumbnails load and focus moves.
- Keep preview overlays visible after adding a favorite and avoid navigation sounds when grid focus follows preview navigation.
- Add an album icon to cover previews and a soft theme-colored glow behind favorite hearts.

## 0.1.0-beta.11 — 2026-10-10

- Restore the current photo when its next neighbor becomes unavailable during a transition.
- Keep photo navigation responsive after a rejected transition.
- Slide zoomed and rotated photos fully out of view during edge navigation.

## 0.1.0-beta.10 — 2026-10-10

- Keep album cover overlays visible when tapping the preview.
- Animate photo edge taps with the same slide transition as dragging.
- Confirm leaving music and photo widget configuration with unsaved changes, using the existing localized modal and sound cues.

## 0.1.0-beta.9 — 2026-10-10

- Keep the outgoing photo offscreen until selection changes, avoiding a flash of the previous image.
- Keep gestures active while image dimensions finish loading.
- Resolve consecutive navigation commands from the live media selection.
- Use immediate cached images during photo swipes instead of a second fade.

## 0.1.0-beta.8 — 2026-10-10

- Show newly added favorites first, preserving existing favorites during the database upgrade.
- Reset preview rotation without animating the next image.
- Smooth the music widget pulse from its resting size and ease back on pause.
- Choose a widget starting track through a searchable list or cover grid.

## 0.1.0-beta.7 — 2026-10-10

- Acquire controller focus inside every settings page, including appearance, interface and controls.
- Highlight focused interface switches and retain category navigation.
- Show visual settings previews only in Appearance and Interface, using gallery tiles, the actual navbar and saved gallery view profiles.
- Remove generic second-panel descriptions from the other settings sections.

## 0.1.0-beta.6 — 2026-10-09

- Acquire focus on a settings category when controller navigation begins.
- Clearly highlight the focused category with a border and background; controller confirmation opens that page.

## 0.1.0-beta.5 — 2026-10-09

- Route controller commands to the upper settings panel even from unpaired entry points, and sound successful focus moves.
- Use multimedia volume for interface cues and raise their playback level, retaining preference and music mute rules.
- Flip photo widget transitions around the central vertical axis rather than rotating in the image plane.
- Lay out favorite media in a staggered mosaic using image proportions and square music artwork; preserve list view and selection actions.

## 0.1.0-beta.4 — 2026-10-09

- Choose a starting track independently for each music widget, or resume the last listened track.
- Add album cover titles, media counts and direct album actions; simplify dense album grids.
- Add left, right, up, down and rotation transitions to album photo widgets.
- Cycle normal, disc and pulse artwork in the music preview; unify playback modes and add a preview favorite button.
- Move music selection actions into the main toolbar and add favorites beside track names.
- Add interface sounds for favorites, settings and saves, respecting sound preferences and music playback.
- Fix refresh indicators triggered by non-touch scrolling and bound folder waits while preserving the last complete listing.
- Align the new widget strings across all supported languages.

## 0.1.0-beta.3 — 2026-10-09

- Open square music widget settings from the title and horizontal widget settings from the artwork; remove the gear and transparent appearance option.
- Open photo widget settings from an invisible top strip, leaving the rest of the image available for launcher long presses.
- Show single-photo choices in a grid and frame them using the widget's actual proportions; reset framing when its shape changes.
- Increase widget image resolution while retaining transparency and bounded native GIF animation.
- Add pressed feedback and a loading guard to music widget controls; leave cover long presses to the launcher.
- Ease the disc back to rest and move bar color editing into a dialog with a swatch beside its effect option.
- Smooth audio-driven widget bars with gradual settling on pause and a dedicated color wheel with brightness control.
- Offer two music widget layouts: full-cover square and horizontal; existing legacy squares retain their configuration and use the full-cover layout.
- Compact About with branding beside the icon, release date beside the version and a prominent support button.
- Hide music roots from Albums by default, with an Interface preference to include them.

## 0.1.0-beta.2 — 2026-10-09

- Use the newest available visual media as an album's automatic cover, preserving custom covers.
- Recheck chosen folders when a gallery panel returns to the foreground instead of retaining a stale browsing cache.

## 0.1.0-beta.1 — 2026-10-09

- Start the first beta release series; build 87 preserves in-place upgrades from development builds.
- Add a localized Ko-fi support button in About and update its date and channel.
- Existing hardware and launcher limitations remain open; beta numbering does not imply completed validation.

## 0.86.0-dev — 2026-10-08

- Album browsing now shows its cover before a visual selection in the current session, even when the previous photo is restored from preferences. Selecting a photo keeps it in the preview while browsing albums.
- Validation: 474 Windows tests passed; lint zero errors; updated in place on Thor and verified album cover on opening. Controller hover confirmation remains pending.

Development versions are prereleases. Dates use the maintainer's Europe/Rome calendar. Older internal iterations are summarized rather than represented as stable releases.

## 0.85.0-dev — 2026-10-08

- Use the fresh Room record for persisted documents instead of a potentially delayed catalog emission. Unpersisted directory results retain their overlay with freshly read access and visibility.
- Add regressions for delayed observation, hidden albums and revoked grants.
- Explain Android's Download/storage-root picker restrictions and guide users to an allowed subfolder in all ten languages.
- Serialize synthetic PNG creation/opening in the test provider and verify eight simultaneous readers receive complete images.
- Wait for Compose to launch the sharing effect before polling Android in the multi-selection render test; keep the existing timeout and file-access assertions.

Final Windows verification: 473 tests / 78 suites passed; lint 0 errors, 233 warnings and 1 hint; assemble succeeded. Physical Thor and Linux verification are pending. An intermediate swipe-preview failure remains open in [known issues](docs/KNOWN_ISSUES.md).

## 0.84.0-dev — 2026-10-08

### Repository and documentation

- Add product documentation, contributor/release guides and reproducible native screenshots with demo content.
- Package audio/video test fixtures so a fresh checkout can run the export/editor tests.
- Keep local workspace configuration, device captures and internal working notes out of version control.
- Add read-only clean-checkout verification and structured bug reports.

### Application

- Compact the upper browsing navbar and use a filled theme-color active indicator.
- Reduce search height/font size; vertically center songs without a blank artist subtitle.
- Keep group playback accessible from the music header; hide the photo-preview mini-player when paused.
- Hide touch screen-swap buttons by default, keeping X available and optional per-section visibility.
- Show neighboring photos while dragging, settle short gestures back and preserve pinch/pan behavior.
- Keep music widget titles on one line and route decorative taps to each widget's settings instead of the gallery.

Windows clean-checkout verification: 470 tests across 78 suites passed; lint 0 errors. Ubuntu verification exposed three failing sharing/folder-grant/photo-frame cases; diagnosis is tracked in known issues. Hardware limits remain in [known issues](docs/KNOWN_ISSUES.md).

## 0.83.0-dev — 2026-10-08

- Organize settings into clearer categories with advanced controls collapsed by default.
- Protect unsaved drafts when navigating back or switching control tabs.
- Keep paired settings previews and per-section toolbar configuration aligned.
- Clarify selected-folder access and system notification/usage-access status.

## Earlier development

Established selected-root libraries, paired display navigation, music playback, copy-safe editors, file operations/trash, widgets, album PINs, themes and ten-language localization. See Git history for individual changes; complete hardware validation remains ongoing.
