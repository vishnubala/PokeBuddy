package com.pokebuddy.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [OwnedPokemon::class, SpeciesResource::class, MegaEnergy::class],
    version = 2,
    exportSchema = false,
)
abstract class PokeDatabase : RoomDatabase() {
    abstract fun ownedDao(): OwnedPokemonDao
    abstract fun speciesDao(): SpeciesResourceDao
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

        @Volatile private var instance: PokeDatabase? = null

        fun get(context: Context): PokeDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, PokeDatabase::class.java, "pokebuddy.db"
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
