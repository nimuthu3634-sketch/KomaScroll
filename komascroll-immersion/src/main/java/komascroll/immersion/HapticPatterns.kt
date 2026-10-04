package komascroll.immersion

import kotlin.math.roundToInt

/**
 * A vibration waveform: alternating off/on segments in milliseconds (starting with "off"), with an
 * amplitude (0..255) per segment. Matches `VibrationEffect.createWaveform(timings, amplitudes, -1)`.
 */
class HapticWaveform(val timings: LongArray, val amplitudes: IntArray) {
    val totalMillis: Long get() = timings.sum()
}

/** Haptic patterns for each kind of sound effect. */
object HapticPatterns {

    /**
     * Parses `"on:amplitude off on:amplitude …"`: vibrate for `on` ms at `amplitude`, pause for
     * `off` ms. Written this way so each pattern reads like the rhythm it plays.
     */
    private fun pattern(spec: String): HapticWaveform {
        val timings = mutableListOf(0L)
        val amplitudes = mutableListOf(0)
        spec.split(' ').forEach { step ->
            val parts = step.split(':')
            if (parts.size == 2) {
                timings += parts[0].toLong()
                amplitudes += parts[1].toInt()
            } else {
                timings += step.toLong()
                amplitudes += 0
            }
        }
        return HapticWaveform(timings.toLongArray(), amplitudes.toIntArray())
    }

    private val PATTERNS = mapOf(
        SfxKind.IMPACT to pattern("70:255"),
        SfxKind.EXPLOSION to pattern("90:255 30 140:190 30 220:110 30 260:50"),
        SfxKind.RUMBLE to pattern("70:110 30 70:130 30 70:110 30 70:130 30 70:110 30 70:90"),
        SfxKind.HEARTBEAT to pattern("45:170 110 70:255 450 45:170 110 70:255"),
        SfxKind.SLASH to pattern("25:255 15 35:120"),
        SfxKind.CRASH to pattern("40:255 25 35:200 25 45:230 25 60:140"),
    )

    /**
     * The waveform for [kind], with amplitudes scaled by [strength] (0..1) and the user's
     * [intensity] (0..1). Quiet effects never drop below a perceptible level.
     */
    fun waveform(kind: SfxKind, strength: Float, intensity: Float = 1f): HapticWaveform {
        val base = PATTERNS.getValue(kind)
        val scale = (0.45f + 0.55f * strength.coerceIn(0f, 1f)) * intensity.coerceIn(0f, 1f)
        val amplitudes = base.amplitudes.map { amplitude ->
            if (amplitude == 0) 0 else (amplitude * scale).roundToInt().coerceIn(1, 255)
        }.toIntArray()
        return HapticWaveform(base.timings.copyOf(), amplitudes)
    }
}
