package dev.geode.feature.studio.handlers

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Studio's own database (ADR-0004): the Projects, the videos in them with only the common fields
 * (#21), the user's default Project, since version 2 the Renders: the queue, and each finished
 * Render's choices and frozen look so it can be made again (#338, #327), since version 3 which
 * one-time imports are done (#347), and since version 4 the video a Short or sibling was made from
 * (#328).
 *
 * Every version is migrated step by step (ADR-0004). A new version adds its [Migration] to
 * [MIGRATIONS] and a sample of the version before it under `src/test/resources/db`.
 */
@Database(
    entities = [ProjectEntity::class, VideoEntity::class, StudioChoiceEntity::class, RenderEntity::class, ImportEntity::class],
    version = StudioDatabase.VERSION,
    exportSchema = false,
)
abstract class StudioDatabase : RoomDatabase() {
    abstract fun dao(): StudioDao

    companion object {
        const val VERSION = 4
        const val NAME = "studio.db"

        /** Every migration, oldest first. */
        val MIGRATIONS: Array<Migration> = arrayOf(AddRenders, AddImports, AddVideoSources)

        fun open(
            context: Context,
            name: String = NAME,
        ): StudioDatabase =
            Room
                .databaseBuilder(context, StudioDatabase::class.java, name)
                .apply { MIGRATIONS.forEach { addMigrations(it) } }
                .build()
    }
}

/** Version 2 (#338): the Renders, queued and finished. */
object AddRenders : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `renders` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`batch` INTEGER NOT NULL, `spec` TEXT NOT NULL, `look` TEXT NOT NULL, `videoFile` TEXT, " +
                "`queuedAtMs` INTEGER NOT NULL)",
        )
    }
}

/** Version 3 (#347): which one-time imports are done. */
object AddImports : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `imports` (`name` TEXT NOT NULL, PRIMARY KEY(`name`))")
    }
}

/** Version 4 (#328): the video a Short or an Export as sibling was made from. */
object AddVideoSources : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `videos` ADD COLUMN `sourceId` INTEGER")
    }
}

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAtMs: Long,
)

@Entity(tableName = "videos", indices = [Index("projectId")])
data class VideoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    /** The [dev.geode.feature.studio.api.VideoKind] name. */
    val kind: String,
    val name: String,
    val file: String,
    val thumbnail: String?,
    val lengthMs: Long,
    val width: Int,
    val height: Int,
    val song: String,
    /** The video it was made from, for a Short or a sibling (#328); null otherwise. */
    val sourceId: Long? = null,
)

/** Studio's own choices, in one row: the default Project (null when none is chosen). */
@Entity(tableName = "choices")
data class StudioChoiceEntity(
    @PrimaryKey val id: Int = ONLY_ROW,
    val defaultProjectId: Long?,
) {
    companion object {
        const val ONLY_ROW = 0
    }
}

/**
 * One Render (#338). [spec] is its choices in [RenderSpecFormat]; [look] its frozen look; [batch] the
 * first Render queued with it. [videoFile] is the video once it is made: a Render without one has not
 * finished, and resumes after a restart.
 */
@Entity(tableName = "renders")
data class RenderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val batch: Long,
    val spec: String,
    val look: String,
    val videoFile: String?,
    val queuedAtMs: Long,
)

/** A one-time import that is done (#347), by its [name]: it is never done again. */
@Entity(tableName = "imports")
data class ImportEntity(
    @PrimaryKey val name: String,
)

/** What an import saved: the new Project's id, and the videos' ids in order. */
class ImportedRows(
    val projectId: Long,
    val videoIds: List<Long>,
)

@Dao
abstract class StudioDao {
    @Query("SELECT * FROM projects ORDER BY createdAtMs DESC, id DESC")
    abstract suspend fun projects(): List<ProjectEntity>

    @Query("SELECT * FROM videos ORDER BY id")
    abstract suspend fun videos(): List<VideoEntity>

    @Query("SELECT defaultProjectId FROM choices WHERE id = 0")
    abstract suspend fun defaultProject(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun setChoices(row: StudioChoiceEntity)

    @Insert
    abstract suspend fun insertProject(row: ProjectEntity): Long

    @Insert
    abstract suspend fun insertVideos(rows: List<VideoEntity>): List<Long>

    @Query("UPDATE projects SET name = :name WHERE id = :id")
    abstract suspend fun renameProject(
        id: Long,
        name: String,
    )

    @Query("UPDATE videos SET projectId = :projectId WHERE id = :id")
    abstract suspend fun moveVideo(
        id: Long,
        projectId: Long,
    )

    /** Saves a Project, and makes it the default when [makeDefault], together. */
    @Transaction
    open suspend fun createProject(
        row: ProjectEntity,
        makeDefault: Boolean,
    ): Long {
        val id = insertProject(row)
        if (makeDefault) setChoices(StudioChoiceEntity(defaultProjectId = id))
        return id
    }

    /**
     * Forgets the Project, moving its videos to [moveTo] or forgetting them too when it is null, and
     * stops it being the default, all together.
     */
    @Transaction
    open suspend fun deleteProject(
        id: Long,
        moveTo: Long?,
    ) {
        if (moveTo == null) deleteVideosIn(id) else moveVideosIn(id, moveTo)
        deleteProjectRow(id)
        clearDefault(id)
    }

    @Query("DELETE FROM videos WHERE projectId = :projectId")
    protected abstract suspend fun deleteVideosIn(projectId: Long)

    @Query("UPDATE videos SET projectId = :to WHERE projectId = :from")
    protected abstract suspend fun moveVideosIn(
        from: Long,
        to: Long,
    )

    @Query("DELETE FROM projects WHERE id = :id")
    protected abstract suspend fun deleteProjectRow(id: Long)

    @Query("UPDATE choices SET defaultProjectId = NULL WHERE defaultProjectId = :id")
    protected abstract suspend fun clearDefault(id: Long)

    @Insert
    protected abstract suspend fun insertRenders(rows: List<RenderEntity>): List<Long>

    @Query("UPDATE renders SET batch = :batch WHERE id IN (:ids)")
    protected abstract suspend fun setBatch(
        ids: List<Long>,
        batch: Long,
    )

    /** Saves [rows] as one batch, named after the first of them, together; returns their ids in order. */
    @Transaction
    open suspend fun queueRenders(rows: List<RenderEntity>): List<Long> {
        val ids = insertRenders(rows)
        if (ids.isNotEmpty()) setBatch(ids, ids.first())
        return ids
    }

    @Query("SELECT * FROM renders WHERE videoFile IS NULL ORDER BY id")
    abstract suspend fun unfinishedRenders(): List<RenderEntity>

    @Query("SELECT look FROM renders WHERE id = :id")
    abstract suspend fun lookOf(id: Long): String?

    /** The Renders that made a video, oldest first. */
    @Query("SELECT * FROM renders WHERE videoFile IS NOT NULL ORDER BY id")
    abstract suspend fun finishedRenders(): List<RenderEntity>

    /** The look of the newest Render that made [file]. */
    @Query("SELECT look FROM renders WHERE videoFile = :file ORDER BY id DESC LIMIT 1")
    abstract suspend fun lookOfVideo(file: String): String?

    @Query("UPDATE renders SET videoFile = :file WHERE id = :id")
    abstract suspend fun renderSaved(
        id: Long,
        file: String,
    )

    @Query("DELETE FROM renders WHERE id = :id")
    abstract suspend fun forgetRender(id: Long)

    @Query("SELECT COUNT(*) FROM imports WHERE name = :name")
    abstract suspend fun importsNamed(name: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun markImported(row: ImportEntity)

    /**
     * Saves [project] and [videos] in it (their Project ids are set here) and marks the import [name]
     * done, all together, so an import is either wholly kept or tried again.
     */
    @Transaction
    open suspend fun importVideos(
        name: String,
        project: ProjectEntity,
        videos: List<VideoEntity>,
    ): ImportedRows {
        val projectId = insertProject(project)
        val ids = insertVideos(videos.map { it.copy(projectId = projectId) })
        markImported(ImportEntity(name))
        return ImportedRows(projectId, ids)
    }
}
