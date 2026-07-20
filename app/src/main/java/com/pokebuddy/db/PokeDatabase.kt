package com.pokebuddy.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [OwnedPokemon::class, FamilyResource::class, MegaEnergy::class],
    version = 7,
    exportSchema = false,
)
abstract class PokeDatabase : RoomDatabase() {
    abstract fun ownedDao(): OwnedPokemonDao
    abstract fun familyDao(): FamilyResourceDao
    abstract fun megaEnergyDao(): MegaEnergyDao

    companion object {
        /**
         * v1 → v2: mega energy moves out of species_resource into its own row-per-variant
         * table (see [MegaEnergy]).
         *
         * species_resource is recreated rather than ALTERed because SQLite can't drop a
         * column in older versions — safe here only because nothing has ever written to
         * that table. owned_pokemon is deliberately untouched, so the scanned index
         * survives the upgrade.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS mega_energy (" +
                        "species TEXT NOT NULL, variant TEXT NOT NULL, " +
                        "amount INTEGER NOT NULL, PRIMARY KEY(species, variant))"
                )
                db.execSQL("DROP TABLE IF EXISTS species_resource")
                db.execSQL(
                    "CREATE TABLE species_resource (" +
                        "species TEXT NOT NULL, candy INTEGER NOT NULL, " +
                        "candyXl INTEGER NOT NULL, megaLevelUnlocked INTEGER NOT NULL, " +
                        "PRIMARY KEY(species))"
                )
            }
        }

        /**
         * v2 → v3: candy moves from a per-species table to a per-family one (see
         * [FamilyResource]).
         *
         * species_resource is dropped rather than migrated: its rows were keyed by species
         * with no family column to map from, and the values are re-read from the game the
         * next time any Pokémon of that family is scanned. owned_pokemon and mega_energy
         * are untouched.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS family_resource (" +
                        "family TEXT NOT NULL, candy INTEGER NOT NULL, " +
                        "candyXl INTEGER NOT NULL, PRIMARY KEY(family))"
                )
                db.execSQL("DROP TABLE IF EXISTS species_resource")
            }
        }

        /**
         * v3 → v4: record each Pokémon's current moveset, so inventory counters can be
         * scored on the moves it actually has rather than the best it could learn.
         *
         * Plain ADD COLUMNs — every existing row keeps its data and simply reads null
         * until the next scan fills the moves in.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE owned_pokemon ADD COLUMN fastMove TEXT")
                db.execSQL("ALTER TABLE owned_pokemon ADD COLUMN chargedMove TEXT")
            }
        }

        /** v4 → v5: a Pokémon can be taught a SECOND charged move. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE owned_pokemon ADD COLUMN chargedMove2 TEXT")
            }
        }

        /**
         * v5 → v6: store the per-individual constants used for stable identity.
         *
         * Existing rows read null and get filled in on their next scan; until then they
         * still match on the old species+CP+maxHP path.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE owned_pokemon ADD COLUMN weight TEXT")
                db.execSQL("ALTER TABLE owned_pokemon ADD COLUMN height TEXT")
                db.execSQL("ALTER TABLE owned_pokemon ADD COLUMN caughtLocation TEXT")
                db.execSQL("ALTER TABLE owned_pokemon ADD COLUMN caughtDate TEXT")
            }
        }

        /**
         * v6 → v7: tracked flags (shiny / lucky / dynamax / size badge) and mega level.
         *
         * shiny is added WITHOUT a NOT NULL default on purpose. Every other flag can be
         * decided from the detail screen, so false is a real answer for them; shiny cannot
         * be seen there at all, and defaulting it to false would record "not shiny" for the
         * whole existing index without anything ever having checked.
         *
         * megaLevel goes on mega_energy rather than owned_pokemon because the game scopes
         * it to species+variant — the key that table already has.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE owned_pokemon ADD COLUMN shiny INTEGER")
                db.execSQL("ALTER TABLE owned_pokemon ADD COLUMN lucky INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE owned_pokemon ADD COLUMN dynamax INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE owned_pokemon ADD COLUMN sizeBadge TEXT")
                db.execSQL("ALTER TABLE mega_energy ADD COLUMN megaLevel TEXT")
            }
        }

        @Volatile private var instance: PokeDatabase? = null

        fun get(context: Context): PokeDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, PokeDatabase::class.java, "pokebuddy.db"
            ).addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                MIGRATION_6_7,
            )
                .build().also { instance = it }
        }
    }
}
