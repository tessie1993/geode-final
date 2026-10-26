package dev.geode.design.glass

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap

/**
 * The matte grain of glass: one small tile of fixed noise, made once and repeated across every glass
 * surface and backdrop. It is drawn with an overlay blend, so on average it neither lightens nor darkens
 * what it sits on, and contrast is unchanged.
 */
object GlassGrain {
    /** The value of each of a [size] by [size] tile's pixels, row by row, from 0 to 1: the same on every run. */
    fun tile(
        size: Int = GlassTokens.GRAIN_TILE,
        seed: Int = SEED,
    ): FloatArray = FloatArray(size * size) { index -> noise(index % size, index / size, seed) }

    /** A hashed noise value from 0 to 1 for the pixel at [x], [y]. */
    fun noise(
        x: Int,
        y: Int,
        seed: Int = SEED,
    ): Float {
        var hash = x * PRIME_X + y * PRIME_Y + seed * PRIME_SEED
        hash = (hash xor (hash ushr MIX_SHIFT_A)) * PRIME_MIX
        hash = hash xor (hash ushr MIX_SHIFT_B)
        return (hash and LOW_BITS) / LOW_BITS.toFloat()
    }

    /** The grain as a repeating brush, its tile made the first time it is asked for. */
    val brush: ShaderBrush by lazy { ShaderBrush(ImageShader(image, TileMode.Repeated, TileMode.Repeated)) }

    private val image: ImageBitmap by lazy {
        val size = GlassTokens.GRAIN_TILE
        val pixels =
            tile(size).map { value ->
                val grey = (value * CHANNEL_MAX).toInt()
                OPAQUE or (grey shl RED_SHIFT) or (grey shl GREEN_SHIFT) or grey
            }
        Bitmap.createBitmap(pixels.toIntArray(), size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    private const val SEED = 0x6E0DE
    private const val PRIME_X = 374_761_393
    private const val PRIME_Y = 668_265_263
    private const val PRIME_SEED = 1_274_126_177
    private const val PRIME_MIX = 1_103_515_245
    private const val MIX_SHIFT_A = 13
    private const val MIX_SHIFT_B = 16
    private const val LOW_BITS = 0xFFFF
    private const val CHANNEL_MAX = 255f
    private const val OPAQUE = -0x1000000
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
}
