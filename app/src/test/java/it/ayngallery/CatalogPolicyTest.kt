package it.ayngallery

import org.junit.Assert.*
import org.junit.Test

class CatalogPolicyTest {
    @Test fun onlyExplicitRootMembershipMakesAlbumsAndMediaVisible() {
        val selected = AlbumEntity("child", "doc:child", "content://test/child", "Selected", "Selected", null)
        val outside = selected.copy(id = "outside", documentKey = "doc:outside", name = "Outside")
        val outsideMedia = image.copy(id = "outside-image", albumId = outside.id)
        val linked = data(source.copy(role = "VISUAL", treeUri = "content://it.ayngallery.sharedstorage/tree/shared%2FDownload"), listOf(selected, outside)).copy(
            media = listOf(image, outsideMedia), membership = listOf(MediaSource(image.id, source.id, 1)),
            albumMembership = listOf(AlbumSource(selected.id, source.id, 1)))
        assertEquals(listOf(image.id), linked.visibleMedia.map { it.id })
        assertEquals(listOf(selected.id), linked.visibleAlbums().map { it.id })
        assertTrue(linked.copy(sources = emptyList()).visibleMedia.isEmpty())
        assertTrue(linked.copy(sources = emptyList()).visibleAlbums().isEmpty())
        assertTrue(linked.copy(sources = linked.sources.map { it.copy(enabled = false) }).visibleMedia.isEmpty())
    }

    @Test fun lockedAlbumCoverUsesManualChoiceOtherwiseNewestWithoutUnlockingMedia() {
        val album = AlbumEntity("child", "doc:child", "content://test/child", "Private", "Private", null)
        val latest = image.copy(id = "latest", modified = 100)
        val visualSource = source.copy(role = "VISUAL")
        val locked = data(visualSource, listOf(album)).copy(media = listOf(image, latest),
            membership = listOf(MediaSource(image.id, source.id, 1), MediaSource(latest.id, source.id, 1)),
            protectedAlbumIds = setOf(album.id))
        assertEquals(latest.id, locked.lockedCover(album)?.id)
        val manual = locked.copy(albums = listOf(album.copy(coverMediaId = image.id)))
        assertEquals(image.id, manual.lockedCover(manual.albums.first())?.id)
        assertTrue(manual.visibleMedia.isEmpty())
        assertFalse(manual.usable(image))
        assertEquals(2, manual.albumMediaCount(album.id))
        assertEquals(0, manual.copy(sources = listOf(visualSource.copy(enabled = false))).albumMediaCount(album.id))
        assertEquals(1, manual.copy(media = listOf(image.copy(trashed = true), latest)).albumMediaCount(album.id))
    }

    @Test fun playlistDocumentsAreNotSongsEvenWhenTheProviderCallsThemAudio() {
        for (extension in listOf("m3u", "M3U8", "pls", "xspf"))
            assertNull(MediaNames.kind("audio/mpeg", "My Favorite.$extension"))
        for (mime in listOf("audio/x-mpegurl", "audio/mpegurl", "application/vnd.apple.mpegurl", "audio/x-scpls"))
            assertNull(MediaNames.kind(mime, "playlist"))
        assertEquals(MediaKind.AUDIO, MediaNames.kind("audio/mpeg", "song.mp3"))
        val cachedPlaylist = song.copy(name = "My Favorite.m3u", mime = "audio/x-mpegurl")
        val cached = data().copy(media = listOf(cachedPlaylist))
        assertTrue(cached.visibleMedia.isEmpty())
        assertFalse(cached.usable(cachedPlaylist))
    }
    @Test fun protectedContainerRemainsBrowsableButDescendantsFavoritesArtworkAndStreamsAreBlocked() {
        val parent = AlbumEntity("parent", "doc:parent", "content://test/parent", "Private", "Private", null)
        val child = AlbumEntity("child", "doc:child", "content://test/child", "Child", "Private/Child", "parent")
        val locked = data(source.copy(role = "MIXED"), listOf(parent, child)).copy(
            albumMembership = listOf(AlbumSource("parent", "source", 1), AlbumSource("child", "source", 1)),
            protectedAlbumIds = setOf("parent"))
        assertTrue(locked.albumLocked("child"))
        assertEquals(listOf(parent), locked.topAlbums())
        assertTrue(locked.childAlbums("parent").isEmpty())
        assertTrue(locked.visibleMedia.isEmpty())
        assertFalse(locked.usable(song))
        assertFalse(locked.artworkAllowed(image))
        assertNull(locked.sourceFor(image, artwork = true))
        assertNull(locked.collectionCover(CollectionEntity("collection", "Favorites", "COLLECTION", coverMediaId = image.id)))
        assertEquals(image, locked.lockedCover(parent))
        assertTrue(song.favorite)
        val unlocked = locked.copy(unlockedAlbumIds = setOf("parent"))
        assertEquals(listOf(child), unlocked.topAlbums())
        assertTrue(unlocked.usable(song))
        assertTrue(unlocked.artworkAllowed(image))
        assertTrue(unlocked.copy(unlockedAlbumIds = emptySet()).visibleMedia.isEmpty())
        // Fresh, small stream snapshots can have only an ancestor relation, not all parent rows.
        assertFalse(locked.copy(albums = emptyList()).usable(song))
    }
    private val image = MediaEntity("image", "doc:image", "content://test/image", "child", "image.png", "image/png", "IMAGE", 1, 1)
    private val song = MediaEntity("song", "doc:song", "content://test/song", "child", "song.mp3", "audio/mpeg", "AUDIO", 1, 1, favorite = true)
    private val source = SourceEntity("source", "content://test/tree", "Music", "MUSIC")
    private fun data(source: SourceEntity = this.source, albums: List<AlbumEntity> = emptyList()) =
        CatalogSnapshot(listOf(source), albums, listOf(image, song),
            listOf(MediaSource("image", "source", 1), MediaSource("song", "source", 1)),
            listOf(AlbumAncestor("child", "parent"), AlbumAncestor("child", "child")))

    @Test fun defaultGalleryOrderingPutsRecentMediaFirstAndSongsAlphabetically() {
        assertEquals("DATE" to true, defaultGalleryOrdering(GalleryRoute(tab = "PHOTO")))
        assertEquals("DATE" to true, defaultGalleryOrdering(GalleryRoute(tab = "VIDEO")))
        assertEquals("DATE" to true, defaultGalleryOrdering(GalleryRoute(tab = "ALBUM", album = "trip")))
        assertEquals("NAME" to false, defaultGalleryOrdering(GalleryRoute(tab = "ALBUM")))
        assertEquals("NAME" to false, defaultGalleryOrdering(GalleryRoute(tab = "MUSIC")))
        assertEquals("FAVORITE_ADDED" to true, defaultGalleryOrdering(GalleryRoute(tab = "FAVORITE")))
    }

    @Test fun musicRootDoesNotPopulatePhotosWithArtwork() {
        assertFalse(data().visible(image))
        assertTrue(data().visible(song))
        assertTrue(data().artworkAllowed(image))
    }
    @Test fun mixedRootRequiresExplicitMixedRole() {
        assertTrue(data(source.copy(role = "MIXED")).visible(image))
        assertTrue(data(source.copy(role = "MIXED")).visible(song))
    }
    @Test fun ancestorHidingAppliesToFavoritesAndArtwork() {
        val parent = AlbumEntity("parent", "doc:parent", "content://test/parent", "Parent", "Parent", null, hidden = true)
        val hidden = data(albums = listOf(parent))
        assertFalse(hidden.visible(song))
        assertFalse(hidden.artworkAllowed(image))
        assertNull(hidden.sourceFor(song))
        assertNull(hidden.sourceFor(image, artwork = true))
        assertTrue(song.favorite)
        assertTrue(data(albums = listOf(parent.copy(hidden = false))).visible(song))
    }
    @Test fun offlineSourceKeepsReferencesButCannotBeOpened() {
        val offline = data(source.copy(reachable = false))
        assertTrue(offline.visible(song))
        assertFalse(offline.usable(song))
        assertFalse(offline.artworkAllowed(image))
    }
    @Test fun albumBrowsingHidesEmptyFoldersButKeepsParentsWithPopulatedDescendants() {
        val visual = source.copy(role = "VISUAL")
        val parent = AlbumEntity("parent", "doc:parent", "content://test/parent", "Parent", "Parent", null)
        val child = AlbumEntity("child", "doc:child", "content://test/child", "Child", "Parent/Child", "parent")
        val empty = AlbumEntity("empty", "doc:empty", "content://test/empty", "Empty", "Empty", null)
        val snapshot = CatalogSnapshot(
            sources = listOf(visual), albums = listOf(parent, child, empty), media = listOf(image),
            membership = listOf(MediaSource(image.id, visual.id, 1)),
            ancestry = listOf(AlbumAncestor(child.id, parent.id), AlbumAncestor(child.id, child.id)),
            albumMembership = listOf(AlbumSource(parent.id, visual.id, 1), AlbumSource(child.id, visual.id, 1), AlbumSource(empty.id, visual.id, 1)),
        )
        val populatedImage = image.copy(albumId = child.id)
        val populated = snapshot.copy(media = listOf(populatedImage), membership = listOf(MediaSource(populatedImage.id, visual.id, 1)))
        assertEquals(setOf("child", "empty"), populated.topAlbums().map { it.id }.toSet())
        assertEquals(listOf(parent, child), populated.visibleAlbums().filter { it.id in populated.albumsWithVisibleMedia })
        assertEquals(listOf(parent, child, empty), populated.visibleAlbums())
        assertEquals(1, populated.populatedSubfolderCounts[parent.id])
        assertNull(populated.populatedSubfolderCounts[child.id])
        val hidden = populated.copy(albums = listOf(parent, child.copy(hidden = true), empty))
        assertTrue(hidden.populatedSubfolderCounts.isEmpty())
    }
    @Test fun disabledAndTrashedAreExcluded() {
        assertFalse(data(source.copy(enabled = false)).visible(song))
        assertFalse(data().visible(song.copy(trashed = true)))
    }
    @Test fun intermediaryFoldersAreSkippedAtEveryLevelButPopulatedAlbumsKeepTheirChildren() {
        fun album(id: String, parent: String?, hidden: Boolean = false) = AlbumEntity(id, "doc:$id", "content://test/$id", id, id, parent, hidden = hidden)
        val albums = listOf(album("wrapper", null), album("middle", "wrapper"), album("first", "middle"),
            album("second", "middle"), album("inside", "first"), album("empty", "middle"), album("hidden", "middle", true))
        val media = listOf("first", "second", "inside", "hidden").map { image.copy(id = it, albumId = it) }
        val snapshot = CatalogSnapshot(sources = listOf(source.copy(role = "VISUAL")), albums = albums, media = media,
            membership = media.map { MediaSource(it.id, source.id, 1) },
            albumMembership = albums.map { AlbumSource(it.id, source.id, 1) })
        assertEquals(setOf("first", "second", "inside", "empty"), snapshot.topAlbums().map { it.id }.toSet())
        assertEquals(setOf("first", "second", "empty"), snapshot.childAlbums("wrapper").map { it.id }.toSet())
        assertEquals(listOf("inside"), snapshot.childAlbums("first").map { it.id })
        assertEquals(2, snapshot.populatedSubfolderCounts["wrapper"])
        assertEquals(1, snapshot.populatedSubfolderCounts["first"])
        assertTrue(snapshot.childAlbums("empty").isEmpty())
        assertEquals(listOf("wrapper"), snapshot.topAlbums(GalleryPreferences(nestedAlbums = true)).map { it.id })
        assertEquals(listOf("middle"), snapshot.childAlbums("wrapper", nested = true).map { it.id })
    }
    @Test fun overlappingSourcesDoNotCreateDuplicateMedia() {
        val base = data()
        val overlap = base.copy(sources = base.sources + source.copy(id = "other"),
            membership = base.membership + MediaSource("song", "other", 1))
        assertEquals(1, overlap.visibleMedia.count { it.id == "song" })
    }
    @Test fun typeRecognitionExcludesEmulatorSoundfontsAndActiveSvg() {
        assertNull(MediaNames.kind("audio/midi", "rom.mid"))
        assertNull(MediaNames.kind("application/octet-stream", "font.sf2"))
        assertNull(MediaNames.kind("image/svg+xml", "active.svg"))
        assertEquals(MediaKind.GIF, MediaNames.kind("application/octet-stream", "animation.GIF"))
    }
    @Test fun naturalNamesCompareNumbersWithoutOverflow() {
        assertEquals(listOf("image2", "image10", "image999999999999999999999"),
            listOf("image10", "image999999999999999999999", "image2").sortedWith(MediaNames.natural))
    }
    @Test fun convertedGifKeepsVideoDecoderAndVideoOnlyAlbumHasACover() {
        val video = image.copy(id = "video", name = "animation.GIF.mp4", mime = "video/mp4", kind = "VIDEO")
        assertTrue(video.convertedGif)
        assertTrue(matchesPreviewCategory("PHOTO", video))
        assertFalse(matchesPreviewCategory("VIDEO", video))
        assertFalse(video.copy(name = "holiday.mp4").convertedGif)
        assertFalse(video.copy(name = "gif-holiday.mp4").convertedGif)
        assertEquals(MediaKind.VIDEO, MediaNames.kind(video.mime, video.name))
        val album = AlbumEntity("child", "doc:child", "content://test/child", "Video", "Video", null)
        val visual = source.copy(role = "VISUAL")
        val data = CatalogSnapshot(listOf(visual), listOf(album), listOf(video),
            listOf(MediaSource(video.id, visual.id, 1)), albumMembership = listOf(AlbumSource(album.id, visual.id, 1)))
        assertEquals(listOf(album), data.visibleAlbums())
        assertEquals(video, data.cover(album))
    }
    @Test fun automaticAlbumCoverUsesNewestVisualWhileManualCoverWins() {
        val album = AlbumEntity("child", "doc:child", "content://test/child", "Photos", "Photos", null)
        val recent = image.copy(id = "recent", modified = 200, kind = "GIF", mime = "image/gif")
        val visual = source.copy(role = "VISUAL")
        val data = CatalogSnapshot(listOf(visual), listOf(album), listOf(image, recent),
            listOf(MediaSource(image.id, visual.id, 1), MediaSource(recent.id, visual.id, 1)),
            albumMembership = listOf(AlbumSource(album.id, visual.id, 1)))
        assertEquals(recent, data.cover(album))
        assertEquals(image, data.cover(album.copy(coverMediaId = image.id)))
        assertEquals(recent, data.copy(media = listOf(recent, image)).cover(album))
    }
    @Test fun externalContentChangesNeverSilentlyRetargetFavorites() {
        assertTrue(DocumentVersions.changed(100, 1, 110, 1))
        assertTrue(DocumentVersions.changed(100, 1, 100, 2))
        assertFalse(DocumentVersions.changed(100, 1, 100, 1))
    }
    @Test fun naturalSortingPreservesMixedNamesAndHandlesTenThousandFiles() {
        // Keep the published ordering, including padded numbers, case and non-ASCII names.
        val chunks = Regex("\\d+|\\D+")
        val previous = Comparator<String> { left, right ->
            val a = chunks.findAll(left.lowercase()).map { it.value }.toList()
            val b = chunks.findAll(right.lowercase()).map { it.value }.toList()
            var result = 0
            for (i in 0 until minOf(a.size, b.size)) {
                val x = a[i]; val y = b[i]
                result = if (x.first().isDigit() && y.first().isDigit()) {
                    val nx = x.trimStart('0').ifEmpty { "0" }; val ny = y.trimStart('0').ifEmpty { "0" }
                    nx.length.compareTo(ny.length).takeIf { it != 0 } ?: nx.compareTo(ny)
                } else x.compareTo(y)
                if (result != 0) break
            }
            if (result != 0) result else a.size.compareTo(b.size).takeIf { it != 0 } ?: left.compareTo(right)
        }
        val names = listOf("", "0", "00", "000.png", "0.png", "img2", "Img02", "img10", "img2a10", "img2a2",
            "IMG2", "é2", "é10", "١abc", "٢x", "İ12", "i12", "image999999999999999999999", "image1", "a", "ab2")
        for (a in names) for (b in names)
            assertEquals("$a / $b", previous.compare(a, b).compareTo(0), MediaNames.natural.compare(a, b).compareTo(0))
        val files = (0 until 10_000).map { "Screenshot_${it}.png" }
        val start = System.nanoTime()
        assertEquals(files, files.shuffled(kotlin.random.Random(37)).sortedWith(MediaNames.natural))
        println("Natural sort 10000 names: ${(System.nanoTime() - start) / 1_000_000} ms (runner, not Thor)")
    }
}
