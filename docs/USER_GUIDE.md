# User guide

DS Gallery began as a personal project for choosing exactly which folders belong in my library and using both screens of my AYN Thor. It also works on single-screen Android phones, where browsing and playback use the same display. The interface can stay minimal or expose more tools and controller shortcuts. Music and video have separate in-app volume controls; audio tracks, subtitles and floating playback are covered below. See the [screenshots and widget recording](../README.md#see-it-in-use-y-swaps-the-screens) for a look at the app on its development device.

X opens actions for the current album or media on the preview display. The first available action is focused; use directions and A to choose, or B to close. Interface can move these menus to the browsing display. R3 cycles Video grid, list and folder views. The right stick can zoom an image from its normal size.

Drag the GIF strip's right handle to collapse or expand it. The strip stays anchored at the lower edge; collapse it fully to its recovery tab and tap to reopen it. Pause collapses the strip; Play restores its previous width, including after visiting another page. Image comparisons also support edge tabs and show remaining capacity above their icon.

The network explorer copies selected files to the other open pane after a confirmation. Move verifies the destination before deleting source files; original folders remain. Open both source and destination folders before starting. Interrupted batches and ZIP extraction can be resumed from Operations after reconnecting the original folders; see [verification limits](KNOWN_ISSUES.md).

## First launch

The first-launch tutorial introduces language, theme, controls and folder linking before opening Albums. Pick a folder whose subfolders contain the albums you want to see. Choosing `Pictures`, for example, shows its media subfolders directly in Albums. Music folders are configured separately.

Add or remove roots in **Settings → Info and permissions → Multimedia folders**. Removing a root disconnects it from the gallery; it does not delete its files. Empty folders are hidden by default; enable **Interface → Show empty folders** to include them. A folder with media in its subfolders stays visible and can use a descendant’s cover. By default, folders appear in a single list; enable folder browsing in Interface to navigate the hierarchy instead. Use the + button in the album toolbar to connect a folder; Android’s picker can create a folder if its provider supports this. Pull down in the browsing panel to refresh after adding files elsewhere.

An empty library shows a centered + tile on the browsing panel. It explains folder selection before opening Android’s picker; Continue is initially selected for controller use. Music has the same empty preview and folder action as the visual categories. Favorites instead explains that nothing has been marked yet and does not suggest adding folders.

Android's standard folder picker can restrict storage roots and the top-level Download folder. Continue uses the Download and full-storage picker, asking for Android all-files permission if needed. You still choose which folder to connect; granting permission does not add every folder to the library. Protected app directories remain excluded. A disconnected SD card or revoked folder grant can be reconnected without discarding saved media references.

On a single-screen device, Settings uses a list and the grid layout is unavailable. **Screens and backup → Force landscape orientation** optionally locks the view to landscape; it is off by default. Turning it off immediately restores the display’s native orientation (portrait on phones). Companion-screen dimming controls are hidden. These defaults do not change paired-screen operation.

## Browse and preview

Albums is the starting section. The navigation bar provides Photos, Albums, Videos, Music and Favorites; hold a section icon to reorder it. Selected icons use the active theme color.

Language and Theme shortcuts share the first row in Interface settings. Album previews can use edge shadows and an optional Studio frame: a checkerboard border that fades inward without covering the centre.

Use the list/grid icon to change view. In grid view, + enlarges thumbnails and − makes them smaller. Each media section remembers its own view and size. Photos and videos sort newest first by default; music sorts alphabetically. Inside photo/video views, timeline grouping can separate entries by day, month or year.

Favorites in grid view form a mosaic with image proportions and square music covers. List view remains available. Favorites are shown newest addition first; existing favorites from earlier versions keep a stable order because their original addition dates were not recorded.

Long-press a media item to select it. Long-press and drag an album to reorder it, including multiple selected albums as one block. Releasing an album without changing its position opens its options instead; a single moved album is deselected after the drop. Dropping on an album moves the selection to that slot; the live preview and saved grid use the same ordering, including mixed 1×/2× sizes. Newly discovered albums appear first. Selection replaces toolbar actions with an operations menu. Copy and move ask for a destination. Albums can be hidden but cannot be deleted from their actions menu. Individual media and media selections can be moved to Trash after confirmation. Favorites can be toggled with the preview heart. Album selection exposes Select all and Deselect all immediately beside the gear. For a single selected album, Choose cover lets you pick one of its photos or restore the automatic cover.

On Thor, the browsing and preview panels are complementary. Y swaps them while keeping the current album media and browsing position. The touch swap button is visible by default in every category; hide it per section in **Settings → Interface** if you prefer the controller shortcut. The upper browsing navbar uses less vertical space. Each section keeps its own browsing position during the session, and ordinary previews follow the focused item in its current sort order. Empty categories show their own placeholder. Preview pins and playing videos are the exceptions described below.

When hiding an album, selection moves to the next remaining album, or the previous one if it was last. If you enable only one navigation section, Interface offers a switch to hide the navigation bar; enabling more sections shows the bar again. Converted animations named `.gif.mp4` or `.gif.webm` remain navigable alongside images and GIFs.

## Controller and gestures

| Input | Default action |
| --- | --- |
| D-pad / analog stick | Move through the browsing panel |
| A | Open the focused item |
| B | Go back; never close the gallery |
| Y | Swap the panels |
| X | Open actions for the focused album or media item; opens Settings when nothing is focused |
| Square / Play top buttons | Smaller / larger gallery thumbnails |
| One finger on a normal-size photo | Drag to previous/next media |
| Two fingers on a photo | Zoom from 50% to 800% |
| One finger on an enlarged photo | Pan |
| Tap the preview center | Hide/show overlays and the mini music player |
| Double-tap a photo preview | Animate zoom in/out; below 100%, first restore the fitted size |
| Double-tap an album cover preview | Open the album, or request its PIN |

Change bindings in **Settings → Controls**. Touching the preview does not transfer controller navigation away from the browsing panel. Home should put both gallery panels behind the launcher; game overlays and Recents behavior should be checked against the [launcher limitations](KNOWN_ISSUES.md).

## Photo frame

Press L3 (with the default controller mapping) to open photo-frame setup. Choose one album or Favorites, continue to set the interval and transition, then press Play. On a paired device, setup and playback controls stay on the browsing screen while photos and GIFs play on the preview screen. Pause from the control panel or tap the slideshow. Opening another page, hiding the panel or enabling power saving suspends the timer. Hidden and protected media are excluded.

## Music and widgets

Use the dropdown to switch between tracks, albums, artists, folders and playlists. Tap a song once to select it without interrupting playback. While music is playing, the full preview stays on that song; selecting another only changes the browsing focus until you activate it. In pause the preview follows the selected song. Previous/Next track shortcuts update the preview and selection together. Tap the selected song again, or press A on it, to start it; activating the currently playing song pauses it. Playing songs rotate their cover in list view and pulse it in grid view. Playback → Music offers theme colors, a full-screen cover backdrop with matching accents, or exact custom background/bar colors. Without artwork, cover mode falls back to the theme. The preview panel contains the full music player; while browsing photos, a small draggable player appears only during playback and hides with other overlays. Playback can also be controlled from the notification and continues when returning to the launcher. Drag the mini player beyond either side to park it; double-tap its right edge to park it on the right. Tap the full player artwork to cycle normal, pulse, disc with a centre hole and disc without a hole. Choose the saved effect in Playback → Music. Its mode button cycles normal order, shuffle and repeat one; the heart adds the track to favorites.

Tap the speaker at the bottom right of the music player to reveal its vertical volume slider. The percentage appears above it; after five idle seconds the slider hides again. This adjusts playback volume independently of Android's multimedia volume.

Playback → Music and Playback → Video each offer **Continue with device closed**, disabled by default. Normally, sleep pauses active playback and does not restart a song previously interrupted by a video. Enabling an option preserves playback that is already running; it never starts a paused item. Video and live TV can keep decoding and using the network, increasing battery use, heat and possibly fan activity. External-display behavior also depends on the device firmware.

Add widgets from your launcher's widget picker. Music widgets follow the system appearance, offer playback effects (including a centered coin flip) and a starting-track choice per widget: resume the last listened song or choose a specific track. The track picker searches file names, titles, artists and albums and switches between list and cover grid. Without a previous song, Play uses the first available alphabetical track. Previous, Play/Pause, Next and mode controls operate without opening the gallery. Tap the title on a square widget or the artwork on a horizontal widget to open its settings.

Lightweight skeleton placeholders appear during initial widget creation and album-photo loading. Normal refreshes keep the last rendered widget. The launcher controls when it creates or displays a widget, so returning to Home can behave differently across launchers.

Photo widgets use an album or one image. Choose an interval and a fade, directional slide or rotation transition for an album, or select a single image from the grid and frame it to the widget's actual proportions. Changing its shape resets the framing. Their background is transparent with rounded image corners. Tap the invisible strip across the top to open settings; long-press the rest of the image to reposition it. GIF animation uses bounded frames and falls back to a still image for oversized files. Long-press positioning and controller focus inside a widget are controlled by the launcher; iiSU currently focuses the whole widget.

## Editing and file operations

Photo editing offers crop, rotation, flips and filters. Audio/video trimming lets you set the interval and scrub the preview; video supports cropping/resizing. On a paired Thor, editing controls occupy the lower display and the upper display shows the current frame or edited image. The trim handles update immediately; the playback cursor and Play remain inside that interval. Save opens Android’s folder picker, starting near the source when the provider permits it, and exports a new copy without replacing the original. Video can export its selected audio as MP3; audio editing offers M4A or MP3. Provider capabilities determine whether a source can be moved or removed.

Open **Settings → Tools → Trash**, or use the trash shortcut on the settings header. The Tools sidebar also opens Find duplicates, Share folder and Explore devices. L1/R1 (or the remapped previous/next section buttons) cycle these four pages in either direction. Select items to restore or permanently delete them; their previews show on the companion panel. Trash defaults to six columns, with sizing from four to eight columns. Restore progress appears over the affected image.

Trash lists the most recently deleted items first. Find duplicates is experimental: review the proposed matches before deleting anything.

When no folders are connected, the first-launch tutorial appears on the upper display. Eight steps cover welcome, language and theme, general shortcuts, images, video, gestures, folders, and a final welcome. L1/R1 or Previous/Next move between pages. Skip tutorial is available until the final page, where Let’s begin opens Albums. Language follows the device unless you choose an override; the four base themes are available during setup. Linked folders appear as square thumbnails in the folder step. Android permission guidance offers access to Download and storage roots without adding any unchosen folders to your library. The other display shows a non-interactive multilingual welcome during setup. You can change these choices and add folders later in Settings.

Trash items remain available for seven days unless permanently deleted. Interface can optionally show a direct-delete action, hidden by default. Both deletion flows ask for confirmation and highlight favorites; direct deletion cannot be undone from Trash. Copy/move destinations use a two-column folder picker, including a guided new-album option. Copy/move operations verify the destination before deleting the original.

## Album protection

Set the shared four-digit PIN in **Settings → Info and permissions → Album PIN**. Use an album's menu or album selection menu to protect/unprotect it. Unlocking requires the PIN; Home and restart end the unlocked session. Covers remain visible with a lock.

This is an in-app access lock, not encryption. Other apps can still access the original files. Writable folders use a `.ayngallery-album-lock.json` marker without the PIN; read-only providers keep the lock privately in the app. Removing a marker externally and refreshing removes its protection.

## Interface and language

Settings controls respond to the controller, with visual previews on the companion panel in Theme and Interface. When you reach the last control in Playback or Controls, press Down again to reveal the remaining notes and bottom spacing. In Controls, L1/R1 switch between Buttons and sticks and Touch gestures; X opens the guide. Sounds shows a trace of the cue being previewed on the companion panel; other sections use their relevant guidance. Preview tiles and the navbar use the same components as the gallery and respect saved view profiles. Interface sounds use multimedia volume and remain muted during music or video playback, including video buffering. Video pause, completion or closing the player releases the video mute.

Theme and Language are the first links in Interface. Settings starts with three-column grid navigation and also offers a list, section visibility, preview and playback options, controls, per-cue sounds, Screens and backup, Trash, and Info and permissions. Save changes using the top-right save icon. Going back with unsaved changes offers a discard decision.

In **Interface → Theme**, keep a plain background, choose a vertical gradient, or select an image from the authorized library. A gradient starts with the theme background and fades toward the chosen color; intensity adjusts the blend. Image opacity controls how strongly it appears behind browsing controls. Save applies the change; preview panels keep their own media background.

Languages: Italian, English, Russian, Korean, Simplified Chinese, Japanese, German, French, Brazilian Portuguese and Spanish. Android 13+ also supports the system per-app language setting.

## Settings files

Settings are private to the Android app. General interface preferences are stored in `/data/user/0/it.ayngallery/shared_prefs/ui_preferences_v1.xml`; launcher widgets and display/session preferences have separate private stores. These files are managed by Android and are not ordinary folders in shared storage.

Use **Settings → Screens and backup → Export settings** to create the clearly named JSON document offered by the picker. Portable exports contain preferences, not media, folder permissions or the operation journal. Importing an older supported export preserves compatibility; folder access must still be granted on the destination device.

## Launcher icon and paired settings

In **Settings → Interface**, choose the light or dark Android icon. The light icon is the default; Android launchers may take a moment to refresh it.

With both displays active, the settings home places **Info and permissions** on the companion screen. About shows the build, version and date together; support and Android details stay on the upper screen, while folders and permissions use the companion screen. In single-screen mode these controls remain on the same page.

Above the credits, **Clear cache** removes derived previews. **Reset app** asks for confirmation before removing app data, preferences and folder links. Its destructive confirmation stays disabled for five seconds. Files in linked folders are preserved. The app closes after resetting; reopen it to start with the tutorial. Reset is unavailable while file operations or cache cleanup are running.

The reset confirmation appears on the lower details display when both panels are available. Controller Back dismisses the confirmation while keeping Info & Permissions open. Failed or interrupted operation history does not disable reset; active or queued transfers and cache cleanup do. Cache cleanup reports its result through a short toast rather than adding text below the buttons.

The trash previews the most recently deleted file when opened, then follows focus or selection. Duplicate search shows one thumbnail per group, six per row; open a group to compare its files and choose a copy to keep. No file is removed automatically.

Sound previews play one cue at a time. Press Stop to end a preview; other preview buttons remain disabled until it ends. Clear cache is in the Trash toolbar and does not remove library files or folder permissions.

In the video player, the translate icon beside CC opens audio-track selection. The speaker beside the elapsed time adjusts video-player volume independently of Android multimedia volume. During video playback, A toggles play/pause. While the video plays, B navigates the library normally. After pausing with A, B closes video playback and returns to the preview of the currently focused item, without also leaving its folder. Previous and Next continue playback in the current gallery sequence. The moon button dims the browsing panel; touch that panel to restore it. Music uses a foreground media service so leaving the gallery should not interrupt playback; force-stopping the app also stops its service.

While a video is open in the player, browsing or touching another video only changes the library selection. Press A to play that selection; if it has a saved position, choose Resume or Restart. Y moves the existing player to the other panel without resetting its position or pause state. The player's Previous/Next buttons follow the playing video's sequence, independently of the item highlighted in the library.

During video playback, the left stick adjusts video-player volume up/down and seeks left/right. Return it to centre before changing axes. Holding it horizontally repeats a jump every half second: 5, 10, 10, 30, 30 and then 60 seconds each. Quick flicks and double taps in the central left/right side bands use the same sequence. The top, bottom and centre remain available for playback controls. Changing direction or waiting over one second resets the next jump to 5 seconds. A brief indicator shows the signed jump beside the video. The D-pad still browses media. Playback settings can disable these stick controls.

Use the **frame-corners** button in the bottom player bar to open Android's floating Picture-in-Picture window. The video continues while browsing other gallery media or settings and while returning to Home or using another app. Tap the window to show Android's pause, full-screen and close controls; drag it to move it. Window sizes follow Android's native limits. If unavailable, check the app's Picture-in-Picture permission in Android settings. Automatic converted-GIF previews retain their still thumbnail while the shared video player is in this window. Available **CC** subtitle controls sit immediately to its right, before player settings. The video also has Android media-notification controls: pause/play affects the same video, including its floating window. A pauses playback. B then closes it without leaving the current folder; during playback B remains a gallery navigation action.

To keep iiSU's Home music or another app's audio alongside the video, enable **Playback → Video → Share audio with other apps**. You will hear both sources; their own controls remain independent. This option starts disabled, preserving normal Android audio-focus behaviour for users who want other media to pause the video.

In Sounds, use the customization icon to import a WAV file lasting at most six seconds. The app validates and stores a private copy; the source stays untouched. The trash icon removes that customization after confirmation and restores the bundled sound. Custom sound previews use their actual waveform.

Photo-frame setup preserves the current display roles. Its preview uses the theme background behind transparent images and GIFs. Drag the lock badge on a protected album preview to open the PIN prompt on the browsing panel; the green cue does not unlock the album without a valid PIN.

A running video stays on the preview panel while you browse Albums, Photos, Videos or visual Favorites. Moving controller focus does not replace it; activating another media explicitly does. Music, network tools and settings show their own content. Keep playing determines whether the video continues in PiP or pauses; returning restores the same video and position. While playing, B remains a browser action; when paused, B closes the player without also leaving the folder. The resume prompt offers A to resume, X to restart and B to cancel.

The duplicate finder keeps search controls on the settings display and places matching groups on the companion display. With one display, controls and results share the same page. Playback settings let you disable left-stick video volume and seeking. While the video is playing, B keeps its normal browser action; once paused, B closes playback without also navigating back. Protected albums stay closed until the PIN is accepted.

PhotoFrame uses the theme background on its full-screen preview. During playback, its controls dim after ten seconds without interaction. The moon dims them immediately; touch the dimmed display to wake it. Video stick controls are explained in Playback settings.

Image overlays stay visible by default. Enable automatic hiding in **Playback → Images** to fade them after five seconds without touch input; an open media action menu suspends this timer, and changing images does not restart it. A single tap anywhere on the image hides or restores the controls; only a swipe changes media. Double-tap zoom hides the controls, and a hidden overlay stays hidden while browsing other photos. Album-cover overlays stay visible.

The custom theme colour picker places the wheel beside saturation and brightness controls on landscape displays. Its HEX field accepts a colour such as `#FFFFFF`; the copy icon copies that value to the clipboard. Image editing offers icon buttons for rotation and mirroring, plus upper/lower-screen crop presets that use the connected displays' current dimensions. Music albums remain in the Music section.

For image backgrounds, use the grouped opacity, X, Y and zoom sliders in Theme settings. Position and zoom are saved with your preferences and included in exported settings. Dense album and photo grids use tighter spacing and smaller corner radii.

PhotoFrame defaults to five seconds and a vertical-axis rotation. After choosing an album, press A to continue. Pause playback to change its interval or transition effect; the moon control is in the PhotoFrame header.

In Interface, use the arrows below each category for quick navbar ordering and choose a default starting section. If that section is hidden, startup uses the first enabled section in Album, Photos, Videos, Music, Favorites priority. Changes apply when saved. Theme and Interface previews begin on Album; L1/R1 cycle enabled categories using your library tab mappings.

During group selection, only checked tiles have a selection outline. The previous focus outline returns when selection ends. The first-run guide includes X for contextual actions/settings and L3 for PhotoFrame.

Library sorting is touch-only by default. Enable **Interface → Controller sorting** to include the sort label and reverse arrow in stick/D-pad navigation. The directional grid-preview option includes a description of the slide/fade alternatives.

In the saved-video dialog, **A resumes**, **X restarts** and **B cancels**. While the video plays, B navigates the gallery. After A pauses the video, B closes it and shows the currently focused preview without leaving the folder.

Selecting another media item, changing sections or using directional/shoulder navigation while the resume dialog is open cancels that pending attempt. A then acts on the current selection. The zoom navigator also animates GIFs while the preview panel is active; its rounded viewport outline remains available for positioning the enlarged image, and its border uses the active theme colour.

Tap the in-app music cover to cycle between normal square, pulse square, disc with a centre hole and disc without a centre hole. The selection is saved. A paused pulse cover rests midway between its smallest and largest sizes.

The GIF overlay can show the actual current frame and total at a stable width. Enable **Playback → Images → Frame selector** to show a compact horizontal filmstrip: tap a frame to pause there, then use Play to resume from it. Hiding the overlay leaves the GIF paused. Drag the arrow-shaped handle on the right to make the strip longer or shorter; its width stays within the space beside the zoom navigator. The strip, counter and zoom navigator hide with the other overlay controls; the navigator shows the same frame as the main preview. Only visible strip thumbnails are decoded and retained in a small memory cache. Very large or unsupported GIFs fall back to ordinary playback and a total-frame count.

During video playback, the navigation header has a centred moon while the screen is awake and search is closed. In **Playback → Video**, optional automatic dimming offers 5, 10, 30 or 60 seconds and five minutes; it is Off by default. Touch the navigation panel to wake it and restart the idle interval.

## Preview pin and comparisons

Ordinary previews follow the album or media under focus. Entering an album shows its focused media; returning to the album list shows its cover. An open video player retains its paused timeline until closed; ordinary, unplayed video previews follow browsing focus. In **Interface**, animated GIF album covers can be enabled or disabled separately from gallery GIF playback.

Enable **Interface → Preview → Preview pin button** to show the pin beside the preview options. It can hold a photo or GIF while you browse other images and albums. Music, FTP and settings temporarily show their own page; leaving them returns to the pinned image. Pressing A on a different visual media asks whether to remove the pin and open that item: A continues, B cancels. Tapping the pin again releases it and shows the item currently focused in the browsing panel. It is session-only and cannot pin videos.

The optional comparison button opens an image-only floating window. Android asks for **Display over other apps** permission on first use. Up to two comparisons can be open: drag with one finger, resize with two. Tap the image to show or hide its close X. The same image’s comparison button also closes it; the notification closes all comparisons. GIF comparisons animate. Windows open on the preview display and can move across its full area. Drag beyond a side edge to park a window as a tab; tap it or swipe inward to restore it. They can coexist with video PiP but cannot open videos.

**Playback → Video → Keep playing** keeps a running video in Android PiP while opening Music, FTP or settings, and on Home. Returning to a visual browsing page restores automatically opened PiP to the preview. PiP explicitly opened with the player button stays floating until you bring it back; other focused videos keep their own previews. With Keep playing off, temporary pages pause the same video instead. There is no separate butterfly button.

Tap a video once to select its preview; tap the selected video again to start it. The player’s bottom row provides PiP, subtitles, audio tracks and settings. Settings contains speed and playback order: single playback (pause on the final frame), repeat one, sequential or shuffle through the folder without repeats until every video has played. The preview overlays hide and reappear with the transport controls. Closing Android PiP keeps the video paused at that position without changing the current gallery/settings section. Android owns the PiP menu and its permissions gear; playback settings are available in the full player.

Video folders and cover frames
-----------------------------

The folders button next to the timeline controls in Videos switches between all videos and folders containing videos. Opening a folder retains the ordinary video grid/list; B returns to the folder overview.

In a video's actions, Set current frame as cover appears above Choose cover frame while that local video is open, playing or paused. It uses the playback position at the instant you press it. Choose cover frame opens a timeline and frame preview. Save stores its position in the video; the generated thumbnail stays in the private artwork cache and can be removed with Clear cache. No image is added to your media folder. A video can also be selected as an album cover.

Selected albums offer a size of 1× or 2×. A 2× album occupies two columns and two rows, with smaller albums filling the space alongside it. Controller navigation follows those positions. These local layout choices and video-frame positions are not part of the portable preferences export.

Photo and Video grids keep their own session position rather than jumping to an item previously opened in an album. An album aligns with its previewed media only when that media belongs to it; otherwise it starts at the top. On the album overview, B dismisses the open media preview and restores the album cover.

FTP sharing
-----------

Open **Settings → Tools → FTP**. FTP is always available here, alongside Trash and Duplicate Finder. Choose the folder to share, connect both devices to the same Wi-Fi network and press Start sharing. Use the displayed address, username and password in an FTP client on the computer or phone. In dual-screen mode, the upper display has sharing controls and the other display explains connection details. Transfers opens the current session’s scrollable history on the upper display and is disabled when sharing stops. Address and password have separate copy buttons; Stop sharing ends the session. The same information remains accessible in single-screen mode.

The client can download files, create folders and upload new files inside the chosen folder. It cannot overwrite, rename or delete existing files. Uploads are staged privately, copied to the provider and verified before success is acknowledged. An interrupted unverified upload is discarded; a verified copy is retained in the operation journal if publication fails. Hidden files are not exposed. Receiving new media also requests a library refresh for configured roots.

Sharing is off by default and never starts automatically. Stop it in the FTP page or its ongoing notification. Credentials change each time sharing starts and are not part of settings exports; the folder permission is device-local. FTP is unencrypted, so use a trusted local network. This implementation uses IPv4 Wi-Fi/Ethernet and passive FTP; resuming partial transfers and FTPS are not supported. Android may stop a long-running data-transfer service according to its foreground-service limits.

The compact music player can be parked beyond either side edge; its recovery tab restores it. The GIF strip stays fixed and collapses with its right handle. Hiding preview controls hides these tabs too. Interacting with these overlays does not change the focused photo or GIF. Android manages video PiP’s placement and edge behavior.

The video-cover picker uses a wide, muted video preview with exact seeking while moving the slider. Current and total times sit above the slider. Only the chosen timestamp is saved; moving the slider does not generate cached covers. Changing an album to 1× or 2× finishes and clears the album selection.

When an image or GIF is enlarged, the left stick pans within it and the right stick up/down adjusts zoom. The D-pad keeps browsing media; leaving zoom restores ordinary stick navigation. Album covers and video controls retain their existing behavior.
## Explore devices (beta)

Open **Settings → Tools → Explore devices**. Share folder and Explore devices have separate sidebar buttons. Browsing does not add folders to the gallery; copying, moving and extraction require explicit actions and confirmation.

**On this device** opens Android's folder chooser. **Computer** accepts a shared-folder address such as `\\PC\Photos` or `smb://PC/Photos`, your account name and password. Both devices must be on the same network and the folder must already be shared on the computer. Use the account password rather than the Windows Hello PIN. Passwords are not saved. The client requires SMB2/3 with signing; SMB1 is unsupported.

The chosen local folder is remembered and reread only when opening the explorer. The connection form retains the last attempted share address and username across tab changes, including failed attempts. Example values must be replaced with your computer’s details. The password is cleared when the form closes and must be entered again. Nothing is automatically shared or searched on the network.

On two displays, each pane keeps its folder and selection. **Control here** chooses the pane for the controller; A opens a folder or selects a file, and B goes back. On one display, switch between device/computer tabs. Previews, copying, verified moving and ZIP extraction are available. Interrupted batches retain a recovery plan; reconnect the original folders to resume. Windows share browsing and a small transfer have been exercised on Thor; server configurations and interrupted-transfer recovery still need case-specific checks.


For sharing and recovery checks, see [Network files](NETWORK_EXPLORER.md).


Album PIN: B removes the last entered digit; with an empty PIN, B closes the dialog. Pinning a preview and releasing it on the same media preserves its zoom and pan. On release after browsing elsewhere, the newly focused media is shown.

Video folder tile sizes and zoom levels are independent of Albums. In the cover-frame picker, held directional input advances one second per hit, then ten seconds after three seconds held and thirty after eight. Changing direction or releasing restores the one-second step.

Image/GIF comparisons are Android overlay windows and may remain visible on Home and over other applications after granting the overlay permission. Up to two are allowed. Some applications and system screens may restrict overlays. Music controls and the GIF filmstrip belong to the gallery preview instead; their dock position is retained during navigation and reset after closing the gallery.

### Pixel-art previews

Open the image or GIF preview options and enable **Sharp pixels (Nearest-neighbor)** to scale without smoothing. The standard filter remains the default. The choice is remembered and included in settings backups; neither option edits the original file.

### Playback categories and media options

Playback keeps shared options above its Images, Video and Music tabs. Edits remain drafts while switching tabs and apply with Save. Display options at the bottom of a media action menu opens its matching tab. Album cover actions are available in Albums, including its album previews; they are omitted in Photos. The settings header provides one list/grid toggle and a direct Trash shortcut; Tools still includes Trash, Duplicate Finder and FTP.

Reduced motion reduces app transitions and disables animated player effects; GIFs and widgets are unaffected. Controller sorting only allows stick/D-pad focus on the sort criterion and reverse-order control; it does not automatically reorder media. The current GIF frame counter depends on the total-frame label; the frame selector remains independently available. Saved choices are preserved when a parent option is disabled.

There are exactly three launcher widgets: Icon Player (2×2), Compact Player (3×1) and one resizable photo widget. Compact Player can shrink to 1×1, showing only the cover as a play/pause button. The separate Coin and legacy square providers have been removed; replace their old launcher instances with either remaining music widget. Playback effects, including coin flip, remain available. Bars automatically use colors from the album cover, with a theme fallback when no artwork is available. The chosen starting track and shared player remain unchanged.


In **Playback**, L1/R1 changes the Images, Video and Music tab. Focus a slider and move left/right to adjust it directly. The settings home opens without a focused category; returning from a page restores that category.

**Interface → Animated album GIF covers** animates eligible visible covers up to the chosen limit (1–40), including the settings preview. Disable it for still covers.

Opening **Sounds** pauses a playing music track. Leaving resumes that same track only if playback has not otherwise been changed. Comparison resize/close controls hide after ten seconds without interaction and when another window is touched.

### Settings and playback continuity

On a freshly opened settings home, the first A, D-pad or stick input only highlights Interface. Further inputs navigate or activate it. Returning from a settings category restores that category. Playback tabs and their options scroll together as one page. Sliders display a focus outline and respond to left/right; Info and permissions can be scrolled down to its credits with the controller.

Opening Sounds fades the music out before pausing it. Leaving fades the same song back in, unless another transport action, track change or active video has superseded it. Opening Music while a song is playing focuses that song in the track list.

Video remembers the folder and focused item for the current app session. Resume is offered only for progress saved within the last seven days, away from the beginning and end. Reaching the end clears resume progress. With Keep playing enabled, a playing video moves to Android PiP on Home and returns to its original preview panel when the gallery reopens. L1/R1 navigation wakes a dimmed navigation screen.

### Transparent image backgrounds

Under Playback → Images, choose the existing checkerboard, a tinted checkerboard, a solid color, a top-to-bottom gradient or a center-to-edge gradient. The example updates while editing. Tint has an opacity slider; gradients have two color choices. Save applies the draft, and the choices are included in settings exports. Source images and video backgrounds are unchanged.

The GIF filmstrip is fixed at the lower edge. Drag its right handle to change width; collapse it to its edge tab from the minimum width. The music overlay and its tab keep a margin from the strip and navigator.

Music preselects the last listened track, or the first song in the current list when none is available. This does not start playback. In **Playback → Music → Player colors**, keep the theme palette, extract colors from the current cover, or choose background and bar colors. Cover colors fade between songs; missing artwork uses the theme. Gradients adapt to light/dark themes and preserve readable controls.

Playback settings show read-only examples for Images, Video and Music; the video is static and music is only a visual simulation. Three-finger downward swipes dim the touched gallery panel even without playback; tap it to wake it. The default is added without replacing an existing custom downward gesture. Interface can enable the Android status bar on the upper screen; gallery system bars are otherwise hidden.

Starting a different video while another is playing asks for confirmation. The current video pauses during the decision and resumes if cancelled. “Do not show again” disables the prompt; Playback → Video can enable it again. Returning to the gallery from Home while PiP is active preserves an open settings page and its unsaved draft.

### Download and the whole internal storage

In Add folder, press **Continue**. If all-files access is missing on Android 11 or later, Android asks you to grant it. Return to the picker, which opens **Internal storage**. If access was already granted, Continue opens the picker directly. Select the entire shared-storage root or Download and confirm **Use this folder**. Only the selected folders are added; granting permission alone does not scan your storage. Android/data, Android/obb and private app folders are excluded; shared media in Android/media remains accessible. On older Android versions, the standard folder picker is used. The chosen Photo/video or Music role is retained. Revoking broad access makes these sources unavailable until it is restored.


### Interface controls and defaults

All five categories, including Favorites, have independently configurable toolbar controls. These can be edited before adding media or favorites; an unavailable preview does not disable the settings. New installations show the screen-swap control in every category. Existing toolbar choices remain saved. Playback options are grouped by images, videos and music; changing settings never changes media files.

The author credit in Info and permissions opens Syllabuss on Telegram. Ko-fi remains the support link.

The FTP address has a copy button beside it. Paste that address into a compatible FTP client on your phone or computer, then use the displayed username and password. The copy button does not include the password.


## Online TV (beta)

TV is disabled by default. Enable it in **Settings → Interface** and optionally show it in the navbar; otherwise open it from Videos. Both first-use notices must be acknowledged. DS Gallery includes no channels, source directories or playlist recommendations and grants no broadcast rights.

Open **Browse channels → + → Add playlist** and enter a name and an HTTPS M3U/M3U8 playlist URL you are authorized to use. Up to 20 personal sources are supported. The same dialog offers **Add channel** for an individual HTTPS stream. Previously saved personal channels are preserved. Remove a playlist with its trash button; this does not remove channels saved separately.

With an empty search, browse your playlists and open one to see its channels. Typing a name searches channels across all your personal playlists, regardless of category or group. Importing and searching do not start playback. A failing source produces an error rather than silently presenting the search as complete.

Use list/grid or R3 to change view. Focus or tap once for a preview; A or a second tap starts the channel. The pin saves or removes a channel from My channels; the separate star in its preview marks it as a favorite. Removing a favorite keeps the saved channel. Existing saved channels retain their previous favorite state. Saved channels can be reordered by dragging and customized with artwork. Favorite changes ask for confirmation. L2/R2 step through saved channels, with selection following playback; switching is gated while a channel loads, with a 20-second startup deadline. Playback, dimming, audio and PiP use the existing player.

Use only official, trusted sources authorized for your location. A public URL does not establish permission. Playlist, logo and stream hosts receive your IP address; the app does not bypass DRM or territorial restrictions. See the [TV guide](TV.md), [privacy notice](PRIVACY.md) and [source rights](TV_RIGHTS.md).

### Individual album views

Each album remembers its own grid/list view and icon size. Video folders keep separate view preferences. Hold an album and release without reordering it to open its options; moving it changes the order instead. Adding a TV favorite is immediate, while removal asks for confirmation. Selecting a different song updates its preview without interrupting the current track.

There is one resizable photo-frame widget, which can shrink to 1×1. It supports either one photo/GIF or an album slideshow through the same configuration; different launcher size previews do not represent different photo-widget implementations.
