package com.pokebuddy.iv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Counter ranking against the real bundled assets.
 *
 * These assert on PROPERTIES a correct ranking must have (the picks resist the defender,
 * their moves are super-effective) rather than on exact species names, which would churn
 * every time the game data updates.
 */
class CounterFinderTest {

    @Before fun setUp() {
        SpeciesTable.load(File("src/main/assets/base_stats.csv").readText())
        MoveTable.load(File("src/main/assets/moves.csv").readText())
    }

    @Test fun move_and_species_tables_load() {
        assertTrue(MoveTable.size > 300)
        assertNotNull(MoveTable.byName("Quick Attack"))
        assertTrue(SpeciesTable.species("Raichu (Alolan)")!!.fastMoves.isNotEmpty())
    }

    @Test fun best_in_game_counters_hit_the_defender_hard() {
        // Machamp is pure fighting: flying/psychic/fairy hit it super-effectively.
        val picks = CounterFinder.bestInGame(listOf("fighting"), limit = 2)
        assertEquals(2, picks.size)
        for (p in picks) {
            val best = listOfNotNull(p.fast, p.charged)
                .maxOf { TypeChart.multiplier(it.type, listOf("fighting")) }
            assertTrue("${p.describe()} has no super-effective move", best > 1.0)
        }
    }

    /** Megas are a transient state, so they can't be sent in as a counter. */
    @Test fun best_in_game_never_recommends_a_mega() {
        val picks = CounterFinder.bestInGame(listOf("dragon"), limit = 10)
        assertTrue(picks.none { it.species.isTransientForm })
    }

    @Test fun dual_type_defenders_are_scored_on_both_types() {
        // Charizard (fire/flying) takes 2.56x from rock.
        val picks = CounterFinder.bestInGame(listOf("fire", "flying"), limit = 3)
        assertTrue(
            "expected a rock/electric/water attacker, got ${picks.map { it.describe() }}",
            picks.any { p ->
                listOfNotNull(p.fast, p.charged).any {
                    TypeChart.multiplier(it.type, listOf("fire", "flying")) > 1.5
                }
            },
        )
    }

    // ---------- Inventory mode: scored on the moves you actually have ----------

    @Test fun inventory_ranks_by_the_moveset_you_own() {
        val owned = listOf(
            // Right typing but a move that does nothing to a ghost.
            CounterFinder.Owned("Gengar", 2000, "Lick", "Sludge Bomb"),
            // Same species, actually-good ghost damage.
            CounterFinder.Owned("Gengar", 1800, "Lick", "Shadow Ball"),
        )
        val picks = CounterFinder.fromInventory(owned, listOf("psychic"), limit = 2)
        assertEquals("Shadow Ball", picks.first().charged?.name)
    }

    @Test fun inventory_only_returns_pokemon_you_own() {
        val owned = listOf(CounterFinder.Owned("Pikachu", 671, "Thunder Shock", "Wild Charge"))
        val picks = CounterFinder.fromInventory(owned, listOf("water"), limit = 2)
        assertEquals(1, picks.size)
        assertEquals("Pikachu", picks.single().species.name)
    }

    @Test fun unknown_species_in_the_index_is_skipped_not_crashed() {
        val owned = listOf(CounterFinder.Owned("Notamon", 100, null, null))
        assertTrue(CounterFinder.fromInventory(owned, listOf("water")).isEmpty())
    }

    @Test fun stab_rewards_a_move_matching_the_attackers_type() {
        // Electric Pikachu with an electric move beats a hypothetical neutral attacker
        // of equal stats, purely from STAB.
        val withStab = CounterFinder.fromInventory(
            listOf(CounterFinder.Owned("Pikachu", 1, "Thunder Shock", "Wild Charge")),
            listOf("water"),
        ).single().score
        val withoutStab = CounterFinder.fromInventory(
            listOf(CounterFinder.Owned("Pikachu", 1, "Quick Attack", "Wild Charge")),
            listOf("water"),
        ).single().score
        assertTrue(withStab > withoutStab)
    }
}
