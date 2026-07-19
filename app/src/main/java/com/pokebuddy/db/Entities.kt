package com.pokebuddy.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per owned Pokémon — the local index from the project scope.
 *
 * IV is stored as exact values (ivAtk/Def/Sta non-null) when the decode is unique, or as
 * a candidate count (ivCandidates > 1, exacts null) when it isn't — mirroring the rule
 * "never silently presents a guess as certain".
 */
@Entity(
    tableName = "owned_pokemon",
    indices = [Index("species"), Index("cp")],
)
data class OwnedPokemon(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val species: String,
    val cp: Int?,
    val hpMax: Int?,
    val ivAtk: Int? = null,
    val ivDef: Int? = null,
    val ivSta: Int? = null,
    val ivPercent: Int? = null,
    /** >1 when the decode returned a candidate set rather than an exact IV. */
    val ivCandidates: Int = 0,
    /** Current moveset as shown on the detail screen ("Quick Attack"), null until read.
     *  Stored as the display name rather than a move id: it's what OCR gives us, and
     *  MoveTable resolves it either way. */
    val fastMove: String? = null,
    val chargedMove: String? = null,
    val appraisalText: String? = null,
    val capturedAt: Long = System.currentTimeMillis(),
)

/**
 * Candy, keyed by EVOLUTION FAMILY rather than species.
 *
 * In Pokémon GO candy is shared across a family: a Pikachu's screen and a Raichu's screen
 * report the same "PIKACHU CANDY" number, and spending it on one depletes it for the other.
 * Keying by species would store that single pool once per species and let the copies drift
 * apart, so [family] (from base_stats.csv) is the primary key.
 *
 * The label on screen names the family's base species ("PIKACHU CANDY"), which is resolved
 * to a family id before storing.
 */
@Entity(tableName = "family_resource")
data class FamilyResource(
    @PrimaryKey val family: String,
    val candy: Int = 0,
    val candyXl: Int = 0,
)

/**
 * Mega/primal energy, ONE ROW PER VARIANT.
 *
 * A species can have any number of mega variants — Pikachu's screen shows both
 * "RAICHU MEGA ENERGY X" and "...Y", and nothing rules out a third later. A column per
 * variant would need a schema change each time; a row per variant needs none, so the
 * variant count is data rather than structure.
 *
 * [variant] is the bare suffix ("X", "Y") or "" for an unsuffixed "MEGA ENERGY".
 * [species] is the MEGA's species (Raichu), which is not the species whose screen it was
 * read from (Pikachu) — mega energy belongs to the evolution, not the pre-evolution.
 *
 * Rows only exist for variants actually observed, so readers see exactly the variants a
 * species really has rather than a padded set of zeros.
 */
@Entity(tableName = "mega_energy", primaryKeys = ["species", "variant"])
data class MegaEnergy(
    val species: String,
    val variant: String,
    val amount: Int,
)
