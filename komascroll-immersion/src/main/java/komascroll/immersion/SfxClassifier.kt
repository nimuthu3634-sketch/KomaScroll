package komascroll.immersion

import kotlin.math.max

/** The kinds of sound effect that get their own haptic pattern. */
enum class SfxKind {
    /** A single hard hit: ドン, バン, BAM, 쿵. */
    IMPACT,

    /** A big blast that fades out: ドカーン, BOOM, 쾅. */
    EXPLOSION,

    /** A low, sustained tremor: ゴゴゴ, RUMBLE, 우르르. */
    RUMBLE,

    /** Two beats, repeated: ドキドキ, BA-DUMP, 두근. */
    HEARTBEAT,

    /** A fast, sharp swipe: ザシュ, SLASH, 휙. */
    SLASH,

    /** Breaking or shattering: ガシャーン, CRASH, 쨍그랑. */
    CRASH,
}

/** One piece of text found on a page, with its height relative to the page height (0..1). */
data class SfxCandidate(val text: String, val relativeHeight: Float)

/** The strongest sound effect on a page; [strength] is 0..1 and scales the haptic intensity. */
data class SfxHit(val kind: SfxKind, val strength: Float, val text: String)

/**
 * Recognizes onomatopoeia (sound effects) in OCR output.
 *
 * Sound effects are short, drawn large and spelled from a fairly small vocabulary, so a line counts
 * when its normalized text is short and contains a known sound. Dialogue lines that merely contain
 * one (e.g. カバン, "bag", contains バン) are mostly excluded by the length limit; size breaks ties.
 */
object SfxClassifier {

    /** Lines longer than this (after normalization) are treated as dialogue, not effects. */
    private const val MAX_SFX_LENGTH = 10

    /** Text at least this tall (relative to the page) counts as fully "loud". */
    private const val LOUD_HEIGHT = 0.08f

    /** Below this relative height, short matches are too small to be drawn effects. */
    private const val MIN_HEIGHT = 0.012f

    /**
     * Small vowels and ッ stretch or cut sounds short (ドゴォッ); small ャュョ are part of syllables.
     * Declared before [LEXICON], which normalizes its entries during initialization.
     */
    private val SMALL_KANA = setOf('ッ', 'ァ', 'ィ', 'ゥ', 'ェ', 'ォ')

    /**
     * Known sounds, most specific first so that ドカン wins over ドン and ドキドキ over ドド.
     * Entries are written naturally and normalized with [normalize] at startup.
     */
    private val LEXICON: List<Pair<String, SfxKind>> = listOf(
        // Japanese (hiragana spellings are covered by normalization)
        "ドキドキ" to SfxKind.HEARTBEAT,
        "ドクンドクン" to SfxKind.HEARTBEAT,
        "ドクン" to SfxKind.HEARTBEAT,
        "バクバク" to SfxKind.HEARTBEAT,
        "ドカーン" to SfxKind.EXPLOSION,
        "ドガーン" to SfxKind.EXPLOSION,
        "ボカーン" to SfxKind.EXPLOSION,
        "ズガーン" to SfxKind.EXPLOSION,
        "ドゴーン" to SfxKind.EXPLOSION,
        "チュドーン" to SfxKind.EXPLOSION,
        "ガシャーン" to SfxKind.CRASH,
        "ガッシャーン" to SfxKind.CRASH,
        "ガシャ" to SfxKind.CRASH,
        "パリーン" to SfxKind.CRASH,
        "バリーン" to SfxKind.CRASH,
        "ガラガラ" to SfxKind.CRASH,
        "ゴゴゴ" to SfxKind.RUMBLE,
        "ゴロゴロ" to SfxKind.RUMBLE,
        "ズズズ" to SfxKind.RUMBLE,
        "ドドド" to SfxKind.RUMBLE,
        "グラグラ" to SfxKind.RUMBLE,
        "ザシュ" to SfxKind.SLASH,
        "ズバッ" to SfxKind.SLASH,
        "ズバ" to SfxKind.SLASH,
        "スパッ" to SfxKind.SLASH,
        "ヒュン" to SfxKind.SLASH,
        "ズドン" to SfxKind.IMPACT,
        "ドゴォ" to SfxKind.IMPACT,
        "ドカッ" to SfxKind.IMPACT,
        "バキッ" to SfxKind.IMPACT,
        "バキ" to SfxKind.IMPACT,
        "ボコッ" to SfxKind.IMPACT,
        "ゴンッ" to SfxKind.IMPACT,
        "バシッ" to SfxKind.IMPACT,
        "ドン" to SfxKind.IMPACT,
        "バン" to SfxKind.IMPACT,
        // Korean
        "두근" to SfxKind.HEARTBEAT,
        "콰앙" to SfxKind.EXPLOSION,
        "쾅" to SfxKind.EXPLOSION,
        "펑" to SfxKind.EXPLOSION,
        "쨍그랑" to SfxKind.CRASH,
        "와장창" to SfxKind.CRASH,
        "우르르" to SfxKind.RUMBLE,
        "쿠구구" to SfxKind.RUMBLE,
        "구구구" to SfxKind.RUMBLE,
        "서걱" to SfxKind.SLASH,
        "슈욱" to SfxKind.SLASH,
        "휙" to SfxKind.SLASH,
        "쿵" to SfxKind.IMPACT,
        "퍽" to SfxKind.IMPACT,
        "탕" to SfxKind.IMPACT,
        "쾅쾅" to SfxKind.IMPACT,
        // Chinese
        "扑通" to SfxKind.HEARTBEAT,
        "噗通" to SfxKind.HEARTBEAT,
        "轰" to SfxKind.EXPLOSION,
        "咔嚓" to SfxKind.CRASH,
        "哗啦" to SfxKind.CRASH,
        "隆隆" to SfxKind.RUMBLE,
        "唰" to SfxKind.SLASH,
        "砰" to SfxKind.IMPACT,
        "嘭" to SfxKind.IMPACT,
        "咚" to SfxKind.IMPACT,
        // English
        "BADUMP" to SfxKind.HEARTBEAT,
        "BADUM" to SfxKind.HEARTBEAT,
        "THUMPTHUMP" to SfxKind.HEARTBEAT,
        "KABOOM" to SfxKind.EXPLOSION,
        "BOOM" to SfxKind.EXPLOSION,
        "BLAM" to SfxKind.EXPLOSION,
        "CRASH" to SfxKind.CRASH,
        "SHATTER" to SfxKind.CRASH,
        "CLANG" to SfxKind.CRASH,
        "RUMBLE" to SfxKind.RUMBLE,
        "SLASH" to SfxKind.SLASH,
        "SHING" to SfxKind.SLASH,
        "SWOOSH" to SfxKind.SLASH,
        "WHOOSH" to SfxKind.SLASH,
        "SWISH" to SfxKind.SLASH,
        "WHAM" to SfxKind.IMPACT,
        "THUD" to SfxKind.IMPACT,
        "SMACK" to SfxKind.IMPACT,
        "BANG" to SfxKind.IMPACT,
        "CRACK" to SfxKind.IMPACT,
        "BAM" to SfxKind.IMPACT,
        "POW" to SfxKind.IMPACT,
    ).map { (word, kind) -> normalize(word) to kind }

    /** Latin words must match a whole token (or its end, for KA-BOOM style prefixes). */
    private fun isLatin(word: String) = word.all { it in 'A'..'Z' }

    /**
     * Uppercases, folds hiragana to katakana and drops everything that varies between drawings of
     * the same sound: punctuation, long-vowel marks, small vowels and stretched letters (BOOOOM).
     */
    fun normalize(text: String): String {
        val folded = buildString(text.length) {
            for (raw in text) {
                val c = if (raw in 'ぁ'..'ゖ') raw + 0x60 else raw.uppercaseChar()
                when {
                    c in SMALL_KANA -> Unit
                    c == 'ー' || c == '〜' || c == '～' -> Unit
                    c.isLetter() -> append(c)
                }
            }
        }
        // Collapse runs of the same Latin letter (BOOOOM -> BOM, GRRRR -> GR) so stretched words match.
        return buildString(folded.length) {
            for (c in folded) {
                if (c in 'A'..'Z' && isNotEmpty() && last() == c) continue
                append(c)
            }
        }
    }

    /** The kind of sound effect [text] spells, or null when it does not look like one. */
    fun classify(text: String): SfxKind? {
        val normalized = normalize(text)
        if (normalized.isEmpty() || normalized.length > MAX_SFX_LENGTH) return null
        val latinTokens = text.uppercase().split(Regex("[^A-Z]+"))
            .map(::normalize)
            .filter { it.isNotEmpty() }
        for ((word, kind) in LEXICON) {
            if (isLatin(word)) {
                if (latinTokens.any { it == word || (it.endsWith(word) && it.length <= word.length + 3) }) return kind
                // Hyphenated or spaced spellings such as BA-DUMP or THUMP THUMP.
                if (latinTokens.joinToString("") == word) return kind
            } else if (normalized.contains(word)) {
                return kind
            }
        }
        return null
    }

    /** The loudest sound effect among [candidates], or null when there is none. */
    fun strongest(candidates: List<SfxCandidate>): SfxHit? {
        if (candidates.isEmpty()) return null
        val typical = candidates.map { it.relativeHeight }.sorted()[candidates.size / 2]
        return candidates.mapNotNull { candidate ->
            if (candidate.relativeHeight < MIN_HEIGHT) return@mapNotNull null
            val kind = classify(candidate.text) ?: return@mapNotNull null
            // Effects stand out from the page's ordinary text, and the biggest ones are the loudest.
            val absolute = (candidate.relativeHeight / LOUD_HEIGHT).coerceAtMost(1f)
            val relative = if (typical > 0f) (candidate.relativeHeight / (typical * 2f)).coerceAtMost(1f) else 1f
            SfxHit(kind, max(absolute, relative).coerceIn(0.3f, 1f), candidate.text)
        }.maxByOrNull { it.strength }
    }
}
