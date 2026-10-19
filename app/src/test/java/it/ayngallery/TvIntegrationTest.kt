package it.ayngallery

import android.app.Application
import androidx.media3.common.Player
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "it")
class TvIntegrationTest {
    @Test fun manualChannelSaveIsNotFavoriteAndNewStarsUseTheirOwnDate() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = TvStore(context)
        val entry = TvCatalogEntry("https://example.com/manual.m3u8", "Manual", null, null, null)
        store.customize(entry, null, null)
        assertFalse(store.channels.single().favorite)
        val initial = store.channels.single().favoriteAddedAt
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(2))
        store.favorite(entry, true)
        assertTrue(store.channels.single().favoriteAddedAt >= initial)
        val date = store.channels.single().favoriteAddedAt
        store.favorite(entry, true)
        assertEquals(date, store.channels.single().favoriteAddedAt)
        val reopened = TvStore(context); reopened.load()
        assertEquals(date, reopened.channels.single().favoriteAddedAt)
    }

    @Test fun savingAndFavoritingChannelsAreIndependentAndPersistent() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = TvStore(context)
        val entry = TvCatalogEntry("https://example.com/live.m3u8", "Channel", null, null, null)
        store.set(entry, true)
        assertFalse(store.channels.single().favorite)
        store.favorite(entry, true)
        assertTrue(store.channels.single().favorite)
        store.favorite(entry, false)
        assertEquals(1, store.channels.size)
        val reopened = TvStore(context); reopened.load()
        assertFalse(reopened.channels.single().favorite)
        store.set(entry, false)
        assertTrue(store.channels.isEmpty())
    }
    @Test @Config(application = TestGalleryApp::class)
    fun tvEntryPreservesFocusedChannelAndUsesFirstSavedAsFallback() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        try {
            val first = TvCatalogEntry("https://example.com/first.m3u8", "First", null, null, null)
            val second = TvCatalogEntry("https://example.com/second.m3u8", "Second", null, null, null)
            app.tv.store.set(second, true); app.tv.store.set(first, true)
            app.tv.enter(); assertEquals(first, app.tv.selected)
            app.tv.select(second); app.tv.enter(); assertEquals(second, app.tv.selected)
        } finally { app.onTerminate() }
    }
    @Test @Config(application = TestGalleryApp::class)
    fun personalSearchCombinesSourcesAndGroupsWithoutBuiltInRequests() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        try {
            assertTrue(app.tv.catalogs().isEmpty())
            app.tv.store.addSource("One", "https://example.com/one.m3u")
            app.tv.store.addSource("Two", "https://example.com/two.m3u")
            val cache = java.io.File(app.cacheDir, "tv-catalogs").apply { mkdirs() }
            app.tv.store.sources.forEachIndexed { index, source ->
                java.io.File(cache, tvDigest(source.url)).writeText("#EXTM3U\n#EXTINF:-1 group-title=\"Group $index\",Shared name $index\nhttps://example.com/$index.m3u8\n")
            }
            val rows = app.tv.searchSources()
            assertEquals(2, rows.size)
            assertTrue(rows.all { it.name.contains("Shared name") })
            assertEquals(setOf("Group 0", "Group 1"), rows.map { it.group }.toSet())
        } finally { app.onTerminate() }
    }
    @Test fun personalSourcesStartEmptyPersistAndCanBeRemoved() {
        val context = RuntimeEnvironment.getApplication()
        val store = TvStore(context)
        assertTrue(store.sources.isEmpty())
        store.addSource("My playlist", "https://example.com/personal.m3u")
        store.addSource("Duplicate", "https://example.com/personal.m3u")
        assertEquals(1, store.sources.size)
        assertEquals(store.sources, TvStore(context).sources)
        assertFalse(store.sourceAcknowledged)
        store.acknowledgeSources()
        assertTrue(TvStore(context).sourceAcknowledged)
        store.removeSource(store.sources.single().id)
        assertTrue(TvStore(context).sources.isEmpty())
    }
    @org.robolectric.annotation.Config(sdk = [35])
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun cachedLogoDecodeUsesSmallBitmapsForChannelTiles() {
        val context = RuntimeEnvironment.getApplication()
        val file = java.io.File(context.cacheDir, "logo.png")
        val image = android.graphics.Bitmap.createBitmap(512, 512, android.graphics.Bitmap.Config.ARGB_8888)
        image.eraseColor(android.graphics.Color.BLUE)
        file.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val decoded = decodeTvArtworkFile(file, 256)!!
        assertEquals(256, decoded.width); assertEquals(256, decoded.height)
        assertTrue(decoded.allocationByteCount <= 256 * 256 * 4)
    }
    @Test fun optionalThumbnailTitlePersistsAndCanBeClearedWithoutLosingTheChannelName() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val channel = TvCatalogEntry("https://example.com/a.m3u8", "News", null, null, null)
        val store = TvStore(context)
        store.customize(channel, "Channel name", null, "On air")
        val reopened = TvStore(context); reopened.load()
        assertEquals("On air", reopened.channels.single().displayTitle)
        reopened.customize(channel, "Renamed", null)
        assertEquals("On air", reopened.channels.single().displayTitle)
        reopened.customize(channel, "Renamed", null, "")
        assertNull(reopened.channels.single().displayTitle)
        assertEquals("Renamed", reopened.channels.single().name)
    }
    @Test fun mixedFavoritesRetainEveryLocalSortAndOnlyIncludeMatchingChannels() {
        val media = listOf(
            MediaEntity("a", "a", "content://test/a", "album", "track10", "audio/mpeg", "AUDIO", 30, 10, duration = 40, added = 20, favoriteAddedAt = 30),
            MediaEntity("b", "b", "content://test/b", "album", "track2", "audio/mpeg", "AUDIO", 10, 30, duration = 20, added = 40, favoriteAddedAt = 10),
            MediaEntity("c", "c", "content://test/c", "album", "track1", "audio/mpeg", "AUDIO", 20, 20, duration = 30, added = 10, favoriteAddedAt = 20))
        val channel = TvSavedChannel(TvCatalogEntry("https://example.com/a.m3u8", "News Sport", null, null, null), 15)
        for (sort in listOf("NAME", "DATE", "SIZE", "DURATION", "ADDED", "FAVORITE_ADDED")) for (descending in listOf(false, true)) {
            val rows = tvFavoriteRows(media, listOf(channel), "", sort, descending, false, "ALL")
            val local = rows.filterIsInstance<TvFavoriteRow.Local>().map { it.media }
            val comparator = mediaOrderComparator(sort)
            assertEquals(media.sortedWith(if (descending) comparator.reversed() else comparator), local)
        }
        for (kind in listOf("IMAGE", "GIF", "AUDIO")) assertTrue(tvFavoriteRows(emptyList(), listOf(channel), "", "DATE", true, false, kind).isEmpty())
        assertTrue(tvFavoriteRows(emptyList(), listOf(channel), "missing", "DATE", true, false, "ALL").isEmpty())
        assertEquals(1, tvFavoriteRows(emptyList(), listOf(channel), "sport news", "DATE", true, false, "VIDEO").size)
        assertTrue(tvFavoriteRows(emptyList(), listOf(channel), "", "DATE", true, true, "ALL").isEmpty())
    }

    @Test fun thumbnailMaintenanceRetainsFreshPreviewsAndBoundsAgeAndDiskUse() {
        val directory = java.io.File(RuntimeEnvironment.getApplication().cacheDir, "tv-thumbnails").apply { mkdirs() }
        val now = System.currentTimeMillis()
        fun image(key: String, age: Long) = java.io.File(directory, tvDigest(key) + ".png").apply { writeBytes(ByteArray(10)); setLastModified(now - age) }
        val old = image("expired", 8 * 24 * 60 * 60 * 1000L)
        val first = image("first", 2000); val second = image("second", 1000)
        val unrelated = java.io.File(directory, "unrelated.txt").apply { writeText("keep") }
        trimTvThumbnails(directory, now)
        assertFalse(old.exists()); assertTrue(first.exists()); assertTrue(second.exists())
        trimTvThumbnails(java.io.File(directory, "../" + directory.name), now, limit = 10)
        assertFalse(first.exists()); assertTrue(second.exists()); assertTrue(unrelated.exists())
        trimTvThumbnails(directory, now, limit = 10)
        assertTrue(second.exists()) // Reopening/idle maintenance keeps the offline preview.
    }

    @Test fun customArtworkCleanupProtectsSavedCoversAndRemovesReplacedRemovedAndAbandonedImages() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val directory = java.io.File(context.filesDir, "tv-user-artwork").apply { mkdirs() }
        fun image() = java.io.File(directory, java.util.UUID.randomUUID().toString() + ".png").apply { writeText("png") }
        val store = TvStore(context); store.load()
        val channel = TvCatalogEntry("https://example.com/a.m3u8", "News", null, null, null)
        val saved = image(); store.customize(channel, null, saved.name)
        val draft = image(); store.discardArtwork(setOf(saved.name, draft.name, "../outside.png"))
        assertTrue(saved.exists()); assertFalse(draft.exists())
        val replacement = image(); store.customize(channel, null, replacement.name)
        assertFalse(saved.exists()); assertTrue(replacement.exists())
        val orphan = image().apply { setLastModified(System.currentTimeMillis() - 2 * 24 * 60 * 60 * 1000L) }
        val reopened = TvStore(context); reopened.load()
        assertFalse(orphan.exists()); assertTrue(replacement.exists())
        reopened.set(channel, false); assertFalse(replacement.exists())
    }

    @Test fun oldCategoryOrderAndHiddenCategoriesSurviveEnablingAndDisablingTv() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val store = PreferenceStore(app)
        val order = listOf("MUSIC", "VIDEO", "FAVORITE", "ALBUM", "PHOTO")
        store.save(store.state.value.copy(navbarOrder = order, disabledCategories = setOf("PHOTO"), defaultSection = "MUSIC"))
        assertFalse(store.state.value.tvEnabled)
        assertEquals(order.filterNot { it == "PHOTO" }, visibleNavbarTabs(store.state.value).map { it.key })
        store.update { it.copy(tvEnabled = true) }
        assertEquals(order, PreferenceStore(app).state.value.navbarOrder)
        assertFalse(visibleNavbarTabs(store.state.value).any { it.key == "TV" })
        store.update { it.copy(tvInNavbar = true) }
        assertEquals(NavbarOrder.withTv(order).filterNot { it == "PHOTO" }, visibleNavbarTabs(store.state.value).map { it.key })
        store.update { it.copy(navbarOrder = listOf("TV") + order, tvInMediaFavorites = true) }
        val roundtrip = PreferencesTransfer.decode(PreferencesTransfer.encode(store.state.value))
        assertEquals(store.state.value, roundtrip)
        store.update { it.copy(tvEnabled = false) }
        assertEquals(order.filterNot { it == "PHOTO" }, visibleNavbarTabs(store.state.value).map { it.key })
        assertEquals("MUSIC", store.state.value.defaultSection)
        assertTrue(store.state.value.tvInMediaFavorites)
        assertFalse(NavbarOrder.valid(order + "TV" + "TV"))
        assertEquals(listOf("MUSIC", "TV", "VIDEO", "FAVORITE", "ALBUM", "PHOTO"), NavbarOrder.withTv(order))
        assertEquals(listOf("TV") + order, NavbarOrder.withTv(listOf("TV") + order))
        val legacy = JSONObject(PreferencesTransfer.encode(roundtrip)).put("version", 30).apply { getJSONObject("preferences").remove("showEmptyFolders") }.apply { getJSONObject("preferences").apply { remove("musicDuringSleep"); remove("videoDuringSleep") } }
        legacy.getJSONObject("preferences").apply { remove("tvEnabled"); remove("tvInNavbar"); remove("tvInMediaFavorites") }
        assertFalse(PreferencesTransfer.decode(legacy.toString()).tvEnabled)
    }

    @Test fun savedChannelsDeduplicateAndKeepPersonalizationAcrossStoreReopening() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val store = TvStore(app)
        val a = TvCatalogEntry("https://example.com/live.m3u8?token=one", "Original", "news.it", null, "News")
        val alias = a.copy(streamUrl = "https://example.com/live.m3u8?token=two")
        store.set(a, true)
        store.customize(a, "My name", null)
        store.set(alias, true)
        assertEquals(1, store.channels.size)
        val reopened = TvStore(app); reopened.load()
        assertEquals("My name", reopened.channels.single().name)
        assertEquals(a.streamUrl, reopened.channels.single().entry.streamUrl)
        reopened.set(alias, false)
        assertTrue(reopened.channels.isEmpty())
        assertTrue(PreferenceStore(app).state.value.disabledCategories.isEmpty())
    }

    @Test fun channelOrderSurvivesEditingAndStoreReopeningAndRejectsIncompleteDragOrders() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = TvStore(context)
        val entries = (1..4).map { TvCatalogEntry("https://example.com/$it.m3u8", "Channel $it", null, null, null) }
        entries.forEach { store.set(it, true) }
        val order = store.channels.map { it.id }.reversed()
        store.reorder(order)
        store.customize(entries[1], "Renamed", null)
        assertEquals(order, store.channels.map { it.id })
        store.reorder(order.dropLast(1))
        assertEquals(order, store.channels.map { it.id })
        val reopened = TvStore(context); reopened.load()
        assertEquals(order, reopened.channels.map { it.id })
        assertEquals("Renamed", reopened.channels.first { it.matches(entries[1]) }.name)
        store.grid(true)
        assertTrue(TvStore(context).grid)
    }

    @org.robolectric.annotation.Config(sdk = [35])
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun artworkDecodeStartsAtOneAndDownsamplesBeforeDecoding() {
        val bitmap = android.graphics.Bitmap.createBitmap(1024, 512, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val decoded = decodeTvArtwork(bytes)
        assertNotNull(decoded)
        assertEquals(512, decoded!!.width)
        assertEquals(256, decoded.height)
        assertEquals(android.graphics.Color.BLUE, decoded.getPixel(0, 0))
        assertNull(decodeTvArtwork("not an image".toByteArray()))
    }

    @Test fun requestsRejectPrivateLiteralAddressesAndDangerousSchemes() {
        for (host in listOf("0.0.0.0", "127.0.0.1", "10.1.2.3", "192.168.1.1", "172.31.1.1", "169.254.169.254", "100.64.1.1", "224.1.1.1", "::1", "fc00::1", "fe80::1", "::ffff:127.0.0.1", "2002:7f00:1::")) {
            assertFalse(host, TvNetwork.publicAddress(InetAddress.getByName(host)))
        }
        assertTrue(TvNetwork.publicAddress(InetAddress.getByName("8.8.8.8")))
        assertTrue(TvNetwork.publicAddress(InetAddress.getByName("2606:4700:4700::1111")))
        for (url in listOf("http://example.com", "file:///etc/passwd", "content://media", "https://user:pass@example.com", "https://localhost/live", "https://a.local/live", "https://example.com:8443/live", "https://example.com/%0d%0a", "https://example.com/%250a")) assertFalse(url, TvNetwork.validUrl(url))
        assertTrue(TvNetwork.validUrl("https://example.com/live?token=a%2Fb"))
        assertThrows(TvNetworkFailure::class.java) { runBlocking { TvNetwork.bytes("https://127.0.0.1/", 100) } }
        assertFalse(TvNetwork.client.followRedirects)
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun liveCommandsDisableSeekingEvenWhenUnderlyingWindowOffersDvr() {
        var writes = 0
        val underlying = java.lang.reflect.Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            when (method.name) {
                "getAvailableCommands" -> Player.Commands.Builder().addAll(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SET_SPEED_AND_PITCH).build()
                "isCommandAvailable" -> true
                "seekTo", "seekForward", "seekBack", "seekToNext", "setPlaybackSpeed" -> { writes++; null }
                else -> null
            }
        } as Player
        val live = TvLiveCommands(underlying)
        assertTrue(live.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
        assertFalse(live.availableCommands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
        assertFalse(live.isCommandAvailable(Player.COMMAND_SEEK_FORWARD))
        live.seekTo(10_000); live.seekForward(); live.seekBack(); live.seekToNext(); live.setPlaybackSpeed(2f)
        assertEquals(0, writes)
    }

    @Test fun transportRejectsRedirectsAndDnsAnswersIntoPrivateNetworks() {
        val dns = TvNetwork.guardedDns(object : okhttp3.Dns {
            override fun lookup(hostname: String) = listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("192.168.1.1"))
        })
        assertThrows(TvNetworkFailure::class.java) { dns.lookup("untrusted.example") }
        for (destination in listOf("https://127.0.0.1/key", "http://example.com/segment", "content://media/private")) {
            var requests = 0
            val client = TvNetwork.client.newBuilder().addInterceptor { chain ->
                requests++
                okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(302).message("Redirect").header("Location", destination)
                    .body(okhttp3.ResponseBody.create(null, byteArrayOf())).build()
            }.build()
            assertThrows(TvNetworkFailure::class.java) {
                client.newCall(okhttp3.Request.Builder().url("https://example.com/live.m3u8").build()).execute().close()
            }
            assertEquals(1, requests)
        }
    }
}
