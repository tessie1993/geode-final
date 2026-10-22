package dev.geode.feature.playback.ui

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

/**
 * The one app-wide error banner (#147, ADR-0004), laid over every screen: the latest failure any
 * feature reported, one at a time. Tapping it, or Dismiss, hides it; it also goes by itself a while
 * after it is first on screen. Stateless (ADR-0003): it draws [model], and draws nothing while
 * [model] is null. Once its message is on screen it sends [BannerAction.Shown], so the time it stays
 * counts from when it was seen.
 */
@Composable
fun ErrorBanner(
    model: BannerModel?,
    onAction: (BannerAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (model == null) return
    LaunchedEffect(model) {
        if (!model.counting) onAction(BannerAction.Shown)
    }
    val dismissDescription = stringResource(R.string.playback_banner_dismiss_description)
    Box(modifier.fillMaxWidth().padding(horizontal = PlayerDimens.bannerMarginX, vertical = PlayerDimens.bannerMarginY)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(PlayerDimens.bannerCorner))
                .background(MaterialTheme.colorScheme.errorContainer)
                .clickable { onAction(BannerAction.Dismiss) }
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(horizontal = PlayerDimens.bannerPaddingX, vertical = PlayerDimens.bannerPaddingY),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                model.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(PlayerDimens.small))
            Text(
                stringResource(R.string.playback_banner_dismiss),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier =
                    Modifier
                        .clickable { onAction(BannerAction.Dismiss) }
                        .semantics { contentDescription = dismissDescription }
                        .padding(PlayerDimens.small),
            )
        }
    }
}
