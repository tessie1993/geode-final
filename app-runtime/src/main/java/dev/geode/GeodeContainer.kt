package dev.geode

import android.content.Context
import dev.geode.data.GeodePrefsFiles
import dev.geode.export.ExportRecovery
import dev.geode.export.RenderScratchFiles
import dev.geode.ui.SharedPrefsUserDataRepository
import dev.geode.ui.UserDataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class GeodeContainer(
    context: Context,
    val refreshWidget: suspend () -> Unit,
) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val prefsFiles = GeodePrefsFiles(context)

    val userData: UserDataRepository = SharedPrefsUserDataRepository(prefsFiles.general, appScope)

    val renderScratchFiles = RenderScratchFiles(context.applicationContext.cacheDir)

    init {
        ExportRecovery(context.applicationContext, renderScratchFiles).start(appScope)
    }
}

interface GeodeContainerOwner {
    val container: GeodeContainer
}

val Context.geodeContainer: GeodeContainer
    get() = (applicationContext as GeodeContainerOwner).container
