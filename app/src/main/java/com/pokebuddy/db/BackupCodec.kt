package com.pokebuddy.db

/**
 * Serialises the whole local index — owned Pokémon, family candy, mega energy — to a JSON
 * document and back, for an explicit, user-initiated backup.
 *
 * The index holds catch locations and dates, which is personal data. That's fine in a backup
 * the user saves to their own storage; it is why this export must never be uploaded anywhere
 * and its fixtures must never be committed. The settings backup is deliberately separate so a
 * shareable settings file never drags the index along.
 *
 * ## Versioning
 *
 * The document is stamped with [SCHEMA_VERSION], kept equal to the Room database version so
 * the two move together. On import:
 *
 *  - a NEWER version than this build understands is refused outright — we can't know what a
 *    future column means, and guessing risks a corrupt restore;
 *  - an EQUAL or OLDER version is applied field-by-field with defaults for anything missing.
 *
 * That older-is-OK rule holds only because every migration so far is purely additive (ADD
 * COLUMN) — an old backup simply lacks newer columns, which default cleanly. A future
 * destructive migration would break the assumption; the guard is that [SCHEMA_VERSION] must
 * be bumped in lockstep with the Room version, so a mismatched shape fails loudly here
 * rather than silently restoring wrong data. See the test that pins them together.
 */
object BackupCodec {

    /** MUST equal the Room `@Database(version = …)`; the round-trip test asserts it. */
    const val SCHEMA_VERSION = 7

    class IncompatibleBackup(val foundVersion: Int) : Exception(
        "Backup is schema version $foundVersion; this build reads $SCHEMA_VERSION" +
            if (foundVersion > SCHEMA_VERSION) " and can't read a newer one." else "."
    )

    class MalformedBackup(cause: Throwable) : Exception("Backup file is not readable", cause)

    data class Snapshot(
        val owned: List<OwnedPokemon>,
        val families: List<FamilyResource>,
        val megas: List<MegaEnergy>,
    )

    // ---- export ----

    fun export(snapshot: Snapshot): String = Json.write(
        linkedMapOf(
            "schemaVersion" to SCHEMA_VERSION,
            "owned" to snapshot.owned.map(::ownedToMap),
            "families" to snapshot.families.map(::familyToMap),
            "megas" to snapshot.megas.map(::megaToMap),
        )
    )

    // ---- import ----

    /**
     * @throws IncompatibleBackup on a version this build won't restore.
     * @throws MalformedBackup when the file isn't the JSON shape we emit.
     */
    fun import(json: String): Snapshot {
        val root = try {
            Json.parse(json) as? Map<*, *> ?: throw MalformedBackup(
                IllegalStateException("top level is not an object")
            )
        } catch (e: Json.ParseException) {
            throw MalformedBackup(e)
        }

        val version = (root["schemaVersion"] as? Int)
            ?: throw MalformedBackup(IllegalStateException("no schemaVersion"))
        if (version > SCHEMA_VERSION) throw IncompatibleBackup(version)

        return try {
            Snapshot(
                owned = rows(root["owned"]).map(::ownedFromMap),
                families = rows(root["families"]).map(::familyFromMap),
                megas = rows(root["megas"]).map(::megaFromMap),
            )
        } catch (e: Exception) {
            if (e is IncompatibleBackup) throw e
            throw MalformedBackup(e)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun rows(node: Any?): List<Map<*, *>> =
        (node as? List<*>).orEmpty().map { it as Map<*, *> }

    // ---- field mapping ----
    //
    // Written out by hand rather than reflected: the mapping is the format's contract, and
    // seeing every column makes a rename or a dropped field obvious in review instead of
    // silently changing what a backup contains.

    private fun ownedToMap(p: OwnedPokemon) = linkedMapOf(
        "id" to p.id, "species" to p.species, "cp" to p.cp, "hpMax" to p.hpMax,
        "ivAtk" to p.ivAtk, "ivDef" to p.ivDef, "ivSta" to p.ivSta,
        "ivPercent" to p.ivPercent, "ivCandidates" to p.ivCandidates,
        "fastMove" to p.fastMove, "chargedMove" to p.chargedMove, "chargedMove2" to p.chargedMove2,
        "weight" to p.weight, "height" to p.height,
        "caughtLocation" to p.caughtLocation, "caughtDate" to p.caughtDate,
        "appraisalText" to p.appraisalText,
        "shiny" to p.shiny, "lucky" to p.lucky, "dynamax" to p.dynamax, "sizeBadge" to p.sizeBadge,
        "capturedAt" to p.capturedAt,
    )

    private fun ownedFromMap(m: Map<*, *>) = OwnedPokemon(
        id = m.long("id") ?: 0L, species = m.str("species") ?: "",
        cp = m.int("cp"), hpMax = m.int("hpMax"),
        ivAtk = m.int("ivAtk"), ivDef = m.int("ivDef"), ivSta = m.int("ivSta"),
        ivPercent = m.int("ivPercent"), ivCandidates = m.int("ivCandidates") ?: 0,
        fastMove = m.str("fastMove"), chargedMove = m.str("chargedMove"),
        chargedMove2 = m.str("chargedMove2"),
        weight = m.str("weight"), height = m.str("height"),
        caughtLocation = m.str("caughtLocation"), caughtDate = m.str("caughtDate"),
        appraisalText = m.str("appraisalText"),
        shiny = m.bool("shiny"), lucky = m.bool("lucky") ?: false,
        dynamax = m.bool("dynamax") ?: false, sizeBadge = m.str("sizeBadge"),
        // A restored row keeps its original capture time; only fall back to "now" for a
        // backup so old it predates the column.
        capturedAt = m.long("capturedAt") ?: System.currentTimeMillis(),
    )

    private fun familyToMap(f: FamilyResource) =
        linkedMapOf("family" to f.family, "candy" to f.candy, "candyXl" to f.candyXl)

    private fun familyFromMap(m: Map<*, *>) = FamilyResource(
        family = m.str("family") ?: "",
        candy = m.int("candy") ?: 0, candyXl = m.int("candyXl") ?: 0,
    )

    private fun megaToMap(e: MegaEnergy) = linkedMapOf(
        "species" to e.species, "variant" to e.variant,
        "amount" to e.amount, "megaLevel" to e.megaLevel,
    )

    private fun megaFromMap(m: Map<*, *>) = MegaEnergy(
        species = m.str("species") ?: "", variant = m.str("variant") ?: "",
        amount = m.int("amount") ?: 0, megaLevel = m.str("megaLevel"),
    )

    // Typed accessors. The JSON reader yields Int for small numbers and Long only when a
    // value overflows Int, so an id or timestamp can arrive as either — coerce both.
    private fun Map<*, *>.str(key: String) = this[key] as? String
    private fun Map<*, *>.int(key: String) = when (val v = this[key]) {
        is Int -> v
        is Long -> v.toInt()
        else -> null
    }
    private fun Map<*, *>.long(key: String) = when (val v = this[key]) {
        is Long -> v
        is Int -> v.toLong()
        else -> null
    }
    private fun Map<*, *>.bool(key: String) = this[key] as? Boolean
}
