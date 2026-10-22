package dev.geode.feature.diagnostics.ui

import dev.geode.engine.types.Micros
import dev.geode.feature.diagnostics.api.DeveloperState
import dev.geode.feature.diagnostics.api.DiagnosticsState
import dev.geode.feature.diagnostics.api.OverlayState
import dev.geode.feature.diagnostics.api.ShareProblem
import dev.geode.feature.diagnostics.api.ShareState
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsModelTest {
    @Test
    fun `each share state shows as the button and message say`() {
        val expected =
            mapOf(
                ShareState.Idle to ShareUi.READY,
                ShareState.Preparing to ShareUi.WORKING,
                ShareState.Sharing to ShareUi.WORKING,
                ShareState.Failed(ShareProblem.WRITE_FAILED) to ShareUi.FAILED_WRITE,
                ShareState.Failed(ShareProblem.NO_SHARE_TARGET) to ShareUi.FAILED_NO_TARGET,
                ShareState.Failed(ShareProblem.TIMED_OUT) to ShareUi.FAILED_TIMED_OUT,
            )
        for ((share, ui) in expected) {
            assertEquals("$share", ui, DiagnosticsState(share = share).toSectionModel(VERSION).share)
        }
    }

    @Test
    fun `the developer options show once unlocked, and the switch follows the overlay`() {
        val locked = DiagnosticsState(developer = DeveloperState.Locked(taps = 3))
        val hidden = DiagnosticsState(developer = DeveloperState.Unlocked(OverlayState.Hidden))
        val shown = DiagnosticsState(developer = DeveloperState.Unlocked(OverlayState.Shown(previewStats)))

        assertEquals(section(developerUnlocked = false, overlayOn = false), locked.toSectionModel(VERSION))
        assertEquals(section(developerUnlocked = true, overlayOn = false), hidden.toSectionModel(VERSION))
        assertEquals(section(developerUnlocked = true, overlayOn = true), shown.toSectionModel(VERSION))
        assertEquals(null, locked.overlayModel())
        assertEquals(null, hidden.overlayModel())
        assertEquals(DeveloperOverlayModel(previewStats), shown.overlayModel())
    }

    @Test
    fun `frame time reads in milliseconds with one decimal`() {
        assertEquals("16.7", frameMillis(Micros(16_667)))
        assertEquals("16.6", frameMillis(Micros(16_649)))
        assertEquals("16.7", frameMillis(Micros(16_650)))
        assertEquals("0.0", frameMillis(Micros(0)))
        assertEquals("33.3", frameMillis(Micros(33_333)))
    }

    private fun section(
        developerUnlocked: Boolean,
        overlayOn: Boolean,
    ) = DiagnosticsSectionModel(VERSION, ShareUi.READY, developerUnlocked, overlayOn)

    private companion object {
        const val VERSION = "1.8.0"
    }
}
