package dev.geode.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.geode.R
import dev.geode.engine.types.Millis
import dev.geode.kernel.FeatureFailure
import dev.geode.kernel.FeatureRuntime
import dev.geode.kernel.ScheduledTimer
import dev.geode.kernel.TimerScheduler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** How long the banner stays on screen once it is seen, as the old notice banner did. */
val ERROR_BANNER_VISIBLE = Millis(8_000)

/**
 * What the app-wide error banner draws (#147, ADR-0004): one failure's [message]. [counting] says
 * whether its time on screen has started; until it has, the banner says it is on screen.
 */
@Immutable
data class ErrorBannerModel(
    val message: String,
    val counting: Boolean,
)

/** What the banner sends back (ADR-0003). */
sealed interface ErrorBannerAction {
    /** The banner's message is on screen. */
    data object Shown : ErrorBannerAction

    data object Dismiss : ErrorBannerAction
}

/**
 * App UI logic for the one app-wide error banner (#147, ADR-0004). It is the App UI's, not any
 * feature's (owner, 2026-10-22): it hears every failure reported to the shared error channel
 * ([failures]) for as long as the app runs, and keeps the one the banner shows.
 *
 * It shows one failure at a time: a newer one replaces the one shown, so they never stack. A failure
 * waits until the banner is on screen ([ErrorBannerAction.Shown]), then stays for
 * [ERROR_BANNER_VISIBLE] from then, or until it is dismissed. A failure that replaces one already on
 * screen is on screen at once, so its time starts when it arrives. Its [model] changes on [main];
 * the countdown runs on [timers].
 *
 * `:app` lays [AppErrorBanner] over every screen at the switch-over (#17), in place of the old
 * playback notice banner.
 */
@Singleton
class AppErrorBannerState(
    failures: Flow<FeatureFailure>,
    private val timers: TimerScheduler,
    private val scope: CoroutineScope,
    private val main: CoroutineDispatcher,
) {
    /** The banner over the app's one [FeatureRuntime]: its error channel, its timers and its main thread. */
    @Inject
    constructor(runtime: FeatureRuntime) : this(runtime.errors.failures, runtime.timers, runtime.scope, runtime.main)

    private val shown = MutableStateFlow<ErrorBannerModel?>(null)

    /** What the banner draws; null while it shows nothing. */
    val model: StateFlow<ErrorBannerModel?> = shown.asStateFlow()

    // The countdown of the failure on screen, and which one it is: a countdown that fires after it
    // was replaced or cancelled hides nothing.
    private var countdown: ScheduledTimer? = null
    private var armed = 0

    init {
        scope.launch(main + CoroutineName(ERROR_BANNER)) { failures.collect { reported(it) } }
    }

    fun onAction(action: ErrorBannerAction) {
        when (action) {
            ErrorBannerAction.Shown -> seen()
            ErrorBannerAction.Dismiss -> dismissed()
        }
    }

    private fun reported(failure: FeatureFailure) {
        val onScreen = shown.value?.counting == true
        shown.value = ErrorBannerModel(failure.message, counting = onScreen)
        if (onScreen) count()
    }

    private fun seen() {
        val waiting = shown.value?.takeUnless { it.counting } ?: return
        shown.value = waiting.copy(counting = true)
        count()
    }

    private fun dismissed() {
        stopCounting()
        shown.value = null
    }

    /** Counts [ERROR_BANNER_VISIBLE] from now, in place of any countdown already running. */
    private fun count() {
        stopCounting()
        val mine = armed
        countdown = timers.schedule(ERROR_BANNER_VISIBLE) { scope.launch(main) { timedOut(mine) } }
    }

    private fun stopCounting() {
        countdown?.cancel()
        countdown = null
        armed++
    }

    private fun timedOut(which: Int) {
        if (which != armed) return
        countdown = null
        shown.value = null
    }

    private companion object {
        const val ERROR_BANNER = "error-banner"
    }
}

/** The app-wide error banner's place: it draws what [state] keeps and sends it every tap. */
@Composable
fun AppErrorBanner(
    state: AppErrorBannerState,
    modifier: Modifier = Modifier,
) {
    val model by state.model.collectAsStateWithLifecycle()
    ErrorBanner(model, onAction = state::onAction, modifier)
}

/**
 * The one app-wide error banner (#147, ADR-0004), laid over every screen: the latest failure any
 * feature reported, one at a time. Tapping it, or Dismiss, hides it. Stateless (ADR-0003): it draws
 * [model], and draws nothing while [model] is null. Once its message is on screen it sends
 * [ErrorBannerAction.Shown], so the time it stays counts from when it was seen.
 */
@Composable
fun ErrorBanner(
    model: ErrorBannerModel?,
    onAction: (ErrorBannerAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (model == null) return
    LaunchedEffect(model) {
        if (!model.counting) onAction(ErrorBannerAction.Shown)
    }
    val dismissDescription = stringResource(R.string.error_banner_dismiss_description)
    Box(modifier.fillMaxWidth().padding(horizontal = BannerDimens.marginX, vertical = BannerDimens.marginY)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(BannerDimens.corner))
                .background(MaterialTheme.colorScheme.errorContainer)
                .clickable { onAction(ErrorBannerAction.Dismiss) }
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(horizontal = BannerDimens.paddingX, vertical = BannerDimens.paddingY),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                model.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(BannerDimens.gap))
            Text(
                stringResource(R.string.error_banner_dismiss),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier =
                    Modifier
                        .clickable { onAction(ErrorBannerAction.Dismiss) }
                        .semantics { contentDescription = dismissDescription }
                        .padding(BannerDimens.gap),
            )
        }
    }
}

/** The banner's sizes, in one place, as the old notice banner had them. */
private object BannerDimens {
    val marginX = 12.dp
    val marginY = 8.dp
    val corner = 14.dp
    val paddingX = 14.dp
    val paddingY = 12.dp
    val gap = 8.dp
}
