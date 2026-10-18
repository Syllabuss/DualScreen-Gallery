# Permissions and privacy

DS Gallery uses the folders you explicitly choose. Photos/videos and music have separate root lists. A chosen root grants access to its contents and subfolders; removing it from the library does not delete files. There is no automatic scan of every directory on the device.

| Access | Purpose | User control |
| --- | --- | --- |
| Android folder/document grant | Read selected roots; write only where the provider grants it | Choose/reconnect/remove roots in Library |
| Notifications | Music playback and supported background operations | System notification settings |
| Usage access | Recognize launcher/Home transitions for paired display lifecycle | Optional, managed in Android settings |
| Media/storage permissions declared for supported Android versions | Compatibility with existing media/document references | Android app permissions; not a substitute for choosing roots |
| Foreground service permissions | Music playback and ongoing supported processing | Visible playback/operation controls |
| Display over other apps | Up to two optional image comparison windows, independent of video PiP | Requested only when opening a comparison; close windows from the notification or disable comparisons in Playback |

Local catalog data caches the last completed listing and keeps stable references, favorites, collections and operation state. It is not a duplicate backup of the media library. Thumbnail/artwork caches can be cleared in **Trash**; original files are unaffected.

Album PIN protection is not file encryption, and covers remain visible. See the user guide before relying on it for privacy. Editors save copies; exports and Android sharing intentionally provide selected media to the chosen app.

No account is needed to use the installed gallery. Local media handling stays on the device; optional network sharing and online TV make the requests described here. Before submitting diagnostics, remove personal paths, media names and screenshots you do not intend to share.

Online TV is disabled by default. Enabling it exposes an entry point but does not automatically download catalogs or play streams. Browsing contacts only playlist URLs explicitly supplied by the user; visible logos contact their image hosts. No source directory is bundled. Explicit playback contacts broadcasters/CDNs for HLS manifests, segments and keys. These services see the public IP address and ordinary connection metadata. No account, tracking identifier, cookie jar or analytics backend is added. Stream URLs may contain access tokens and stay in private TV storage; do not share that private file as a diagnostic. Error text omits those URLs.

Saved TV channels and custom artwork use separate private storage. User playlist metadata has a bounded six-hour cache with offline fallback. Derived logos use an isolated cache capped at 20 MiB, evicted after seven days without use and included in Clear cache; custom thumbnail originals remain saved. Disabling TV preserves saved channels. Network access does not grant local file access or add device folders to the library. See [Online TV](TV.md) for limits.

Optional FTP sharing exposes only the folder explicitly chosen for sharing on your local network. It is disabled by default and starts only through the FTP page. A visible foreground notification provides Stop. A fresh password is generated per session; credentials are not exported or sent to a service. FTP traffic is not encrypted. The server allows downloads and new uploads, but no remote deletion or overwriting of existing files. Uploads are limited to 8 GiB each and reserve at least 128 MiB of internal storage while receiving, even for SD-card destinations. Private staging copies of failed verified uploads remain recoverable through the operation journal.

Image comparisons use a foreground service while their windows are open. They read only media already authorized for the library and retain scaled previews in memory; they do not export files. Removing access, hiding or locking the source album closes its comparisons. The preview pin is a temporary viewing control, unrelated to the album security PIN.

All-files access, when explicitly granted, lets the Android folder picker expose shared-storage root and Download. It does not add those directories to the library: only roots the user links are indexed. Android/data and Android/obb remain outside this provider. Usage access is optional and used to recognize launcher transitions, not to upload app usage. See [authorized TV use and rights reports](TV_RIGHTS.md).
