package com.pokebuddy.iv

/**
 * Picks a trustworthy CP from the noisy reads a burst of frames produces.
 *
 * On the detail screen the animating sprite occludes the CP, so OCR yields missing reads
 * or wrong partials that still look clean (e.g. Moltres briefly reads "131"). The guard:
 * a CP is only believable if it has at least one valid IV solution for this species + HP.
 * Among the survivors we take the max — dropped-digit partials read *smaller* than the
 * true value and rarely stay feasible, so the true CP wins.
 */
object CpResolver {

    /** @return the best feasible CP, or null if none of [candidates] is feasible. */
    fun resolve(candidates: List<Int>, base: BaseStats?, hp: Int?): Int? {
        val distinct = candidates.distinct()
        if (base == null || hp == null) return distinct.maxOrNull()
        return distinct
            .filter { it in 10..9999 && IvDecoder.decode(base, it, hp).solutions.isNotEmpty() }
            .maxOrNull()
    }

    fun isFeasible(base: BaseStats, cp: Int, hp: Int): Boolean =
        cp in 10..9999 && IvDecoder.decode(base, cp, hp).solutions.isNotEmpty()
}
