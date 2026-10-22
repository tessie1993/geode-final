package dev.geode.design.theme

import androidx.compose.ui.graphics.Color
import dev.geode.design.R

/** The App UI's theme packs, one per Element (CONTEXT.md). Lapis Lazuli stays the default until #17. */
object ThemePackCatalog {
    /**
     * Water (#105): translucent inner-lit sea-glass pebbles on opaque wet slate, over deep teal-black water. Its
     * parts are drawn in code ([WaterSurface]) until the art lands.
     */
    val water =
        ThemePack(
            slug = "water",
            palette =
                StonePalette(
                    background = WaterTokens.Background,
                    backgroundDeep = WaterTokens.BackgroundDeep,
                    surface = WaterTokens.Surface,
                    surfaceHigh = WaterTokens.SurfaceHigh,
                    primary = WaterTokens.Aqua,
                    secondary = WaterTokens.SeaGreen,
                    accent = WaterTokens.Amber,
                    glow = WaterTokens.Glow,
                    onBackground = WaterTokens.Text,
                    onSurface = WaterTokens.Text,
                    muted = WaterTokens.Muted,
                    outline = WaterTokens.Divider,
                    danger = WaterTokens.Danger,
                ),
            motion = WaterMotion,
            // ponytail: S1 (wet slate tile), B1/L2 (water tiles) and the H0 Hero scene are not in the repo;
            // panels stay untextured and CrystalBackground draws the plain water gradient until they land.
            material =
                StoneMaterial(
                    tile = null,
                    glowOverlay = null,
                    refractionOverlay = null,
                    ambientPortrait = null,
                    ambientLandscape = null,
                    ambientSquare = null,
                    backgroundOpacity = 1f,
                    surfaceOpacity = 1f,
                    disabledOpacity = 0.4f,
                ),
            // ponytail: W-A1 (tap), W-A2 (select spin) and W-A4 (page) sounds are not cut yet; nothing plays them today.
            sounds = StoneSounds(click = null, confirm = null, swoop = null),
            surfaces = emptyMap(),
            drawn = { component, state, modifier, reducedMotion -> WaterSurface(component, state, modifier, reducedMotion) },
            pebbleText = WaterTokens.OnPebble,
            haptics = WaterHaptics,
        )

    /** Every pack the Element picker can offer. */
    val all: List<ThemePack> by lazy { listOf(lapisLazuli, water) }

    /** The pack with [slug], or Lapis Lazuli for an unknown one. */
    fun bySlug(slug: String?): ThemePack = all.firstOrNull { it.slug == slug } ?: lapisLazuli

    val lapisLazuli =
        ThemePack(
            slug = "lapis-lazuli",
            palette =
                StonePalette(
                    background = Color(0xFF050A20),
                    backgroundDeep = Color(0xFF02040D),
                    surface = Color(0xFF10265C),
                    surfaceHigh = Color(0xFF173A83),
                    primary = Color(0xFF6D98FF),
                    secondary = Color(0xFFD1B36A),
                    accent = Color(0xFF9BB9FF),
                    glow = Color(0xFF80A7FF),
                    onBackground = Color(0xFFF7F8FF),
                    onSurface = Color(0xFFF7F8FF),
                    muted = Color(0xFFBEC7E2),
                    outline = Color(0xFF516491),
                    danger = Color(0xFFFF817D),
                ),
            motion =
                StoneMotion(
                    pressDurationMs = 95,
                    pressScale = 0.972f,
                    innerGlowGain = 1.42f,
                    releaseDurationMs = 240,
                    focusDurationMs = 150,
                    edgeLightGain = 1.25f,
                    selectedDurationMs = 180,
                    reduceMotionCrossfadeMs = 120,
                ),
            material =
                StoneMaterial(
                    tile = R.drawable.tp_lapis_lazuli_material_tile,
                    glowOverlay = R.drawable.tp_lapis_lazuli_glow_overlay,
                    refractionOverlay = R.drawable.tp_lapis_lazuli_refraction_overlay,
                    ambientPortrait = R.drawable.tp_lapis_lazuli_ambient_portrait,
                    ambientLandscape = R.drawable.tp_lapis_lazuli_ambient_landscape,
                    ambientSquare = R.drawable.tp_lapis_lazuli_ambient_square,
                    backgroundOpacity = 0.44f,
                    surfaceOpacity = 0.72f,
                    disabledOpacity = 0.18f,
                ),
            sounds =
                StoneSounds(
                    click = R.raw.tp_lapis_lazuli_click_soft,
                    confirm = R.raw.tp_lapis_lazuli_confirm,
                    swoop = R.raw.tp_lapis_lazuli_swoop,
                ),
            surfaces =
                mapOf(
                    StoneComponent.ALBUM_TILE to
                        StoneStateArt(
                            default = R.drawable.tp_lapis_lazuli_album_tile_default,
                            focused = R.drawable.tp_lapis_lazuli_album_tile_focused,
                            pressed = R.drawable.tp_lapis_lazuli_album_tile_pressed,
                            selected = R.drawable.tp_lapis_lazuli_album_tile_selected,
                            disabled = R.drawable.tp_lapis_lazuli_album_tile_disabled,
                        ),
                    StoneComponent.BOTTOM_SHEET to
                        StoneStateArt(
                            default = R.drawable.tp_lapis_lazuli_bottom_sheet_default,
                            focused = R.drawable.tp_lapis_lazuli_bottom_sheet_focused,
                            pressed = R.drawable.tp_lapis_lazuli_bottom_sheet_pressed,
                            selected = R.drawable.tp_lapis_lazuli_bottom_sheet_selected,
                            disabled = R.drawable.tp_lapis_lazuli_bottom_sheet_disabled,
                        ),
                    StoneComponent.CARD to
                        StoneStateArt(
                            default = R.drawable.tp_lapis_lazuli_card_default,
                            focused = R.drawable.tp_lapis_lazuli_card_focused,
                            pressed = R.drawable.tp_lapis_lazuli_card_pressed,
                            selected = R.drawable.tp_lapis_lazuli_card_selected,
                            disabled = R.drawable.tp_lapis_lazuli_card_disabled,
                        ),
                    StoneComponent.CHIP to
                        StoneStateArt(
                            default = R.drawable.tp_lapis_lazuli_chip_default,
                            focused = R.drawable.tp_lapis_lazuli_chip_focused,
                            pressed = R.drawable.tp_lapis_lazuli_chip_pressed,
                            selected = R.drawable.tp_lapis_lazuli_chip_selected,
                            disabled = R.drawable.tp_lapis_lazuli_chip_disabled,
                        ),
                    StoneComponent.COMPACT_BUTTON to
                        StoneStateArt(
                            default = R.drawable.tp_lapis_lazuli_compact_button_default,
                            focused = R.drawable.tp_lapis_lazuli_compact_button_focused,
                            pressed = R.drawable.tp_lapis_lazuli_compact_button_pressed,
                            selected = R.drawable.tp_lapis_lazuli_compact_button_selected,
                            disabled = R.drawable.tp_lapis_lazuli_compact_button_disabled,
                        ),
                    StoneComponent.DIALOG to
                        StoneStateArt(
                            default = R.drawable.tp_lapis_lazuli_dialog_default,
                            focused = R.drawable.tp_lapis_lazuli_dialog_focused,
                            pressed = R.drawable.tp_lapis_lazuli_dialog_pressed,
                            selected = R.drawable.tp_lapis_lazuli_dialog_selected,
                            disabled = R.drawable.tp_lapis_lazuli_dialog_disabled,
                        ),
                    StoneComponent.ICON_BUTTON to
                        StoneStateArt(
                            default = R.drawable.tp_lapis_lazuli_icon_button_default,
                            focused = R.drawable.tp_lapis_lazuli_icon_button_focused,
                            pressed = R.drawable.tp_lapis_lazuli_icon_button_pressed,
                            selected = R.drawable.tp_lapis_lazuli_icon_button_selected,
                            disabled = R.drawable.tp_lapis_lazuli_icon_button_disabled,
                        ),
                    StoneComponent.KNOB to
                        StoneStateArt(
                            default = R.drawable.tp_lapis_lazuli_knob_default,
                            focused = R.drawable.tp_lapis_lazuli_knob_focused,
                            pressed = R.drawable.tp_lapis_lazuli_knob_pressed,
                            selected = R.drawable.tp_lapis_lazuli_knob_selected,
                            disabled = R.drawable.tp_lapis_lazuli_knob_disabled,
                        ),
                    StoneComponent.LIST_ROW to
                        StoneStateArt(
                            default = R.drawable.tp_lapis_lazuli_list_row_default,
                            focused = R.drawable.tp_lapis_lazuli_list_row_focused,
                            pressed = R.drawable.tp_lapis_lazuli_list_row_pressed,
                            selected = R.drawable.tp_lapis_lazuli_list_row_selected,
                            disabled = R.drawable.tp_lapis_lazuli_list_row_disabled,
                        ),
                    StoneComponent.MINI_PLAYER to
                        StoneStateArt(
                            default = R.drawable.tp_lapis_lazuli_mini_player_default,
                            focused = R.drawable.tp_lapis_lazuli_mini_player_focused,
                            pressed = R.drawable.tp_lapis_lazuli_mini_player_pressed,
                            selected = R.drawable.tp_lapis_lazuli_mini_player_selected,
                            disabled = R.drawable.tp_lapis_lazuli_mini_player_disabled,
                        ),
                    StoneComponent.NAVIGATION_BAR to
                        StoneStateArt(
                            default = R.drawable.tp_lapis_lazuli_navigation_bar_default,
                            focused = R.drawable.tp_lapis_lazuli_navigation_bar_focused,
                            pressed = R.drawable.tp_lapis_lazuli_navigation_bar_pressed,
                            selected = R.drawable.tp_lapis_lazuli_navigation_bar_selected,
                            disabled = R.drawable.tp_lapis_lazuli_navigation_bar_disabled,
                        ),
                    StoneComponent.PRIMARY_BUTTON to
                        StoneStateArt(
                            default = R.drawable.tp_lapis_lazuli_primary_button_default,
                            focused = R.drawable.tp_lapis_lazuli_primary_button_focused,
                            pressed = R.drawable.tp_lapis_lazuli_primary_button_pressed,
                            selected = R.drawable.tp_lapis_lazuli_primary_button_selected,
                            disabled = R.drawable.tp_lapis_lazuli_primary_button_disabled,
                        ),
                    StoneComponent.PROGRESS_RING to
                        StoneStateArt(
                            default = R.drawable.tp_lapis_lazuli_progress_ring_default,
                            focused = R.drawable.tp_lapis_lazuli_progress_ring_focused,
                            pressed = R.drawable.tp_lapis_lazuli_progress_ring_pressed,
                            selected = R.drawable.tp_lapis_lazuli_progress_ring_selected,
                            disabled = R.drawable.tp_lapis_lazuli_progress_ring_disabled,
                        ),
                    StoneComponent.SECONDARY_BUTTON to
                        StoneStateArt(
                            default = R.drawable.tp_lapis_lazuli_secondary_button_default,
                            focused = R.drawable.tp_lapis_lazuli_secondary_button_focused,
                            pressed = R.drawable.tp_lapis_lazuli_secondary_button_pressed,
                            selected = R.drawable.tp_lapis_lazuli_secondary_button_selected,
                            disabled = R.drawable.tp_lapis_lazuli_secondary_button_disabled,
                        ),
                    StoneComponent.SLIDER_THUMB to
                        StoneStateArt(
                            default = R.drawable.tp_lapis_lazuli_slider_thumb_default,
                            focused = R.drawable.tp_lapis_lazuli_slider_thumb_focused,
                            pressed = R.drawable.tp_lapis_lazuli_slider_thumb_pressed,
                            selected = R.drawable.tp_lapis_lazuli_slider_thumb_selected,
                            disabled = R.drawable.tp_lapis_lazuli_slider_thumb_disabled,
                        ),
                    StoneComponent.SLIDER_TRACK to
                        StoneStateArt(
                            default = R.drawable.tp_lapis_lazuli_slider_track_default,
                            focused = R.drawable.tp_lapis_lazuli_slider_track_focused,
                            pressed = R.drawable.tp_lapis_lazuli_slider_track_pressed,
                            selected = R.drawable.tp_lapis_lazuli_slider_track_selected,
                            disabled = R.drawable.tp_lapis_lazuli_slider_track_disabled,
                        ),
                    StoneComponent.TEXT_FIELD to
                        StoneStateArt(
                            default = R.drawable.tp_lapis_lazuli_text_field_default,
                            focused = R.drawable.tp_lapis_lazuli_text_field_focused,
                            pressed = R.drawable.tp_lapis_lazuli_text_field_pressed,
                            selected = R.drawable.tp_lapis_lazuli_text_field_selected,
                            disabled = R.drawable.tp_lapis_lazuli_text_field_disabled,
                        ),
                    StoneComponent.TOGGLE to
                        StoneStateArt(
                            default = R.drawable.tp_lapis_lazuli_toggle_default,
                            focused = R.drawable.tp_lapis_lazuli_toggle_focused,
                            pressed = R.drawable.tp_lapis_lazuli_toggle_pressed,
                            selected = R.drawable.tp_lapis_lazuli_toggle_selected,
                            disabled = R.drawable.tp_lapis_lazuli_toggle_disabled,
                        ),
                ),
        )
}
