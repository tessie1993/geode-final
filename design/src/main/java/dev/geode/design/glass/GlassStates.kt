package dev.geode.design.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The friendly middle of a page that has nothing to show yet, or could not show it: an optional [icon],
 * a short [title], one helpful line ([body]) and at most one action. Used for empty, error and
 * first-time states alike, so they all read the same way.
 */
@Composable
fun GlassMessage(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = GlassSpacing.ExtraLarge, vertical = GlassSpacing.Section),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(GlassSpacing.Medium),
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(MessageIcon),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (body != null) {
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            GlassButton(onClick = onAction, filled = false) { Text(actionLabel) }
        }
    }
}

/** Something is on its way: a spinner, and a short [label] saying what, when there is one. */
@Composable
fun GlassLoading(
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(GlassSpacing.Section),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(GlassSpacing.Medium),
    ) {
        CircularProgressIndicator()
        if (label != null) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** How far along something is, or, with a null [progress], that it is under way. */
@Composable
fun GlassProgress(
    progress: Float?,
    modifier: Modifier = Modifier,
) {
    if (progress == null) {
        LinearProgressIndicator(modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = modifier.fillMaxWidth())
    }
}

/**
 * A notice that floats over the page, read out politely: news, a success or a failure, in its [tone]'s
 * colours, with at most one action. Opaque, so it reads over anything, the live Visual included.
 */
@Composable
fun GlassNotice(
    message: String,
    modifier: Modifier = Modifier,
    tone: GlassTone = GlassTone.INFO,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = LocalGlassStyle.current.colors
    val shape = MaterialTheme.shapes.large
    val content: Color =
        when (tone) {
            GlassTone.INFO -> colors.textPrimary
            GlassTone.SUCCESS -> colors.onAccentContainer
            GlassTone.ERROR -> colors.onErrorContainer
        }
    val surface =
        when (tone) {
            GlassTone.INFO -> Modifier.glassSurface(GlassKind.CARD, shape, GlassBackdrop.Unknown)
            GlassTone.SUCCESS -> Modifier.background(colors.accentContainer, shape)
            GlassTone.ERROR -> Modifier.background(colors.errorContainer, shape)
        }
    Row(
        modifier
            .fillMaxWidth()
            .then(surface)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(start = GlassSpacing.Large, end = GlassSpacing.Small, top = GlassSpacing.Small, bottom = GlassSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GlassSpacing.Small),
    ) {
        Text(
            message,
            modifier = Modifier.weight(1f).padding(vertical = GlassSpacing.Small),
            style = MaterialTheme.typography.bodyMedium,
            color = content,
        )
        if (actionLabel != null && onAction != null) {
            val haptics = LocalGlassHaptics.current
            TextButton(onClick = { pressed(haptics, onAction) }) { Text(actionLabel, color = content) }
        }
    }
}

/** The icon above a [GlassMessage]. */
private val MessageIcon = 40.dp
