package it.ayngallery

import androidx.room.*
import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

enum class MediaKind { IMAGE, GIF, VIDEO, AUDIO }
enum class SourceRole { VISUAL, MUSIC, MIXED }

@Entity(tableName = "sources", indices = [Index(value = ["treeUri"], unique = true)])
data class SourceEntity(@PrimaryKey val id: String, val treeUri: String, val title: String,
    val role: String, val enabled: Boolean = true, val reachable: Boolean = true,
    val error: String? = null, val lastScan: Long = 0, val restoreUri: String? = null)

@Entity(tableName = "albums", indices = [Index(value = ["documentKey"], unique = true)])
data class AlbumEntity(@PrimaryKey val id: String, val documentKey: String, val documentUri: String,
    val name: String, val path: String, val parentId: String?, val hidden: Boolean = false,
    val coverMediaId: String? = null, val systemExcluded: Boolean = false)

@Entity(tableName = "album_ancestors", primaryKeys = ["albumId", "ancestorId"], indices = [Index("ancestorId")])
data class AlbumAncestor(val albumId: String, val ancestorId: String)

@Entity(tableName = "album_sources", primaryKeys = ["albumId", "sourceId"], indices = [Index("sourceId")])
data class AlbumSource(val albumId: String, val sourceId: String, val scan: Long, val present: Boolean = true)

@Entity(tableName = "media", indices = [Index(value = ["documentKey"], unique = true), Index("albumId")])
data class MediaEntity(@PrimaryKey val id: String, val documentKey: String, val uri: String,
    val albumId: String, val name: String, val mime: String, val kind: String,
    val size: Long, val modified: Long, val duration: Long? = null,
    val title: String? = null, val artist: String? = null, val musicAlbum: String? = null,
    val artworkUri: String? = null, val artworkMediaId: String? = null,
    val favorite: Boolean = false, val available: Boolean = true, val trashed: Boolean = false,
    val videoPosition: Long = 0, val added: Long = System.currentTimeMillis(), val deviceUri: String? = null, @ColumnInfo(defaultValue = "0") val favoriteAddedAt: Long = 0, @ColumnInfo(defaultValue = "0") val audioMetadataRead: Boolean = false, @ColumnInfo(defaultValue = "0") val videoPositionAt: Long = 0)

@Entity(tableName = "media_sources", primaryKeys = ["mediaId", "sourceId"], indices = [Index("sourceId")])
data class MediaSource(val mediaId: String, val sourceId: String, val scan: Long)

/** Durable logical-ID redirects keep old pins/routes valid after an explicitly confirmed merge. */
@Entity(tableName = "media_relinks")
data class MediaRelink(@PrimaryKey val oldId: String, val mediaId: String)

@Entity(tableName = "collections")
data class CollectionEntity(@PrimaryKey val id: String, val name: String, val kind: String,
    val coverMediaId: String? = null)

@Entity(tableName = "collection_items", primaryKeys = ["collectionId", "mediaId"], indices = [Index("mediaId")])
data class CollectionItem(val collectionId: String, val mediaId: String, val position: Int)

@Entity(tableName = "artwork_choices")
data class ArtworkChoiceEntity(@PrimaryKey val target: String, val coverMediaId: String?, val priority: String?)

@Entity(tableName = "comparison_sessions", indices = [Index(value = ["name"], unique = true)])
data class ComparisonSessionEntity(@PrimaryKey val id: String, val name: String, val state: String)

@Entity(tableName = "file_operations", indices = [Index("mediaId")])
data class FileOperation(@PrimaryKey val id: String, val mediaId: String, val kind: String,
    val sourceUri: String, val sourceKey: String, val sourceAlbumId: String, val originalName: String,
    val mime: String, val expectedSize: Long, val expectedModified: Long,
    val destinationParent: String?, val destinationAlbumId: String?, val requestedName: String,
    val keepBoth: Boolean, val resultMediaId: String, val phase: String = "QUEUED",
    val temporaryUri: String? = null, val publishedUri: String? = null, val finalName: String? = null,
    val digest: String? = null, val bytes: Long = 0, val cancelRequested: Boolean = false,
    val message: String? = null, val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = createdAt,
    val sourceDeletedAt: Long? = null, val sourceDeletionStartedAt: Long? = null, val archiveAt: Long? = null,
    val retainedMediaId: String? = null, val recipe: String? = null)

@Entity(tableName = "trash", indices = [Index("expiresAt")])
data class TrashEntry(@PrimaryKey val mediaId: String, val operationId: String, val fileName: String,
    val originalUri: String, val originalAlbumId: String, val originalName: String,
    val mime: String, val size: Long, val digest: String, val deletedAt: Long, val expiresAt: Long,
    val purging: Boolean = false)

@Dao
interface CatalogDao {
    @Query("SELECT * FROM comparison_sessions ORDER BY name") fun observeComparisonSessions(): Flow<List<ComparisonSessionEntity>>
    @Query("SELECT * FROM comparison_sessions ORDER BY name") suspend fun comparisonSessions(): List<ComparisonSessionEntity>
    @Query("SELECT * FROM comparison_sessions WHERE name=:name") suspend fun comparisonSession(name: String): ComparisonSessionEntity?
    @Upsert suspend fun saveComparisonSessions(sessions: List<ComparisonSessionEntity>)
    @Query("DELETE FROM comparison_sessions WHERE name=:name") suspend fun deleteComparisonSession(name: String)
    @RawQuery(observedEntities = [SourceEntity::class, AlbumEntity::class, MediaEntity::class, MediaSource::class,
        AlbumAncestor::class, AlbumSource::class, CollectionEntity::class, CollectionItem::class, ArtworkChoiceEntity::class, MediaRelink::class])
    fun observeCatalogChanges(query: androidx.sqlite.db.SupportSQLiteQuery): Flow<Int>
    @Query("SELECT DISTINCT mediaId FROM file_operations WHERE kind IN ('MOVE','RENAME','TRASH') AND phase IN ('DELETING','DELETED','INDEXING') ORDER BY mediaId")
    fun observeMutatingMedia(): Flow<List<String>>
    @Query("SELECT DISTINCT mediaId FROM file_operations WHERE kind IN ('MOVE','RENAME','TRASH') AND phase IN ('DELETING','DELETED','INDEXING') ORDER BY mediaId")
    suspend fun mutatingMedia(): List<String>
    @Query("SELECT * FROM sources ORDER BY title") fun observeSources(): Flow<List<SourceEntity>>
    @Query("SELECT * FROM albums ORDER BY name") fun observeAlbums(): Flow<List<AlbumEntity>>
    @Query("SELECT * FROM media ORDER BY name") fun observeMedia(): Flow<List<MediaEntity>>
    @Query("SELECT * FROM media_sources") fun observeMembership(): Flow<List<MediaSource>>
    @Query("SELECT * FROM album_ancestors") fun observeAncestors(): Flow<List<AlbumAncestor>>
    @Query("SELECT * FROM album_sources") fun observeAlbumSources(): Flow<List<AlbumSource>>
    @Query("SELECT * FROM collections ORDER BY name") fun observeCollections(): Flow<List<CollectionEntity>>
    @Query("SELECT * FROM collection_items ORDER BY position") fun observeCollectionItems(): Flow<List<CollectionItem>>
    @Query("SELECT * FROM sources") suspend fun sources(): List<SourceEntity>
    @Query("SELECT * FROM sources WHERE id=:id") suspend fun source(id: String): SourceEntity?
    @Query("SELECT * FROM albums") suspend fun albums(): List<AlbumEntity>
    @Query("SELECT * FROM media") suspend fun media(): List<MediaEntity>
    @Query("SELECT * FROM media WHERE id=:id") suspend fun media(id: String): MediaEntity?
    @Query("SELECT * FROM media_sources WHERE mediaId=:id") suspend fun membershipFor(id: String): List<MediaSource>
    @Query("SELECT * FROM sources WHERE id IN (SELECT sourceId FROM media_sources WHERE mediaId=:id)") suspend fun sourcesFor(id: String): List<SourceEntity>
    @Query("SELECT * FROM album_ancestors WHERE albumId=:id") suspend fun ancestryFor(id: String): List<AlbumAncestor>
    @Query("SELECT * FROM albums WHERE id=:id OR id IN (SELECT ancestorId FROM album_ancestors WHERE albumId=:id)") suspend fun visibilityAlbumsFor(id: String): List<AlbumEntity>
    @Query("SELECT EXISTS(SELECT 1 FROM file_operations WHERE mediaId=:id AND kind IN ('MOVE','RENAME','TRASH') AND phase IN ('DELETING','DELETED','INDEXING'))") suspend fun mutating(id: String): Boolean
    @Query("SELECT * FROM media_sources") suspend fun membership(): List<MediaSource>
    @Query("SELECT * FROM album_ancestors") suspend fun ancestors(): List<AlbumAncestor>
    @Query("SELECT * FROM album_sources") suspend fun albumSources(): List<AlbumSource>
    @Query("SELECT * FROM collections") suspend fun collections(): List<CollectionEntity>
    @Query("SELECT * FROM collection_items ORDER BY position") suspend fun collectionItems(): List<CollectionItem>
    @Query("SELECT * FROM artwork_choices") suspend fun artworkChoices(): List<ArtworkChoiceEntity>
    @Upsert suspend fun saveArtworkChoice(choice: ArtworkChoiceEntity)
    @Query("DELETE FROM artwork_choices WHERE target=:target") suspend fun deleteArtworkChoice(target: String)
    @Query("SELECT * FROM file_operations ORDER BY createdAt DESC") fun observeOperations(): Flow<List<FileOperation>>
    @Query("SELECT * FROM file_operations ORDER BY createdAt DESC") suspend fun operations(): List<FileOperation>
    @Query("SELECT * FROM file_operations WHERE id=:id") suspend fun operation(id: String): FileOperation?
    @Upsert suspend fun saveOperation(operation: FileOperation)
    @Query("UPDATE file_operations SET cancelRequested=1 WHERE id=:id") suspend fun cancelOperation(id: String)
    @Query("UPDATE file_operations SET cancelRequested=0 WHERE id=:id") suspend fun resumeOperation(id: String)
    @Query("SELECT * FROM trash ORDER BY deletedAt DESC") fun observeTrash(): Flow<List<TrashEntry>>
    @Query("SELECT * FROM trash") suspend fun trash(): List<TrashEntry>
    @Query("SELECT * FROM trash WHERE mediaId=:id") suspend fun trash(id: String): TrashEntry?
    @Upsert suspend fun saveTrash(entry: TrashEntry)
    @Query("DELETE FROM trash WHERE mediaId=:id") suspend fun removeTrash(id: String)
    @Query("DELETE FROM media_sources WHERE mediaId=:id") suspend fun clearMembership(id: String)
    @Query("DELETE FROM media_sources WHERE sourceId=:id") suspend fun clearSourceMembership(id: String)
    @Query("DELETE FROM media_sources WHERE mediaId IN (:ids)") suspend fun clearMemberships(ids: List<String>)
    @Query("SELECT * FROM media_relinks") suspend fun relinks(): List<MediaRelink>
    @Query("SELECT mediaId FROM media_relinks WHERE oldId=:id") suspend fun relink(id: String): String?
    @Upsert suspend fun saveRelink(link: MediaRelink)
    @Upsert suspend fun saveRelinks(links: List<MediaRelink>)
    @Query("DELETE FROM media WHERE id=:id") suspend fun deleteMedia(id: String)
    @Query("DELETE FROM media WHERE id IN (:ids)") suspend fun deleteMediaBatch(ids: List<String>)
    @Upsert suspend fun saveSource(source: SourceEntity)
    @Upsert suspend fun saveAlbums(albums: List<AlbumEntity>)
    @Upsert suspend fun saveMedia(media: List<MediaEntity>)
    @Upsert suspend fun saveMembership(items: List<MediaSource>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun saveAncestors(items: List<AlbumAncestor>)
    @Upsert suspend fun saveAlbumSources(items: List<AlbumSource>)
    @Query("UPDATE album_sources SET present=0 WHERE sourceId=:id AND scan != :scan") suspend fun missingAlbums(id: String, scan: Long)
    @Query("UPDATE albums SET hidden=:hidden WHERE id IN (:ids)") suspend fun hideAlbums(ids: List<String>, hidden: Boolean)
    @Query("UPDATE albums SET coverMediaId=:mediaId WHERE id=:id") suspend fun albumCover(id: String, mediaId: String?)
    @Query("UPDATE media SET favorite=:favorite, favoriteAddedAt=CASE WHEN :favorite=0 THEN 0 WHEN favorite=1 THEN favoriteAddedAt ELSE :at END WHERE id=:id") suspend fun favorite(id: String, favorite: Boolean, at: Long = System.currentTimeMillis())
    @Query("UPDATE media SET videoPosition=:position, videoPositionAt=:at WHERE id=:id") suspend fun videoPosition(id: String, position: Long, at: Long)
    @Upsert suspend fun saveCollection(collection: CollectionEntity)
    @Upsert suspend fun saveCollectionItems(items: List<CollectionItem>)
    @Query("DELETE FROM collection_items WHERE collectionId=:id") suspend fun clearCollection(id: String)
    @Query("DELETE FROM collections WHERE id=:id") suspend fun deleteCollection(id: String)
}

@Database(entities = [SourceEntity::class, AlbumEntity::class, AlbumAncestor::class, MediaEntity::class,
    MediaSource::class, CollectionEntity::class, CollectionItem::class, AlbumSource::class,
    FileOperation::class, TrashEntry::class, ArtworkChoiceEntity::class, MediaRelink::class, ComparisonSessionEntity::class], version = 14, exportSchema = true)
abstract class CatalogDatabase : RoomDatabase() {
    abstract fun catalog(): CatalogDao
    companion object {
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE media ADD COLUMN videoPositionAt INTEGER NOT NULL DEFAULT 0") }
        }
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media ADD COLUMN audioMetadataRead INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media ADD COLUMN favoriteAddedAt INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS comparison_sessions (id TEXT NOT NULL, name TEXT NOT NULL, state TEXT NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_comparison_sessions_name ON comparison_sessions (name)")
            }
        }
        /** Legacy copies remain intact; Room's version makes this import happen only once. */
        fun comparisonMigration(context: Context) = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_10_11.migrate(db)
                context.getSharedPreferences("comparison_v1", Context.MODE_PRIVATE).all.forEach { (key, raw) ->
                    if (key.startsWith("saved:") && raw is String && ComparisonStore.decode(raw) != null) {
                        val name = key.removePrefix("saved:")
                        val id = java.util.UUID.nameUUIDFromBytes("legacy-comparison:$name".toByteArray(Charsets.UTF_8)).toString()
                        db.execSQL("INSERT INTO comparison_sessions (id,name,state) VALUES(?,?,?)", arrayOf(id, name, raw))
                    }
                }
            }
        }
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("CREATE TABLE IF NOT EXISTS media_relinks (oldId TEXT NOT NULL, mediaId TEXT NOT NULL, PRIMARY KEY(oldId))") }
        }
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE sources ADD COLUMN restoreUri TEXT") }
        }
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE media ADD COLUMN deviceUri TEXT") }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS album_sources (albumId TEXT NOT NULL, sourceId TEXT NOT NULL, scan INTEGER NOT NULL, present INTEGER NOT NULL, PRIMARY KEY(albumId, sourceId))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_album_sources_sourceId ON album_sources(sourceId)")
                db.execSQL("INSERT OR IGNORE INTO album_sources SELECT m.albumId, s.sourceId, s.scan, 1 FROM media m INNER JOIN media_sources s ON m.id=s.mediaId")
                db.execSQL("INSERT OR IGNORE INTO album_sources SELECT p.ancestorId, s.sourceId, s.scan, 1 FROM album_ancestors p INNER JOIN media m ON p.albumId=m.albumId INNER JOIN media_sources s ON m.id=s.mediaId")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS file_operations (id TEXT NOT NULL, mediaId TEXT NOT NULL, kind TEXT NOT NULL, sourceUri TEXT NOT NULL, sourceKey TEXT NOT NULL, sourceAlbumId TEXT NOT NULL, originalName TEXT NOT NULL, mime TEXT NOT NULL, expectedSize INTEGER NOT NULL, expectedModified INTEGER NOT NULL, destinationParent TEXT, destinationAlbumId TEXT, requestedName TEXT NOT NULL, keepBoth INTEGER NOT NULL, resultMediaId TEXT NOT NULL, phase TEXT NOT NULL, temporaryUri TEXT, publishedUri TEXT, finalName TEXT, digest TEXT, bytes INTEGER NOT NULL, cancelRequested INTEGER NOT NULL, message TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, sourceDeletedAt INTEGER, sourceDeletionStartedAt INTEGER, PRIMARY KEY(id))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_file_operations_mediaId ON file_operations(mediaId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS trash (mediaId TEXT NOT NULL, operationId TEXT NOT NULL, fileName TEXT NOT NULL, originalUri TEXT NOT NULL, originalAlbumId TEXT NOT NULL, originalName TEXT NOT NULL, mime TEXT NOT NULL, size INTEGER NOT NULL, digest TEXT NOT NULL, deletedAt INTEGER NOT NULL, expiresAt INTEGER NOT NULL, purging INTEGER NOT NULL, PRIMARY KEY(mediaId))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_trash_expiresAt ON trash(expiresAt)")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE file_operations ADD COLUMN archiveAt INTEGER")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS artwork_choices (target TEXT NOT NULL, coverMediaId TEXT, priority TEXT, PRIMARY KEY(target))")
            }
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE file_operations ADD COLUMN retainedMediaId TEXT")
            }
        }
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE file_operations ADD COLUMN recipe TEXT")
            }
        }
    }
}
