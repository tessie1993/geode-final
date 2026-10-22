package dev.geode.feature.studio.handlers

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration

/**
 * Studio's own database (ADR-0004): the Projects and the videos in them, with only the common fields
 * (#21). The render queue (#22) adds its tables here.
 *
 * Every version is migrated step by step (ADR-0004). A new version adds its [Migration] to
 * [MIGRATIONS] and a sample of the version before it under `src/test/resources/db`.
 */
@Database(entities = [ProjectEntity::class, VideoEntity::class], version = StudioDatabase.VERSION, exportSchema = false)
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
    /** The persisted tree uri the user picked; null for the "Unsorted" Project. */
    val folder: String?,
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

@Dao
abstract class StudioDao {
    @Query("SELECT * FROM projects ORDER BY createdAtMs DESC, id DESC")
    abstract suspend fun projects(): List<ProjectEntity>

    @Query("SELECT * FROM videos")
    abstract suspend fun videos(): List<VideoEntity>

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

    /** Forgets the Project and its videos together. */
    @Transaction
    open suspend fun deleteProject(id: Long) {
        deleteVideosIn(id)
        deleteProjectRow(id)
    }

    @Query("DELETE FROM videos WHERE projectId = :projectId")
    protected abstract suspend fun deleteVideosIn(projectId: Long)

    @Query("DELETE FROM projects WHERE id = :id")
    protected abstract suspend fun deleteProjectRow(id: Long)
}
