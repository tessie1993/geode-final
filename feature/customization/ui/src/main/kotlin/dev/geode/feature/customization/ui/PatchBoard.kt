package dev.geode.feature.customization.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.geode.design.forest.LocalForestColors
import dev.geode.feature.customization.api.ModulatorId
import dev.geode.feature.customization.api.Reaction
import dev.geode.feature.customization.api.ReactionSource
import dev.geode.feature.customization.api.Target

/** One cable of the board: from source row [from] to target row [to]; [loop] when it is part of a loop. */
data class Cable(
    val from: Int,
    val to: Int,
    val loop: Boolean,
)

/** The patch-cable board's layout: its two columns and the cables between them. */
data class Board(
    val sources: List<ReactionSource>,
    val targets: List<Target>,
    val cables: List<Cable>,
)

/**
 * The board of [reactions] (#12): every source and every target once, and one cable per link. Loops
 * between Modulators are allowed (#11); a cable is marked as a loop when the Modulator it moves leads,
 * through further links, back to the Modulator it comes from.
 */
fun boardOf(reactions: List<Reaction>): Board {
    val sources = reactions.map { it.source }.distinct()
    val targets = reactions.map { it.target }.distinct()
    // Which Modulators each Modulator moves.
    val moves: Map<ModulatorId, Set<ModulatorId>> =
        reactions
            .mapNotNull { reaction ->
                val from = (reaction.source as? ReactionSource.Generated)?.modulator
                val to = (reaction.target as? Target.ModulatorField)?.modulator
                if (from != null && to != null) from to to else null
            }.groupBy({ it.first }, { it.second })
            .mapValues { it.value.toSet() }

    fun reaches(
        start: ModulatorId,
        goal: ModulatorId,
    ): Boolean {
        val seen = mutableSetOf<ModulatorId>()
        val waiting = ArrayDeque(listOf(start))
        while (waiting.isNotEmpty()) {
            val next = waiting.removeFirst()
            if (next == goal) return true
            if (seen.add(next)) waiting.addAll(moves[next].orEmpty())
        }
        return false
    }
    val cables =
        reactions.map { reaction ->
            val from = (reaction.source as? ReactionSource.Generated)?.modulator
            val to = (reaction.target as? Target.ModulatorField)?.modulator
            Cable(
                from = sources.indexOf(reaction.source),
                to = targets.indexOf(reaction.target),
                loop = from != null && to != null && reaches(to, from),
            )
        }
    return Board(sources, targets, cables)
}

/** The patch-cable board: sources on the left, targets on the right, a cable for every link, loops dashed. */
@Composable
internal fun PatchBoard(model: CustomizeModel) {
    val board = boardOf(model.reactions.flatMap { group -> group.links.map { it.reaction } })
    val colors = LocalForestColors.current
    val rows = maxOf(board.sources.size, board.targets.size)
    Row(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.customize_board_sources),
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            stringResource(R.string.customize_board_targets),
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
    Box(Modifier.fillMaxWidth().height(CustomizeDimens.boardRow * rows)) {
        Canvas(Modifier.fillMaxSize()) {
            val row = CustomizeDimens.boardRow.toPx()
            val left = CustomizeDimens.boardLabel.toPx()
            val right = size.width - left
            val stroke = CustomizeDimens.boardCable.toPx()
            val node = CustomizeDimens.boardNode.toPx()
            val dashed = PathEffect.dashPathEffect(LOOP_DASH)
            board.cables.forEach { cable ->
                val start = Offset(left, (cable.from + HALF_ROW) * row)
                val end = Offset(right, (cable.to + HALF_ROW) * row)
                val bend = (right - left) / 2
                val path =
                    Path().apply {
                        moveTo(start.x, start.y)
                        cubicTo(start.x + bend, start.y, end.x - bend, end.y, end.x, end.y)
                    }
                drawPath(
                    path,
                    color = if (cable.loop) colors.highlight else colors.accent,
                    style = Stroke(stroke, cap = StrokeCap.Round, pathEffect = if (cable.loop) dashed else null),
                )
                drawCircle(colors.accent, node, start)
                drawCircle(colors.accent, node, end)
            }
        }
        Row(Modifier.fillMaxSize()) {
            BoardColumn(board.sources.map { sourceName(it, model) }, TextAlign.Start)
            Spacer(Modifier.weight(1f))
            BoardColumn(board.targets.map { targetName(it, model) }, TextAlign.End)
        }
    }
}

/** Where a cable meets its row: the middle. */
private const val HALF_ROW = 0.5f

@Composable
private fun BoardColumn(
    labels: List<String>,
    align: TextAlign,
) {
    Column(Modifier.width(CustomizeDimens.boardLabel)) {
        labels.forEach { label ->
            Box(Modifier.fillMaxWidth().height(CustomizeDimens.boardRow), contentAlignment = Alignment.CenterStart) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = align,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
