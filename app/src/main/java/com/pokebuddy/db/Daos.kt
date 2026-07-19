package com.pokebuddy.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert

/**
 * DAO methods are plain (blocking) — the capture pipeline already runs on a worker
 * thread, so it calls these off the main thread directly.
 */
@Dao
interface OwnedPokemonDao {
    @Insert
    fun insert(pokemon: OwnedPokemon): Long

    @Query("SELECT * FROM owned_pokemon ORDER BY capturedAt DESC")
    fun all(): List<OwnedPokemon>

    @Query("SELECT * FROM owned_pokemon WHERE species = :species ORDER BY cp DESC")
    fun bySpecies(species: String): List<OwnedPokemon>

    @Query("SELECT MAX(cp) FROM owned_pokemon WHERE species = :species")
    fun bestCp(species: String): Int?

    @Query("SELECT COUNT(*) FROM owned_pokemon")
    fun count(): Int

    /** Best known IV% among owned of that species. Null when none is decoded exactly yet. */
    @Query("SELECT MAX(ivPercent) FROM owned_pokemon WHERE species = :species")
    fun bestIvPercent(species: String): Int?

    @Query("SELECT COUNT(*) FROM owned_pokemon WHERE species = :species")
    fun countOfSpecies(species: String): Int

    // --- IV ranking (within a species only — cross-species IV rank isn't meaningful here) ---
    //
    // Ranks are over rows whose IV is actually KNOWN. A NULL ivPercent fails the `>`
    // comparison in SQL, so undecoded Pokémon neither inflate a rank nor pad the total —
    // "3rd of 5" always means 3rd among 5 we can genuinely compare.

    /** Rank of an IV% among owned of that species, 1 = highest. */
    @Query("SELECT COUNT(*) + 1 FROM owned_pokemon WHERE species = :species AND ivPercent > :percent")
    fun ivRankInSpecies(species: String, percent: Int): Int

    @Query("SELECT COUNT(*) FROM owned_pokemon WHERE species = :species AND ivPercent IS NOT NULL")
    fun countOfSpeciesWithIv(species: String): Int

    /**
     * The same Pokémon rescanned — species, CP and max HP all agreeing. Used to update a
     * row instead of inserting a duplicate every time a screen is captured.
     *
     * Two genuinely different Pokémon can collide here, but only if they're identical on
     * every value we can read, in which case they'd also share an IV candidate set — they
     * are indistinguishable to us, and the index exists to answer "what's my best", which
     * a duplicate wouldn't change.
     */
    @Query("SELECT * FROM owned_pokemon WHERE species = :species AND cp = :cp AND hpMax IS :hpMax LIMIT 1")
    fun findMatch(species: String, cp: Int?, hpMax: Int?): OwnedPokemon?

    /** Rewrites a row's IV — exact values when known, else nulls plus a candidate count. */
    @Query(
        "UPDATE owned_pokemon SET ivAtk = :atk, ivDef = :def, ivSta = :sta, " +
            "ivPercent = :percent, ivCandidates = :candidates WHERE id = :id"
    )
    fun updateIv(id: Long, atk: Int?, def: Int?, sta: Int?, percent: Int?, candidates: Int)
}

@Dao
interface SpeciesResourceDao {
    @Upsert
    fun upsert(resource: SpeciesResource)

    @Query("SELECT * FROM species_resource WHERE species = :species")
    fun get(species: String): SpeciesResource?
}

@Dao
interface MegaEnergyDao {
    @Upsert
    fun upsert(energy: MegaEnergy)

    /** Every variant we've actually seen for this species — one row each, ordered so an
     *  unsuffixed variant leads and X/Y follow. Empty when the species has no mega. */
    @Query("SELECT * FROM mega_energy WHERE species = :species ORDER BY variant")
    fun forSpecies(species: String): List<MegaEnergy>
}
