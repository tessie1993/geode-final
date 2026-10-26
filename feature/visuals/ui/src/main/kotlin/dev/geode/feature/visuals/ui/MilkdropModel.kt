package dev.geode.feature.visuals.ui

import androidx.compose.runtime.Immutable
import dev.geode.engine.types.MilkdropStyles
import dev.geode.feature.visuals.api.ImportReport
import dev.geode.feature.visuals.api.MilkdropKind
import dev.geode.feature.visuals.api.MilkdropPreset
import dev.geode.feature.visuals.api.VisualsCommand
import dev.geode.feature.visuals.api.VisualsState

/**
 * What the MilkDrop screen shows (#145): the [presets], built-in and the person's own together, and the
 * [textures]; null until the MilkDrop folder has been listed ([loading]). While [importing], the import
 * buttons wait. [report] is how the last import went, until it is dismissed. Every preset is a Style
 * of its own (#305): [playing] is the file of the one the Visual shows, null when it shows another Style.
 */
@Immutable
data class MilkdropModel(
    val loading: Boolean,
    val importing: Boolean,
    val presets: List<MilkdropPreset>,
    val textures: List<String>,
    val report: ImportReport?,
    val playing: String? = null,
) {
    /** Whether an import can start now. */
    val canImport: Boolean get() = !loading && !importing
}

/** Everything the person can do on the MilkDrop screen. */
sealed interface MilkdropAction {
    /** Open the system picker for files of [kind]. A launcher, so the route's. */
    data class Pick(
        val kind: MilkdropKind,
    ) : MilkdropAction

    /** The last import's report has been read. */
    data object DismissReport : MilkdropAction

    /** Show the preset [file] in the Visual: its own Style is chosen, like any Style. */
    data class Play(
        val file: String,
    ) : MilkdropAction
}

/** The Command an action sends; null for one the route carries out itself. */
fun MilkdropAction.toCommand(): VisualsCommand? =
    when (this) {
        is MilkdropAction.Pick -> null
        MilkdropAction.DismissReport -> VisualsCommand.DismissImportReport
        is MilkdropAction.Play -> VisualsCommand.Choose(MilkdropStyles.of(file))
    }

/** The MilkDrop screen's model. */
fun VisualsState.toMilkdropModel(): MilkdropModel {
    val shelf = milkdrop
    return MilkdropModel(
        loading = shelf == null,
        importing = shelf?.importing == true,
        presets = shelf?.presets.orEmpty(),
        textures = shelf?.textures.orEmpty(),
        report = shelf?.lastImport,
        playing = current?.let(MilkdropStyles::file),
    )
}
