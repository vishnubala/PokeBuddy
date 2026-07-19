package com.pokebuddy.iv

import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Decodes exact IVs (or the candidate set) from what we can read off the screen:
 * base stats + CP + HP, optionally narrowed by level and appraisal.
 *
 * Never presents a guess as certain — [DecodeResult.isExact] is only true when every
 * surviving candidate agrees on the IV triple.
 */
object IvDecoder {

    /** In-game CP for a given base/IV/CPM. Mirrors PoGO's formula exactly (floor, min 10). */
    fun cp(base: BaseStats, iv: Iv, cpm: Double): Int {
        val a = base.attack + iv.attack
        val d = base.defense + iv.defense
        val s = base.stamina + iv.stamina
        val raw = a * sqrt(d.toDouble()) * sqrt(s.toDouble()) * cpm * cpm / 10.0
        return floor(raw).toInt().coerceAtLeast(10)
    }

    /** In-game HP (max) for a given base stamina/IV/CPM. */
    fun hp(base: BaseStats, ivStamina: Int, cpm: Double): Int =
        floor((base.stamina + ivStamina) * cpm).toInt().coerceAtLeast(10)

    /**
     * @param levels which levels to consider. Defaults to every level in [Cpm].
     *   Pass a narrower list when the level is known (e.g. from a Power-Up cost lookup).
     */
    fun decode(
        base: BaseStats,
        cp: Int,
        hp: Int,
        appraisal: Appraisal = Appraisal(),
        levels: List<Double> = Cpm.levels,
    ): DecodeResult {
        val solutions = ArrayList<IvSolution>()
        for (level in levels) {
            val cpm = Cpm.byLevel[level] ?: continue
            for (s in 0..15) {
                if (hp(base, s, cpm) != hp) continue          // stamina fixed by HP first
                for (a in 0..15) for (d in 0..15) {
                    val iv = Iv(a, d, s)
                    if (cp(base, iv, cpm) != cp) continue
                    if (!appraisal.matches(iv)) continue
                    solutions.add(IvSolution(level, iv))
                }
            }
        }
        return DecodeResult(solutions)
    }

    /**
     * Decodes using IVs measured from the appraisal bars, corroborated against CP+HP.
     *
     * The bars give an exact triple, but reading pixels can misfire, so we don't trust
     * them alone: we ask whether that triple can actually produce the observed CP and HP
     * at some level. If it can, two independent readings agree and the result is exact.
     *
     * If it can't, the bar read is suspect and we DROP it entirely, falling back to CP+HP
     * and reporting the candidate set. Note we deliberately do not fall back to the coarse
     * appraisal bands derived from those same bars: a one-bar misread also shifts the
     * best-stat set and sum tier, which can exclude the true IV — a bad read must widen
     * the answer, never narrow it to the wrong place.
     */
    fun decodeCorroborated(
        base: BaseStats,
        cp: Int,
        hp: Int,
        bars: Iv,
        levels: List<Double> = Cpm.levels,
    ): DecodeResult {
        val corroborated = decode(base, cp, hp, Appraisal(measuredIv = bars), levels)
        return if (!corroborated.isEmpty) corroborated else decode(base, cp, hp, levels = levels)
    }
}
