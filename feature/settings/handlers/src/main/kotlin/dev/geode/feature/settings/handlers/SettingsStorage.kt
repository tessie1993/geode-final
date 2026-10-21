package dev.geode.feature.settings.handlers

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope

/**
 * The one DataStore the settings live in (ADR-0004). The first time it is read, it takes today's
 * settings from the old SharedPreferences files. A file that cannot be parsed is replaced by an
 * empty one, which the migration then fills from the old files again.
 */
fun settingsDataStore(
    context: Context,
    scope: CoroutineScope,
): DataStore<Preferences> {
    val app = context.applicationContext
    return PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        migrations = listOf(LegacySettingsMigration { readLegacyPreferences(app) }),
        scope = scope,
        produceFile = { app.preferencesDataStoreFile(SettingsKeys.FILE) },
    )
}

/** The settings in today's SharedPreferences files, by key; nothing else in those files. */
fun readLegacyPreferences(context: Context): Map<String, Any?> =
    SettingsKeys.LEGACY_FILES
        .flatMap { (file, keys) ->
            context
                .getSharedPreferences(file, Context.MODE_PRIVATE)
                .all
                .filterKeys { it in keys }
                .entries
        }.associate { it.key to it.value }

/**
 * Moves the settings into DataStore once, when it holds no [SettingsKeys.VERSION] yet, with their
 * keys and formats unchanged. The old files are read and left as they are: the old code still uses
 * them until the switch-over (#17).
 */
class LegacySettingsMigration(
    private val readLegacy: suspend () -> Map<String, Any?>,
) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean = currentData[VERSION] == null

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData
            .toMutablePreferences()
            .apply {
                write(SettingsCodec.encode(SettingsCodec.decode(readLegacy())))
                this[VERSION] = SettingsKeys.CURRENT_VERSION
            }.toPreferences()

    override suspend fun cleanUp() = Unit

    private companion object {
        val VERSION = intPreferencesKey(SettingsKeys.VERSION)
    }
}

/** What DataStore holds, as the codec reads it: key name to value. */
internal fun Preferences.byName(): Map<String, Any?> = asMap().mapKeys { it.key.name }

/** Writes what the codec encoded; a null removes the key. */
internal fun MutablePreferences.write(entries: Map<String, Any?>) {
    for ((name, value) in entries) {
        when (value) {
            null -> remove(stringPreferencesKey(name))
            is Boolean -> this[booleanPreferencesKey(name)] = value
            is Int -> this[intPreferencesKey(name)] = value
            is Float -> this[floatPreferencesKey(name)] = value
            is String -> this[stringPreferencesKey(name)] = value
            is Set<*> -> this[stringSetPreferencesKey(name)] = value.filterIsInstance<String>().toSet()
            else -> error("$name: cannot store a ${value::class.simpleName}")
        }
    }
}
