package dev.geode.feature.customization.ui

import dev.geode.feature.customization.api.ModulatorId
import dev.geode.feature.customization.api.ModulatorParameter
import dev.geode.feature.customization.api.Reaction
import dev.geode.feature.customization.api.ReactionId
import dev.geode.feature.customization.api.ReactionSource
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.SoundSignal
import dev.geode.feature.customization.api.Target
import org.junit.Assert.assertEquals
import org.junit.Test

class PatchBoardTest {
    private val bass = ReactionSource.Sound(SoundSignal.BASS)
    private val one = ReactionSource.Generated(ModulatorId(1))
    private val two = ReactionSource.Generated(ModulatorId(2))
    private val zoom = Target.Shared(SharedProperty.ZOOM)
    private val twoRate = Target.ModulatorField(ModulatorId(2), ModulatorParameter.RATE)
    private val oneDepth = Target.ModulatorField(ModulatorId(1), ModulatorParameter.DEPTH)

    private fun link(
        id: Int,
        source: ReactionSource,
        target: Target,
    ) = Reaction(ReactionId(id), source, target)

    @Test
    fun `the board lists each source and each target once, in the order they first appear`() {
        val board = boardOf(listOf(link(1, bass, zoom), link(2, one, zoom), link(3, bass, twoRate)))

        assertEquals(listOf(bass, one), board.sources)
        assertEquals(listOf(zoom, twoRate), board.targets)
        assertEquals(listOf(Cable(0, 0, loop = false), Cable(1, 0, loop = false), Cable(0, 1, loop = false)), board.cables)
    }

    @Test
    fun `a cable is part of a loop when the Modulator it moves leads back to its source`() {
        val board = boardOf(listOf(link(1, one, twoRate), link(2, two, oneDepth), link(3, two, zoom)))

        assertEquals(listOf(true, true, false), board.cables.map { it.loop })
    }

    @Test
    fun `a chain of Modulators that never returns is not a loop`() {
        val board = boardOf(listOf(link(1, one, twoRate), link(2, two, zoom)))

        assertEquals(listOf(false, false), board.cables.map { it.loop })
    }

    @Test
    fun `a Modulator that moves itself is a loop of one`() {
        assertEquals(listOf(true), boardOf(listOf(link(1, one, oneDepth))).cables.map { it.loop })
    }

    @Test
    fun `an empty Customization has an empty board`() {
        assertEquals(Board(emptyList(), emptyList(), emptyList()), boardOf(emptyList()))
    }
}
