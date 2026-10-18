# Online TV (beta)

TV starts disabled. Enable it in Interface and optionally place it in the navbar.
Two first-use notices explain authorized use and external network requests.
There are no bundled channels, providers, playlist suggestions or automatic catalog requests.
Previously saved personal channels are preserved.

Use + to add a channel by name and HTTPS stream URL. With an empty library, open
Browse and +; the playlist dialog also offers Add channel. In Browse channels, + adds
a personal HTTPS M3U/M3U8 playlist URL (up to 20 sources). Use trusted sources you
are authorized to access. Playlists are parsed without preparing or playing streams.
The existing HTTPS-only URL, DNS, redirect and size limits remain enforced.

With an empty search, Browse shows personal playlists. Open one to list its channels.
Typing a name searches directly across all personal playlists, independent of their
category or group. Fetches are cached and bounded to 20,000 channels in total;
no media playback starts from searching. A failed source is reported instead of
silently claiming a complete search. Saved channels and personal playlists are separate.

Playback uses the video coordinator and PiP. Saved-channel list/grid, favorite
confirmation, drag ordering and custom artwork remain available. No DVR, stream
recording, DRM removal or geoblocking bypass is provided.
See PRIVACY.md and TV_RIGHTS.md. This remains an experimental beta.
