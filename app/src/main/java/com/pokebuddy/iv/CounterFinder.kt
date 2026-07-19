package com.pokebuddy.iv

/**
 * Ranks counters against a defender's typing, in the two modes the overlay needs:
 *
 *  - [bestInGame]  — any species, assuming its best learnable moveset.
 *  - [fromInventory] — only Pokémon you own, scored on the moves they ACTUALLY have.
 *
 * The two answer different questions: the first is "what should I be building", the second
 * is "what can I throw at this right now".
 *
 * ## What this score is, and isn't
 *
 * Score is offensive output only: attack stat × move damage × type effectiveness, with STAB.
 * It deliberately does NOT model the defender's attacks, dodging, breakpoints, relobby time,
 * or shields — a real battle sim is a different project. It also runs on pvpoke's PvP move
 * numbers, which differ from Pokémon GO's PvE values.
 *
 * So the ranking is a sound *relative* ordering for "what hits this hard", not a definitive
 * best-counter list, and callers should present it that way.
 */
object CounterFinder {

    /** Same-type attack bonus: a move matching the attacker's type hits harder. */
    const val STAB = 1.2

    data class Counter(
        val species: SpeciesTable.Species,
        val fast: MoveTable.Move?,
        val charged: MoveTable.Move?,
        val score: Double,
    ) {
        /** "Rhyperior — Mud Slap / Rock Wrecker" */
        fun describe(): String {
            val moves = listOfNotNull(fast?.name, charged?.name).joinToString(" / ")
            return if (moves.isEmpty()) species.name else "${species.name} — $moves"
        }
    }

    /**
     * Offensive value of one move against [defenderTypes], scaled by the attacker's own
     * types (STAB) and attack stat.
     */
    private fun moveScore(
        move: MoveTable.Move,
        attackerTypes: List<String>,
        defenderTypes: List<String>,
    ): Double {
        val stab = if (move.type in attackerTypes.map { it.lowercase() }) STAB else 1.0
        return move.damagePerTurn() * stab * TypeChart.multiplier(move.type, defenderTypes)
    }

    /**
     * Combined output of a fast + charged pairing. Weighted toward the charged move because
     * that's where most damage lands, while still rewarding fast-move pressure.
     */
    private fun pairScore(
        fast: MoveTable.Move?,
        charged: MoveTable.Move?,
        attackerTypes: List<String>,
        defenderTypes: List<String>,
        attack: Int,
    ): Double {
        val f = fast?.let { moveScore(it, attackerTypes, defenderTypes) } ?: 0.0
        val c = charged?.let { moveScore(it, attackerTypes, defenderTypes) } ?: 0.0
        return (f + c * 2.0) * (attack / 100.0)
    }

    /** Best learnable fast/charged pairing for a species against this defender. */
    private fun bestPairing(
        species: SpeciesTable.Species,
        defenderTypes: List<String>,
    ): Counter {
        val fasts = species.fastMoves.mapNotNull { MoveTable[it] }
        val chargeds = species.chargedMoves.mapNotNull { MoveTable[it] }
        val bestFast = fasts.maxByOrNull { moveScore(it, species.types, defenderTypes) }
        val bestCharged = chargeds.maxByOrNull { moveScore(it, species.types, defenderTypes) }
        return Counter(
            species, bestFast, bestCharged,
            pairScore(bestFast, bestCharged, species.types, defenderTypes, species.stats.attack),
        )
    }

    /**
     * Top counters across the whole Pokédex, each with its best learnable moveset.
     *
     * Mega/Primal forms are excluded: they're a transient battle state you can't simply
     * send into a fight, so recommending one as a counter isn't actionable.
     */
    fun bestInGame(defenderTypes: List<String>, limit: Int = 2): List<Counter> =
        SpeciesTable.all()
            .filterNot { it.isTransientForm }
            .map { bestPairing(it, defenderTypes) }
            .filter { it.score > 0 }
            .sortedByDescending { it.score }
            .take(limit)

    /** One owned Pokémon, as the index knows it. */
    data class Owned(
        val species: String,
        val cp: Int?,
        val fastMove: String?,
        val chargedMove: String?,
    )

    /**
     * Top counters from what you own, scored on each Pokémon's ACTUAL moveset.
     *
     * A Pokémon whose moves are unknown is scored on its moves alone being absent — it
     * still ranks on stats and typing, but can't beat an equivalent Pokémon whose real
     * moves are known to be good, which is the honest ordering given what we know.
     */
    fun fromInventory(
        owned: List<Owned>,
        defenderTypes: List<String>,
        limit: Int = 2,
    ): List<Counter> = owned.mapNotNull { o ->
        val species = SpeciesTable.species(o.species) ?: return@mapNotNull null
        val fast = o.fastMove?.let { MoveTable.byName(it) ?: MoveTable[it] }
        val charged = o.chargedMove?.let { MoveTable.byName(it) ?: MoveTable[it] }
        Counter(
            species, fast, charged,
            pairScore(fast, charged, species.types, defenderTypes, species.stats.attack),
        )
    }
        .filter { it.score > 0 }
        .sortedByDescending { it.score }
        .take(limit)
}
