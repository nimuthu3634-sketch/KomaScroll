package komascroll.core

/**
 * KomaScroll branding constants, kept in one place so upstream files only need a one-line reference.
 */
object KomaScrollBranding {
    /** Deep teal (#00897B), the seed colour for the default "Custom" Material 3 theme. */
    const val SEED_COLOR: Int = 0xFF00897B.toInt()

    const val GITHUB_URL = "https://github.com/nimuthu3634-sketch/KomaScroll"

    /**
     * Komikku's changelog, translation, privacy-policy and community links describe Komikku, not this fork.
     * They stay in the code (to keep upstream merges simple) but are hidden while this is false.
     */
    const val SHOW_UPSTREAM_LINKS = false
}
