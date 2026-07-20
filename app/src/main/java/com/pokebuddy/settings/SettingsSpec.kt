package com.pokebuddy.settings

/**
 * The settings themselves, described as DATA rather than as a hand-written screen.
 *
 * Everything downstream is generated from this list — the settings UI builds a row per
 * entry, and [SettingsCodec] exports and re-imports by walking it. Adding a setting is one
 * entry here, with no matching edit in a layout file or an export routine that would
 * otherwise be easy to forget and hard to notice.
 *
 * Framework-independent on purpose, so both the codec and the defaults are JVM-testable.
 */
object SettingsSpec {

    sealed interface Setting<T> {
        val key: String
        val label: String
        val help: String
        val default: T
    }

    data class BoolSetting(
        override val key: String,
        override val label: String,
        override val help: String,
        override val default: Boolean,
    ) : Setting<Boolean>

    data class IntSetting(
        override val key: String,
        override val label: String,
        override val help: String,
        override val default: Int,
        val min: Int,
        val max: Int,
        val step: Int = 1,
        /** Rendered after the value ("s", "sp"); cosmetic only. */
        val unit: String = "",
    ) : Setting<Int> {
        fun clamp(v: Int): Int = v.coerceIn(min, max)
    }

    /**
     * Bumped whenever a key is renamed or its meaning changes, so an old exported file can
     * be recognised instead of being applied as though it still meant the same thing. The
     * same discipline as the Room migrations — a backup that can't be restored is worse
     * than no backup, because it's only discovered after a reinstall.
     */
    const val VERSION = 1

    val OVERLAY_TEXT_SP = IntSetting(
        "overlayTextSp", "Panel text size",
        "Body text in the floating panel.", default = 15, min = 10, max = 24, unit = "sp",
    )

    val PANEL_DISMISS_SECONDS = IntSetting(
        "panelDismissSeconds", "Panel auto-hide",
        "How long the panel stays up before hiding itself. 0 keeps it until dismissed.",
        default = 12, min = 0, max = 60, unit = "s",
    )

    val REMEMBER_PANEL_POSITION = BoolSetting(
        "rememberPanelPosition", "Remember panel position",
        "Keep the panel where you last dragged it, instead of resetting each session.",
        default = true,
    )

    val SHOW_RANK = BoolSetting(
        "showRank", "Show IV rank",
        "Adds \"3rd of 12 Pikachu by IV\" under the IV line.", default = true,
    )

    val CAPTURE_BURST_FRAMES = IntSetting(
        "captureBurstFrames", "Frames per scan",
        "More frames read the animating sprite more reliably, but each scan takes longer.",
        default = 6, min = 2, max = 12,
    )

    val AUTO_APPRAISE_ON_SCAN = BoolSetting(
        "autoAppraiseOnScan", "Auto-appraise on scan",
        "Drive the appraisal menu automatically to pin an exact IV. Needs the accessibility " +
            "service; has no effect without it.",
        default = false,
    )

    /** Panel position, persisted rather than shown — the UI for it is dragging the panel. */
    val PANEL_X = IntSetting("panelX", "", "", default = 28, min = 0, max = 4000)
    val PANEL_Y = IntSetting("panelY", "", "", default = 220, min = 0, max = 4000)

    /** Everything the settings SCREEN shows, in display order. */
    val visible: List<Setting<*>> = listOf(
        OVERLAY_TEXT_SP,
        PANEL_DISMISS_SECONDS,
        REMEMBER_PANEL_POSITION,
        SHOW_RANK,
        CAPTURE_BURST_FRAMES,
        AUTO_APPRAISE_ON_SCAN,
    )

    /** Everything persisted, including the values with no visible row. */
    val all: List<Setting<*>> = visible + listOf(PANEL_X, PANEL_Y)
}
