# Test media

These two small synthetic fixtures are used by audio/video editor, preview and export tests. They contain test segments/tones rather than personal media. Keeping them here makes a fresh checkout self-contained; there is no dependency on a developer's ignored `qa` folder.

- `audio-segments.m4a`: six-second synthetic segmented audio.
- `video-segments.mp4`: six-second 160×120 synthetic video with audio.

Fixtures are accessed relative to the app module's Gradle test working directory. Screenshots use separately drawn original demo illustrations in `DocumentationScreenshotTest`.
