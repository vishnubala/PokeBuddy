package com.pokebuddy.iv

/**
 * Pokémon GO type effectiveness.
 *
 * Groundwork for counter/resistance suggestions: [multiplier] gives the damage scaling of an
 * attacking type against a defender's (one or two) types.
 *
 * Encoded as the per-attacking-type lists the chart is normally published as — "fire is
 * strong against grass, ice, bug, steel" — rather than an 18×18 grid, because a grid is 324
 * hand-typed cells and a typo in one is invisible. The matrix is derived from these.
 *
 * GO's multipliers differ from the main series: there is no true immunity, only a second
 * resistance step. Attacking a type the main games call immune deals [IMMUNE] (~0.39×), not 0.
 */
object TypeChart {

    const val SUPER_EFFECTIVE = 1.6
    const val NOT_VERY_EFFECTIVE = 0.625      // 1 / 1.6
    const val IMMUNE = 0.390625               // 1 / 1.6^2 — "double resist" in GO
    const val NEUTRAL = 1.0

    val TYPES = listOf(
        "normal", "fire", "water", "electric", "grass", "ice", "fighting", "poison",
        "ground", "flying", "psychic", "bug", "rock", "ghost", "dragon", "dark",
        "steel", "fairy",
    )

    /** attacking type → (super effective against, resisted by, doubly resisted by) */
    private data class Row(val strong: List<String>, val weak: List<String>, val none: List<String> = emptyList())

    private val CHART = mapOf(
        "normal" to Row(emptyList(), listOf("rock", "steel"), listOf("ghost")),
        "fire" to Row(listOf("grass", "ice", "bug", "steel"), listOf("fire", "water", "rock", "dragon")),
        "water" to Row(listOf("fire", "ground", "rock"), listOf("water", "grass", "dragon")),
        "electric" to Row(listOf("water", "flying"), listOf("electric", "grass", "dragon"), listOf("ground")),
        "grass" to Row(
            listOf("water", "ground", "rock"),
            listOf("fire", "grass", "poison", "flying", "bug", "dragon", "steel"),
        ),
        "ice" to Row(listOf("grass", "ground", "flying", "dragon"), listOf("fire", "water", "ice", "steel")),
        "fighting" to Row(
            listOf("normal", "ice", "rock", "dark", "steel"),
            listOf("poison", "flying", "psychic", "bug", "fairy"),
            listOf("ghost"),
        ),
        "poison" to Row(listOf("grass", "fairy"), listOf("poison", "ground", "rock", "ghost"), listOf("steel")),
        "ground" to Row(
            listOf("fire", "electric", "poison", "rock", "steel"),
            listOf("grass", "bug"),
            listOf("flying"),
        ),
        "flying" to Row(listOf("grass", "fighting", "bug"), listOf("electric", "rock", "steel")),
        "psychic" to Row(listOf("fighting", "poison"), listOf("psychic", "steel"), listOf("dark")),
        "bug" to Row(
            listOf("grass", "psychic", "dark"),
            listOf("fire", "fighting", "poison", "flying", "ghost", "steel", "fairy"),
        ),
        "rock" to Row(listOf("fire", "ice", "flying", "bug"), listOf("fighting", "ground", "steel")),
        "ghost" to Row(listOf("psychic", "ghost"), listOf("dark"), listOf("normal")),
        "dragon" to Row(listOf("dragon"), listOf("steel"), listOf("fairy")),
        "dark" to Row(listOf("psychic", "ghost"), listOf("fighting", "dark", "fairy")),
        "steel" to Row(listOf("ice", "rock", "fairy"), listOf("fire", "water", "electric", "steel")),
        "fairy" to Row(listOf("fighting", "dragon", "dark"), listOf("fire", "poison", "steel")),
    )

    /** Damage scaling of [attacking] against a single defending type. */
    fun multiplier(attacking: String, defending: String): Double {
        val row = CHART[attacking.lowercase()] ?: return NEUTRAL
        val d = defending.lowercase()
        return when (d) {
            in row.none -> IMMUNE
            in row.weak -> NOT_VERY_EFFECTIVE
            in row.strong -> SUPER_EFFECTIVE
            else -> NEUTRAL
        }
    }

    /** Damage scaling against a defender's full typing; dual types multiply. */
    fun multiplier(attacking: String, defending: List<String>): Double =
        defending.fold(1.0) { acc, t -> acc * multiplier(attacking, t) }

    /** Attacking types sorted by how hard they hit [defending], best first. */
    fun counters(defending: List<String>): List<Pair<String, Double>> =
        TYPES.map { it to multiplier(it, defending) }
            .filter { it.second > NEUTRAL }
            .sortedByDescending { it.second }

    /** Attacking types [defending] resists, most resisted first. */
    fun resistances(defending: List<String>): List<Pair<String, Double>> =
        TYPES.map { it to multiplier(it, defending) }
            .filter { it.second < NEUTRAL }
            .sortedBy { it.second }
}
