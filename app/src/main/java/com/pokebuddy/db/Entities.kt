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
    /** Second charged move, once unlocked. Null for Pokémon that only have one. */
    val chargedMove2: String? = null,
    /**
     * Per-individual constants, fixed when the Pokémon was caught and unchanged by powering
     * up or evolving — which is exactly what makes them usable as identity. CP and maxHP
     * both move, so matching on those alone turns one powered-up Pokémon into two rows.
     *
     * Catch location and date stay on the device: they are personal data and must never be
     * committed as test fixtures (see ROADMAP).
     */
    val weight: String? = null,
    val height: String? = null,
    val caughtLocation: String? = null,
    val caughtDate: String? = null,
    val appraisalText: String? = null,
    /**
     * Tracked flags. [lucky] and [dynamax] come straight from detail-screen TEXT
     * ("LUCKY POKÉMON" under the name; a "Dynamax" row where the mega timer sits), so they
     * need no pixel work.
     *
     * [shiny] is different and deliberately NULLABLE rather than a false default: the
     * detail screen carries NO shiny marker (verified by diffing shiny and non-shiny
     * captures of the same species), so a detail scan genuinely does not know. Only the box
     * grid marks shiny, via teal sparkles on the tile sprite. Null means "not yet
     * determined"; false must only ever be written by something that actually looked.
     */
    val shiny: Boolean? = null,
    val lucky: Boolean = false,
    val dynamax: Boolean = false,
    /** Size badge shown in place of the WEIGHT/HEIGHT label ("LIGHTEST", "TALLEST", …). */
    val sizeBadge: String? = null,
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
    /**
     * Mega level for this variant ("Base Level" / "High Level" / "Max Level"), from the
     * panel behind the detail screen's DNA icon.
     *
     * It lives here rather than on [OwnedPokemon] because the game scopes it that way: the
     * panel is titled "Raichu's Mega Level" and levelling one Raichu levels them all. The
     * species+variant key this table already uses is exactly the right key for it.
     *
     * Null until that panel has actually been read — it is not on the detail screen.
     */
    val megaLevel: String? = null,
)
