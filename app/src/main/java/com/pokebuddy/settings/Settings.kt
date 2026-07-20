package com.pokebuddy.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences-backed reader/writer for [SettingsSpec].
 *
 * The thin Android-facing half: everything with logic worth testing lives in the spec and
 * [SettingsCodec], which are plain Kotlin.
 */
class Settings private constructor(private val prefs: SharedPreferences) {

    fun get(s: SettingsSpec.BoolSetting): Boolean = prefs.getBoolean(s.key, s.default)
    fun get(s: SettingsSpec.IntSetting): Int = s.clamp(prefs.getInt(s.key, s.default))

    fun set(s: SettingsSpec.BoolSetting, v: Boolean) = prefs.edit().putBoolean(s.key, v).apply()
    fun set(s: SettingsSpec.IntSetting, v: Int) = prefs.edit().putInt(s.key, s.clamp(v)).apply()

    /** Current values keyed by setting id — the shape [SettingsCodec.export] wants. */
    fun snapshot(): Map<String, Any> = SettingsSpec.all.associate { s ->
        s.key to when (s) {
            is SettingsSpec.BoolSetting -> get(s)
            is SettingsSpec.IntSetting -> get(s)
        }
    }

    /** Applies a whole imported map at once, so a failed import can't half-apply. */
    fun restore(values: Map<String, Any>) {
        val e = prefs.edit()
        SettingsSpec.all.forEach { s ->
            when (s) {
                is SettingsSpec.BoolSetting -> (values[s.key] as? Boolean)?.let { e.putBoolean(s.key, it) }
                is SettingsSpec.IntSetting -> (values[s.key] as? Int)?.let { e.putInt(s.key, s.clamp(it)) }
            }
        }
        e.apply()
    }

    fun resetToDefaults() = prefs.edit().clear().apply()

    companion object {
        fun get(context: Context): Settings = Settings(
            context.applicationContext.getSharedPreferences("pokebuddy_settings", Context.MODE_PRIVATE)
        )
    }
}
