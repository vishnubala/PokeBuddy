package com.pokebuddy.iv

/**
 * Picks which FORM a screen is showing, when the name alone doesn't say.
 *
 * The type row handles most of it (Alolan Grimer is poison/dark vs plain poison), but it
 * fails wherever a variant shares its base form's typing — Armored Mewtwo, the Origin
 * formes, and the Kyurem fusions are all type-identical to their base.
 *
 * The extra lever here is FEASIBILITY: a form whose base stats admit no valid IV for the
 * observed CP and HP cannot be the one on screen. Mewtwo (300 attack) and Armored Mewtwo
 * (182) will rarely both explain the same numbers, so running the surviving candidates
 * through [IvDecoder] usually leaves exactly one.
 *
 * Where it doesn't, the answer is still often useful — see [Resolution.statsAgree].
 */
object FormResolver {

    /**
     * @property species the form to decode with, or null when the choice is genuinely open.
     * @property candidates every form still standing. Size 1 means resolved; more means the
     *   panel should say which ones rather than pick.
     * @property statsAgree true when the survivors share identical base stats, so the IV
     *   maths is the same whichever is right and only the LABEL is uncertain. Kyurem (Black)
     *   and (White) are both 310/183/245, exactly as Pikachu's costumes are all 112 attack.
     */
    data class Resolution(
        val species: SpeciesTable.Species?,
        val candidates: List<SpeciesTable.Species>,
        val statsAgree: Boolean,
    ) {
        /** Safe to report an exact IV: one form, or several that decode identically. */
        val ivIsSafe: Boolean get() = species != null

        /** Resolved to a single named form, so the label can be stated outright. */
        val nameIsCertain: Boolean get() = candidates.size == 1
    }

    /**
     * @param cp/[hpMax] null when the screen didn't yield them; feasibility is then skipped
     *   and the result falls back to whatever the type row alone could do.
     */
    fun resolve(
        name: String,
        types: List<String>,
        cp: Int? = null,
        hpMax: Int? = null,
    ): Resolution {
        val all = SpeciesTable.formsFor(name)
        if (all.size <= 1) {
            val only = SpeciesTable.species(name)
            return Resolution(only, listOfNotNull(only), statsAgree = true)
        }

        // Types first — cheap, and decisive for regional forms.
        val byType = if (types.isEmpty()) all else {
            val wanted = types.map { it.lowercase() }.toSet()
            all.filter { it.types.toSet() == wanted }.ifEmpty { all }
        }
        if (byType.size == 1) return Resolution(byType.single(), byType, statsAgree = true)

        // Then feasibility: drop forms whose stats can't produce this CP/HP at any level.
        val feasible = if (cp == null || hpMax == null) byType else {
            byType.filter { IvDecoder.decode(it.stats, cp, hpMax).solutions.isNotEmpty() }
                // Every candidate ruled out means the CP/HP read is wrong, not that the
                // Pokémon doesn't exist — keep the type-narrowed set rather than returning
                // an empty answer that reads as "unknown species".
                .ifEmpty { byType }
        }
        if (feasible.size == 1) return Resolution(feasible.single(), feasible, statsAgree = true)

        // Several forms survive. If they decode identically the IV is still exact, so
        // report it against the first and let the caller name the tie. If they don't,
        // there is a real choice to make and guessing it would produce a confident wrong
        // IV — decline, which is what the old type-only path did for every one of these.
        val agree = feasible.all { it.stats == feasible.first().stats }
        return Resolution(
            species = if (agree) feasible.first() else null,
            candidates = feasible,
            statsAgree = agree,
        )
    }
}
