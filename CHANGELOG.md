# Changelog

## 0.1.0-beta.126 — 2026-10-20

This update rounds out single-screen use and makes album ordering more deliberate.

- Reorder albums with a dedicated pair-swap mode: select two albums to exchange them, keep focus on the moved album, or press Back to leave the mode. Opening album actions no longer resizes its tile.
- On one screen, choose a vertical or horizontal album grid, or use the carousel. The carousel follows the screen orientation and keeps a clear focus on the selected album.
- The music player and queue now adapt their layout to portrait and landscape phones. Full-screen video, media selection and category navigation also behave more consistently on a single display.
- Add multiple media items by dragging across the library. Touch selection scrolls with the list, while albums retain their own reorder and selection behavior.
- Preserve the selected screen pair when changing between one-screen and dual-screen operation, and improve settings focus restoration after display recreation.
- Stabilize older settings imports and lifecycle tests, and update guidance for single-screen devices and album reordering.

Install the signed APK over the existing app to keep folders, preferences and favorites. The release includes the matching LGPL relinking kit and SHA-256 checksums.

## 0.1.0-beta.124 — 2026-10-18

First public beta of DS Gallery.

- Choose the folders included in your library and browse photos, GIFs, albums, videos, music and favorites across two screens.
- Use touch or controller navigation, independent section/album layouts, custom covers, themes and configurable shortcuts.
- Inspect images with zoom, pinned previews, floating comparisons and a GIF frame selector.
- Play music and video with separate volume controls, audio/subtitle selection, background music and video picture-in-picture.
- Add three resizable home-screen widgets: Icon Player, Compact Player and Photo frame. Compact Player supports a cover-only 1×1 layout.
- Share a selected folder over FTP or browse FTP/SMB shares through Tools. Online TV is optional and accepts user-provided sources; no channel catalog is included.
- Follow an eight-page setup tutorial, with ten interface languages.
- Keep music previews aligned with playback: browsing alone does not replace a playing track's preview; previous/next follows the new track, and paused browsing follows selection.
- Include GPL-3.0-only source, third-party notices and a matching LGPL reconstruction kit with every distributed APK.

See [known limitations](docs/KNOWN_ISSUES.md) and the [user guide](docs/USER_GUIDE.md). Earlier private development builds are not public releases of this repository.
