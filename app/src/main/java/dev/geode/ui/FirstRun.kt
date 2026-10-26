package dev.geode.ui

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.geode.R
import dev.geode.design.glass.GlassButton
import dev.geode.design.glass.calmBackdrop
import dev.geode.feature.settings.api.FolderCommand
import dev.geode.feature.settings.api.FolderUri
import dev.geode.feature.settings.api.OnboardingCommand
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.settings.api.SettingsState
import dev.geode.feature.studio.ui.StudioSetupRoute
import dev.geode.ui.shell.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The audio-read permission for this device's API level. */
internal val audioPermission: String
    get() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

/** What first-run setup asks for (#187): music access, notifications and the microphone. */
private val setupPermissions: Array<String>
    get() =
        buildList {
            add(audioPermission)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.RECORD_AUDIO)
        }.toTypedArray()

/** The steps of first-run setup that come after the photosensitivity notice, in order. */
private enum class SetupStep {
    TOUR,
    PERMISSIONS,
    SAVE_FOLDER,
}

/**
 * First-run setup (#187), over the app, from what Settings keeps: the photosensitivity notice, then
 * the intro tour (every page can Skip now), the permissions, and the Save folder, which cannot be
 * skipped. The app does not finish starting until the Save folder is set, and goes back to that step
 * whenever the folder is lost (deleted, or its access withdrawn). [replayTour] is Settings > Help
 * replaying the tour, and [onReplayOver] ends it. The tour walks the real app through [onNavigate].
 */
@Composable
fun FirstRunSetup(
    settings: Settings,
    replayTour: Boolean,
    onReplayOver: () -> Unit,
    onNavigate: (Place) -> Unit,
) {
    val state by settings.state.collectAsStateWithLifecycle()
    val values = (state as? SettingsState.Ready)?.values ?: return
    val onboarding = values.onboarding
    val folder = values.folders.videoFolder
    val folderUsable = rememberFolderUsable(folder)
    var step by rememberSaveable { mutableStateOf(if (onboarding.tutorialOnFirstRun) SetupStep.TOUR else SetupStep.PERMISSIONS) }
    val finishTour = { settings.send(OnboardingCommand.FinishTutorial) }
    when {
        !onboarding.safetyAcknowledged ->
            SafetyConsent(onAcknowledge = { settings.send(OnboardingCommand.AcknowledgeSafety) })
        !onboarding.setupDone && step == SetupStep.TOUR ->
            Tour(settings, onboarding.tutorialOnFirstRun, onNavigate) {
                finishTour()
                step = SetupStep.PERMISSIONS
            }
        !onboarding.setupDone && step == SetupStep.PERMISSIONS -> PermissionsStep { step = SetupStep.SAVE_FOLDER }
        !onboarding.setupDone || folderUsable == false ->
            SaveFolderStep(settings, folder) {
                if (!onboarding.setupDone) {
                    settings.send(OnboardingCommand.FinishSetup)
                    onNavigate(Place.START)
                }
            }
        replayTour ->
            Tour(settings, onboarding.tutorialOnFirstRun, onNavigate) {
                finishTour()
                onReplayOver()
            }
    }
}

@Composable
private fun Tour(
    settings: Settings,
    showNextTime: Boolean,
    onNavigate: (Place) -> Unit,
    onOver: () -> Unit,
) {
    TutorialOverlay(
        steps = TutorialStep.entries,
        dontShowAgain = !showNextTime,
        onDontShowAgainChange = { settings.send(OnboardingCommand.SetTutorialOnFirstRun(!it)) },
        onNavigate = onNavigate,
        onDismiss = onOver,
    )
}

/** Music access, notifications and the microphone, asked for together; any answer goes on. */
@Composable
private fun PermissionsStep(onDone: () -> Unit) {
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onDone() }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .calmBackdrop()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.first_run_access_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.first_run_access_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        GlassButton(onClick = { ask.launch(setupPermissions) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.first_run_access_allow))
        }
        Spacer(Modifier.height(10.dp))
        GlassButton(onClick = onDone, modifier = Modifier.fillMaxWidth(), filled = false) {
            Text(stringResource(R.string.first_run_access_skip))
        }
    }
}

/** The Save folder step: Studio's own setup step, folded into first run (#187). It goes on only with a folder. */
@Composable
private fun SaveFolderStep(
    settings: Settings,
    folder: FolderUri?,
    onDone: () -> Unit,
) {
    Box(Modifier.fillMaxSize().calmBackdrop().systemBarsPadding()) {
        StudioSetupRoute(
            folder = folder?.value,
            onFolderPicked = { settings.send(FolderCommand.SetVideoFolder(FolderUri(it))) },
            onDone = { if (folder != null) onDone() },
        )
    }
}

/**
 * Whether [folder] can still be written to: it exists and its persisted read and write grant is still
 * held. Checked again each time the app comes back to the front. Null while it is being checked.
 */
@Composable
private fun rememberFolderUsable(folder: FolderUri?): Boolean? {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val resumed by lifecycle.currentStateFlow.collectAsState()
    val usable by produceState<Boolean?>(null, folder, resumed.isAtLeast(Lifecycle.State.RESUMED)) {
        value = withContext(Dispatchers.IO) { folder != null && context.folderUsable(Uri.parse(folder.value)) }
    }
    return usable
}

private fun Context.folderUsable(tree: Uri): Boolean {
    val granted =
        contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission }
    if (!granted) return false
    return runCatching {
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        contentResolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use {
            it.moveToFirst()
        } ?: false
    }.getOrDefault(false)
}
