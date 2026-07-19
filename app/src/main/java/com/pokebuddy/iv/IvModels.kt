package com.pokebuddy.iv

import kotlin.math.roundToInt

/** Per-species base stats from public GameMaster data. */
data class BaseStats(val attack: Int, val defense: Int, val stamina: Int)

enum class Stat { ATTACK, DEFENSE, STAMINA }

/** Individual Values — each 0..15. */
data class Iv(val attack: Int, val defense: Int, val stamina: Int) {
    val sum: Int get() = attack + defense + stamina
    val percent: Int get() = (sum * 100.0 / 45.0).roundToInt()

    /** Which stat(s) are (tied for) highest — matches PoGO's appraisal highlight. */
    val bestStats: Set<Stat>
        get() {
            val max = maxOf(attack, defense, stamina)
            return buildSet {
                if (attack == max) add(Stat.ATTACK)
                if (defense == max) add(Stat.DEFENSE)
                if (stamina == max) add(Stat.STAMINA)
            }
        }

    val bestValue: Int get() = maxOf(attack, defense, stamina)
}

/**
 * Constraints read from the in-game appraisal. Every field is optional so the decoder
 * works with whatever we managed to read (a bare star tier, or full per-stat detail).
 *
 * PoGO appraisal maps to:
 *  - [ivSumRange]  from the star tier (e.g. 4-star = total IV 37..45).
 *  - [bestStats]   the stat(s) the leader highlights as best.
 *  - [bestValueRange] the "best stat" descriptor band: 0..7, 8..12, 13..14, or 15..15.
 */
data class Appraisal(
    val ivSumRange: IntRange? = null,
    val bestStats: Set<Stat> = emptySet(),
    val bestValueRange: IntRange? = null,
    /**
     * The exact per-stat IVs read off the appraisal BARS, when we measured them. Unlike
     * the fields above (which model the coarse in-game text), this pins the triple — so
     * it is only ever used via [IvDecoder.decodeCorroborated], which keeps it as a claim
     * to be confirmed against CP+HP rather than trusted outright.
     */
    val measuredIv: Iv? = null,
) {
    fun matches(iv: Iv): Boolean {
        if (measuredIv != null && iv != measuredIv) return false
        if (ivSumRange != null && iv.sum !in ivSumRange) return false
        if (bestStats.isNotEmpty() && iv.bestStats != bestStats) return false
        if (bestValueRange != null && iv.bestValue !in bestValueRange) return false
        return true
    }

    companion object {
        /** 4-star appraisal band: total IV 37..45 (≈82–100%). */
        val FOUR_STAR = IntRange(37, 45)
        /** "Best stat" descriptor bands the appraisal text encodes. */
        val BEST_15 = IntRange(15, 15)
        val BEST_13_14 = IntRange(13, 14)
        val BEST_8_12 = IntRange(8, 12)
        val BEST_0_7 = IntRange(0, 7)
    }
}

data class IvSolution(val level: Double, val iv: Iv)

data class DecodeResult(val solutions: List<IvSolution>) {
    val isEmpty: Boolean get() = solutions.isEmpty()

    /** Distinct IV triples across all candidate levels. */
    val distinctIvs: List<Iv> get() = solutions.map { it.iv }.distinct()

    /** True when every candidate agrees on the IV triple (level may still be ambiguous). */
    val isExact: Boolean get() = distinctIvs.size == 1

    val exactIv: Iv? get() = if (isExact) distinctIvs.first() else null

    // --- Percent view ---
    //
    // IV% depends only on the SUM of the three stats, so it is pinned down far more
    // often than the attack/defense/stamina split is: several candidate triples that
    // differ in how they distribute the stats can share one total. When the question
    // is "how good is this?", answer it from here rather than from [exactIv].

    val distinctPercents: List<Int> get() = distinctIvs.map { it.percent }.distinct().sorted()

    /** True when every candidate agrees on the IV%, even if the split is still unknown. */
    val isPercentExact: Boolean get() = distinctPercents.size == 1

    val exactPercent: Int? get() = distinctPercents.singleOrNull()

    /** The honest span of possible IV% — a single value when [isPercentExact]. */
    val percentRange: IntRange?
        get() = distinctPercents.takeIf { it.isNotEmpty() }?.let { it.first()..it.last() }
}
