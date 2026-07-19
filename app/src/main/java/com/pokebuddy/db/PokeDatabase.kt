package com.pokebuddy.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [OwnedPokemon::class, FamilyResource::class, MegaEnergy::class],
    version = 3,
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

        @Volatile private var instance: PokeDatabase? = null

        fun get(context: Context): PokeDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, PokeDatabase::class.java, "pokebuddy.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
        }
    }
}
