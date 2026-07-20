package com.pokebuddy.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DB backup format. Tested hard because its failure mode is invisible until a restore
 * after a reinstall — when the original database is already gone.
 */
class BackupCodecTest {

    private fun owned(
        id: Long = 1, species: String = "Pikachu", cp: Int? = 671, hpMax: Int? = 86,
        location: String? = null, date: String? = null, shiny: Boolean? = null,
    ) = OwnedPokemon(
        id = id, species = species, cp = cp, hpMax = hpMax,
        ivAtk = 3, ivDef = 15, ivSta = 12, ivPercent = 66, ivCandidates = 0,
        fastMove = "Quick Attack", chargedMove = "Thunderbolt", chargedMove2 = null,
        weight = "7.5kg", height = "0.46m", caughtLocation = location, caughtDate = date,
        appraisalText = null, shiny = shiny, lucky = false, dynamax = false, sizeBadge = null,
        capturedAt = 1_784_432_327_641,
    )

    private fun snapshot(vararg p: OwnedPokemon) = BackupCodec.Snapshot(
        owned = p.toList(),
        families = listOf(FamilyResource("PIKACHU", candy = 835, candyXl = 4)),
        megas = listOf(MegaEnergy("Raichu", "X", amount = 1350, megaLevel = "Base Level")),
    )

    private fun roundTrip(s: BackupCodec.Snapshot) = BackupCodec.import(BackupCodec.export(s))

    @Test fun a_full_snapshot_round_trips_field_for_field() {
        val original = snapshot(owned())
        val back = roundTrip(original)
        assertEquals(original.owned, back.owned)
        assertEquals(original.families, back.families)
        assertEquals(original.megas, back.megas)
    }

    @Test fun ids_survive_so_identity_is_preserved() {
        val back = roundTrip(snapshot(owned(id = 42), owned(id = 99, species = "Grimer")))
        assertEquals(listOf(42L, 99L), back.owned.map { it.id })
    }

    /**
     * The reason this codec uses a real parser rather than the settings codec's regex: catch
     * locations are free text with commas, and a naive split would shred them.
     */
    @Test fun a_catch_location_with_commas_survives() {
        val back = roundTrip(snapshot(owned(location = "Waterloo, Ontario, Canada")))
        assertEquals("Waterloo, Ontario, Canada", back.owned.single().caughtLocation)
    }

    @Test fun quotes_and_backslashes_and_unicode_in_a_location_survive() {
        val nasty = """O'Hare "Terminal 3", C:\x — Kraków, Ōsaka"""
        val back = roundTrip(snapshot(owned(location = nasty)))
        assertEquals(nasty, back.owned.single().caughtLocation)
    }

    @Test fun nulls_stay_null_rather_than_becoming_the_string_null() {
        val back = roundTrip(snapshot(owned(cp = null, hpMax = null, location = null)))
        val p = back.owned.single()
        assertNull(p.cp); assertNull(p.hpMax); assertNull(p.caughtLocation)
    }

    /** shiny is tri-state — null (not looked), true, false — and all three must survive. */
    @Test fun tri_state_shiny_survives_each_value() {
        assertNull(roundTrip(snapshot(owned(shiny = null))).owned.single().shiny)
        assertEquals(true, roundTrip(snapshot(owned(shiny = true))).owned.single().shiny)
        assertEquals(false, roundTrip(snapshot(owned(shiny = false))).owned.single().shiny)
    }

    @Test fun an_empty_index_round_trips() {
        val empty = BackupCodec.Snapshot(emptyList(), emptyList(), emptyList())
        val back = roundTrip(empty)
        assertTrue(back.owned.isEmpty() && back.families.isEmpty() && back.megas.isEmpty())
    }

    // ---- version guard ----

    @Test fun a_newer_backup_is_refused() {
        val newer = BackupCodec.export(snapshot(owned()))
            .replace("\"schemaVersion\":${BackupCodec.SCHEMA_VERSION}", "\"schemaVersion\":999")
        val e = assertThrows(BackupCodec.IncompatibleBackup::class.java) {
            BackupCodec.import(newer)
        }
        assertEquals(999, e.foundVersion)
    }

    /**
     * An older backup restores, with columns added since defaulting cleanly. Simulated by
     * exporting then stripping a newer field and lowering the version — the shape an older
     * build would have produced.
     */
    @Test fun an_older_backup_restores_with_new_columns_defaulted() {
        val older = """
            {"schemaVersion":6,"owned":[{"id":7,"species":"Grimer","cp":377,"hpMax":88}],
             "families":[],"megas":[]}
        """.trimIndent()
        val p = BackupCodec.import(older).owned.single()
        assertEquals("Grimer", p.species)
        assertEquals(377, p.cp)
        assertNull("shiny defaults to null", p.shiny)
        assertEquals("lucky defaults to false", false, p.lucky)
    }

    @Test fun garbage_is_reported_as_malformed_not_crashed() {
        assertThrows(BackupCodec.MalformedBackup::class.java) {
            BackupCodec.import("this is not json")
        }
        assertThrows(BackupCodec.MalformedBackup::class.java) {
            BackupCodec.import("""{"owned":[]}""")   // no version
        }
    }

    /**
     * The load-bearing invariant: the backup version and the Room database version move
     * together. They can't drift because `@Database(version = …)` literally reads
     * [BackupCodec.SCHEMA_VERSION] — this test documents that the constant is a real,
     * positive schema version rather than a stray default, and fails loudly if it's ever
     * decoupled from something sensible.
     *
     * (The link can't be checked reflectively: androidx.room.Database has BINARY retention,
     * so the annotation isn't present at runtime — which is exactly why the shared constant,
     * not a reflective assertion, is the mechanism that keeps them in step.)
     */
    @Test fun schema_version_is_a_sane_positive_number() {
        assertTrue(BackupCodec.SCHEMA_VERSION >= 7)
    }
}
