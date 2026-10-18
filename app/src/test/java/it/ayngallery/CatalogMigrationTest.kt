package it.ayngallery

import android.app.Application
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "it")
class CatalogMigrationTest {
    @Test fun v13UpgradePreservesVideoPositionAndMarksItsAgeUnknown() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val legacy = app.openOrCreateDatabase("catalog.db", 0, null)
        val entities = JSONObject(File("schemas/it.ayngallery.CatalogDatabase/13.json").readText()).getJSONObject("database").getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i); val name = entity.getString("tableName")
            legacy.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", name))
            val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
            for (j in 0 until indices.length()) legacy.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", name))
        }
        legacy.execSQL("INSERT INTO media (id,documentKey,uri,albumId,name,mime,kind,size,modified,favorite,available,trashed,videoPosition,added) VALUES('video','test:video','content://test/video','folder','video.mp4','video/mp4','VIDEO',100,1,1,1,0,30000,1)")
        legacy.version = 13; legacy.close()
        val catalog = Catalog(app)
        try {
            assertEquals(14, catalog.db.openHelper.readableDatabase.version)
            val video = catalog.dao.media("video")!!
            assertEquals(30000L, video.videoPosition); assertEquals(0L, video.videoPositionAt); assertTrue(video.favorite)
            catalog.dao.videoPosition("video", 45000, 123456)
            assertEquals(123456L, catalog.dao.media("video")!!.videoPositionAt)
        } finally { catalog.scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin(); catalog.db.close() }
    }
    @Test fun favoriteDatesSurviveUpgradeAndOnlyChangeOnNewAddition() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val legacy = app.openOrCreateDatabase("catalog.db", 0, null)
        val entities = JSONObject(File("schemas/it.ayngallery.CatalogDatabase/11.json").readText()).getJSONObject("database").getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i); val name = entity.getString("tableName")
            legacy.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", name))
            val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
            for (j in 0 until indices.length()) legacy.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", name))
        }
        legacy.execSQL("INSERT INTO media (id,documentKey,uri,albumId,name,mime,kind,size,modified,favorite,available,trashed,videoPosition,added) VALUES('stable','test:image','content://test/image','folder','image.png','image/png','IMAGE',100,1,1,1,0,0,1)")
        legacy.version = 11; legacy.close()
        val catalog = Catalog(app)
        try {
            assertTrue(catalog.dao.media("stable")!!.favorite)
            assertEquals(0L, catalog.dao.media("stable")!!.favoriteAddedAt)
            catalog.dao.favorite("stable", true, 100L)
            assertEquals(0L, catalog.dao.media("stable")!!.favoriteAddedAt)
            catalog.dao.favorite("stable", false, 200L)
            catalog.dao.favorite("stable", true, 300L)
            assertEquals(300L, catalog.dao.media("stable")!!.favoriteAddedAt)
            catalog.dao.favorite("stable", true, 400L)
            assertEquals(300L, catalog.dao.media("stable")!!.favoriteAddedAt)
        } finally { catalog.scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin(); catalog.db.close() }
    }
    @Test fun v12MetadataUpgradePreservesFavoritesAndDefaultsToUnread() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val legacy = app.openOrCreateDatabase("catalog.db", 0, null)
        val entities = JSONObject(File("schemas/it.ayngallery.CatalogDatabase/12.json").readText()).getJSONObject("database").getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i); val name = entity.getString("tableName")
            legacy.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", name))
            val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
            for (j in 0 until indices.length()) legacy.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", name))
        }
        legacy.execSQL("INSERT INTO media (id,documentKey,uri,albumId,name,mime,kind,size,modified,favorite,available,trashed,videoPosition,added) VALUES('stable','test:image','content://test/image','folder','image.png','image/png','IMAGE',100,1,1,1,0,0,1)")
        legacy.version = 12; legacy.close()
        val catalog = Catalog(app)
        try {
            assertTrue(catalog.dao.media("stable")!!.favorite)
            assertFalse(catalog.dao.media("stable")!!.audioMetadataRead)
            assertEquals(0L, catalog.dao.media("stable")!!.favoriteAddedAt)
            catalog.dao.favorite("stable", true, 100L)
            assertEquals(0L, catalog.dao.media("stable")!!.favoriteAddedAt)
            catalog.dao.favorite("stable", false, 200L)
            catalog.dao.favorite("stable", true, 300L)
            assertEquals(300L, catalog.dao.media("stable")!!.favoriteAddedAt)
            catalog.dao.favorite("stable", true, 400L)
            assertEquals(300L, catalog.dao.media("stable")!!.favoriteAddedAt)
        } finally { catalog.scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin(); catalog.db.close() }
    }
    @Test fun v10MovesNamedComparisonsToRoomOnceWithoutChangingCurrentViewOrLegacyCopies() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val legacy = app.openOrCreateDatabase("catalog.db", 0, null)
        val entities = JSONObject(File("schemas/it.ayngallery.CatalogDatabase/10.json").readText()).getJSONObject("database").getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i); val name = entity.getString("tableName")
            legacy.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", name))
            val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
            for (j in 0 until indices.length()) legacy.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", name))
        }
        legacy.version = 10; legacy.close()
        val prefs = app.getSharedPreferences("comparison_v1", 0)
        val current = ComparisonStore.encode(ComparisonState(linked = true)).toString()
        val saved = ComparisonStore.encode(ComparisonState().pin("missing", listOf("missing"))!!.transform("pane_2", ViewTransform(3f, .2f, -.2f))).toString()
        prefs.edit().putString("current", current).putString("saved:Legacy session", saved).putString("saved:Unreadable preserved", "invalid fixture").commit()
        var catalog = Catalog(app)
        try {
            assertEquals(14, catalog.db.openHelper.readableDatabase.version)
            assertEquals(saved, catalog.dao.comparisonSession("Legacy session")!!.state)
            assertEquals(current, prefs.getString("current", null)); assertEquals(saved, prefs.getString("saved:Legacy session", null))
            assertEquals("invalid fixture", prefs.getString("saved:Unreadable preserved", null))
            catalog.dao.deleteComparisonSession("Legacy session")
            catalog.scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin(); catalog.db.close(); catalog = Catalog(app)
            assertTrue(catalog.dao.comparisonSessions().isEmpty())
            assertEquals(14, catalog.db.openHelper.readableDatabase.version)
        } finally { catalog.scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin(); catalog.db.close() }
    }
    @Test fun v7DeviceAliasUpgradeKeepsFavoriteCollectionsAndRecipeJournal() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val legacy = app.openOrCreateDatabase("catalog.db", 0, null)
        val entities = JSONObject(File("schemas/it.ayngallery.CatalogDatabase/7.json").readText()).getJSONObject("database").getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i); val name = entity.getString("tableName")
            legacy.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", name))
            val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
            for (j in 0 until indices.length()) legacy.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", name))
        }
        legacy.execSQL("INSERT INTO media VALUES('stable','test:image','content://test/tree/root/document/image','folder','image.png','image/png','IMAGE',100,1,NULL,NULL,NULL,NULL,NULL,NULL,1,1,0,0,1)")
        legacy.execSQL("INSERT INTO collections VALUES('collection','Personal','MEDIA','stable')")
        legacy.execSQL("INSERT INTO collection_items VALUES('collection','stable',0)")
        legacy.execSQL("""INSERT INTO file_operations (id,mediaId,kind,sourceUri,sourceKey,sourceAlbumId,originalName,mime,expectedSize,
            expectedModified,requestedName,keepBoth,resultMediaId,phase,digest,bytes,cancelRequested,createdAt,updatedAt,recipe)
            VALUES('job','stable','EXPORT_IMAGE','content://test/tree/root/document/image','test:image','folder','image.png','image/png',100,
            1,'copy.png',1,'copy','QUEUED','old-digest',100,0,1000,2000,'recipe-fixture')""")
        legacy.version = 7; legacy.close()
        val catalog = Catalog(app)
        try {
            assertEquals(14, catalog.db.openHelper.readableDatabase.version)
            val item = catalog.dao.media("stable")!!; assertTrue(item.favorite); assertNull(item.deviceUri)
            assertEquals("stable", catalog.dao.collectionItems().single().mediaId)
            assertEquals("recipe-fixture", catalog.dao.operation("job")!!.recipe)
        } finally { catalog.scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin(); catalog.db.close() }
    }
    @Test fun v6RecipeUpgradePreservesRetainedDuplicateGuardAndAddsNullableRecipe() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val legacy = app.openOrCreateDatabase("catalog.db", 0, null)
        val schema = JSONObject(File("schemas/it.ayngallery.CatalogDatabase/6.json").readText()).getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i); val name = entity.getString("tableName")
            legacy.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", name))
            val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
            for (j in 0 until indices.length()) legacy.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", name))
        }
        legacy.execSQL("""INSERT INTO file_operations (id,mediaId,kind,sourceUri,sourceKey,sourceAlbumId,originalName,mime,expectedSize,
            expectedModified,requestedName,keepBoth,resultMediaId,phase,digest,bytes,cancelRequested,createdAt,updatedAt,retainedMediaId)
            VALUES('job','stable','TRASH','content://test/tree/root/document/image','test:image','folder','image.png','image/png',100,
            1,'image.png',1,'stable','QUEUED','old-digest',100,0,1000,2000,'keeper')""")
        legacy.version = 6; legacy.close()
        val catalog = Catalog(app)
        try {
            assertEquals(14, catalog.db.openHelper.readableDatabase.version)
            val job = catalog.dao.operation("job")!!
            assertEquals("keeper", job.retainedMediaId); assertEquals("old-digest", job.digest); assertEquals(100L, job.bytes); assertNull(job.recipe)
        } finally { catalog.scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin(); catalog.db.close() }
    }
    @Test fun v5DuplicateGuardUpgradeKeepsJournalDigestsArtworkAndReferences() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val legacy = app.openOrCreateDatabase("catalog.db", 0, null)
        val schema = JSONObject(File("schemas/it.ayngallery.CatalogDatabase/5.json").readText()).getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i); val name = entity.getString("tableName")
            legacy.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", name))
            val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
            for (j in 0 until indices.length()) legacy.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", name))
        }
        legacy.execSQL("INSERT INTO albums VALUES('folder','test:root','content://test/tree/root/document/root','Folder','Folder',NULL,0,'stable',0)")
        legacy.execSQL("INSERT INTO media VALUES('stable','test:image','content://test/tree/root/document/image','folder','image.png','image/png','IMAGE',100,1,NULL,NULL,NULL,NULL,NULL,NULL,1,1,0,0,1)")
        legacy.execSQL("INSERT INTO collections VALUES('collection','Personal','MEDIA','stable')")
        legacy.execSQL("INSERT INTO collection_items VALUES('collection','stable',0)")
        legacy.execSQL("INSERT INTO artwork_choices VALUES('TRACK:stable','stable',NULL)")
        legacy.execSQL("""INSERT INTO file_operations (id,mediaId,kind,sourceUri,sourceKey,sourceAlbumId,originalName,mime,expectedSize,
            expectedModified,requestedName,keepBoth,resultMediaId,phase,digest,bytes,cancelRequested,createdAt,updatedAt,archiveAt)
            VALUES('job','stable','TRASH','content://test/tree/root/document/image','test:image','folder','image.png','image/png',100,
            1,'image.png',1,'stable','QUEUED','old-digest',0,0,1000,2000,1500)""")
        legacy.version = 5; legacy.close()
        val catalog = Catalog(app)
        try {
            assertEquals(14, catalog.db.openHelper.readableDatabase.version)
            assertTrue(catalog.dao.media("stable")!!.favorite)
            assertEquals("stable", catalog.dao.collectionItems().single().mediaId)
            assertEquals("stable", catalog.dao.artworkChoices().single().coverMediaId)
            val job = catalog.dao.operation("job")!!
            assertEquals("old-digest", job.digest); assertEquals(1500L, job.archiveAt); assertNull(job.retainedMediaId)
        } finally { catalog.scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin(); catalog.db.close() }
    }
    @Test fun v4ArtworkUpgradePreservesExistingMediaCoversAndCollections() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val legacy = app.openOrCreateDatabase("catalog.db", 0, null)
        val schema = JSONObject(File("schemas/it.ayngallery.CatalogDatabase/4.json").readText()).getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i); val name = entity.getString("tableName")
            legacy.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", name))
            val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
            for (j in 0 until indices.length()) legacy.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", name))
        }
        legacy.execSQL("INSERT INTO albums VALUES('folder','test:root','content://test/tree/root/document/root','Folder','Folder',NULL,0,'stable',0)")
        legacy.execSQL("INSERT INTO media VALUES('stable','test:image','content://test/tree/root/document/image','folder','image.png','image/png','IMAGE',100,1,NULL,NULL,NULL,NULL,NULL,NULL,1,1,0,0,1)")
        legacy.execSQL("INSERT INTO collections VALUES('collection','Personal','MEDIA','stable')")
        legacy.execSQL("INSERT INTO collection_items VALUES('collection','stable',0)")
        legacy.version = 4; legacy.close()
        val catalog = Catalog(app)
        try {
            assertEquals(14, catalog.db.openHelper.readableDatabase.version)
            assertTrue(catalog.dao.media("stable")!!.favorite)
            assertEquals("stable", catalog.dao.albums().single().coverMediaId)
            assertEquals("stable", catalog.dao.collections().single().coverMediaId)
            assertEquals("stable", catalog.dao.collectionItems().single().mediaId)
            assertTrue(catalog.dao.artworkChoices().isEmpty())
        } finally { catalog.scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin(); catalog.db.close() }
    }
    @Test fun v3JournalUpgradePreservesPendingDeletionWitnessAndTrashDeadline() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val legacy = app.openOrCreateDatabase("catalog.db", 0, null)
        val schema = JSONObject(File("schemas/it.ayngallery.CatalogDatabase/3.json").readText()).getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i); val name = entity.getString("tableName")
            legacy.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", name))
            val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
            for (j in 0 until indices.length()) legacy.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", name))
        }
        legacy.execSQL("""INSERT INTO file_operations (id,mediaId,kind,sourceUri,sourceKey,sourceAlbumId,originalName,mime,expectedSize,
            expectedModified,requestedName,keepBoth,resultMediaId,phase,bytes,cancelRequested,createdAt,updatedAt,sourceDeletionStartedAt)
            VALUES('job','stable','TRASH','content://test/tree/root/document/image','test:image','folder','image.png','image/png',100,
            1,'image.png',1,'stable','PARTIAL',100,0,1000,2000,1500)""")
        legacy.execSQL("""INSERT INTO trash VALUES('stable','job','00000000-0000-0000-0000-000000000000.bin',
            'content://test/tree/root/document/image','folder','image.png','image/png',100,'sha',1500,604801500,0)""")
        legacy.version = 3; legacy.close()
        val catalog = Catalog(app)
        try {
            assertEquals(14, catalog.db.openHelper.readableDatabase.version)
            val job = catalog.dao.operation("job")!!
            assertEquals("PARTIAL", job.phase); assertEquals(1500L, job.sourceDeletionStartedAt); assertNull(job.archiveAt)
            assertEquals(604801500L, catalog.dao.trash("stable")!!.expiresAt)
        } finally { catalog.scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin(); catalog.db.close() }
    }
    @Test fun v1UpgradePreservesFavoritesCollectionsAndDisplayIndependentIds() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val legacy = app.openOrCreateDatabase("catalog.db", 0, null)
        val schema = JSONObject(File("schemas/it.ayngallery.CatalogDatabase/1.json").readText()).getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val name = entity.getString("tableName")
            legacy.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", name))
            val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
            for (j in 0 until indices.length()) legacy.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", name))
        }
        legacy.execSQL("INSERT INTO sources VALUES('scope','content://test/tree/root','Chosen folder','VISUAL',1,1,NULL,7)")
        legacy.execSQL("INSERT INTO albums VALUES('folder','test:root','content://test/tree/root/document/root','Folder','Folder',NULL,0,'stable',0)")
        legacy.execSQL("INSERT INTO media VALUES('stable','test:image','content://test/tree/root/document/image','folder','image.png','image/png','IMAGE',100,1,NULL,NULL,NULL,NULL,NULL,NULL,1,1,0,0,1)")
        legacy.execSQL("INSERT INTO media_sources VALUES('stable','scope',7)")
        legacy.execSQL("INSERT INTO album_ancestors VALUES('folder','folder')")
        legacy.execSQL("INSERT INTO collections VALUES('collection','Personal','MEDIA','stable')")
        legacy.execSQL("INSERT INTO collection_items VALUES('collection','stable',0)")
        legacy.version = 1; legacy.close()
        val catalog = Catalog(app)
        try {
            val data = catalog.readSnapshot()
            assertTrue(data.byId["stable"]!!.favorite)
            assertEquals("stable", data.collections.single().coverMediaId)
            assertEquals("stable", data.collectionItems.single().mediaId)
            assertEquals("folder", data.visibleAlbums().single().id)
            assertEquals(14, catalog.db.openHelper.readableDatabase.version)
        } finally { catalog.scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin(); catalog.db.close() }
    }
}
