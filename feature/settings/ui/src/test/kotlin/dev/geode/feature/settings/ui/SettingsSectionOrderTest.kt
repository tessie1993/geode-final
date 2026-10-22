package dev.geode.feature.settings.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The sections the owner chose on 2026-10-22 (#101), in their order. CPU/GPU waits for #28. */
class SettingsSectionOrderTest {
    @Test
    fun `the sections are shown in the chosen order`() {
        assertEquals(
            listOf(
                SettingsSection.THEME,
                SettingsSection.APPLICATION,
                SettingsSection.AUDIO,
                SettingsSection.EXPORT,
                SettingsSection.DEFAULT_PATHS,
                SettingsSection.HELP,
                SettingsSection.ABOUT,
            ),
            SettingsSection.entries,
        )
    }
}
