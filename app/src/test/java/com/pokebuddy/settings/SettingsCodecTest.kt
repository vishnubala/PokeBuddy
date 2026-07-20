package com.pokebuddy.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backup format. Worth testing carefully because its failure mode is invisible until
 * someone reinstalls and tries to restore — by which point the original is gone.
 */
class SettingsCodecTest {

    @Test fun defaults_round_trip_unchanged() {
        val exported = SettingsCodec.export(emptyMap())
        val back = SettingsCodec.import(exported)
        SettingsSpec.all.forEach { s ->
            assertEquals("default for ${s.key}", s.default, back[s.key])
        }
    }

    @Test fun edited_values_round_trip() {
        val values = mapOf(
            SettingsSpec.OVERLAY_TEXT_SP.key to 20,
            SettingsSpec.SHOW_RANK.key to false,
            SettingsSpec.PANEL_DISMISS_SECONDS.key to 0,
            SettingsSpec.PANEL_X.key to 512,
        )
        val back = SettingsCodec.import(SettingsCodec.export(values))
        assertEquals(20, back[SettingsSpec.OVERLAY_TEXT_SP.key])
        assertEquals(false, back[SettingsSpec.SHOW_RANK.key])
        assertEquals(0, back[SettingsSpec.PANEL_DISMISS_SECONDS.key])
        assertEquals(512, back[SettingsSpec.PANEL_X.key])
    }

    /** A backup from a future/older schema must be refused, not applied piecemeal. */
    @Test fun a_backup_from_another_version_is_refused() {
        val wrong = SettingsCodec.export(emptyMap())
            .replace("\"version\": ${SettingsSpec.VERSION}", "\"version\": 99")
        val e = assertThrows(SettingsCodec.IncompatibleBackup::class.java) {
            SettingsCodec.import(wrong)
        }
        assertEquals(99, e.foundVersion)
    }

    @Test fun a_file_with_no_version_is_refused() {
        assertThrows(SettingsCodec.IncompatibleBackup::class.java) {
            SettingsCodec.import("""{"settings":{"showRank":false}}""")
        }
    }

    /**
     * A backup taken before a setting existed must still restore — the new key just takes
     * its default. Otherwise every added setting invalidates every existing backup.
     */
    @Test fun a_backup_missing_a_key_falls_back_to_its_default() {
        val partial = """{"version": ${SettingsSpec.VERSION}, "settings": {"showRank": false}}"""
        val back = SettingsCodec.import(partial)
        assertEquals(false, back[SettingsSpec.SHOW_RANK.key])
        assertEquals(
            SettingsSpec.OVERLAY_TEXT_SP.default,
            back[SettingsSpec.OVERLAY_TEXT_SP.key],
        )
    }

    /** An out-of-range value is clamped, not trusted — a 200sp panel can't be undone in-app. */
    @Test fun out_of_range_values_are_clamped() {
        val silly = """{"version": ${SettingsSpec.VERSION}, "settings": {"overlayTextSp": 200}}"""
        assertEquals(
            SettingsSpec.OVERLAY_TEXT_SP.max,
            SettingsCodec.import(silly)[SettingsSpec.OVERLAY_TEXT_SP.key],
        )
    }

    @Test fun unknown_keys_are_ignored_rather_than_failing_the_import() {
        val extra = """{"version": ${SettingsSpec.VERSION}, "settings": {"somethingElse": 3}}"""
        assertEquals(SettingsSpec.all.size, SettingsCodec.import(extra).size)
    }

    /**
     * Every key at a NON-default value, so a key that silently falls back to its default
     * can't pass by coincidence. This is the test that would catch a format or regex bug
     * in the hand-rolled codec — the previous round-trip tests only cover a handful of keys,
     * and defaults round-tripping proves nothing about parsing.
     */
    @Test fun every_key_round_trips_at_a_non_default_value() {
        val values = SettingsSpec.all.associate { s ->
            s.key to when (s) {
                is SettingsSpec.BoolSetting -> !s.default
                // Pick a legal value that differs from the default at both ends of the range.
                is SettingsSpec.IntSetting ->
                    if (s.default == s.max) s.default - 1 else s.default + 1
            }
        }
        val back = SettingsCodec.import(SettingsCodec.export(values))
        SettingsSpec.all.forEach { s ->
            assertEquals("round trip for ${s.key}", values[s.key], back[s.key])
            assertTrue("${s.key} must differ from its default", values[s.key] != s.default)
        }
    }

    /** The exported document must actually be JSON-shaped, not just something we can re-read. */
    @Test fun export_is_well_formed_json() {
        val json = SettingsCodec.export(emptyMap())
        assertTrue(json.trimStart().startsWith("{"))
        assertTrue(json.trimEnd().endsWith("}"))
        // Balanced braces, and no trailing comma before a close.
        assertEquals(json.count { it == '{' }, json.count { it == '}' })
        assertTrue("trailing comma", !Regex(",\\s*[}\\]]").containsMatchIn(json))
        SettingsSpec.all.forEach { assertTrue("${it.key} missing", json.contains("\"${it.key}\"")) }
    }

    /** A key whose name is a prefix of another must not capture the wrong value. */
    @Test fun a_prefix_key_does_not_capture_another_keys_value() {
        val json = """{"version": ${SettingsSpec.VERSION}, "settings": {
            "panelXY": 999, "panelX": 111, "panelY": 222 }}"""
        val back = SettingsCodec.import(json)
        assertEquals(111, back[SettingsSpec.PANEL_X.key])
        assertEquals(222, back[SettingsSpec.PANEL_Y.key])
    }

    @Test fun every_spec_key_is_unique() {
        val keys = SettingsSpec.all.map { it.key }
        assertEquals(keys.size, keys.distinct().size)
    }

    @Test fun every_int_default_sits_inside_its_own_range() {
        SettingsSpec.all.filterIsInstance<SettingsSpec.IntSetting>().forEach {
            assertTrue("${it.key} default out of range", it.default in it.min..it.max)
        }
    }
}
