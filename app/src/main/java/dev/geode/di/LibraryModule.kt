package dev.geode.di

import android.app.Application
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.geode.feature.library.api.Library
import dev.geode.feature.library.handlers.ContentPlaylistFiles
import dev.geode.feature.library.handlers.LibraryDatabase
import dev.geode.feature.library.handlers.LibrarySources
import dev.geode.feature.library.handlers.MediaStoreScanner
import dev.geode.feature.library.handlers.RoomLibraryStorage
import dev.geode.feature.library.handlers.TagReader
import dev.geode.feature.library.handlers.libraryStore
import dev.geode.feature.playback.api.Playback
import dev.geode.feature.playback.handlers.PlaybackStorage
import dev.geode.kernel.FeatureRuntime
import dev.geode.library.ImportingLibraryStorage
import javax.inject.Singleton

/**
 * The Library's one app-scoped store (ADR-0003, ADR-0004): the songs on the phone, the favourites, the
 * edits and the excluded folders, for every screen and the car. It is built on first use and lives as
 * long as the process, so where the user is browsing survives every route (#17).
 */
@Module
@InstallIn(SingletonComponent::class)
object LibraryModule {
    @Provides
    @Singleton
    fun provideLibrary(
        application: Application,
        playback: Playback,
        playbackStorage: PlaybackStorage,
        runtime: FeatureRuntime,
    ): Library =
        libraryStore(
            playback,
            LibrarySources(
                scanner = MediaStoreScanner(application),
                tags = TagReader.NONE,
                storage =
                    ImportingLibraryStorage.of(
                        context = application,
                        stored = RoomLibraryStorage(LibraryDatabase.open(application).dao()),
                        playback = playbackStorage,
                        marks = application.getSharedPreferences(IMPORT_MARKS, Context.MODE_PRIVATE),
                    ),
                files = ContentPlaylistFiles(application.contentResolver),
            ),
            runtime,
        )

    private const val IMPORT_MARKS = "geode_library_import"
}
