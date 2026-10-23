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

/**
 * Studio's own database (ADR-0004): the Projects, the videos in them with only the common fields
 * (#21), and the user's default Project. The render queue (#22) adds its tables here.
 *
 * Every version is migrated step by step (ADR-0004). A new version adds its [Migration] to
 * [MIGRATIONS] and a sample of the version before it under `src/test/resources/db`.
 */
@Database(
    entities = [ProjectEntity::class, VideoEntity::class, StudioChoiceEntity::class],
    version = StudioDatabase.VERSION,
    exportSchema = false,
)
abstract class StudioDatabase : RoomDatabase() {
    abstract fun dao(): StudioDao

    companion object {
        const val VERSION = 1
        const val NAME = "studio.db"

        /** Every migration, oldest first. */
        val MIGRATIONS: Array<Migration> = emptyArray()

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
}
