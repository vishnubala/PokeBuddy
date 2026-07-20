package com.pokebuddy.settings

/**
 * Serialises settings to and from a small JSON document, for the backup/restore flow.
 *
 * Framework-independent so it can be unit-tested without a device, and hand-rolled rather
 * than pulling in a JSON library for a flat map of scalars.
 *
 * The file carries a [SettingsSpec.VERSION]. Import REFUSES a version it doesn't know
 * rather than applying it partially: a backup silently restored under changed key meanings
 * is worse than one that plainly says it can't be used.
 *
 * Settings only. It never contains anything from the Pokémon index, which holds catch
 * locations and dates — that export is separate and deliberately so.
 */
object SettingsCodec {

    class IncompatibleBackup(val foundVersion: Int) :
        Exception("Settings backup is version $foundVersion; this build reads ${SettingsSpec.VERSION}")

    /** @param values current value per key; missing keys fall back to the spec default. */
    fun export(values: Map<String, Any?>): String = buildString {
        append("{\n  \"version\": ").append(SettingsSpec.VERSION).append(",\n")
        append("  \"settings\": {\n")
        SettingsSpec.all.forEachIndexed { i, s ->
            val v = values[s.key] ?: s.default
            append("    \"").append(s.key).append("\": ")
            when (s) {
                is SettingsSpec.BoolSetting -> append(v == true)
                is SettingsSpec.IntSetting -> append((v as? Int) ?: s.default)
            }
            append(if (i == SettingsSpec.all.lastIndex) "\n" else ",\n")
        }
        append("  }\n}")
    }

    /**
     * @return one entry per key in the spec — unknown keys in the file are ignored and
     *   missing ones take their default, so a backup taken before a setting existed still
     *   restores cleanly.
     * @throws IncompatibleBackup if the file's version isn't the one this build understands.
     */
    fun import(json: String): Map<String, Any> {
        val version = Regex("\"version\"\\s*:\\s*(\\d+)").find(json)
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: throw IncompatibleBackup(-1)
        if (version != SettingsSpec.VERSION) throw IncompatibleBackup(version)

        return SettingsSpec.all.associate { s ->
            val raw = Regex("\"${Regex.escape(s.key)}\"\\s*:\\s*([^,\\s}]+)").find(json)
                ?.groupValues?.get(1)
            s.key to when (s) {
                is SettingsSpec.BoolSetting -> raw?.toBooleanStrictOrNull() ?: s.default
                // Clamp rather than trust: a hand-edited or older file could carry a value
                // outside the range, and a 200sp panel is not a recoverable state.
                is SettingsSpec.IntSetting -> s.clamp(raw?.toIntOrNull() ?: s.default)
            }
        }
    }
}
