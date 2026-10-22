package dev.geode.feature.visuals.core

import dev.geode.feature.visuals.api.BrokenFile
import dev.geode.feature.visuals.api.ImportProblem
import dev.geode.feature.visuals.api.MilkdropKind
import dev.geode.feature.visuals.api.MilkdropPreset
import java.security.MessageDigest

/** A file the person picked, as the handlers read it: its [name] as the picker gave it, and what came of reading it. */
sealed interface PickedFile {
    val name: String

    /** Read in full, within [MilkdropFiles.limit]. */
    class Read(
        override val name: String,
        val bytes: ByteArray,
    ) : PickedFile

    /** It ran past [MilkdropFiles.limit], so reading stopped. */
    data class TooLarge(
        override val name: String,
    ) : PickedFile

    data class Unreadable(
        override val name: String,
    ) : PickedFile
}

/** What to do with one picked file: write it as [stored], or not, because it is [broken]. */
sealed interface Planned {
    /** Write [bytes] as [stored] in the MilkDrop folder; the lists show it as [shown]. */
    class Write(
        val picked: String,
        val stored: String,
        val shown: String,
        val bytes: ByteArray,
    ) : Planned

    data class Refuse(
        val broken: BrokenFile,
    ) : Planned
}

/**
 * The rules of the MilkDrop folder (#145), as pure functions: which picked files may come in and under
 * what name, and how the folder is listed beside the built-in presets. Taken over from the old importers
 * (`MilkPackImporter`, `TextureStore`, `PresetStore.milkFileName`) so names come out exactly as before,
 * and every file is now checked before it is written: a broken one is refused with a reason, and never
 * stops the others or touches what is already there.
 */
object MilkdropFiles {
    /** The extension every preset file has. */
    const val PRESET_EXTENSION = "milk"

    /** No real preset comes near this; anything bigger is not one. */
    const val MAX_PRESET_BYTES = 1024 * 1024

    /** Comfortably above any real MilkDrop texture, far below what exhausts the heap. */
    const val MAX_TEXTURE_BYTES = 16 * 1024 * 1024

    /** The image types MilkDrop can sample, by extension. */
    val TEXTURE_EXTENSIONS: Set<String> = setOf("png", "jpg", "jpeg", "bmp", "dib", "tga", "dds")

    private val unsafeInPreset = Regex("[^A-Za-z0-9-_ ]")
    private val unsafeInTexture = Regex("[^A-Za-z0-9]")
    private val presetSection = Regex("^\\s*\\[preset\\d*]", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

    private const val HASH_BYTES = 4
    private const val BYTE_MASK = 0xff
    private const val TGA_HEADER_BYTES = 18
    private const val TGA_COLOR_MAP_TYPE_AT = 1
    private const val TGA_IMAGE_TYPE_AT = 2
    private const val TGA_PIXEL_DEPTH_AT = 16
    private val tgaImageTypes = setOf(1, 2, 3, 9, 10, 11)
    private val tgaPixelDepths = setOf(8, 15, 16, 24, 32)

    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val bmp = "BM".toByteArray(Charsets.US_ASCII)
    private val dds = "DDS ".toByteArray(Charsets.US_ASCII)

    /** The most the handlers read of one picked file of [kind]. */
    fun limit(kind: MilkdropKind): Int =
        when (kind) {
            MilkdropKind.PRESETS -> MAX_PRESET_BYTES
            MilkdropKind.TEXTURES -> MAX_TEXTURE_BYTES
        }

    /**
     * What to do with each of [picked], in order. [present] is every file name already in the folder
     * for [kind] (built-in presets included); a file whose name is taken, there or earlier in this
     * pick, is refused rather than written over.
     */
    fun plan(
        kind: MilkdropKind,
        picked: List<PickedFile>,
        present: Collection<String>,
    ): List<Planned> {
        val taken = present.map { key(kind, it) }.toMutableSet()
        return picked.map { file ->
            val planned = plan(kind, file, taken)
            if (planned is Planned.Write) taken += key(kind, planned.stored)
            planned
        }
    }

    private fun plan(
        kind: MilkdropKind,
        file: PickedFile,
        taken: Set<String>,
    ): Planned {
        fun refuse(problem: ImportProblem) = Planned.Refuse(BrokenFile(file.name, problem))
        if (kind == MilkdropKind.TEXTURES && extension(file.name) !in TEXTURE_EXTENSIONS) return refuse(ImportProblem.WRONG_TYPE)
        return when (file) {
            is PickedFile.Unreadable -> refuse(ImportProblem.UNREADABLE)
            is PickedFile.TooLarge -> refuse(ImportProblem.TOO_LARGE)
            is PickedFile.Read -> {
                val stored = storedName(kind, file.name)
                val clash = key(kind, stored) in taken || key(kind, asNamed(kind, file.name)) in taken
                val problem = if (clash) ImportProblem.ALREADY_THERE else contentProblem(kind, file.name, file.bytes)
                if (problem == null) Planned.Write(file.name, stored, shown(kind, stored), file.bytes) else refuse(problem)
            }
        }
    }

    private fun contentProblem(
        kind: MilkdropKind,
        name: String,
        bytes: ByteArray,
    ): ImportProblem? =
        when {
            bytes.isEmpty() -> ImportProblem.EMPTY
            kind == MilkdropKind.PRESETS -> if (isPreset(bytes)) null else ImportProblem.NOT_A_PRESET
            else -> if (isImage(extension(name), bytes)) null else ImportProblem.NOT_AN_IMAGE
        }

    /** Whether [bytes] read as a MilkDrop preset: text, with a `[preset00]` section. */
    fun isPreset(bytes: ByteArray): Boolean {
        val text = bytes.toString(Charsets.ISO_8859_1)
        return '\u0000' !in text && presetSection.containsMatchIn(text)
    }

    /** Whether [bytes] start the way an image of type [extension] does. */
    fun isImage(
        extension: String,
        bytes: ByteArray,
    ): Boolean =
        when (extension) {
            "png" -> bytes.startsWith(png)
            "jpg", "jpeg" -> bytes.startsWith(jpeg)
            "bmp", "dib" -> bytes.startsWith(bmp)
            "dds" -> bytes.startsWith(dds)
            "tga" -> isTga(bytes)
            else -> false
        }

    private fun isTga(bytes: ByteArray): Boolean {
        if (bytes.size < TGA_HEADER_BYTES) return false
        val colorMapType = bytes[TGA_COLOR_MAP_TYPE_AT].toInt() and BYTE_MASK
        val imageType = bytes[TGA_IMAGE_TYPE_AT].toInt() and BYTE_MASK
        val pixelDepth = bytes[TGA_PIXEL_DEPTH_AT].toInt() and BYTE_MASK
        return colorMapType <= 1 && imageType in tgaImageTypes && pixelDepth in tgaPixelDepths
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    /**
     * The file name a picked file is kept under: a preset as `<name>.milk`, a texture under its own
     * name; either with anything unsafe replaced and a short hash added, so two names never collide.
     */
    fun storedName(
        kind: MilkdropKind,
        name: String,
    ): String =
        when (kind) {
            MilkdropKind.PRESETS -> presetFileName(name)
            MilkdropKind.TEXTURES -> textureFileName(name)
        }

    private fun presetFileName(name: String): String {
        val stem = (if (extension(name) == PRESET_EXTENSION) name.substringBeforeLast('.') else name).ifEmpty { "preset" }
        val safe = stem.replace(unsafeInPreset, "_")
        return (if (safe == stem) stem else "$safe-${hash(stem)}") + ".$PRESET_EXTENSION"
    }

    private fun textureFileName(name: String): String {
        val rawBase = name.substringBeforeLast('.')
        val extension = extension(name)
        val base = rawBase.replace(unsafeInTexture, "_")
        val safeBase = if (base.firstOrNull()?.isDigit() == true) "t$base" else base.ifEmpty { "tex" }
        return if (safeBase == rawBase) "$safeBase.$extension" else "${safeBase}_${hash(rawBase)}.$extension"
    }

    /** [name] as the folder would hold it unchanged: a preset always ends in `.milk`. */
    private fun asNamed(
        kind: MilkdropKind,
        name: String,
    ): String = if (kind == MilkdropKind.PRESETS && extension(name) != PRESET_EXTENSION) "$name.$PRESET_EXTENSION" else name

    /**
     * What makes two names the same file: case never matters, and for a texture only the name before
     * the extension does, because a preset asks for a texture by that alone.
     */
    private fun key(
        kind: MilkdropKind,
        file: String,
    ): String =
        when (kind) {
            MilkdropKind.PRESETS -> file.lowercase()
            MilkdropKind.TEXTURES -> file.substringBeforeLast('.').lowercase()
        }

    private fun shown(
        kind: MilkdropKind,
        stored: String,
    ): String = if (kind == MilkdropKind.PRESETS) stored.removeSuffix(".$PRESET_EXTENSION") else stored

    private fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    private fun hash(text: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .take(HASH_BYTES)
            .joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }

    /**
     * The presets as the MilkDrop list shows them: every one of [builtIns], and every other preset file
     * in [files] as the person's own, named by its file name; all sorted by name. A built-in that is
     * also in the folder is listed once, as built-in.
     */
    fun presets(
        builtIns: List<MilkdropPreset>,
        files: List<String>,
    ): List<MilkdropPreset> {
        val builtInFiles = builtIns.map { it.file }.toSet()
        val own =
            files
                .filter { extension(it) == PRESET_EXTENSION && it !in builtInFiles }
                .map { MilkdropPreset(it, shown(MilkdropKind.PRESETS, it), author = null, builtIn = false) }
        return (builtIns + own).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    /** The image files in [files], the ones MilkDrop can sample, sorted by name. */
    fun textures(files: List<String>): List<String> =
        files.filter { extension(it) in TEXTURE_EXTENSIONS }.sortedWith(String.CASE_INSENSITIVE_ORDER)
}
