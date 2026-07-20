package com.pokebuddy.settings

import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings screen, built at runtime from [SettingsSpec] rather than from a layout file.
 *
 * A layout would duplicate the spec — one entry per setting in XML, another in the read/
 * write code, a third in the exporter — and those copies drift. Generating the rows means a
 * new setting is a single entry in the spec and appears here automatically.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: Settings

    /**
     * Backup goes through the Storage Access Framework rather than a path of our own.
     *
     * That means the user picks the destination in the system picker, the file lands
     * wherever they chose, and the app needs no storage permission at all. It also keeps the
     * export explicitly user-initiated, which is the rule for anything leaving this app.
     */
    private val exportTo = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { writeBackup(it) } }

    private val importFrom = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { readBackup(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings.get(this)
        title = "PokeBuddy settings"

        val pad = dp(20)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        SettingsSpec.visible.forEach { column.addView(rowFor(it)) }
        column.addView(backupSection())
        column.addView(resetButton())
        setContentView(ScrollView(this).apply { addView(column) })
    }

    private fun backupSection() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(section("Backup", "Settings only — never the Pokémon index, which holds " +
            "catch locations and dates. That export is separate."))
        addView(Button(this@SettingsActivity).apply {
            text = "Export settings…"
            setOnClickListener { exportTo.launch(defaultBackupName()) }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        })
        addView(Button(this@SettingsActivity).apply {
            text = "Import settings…"
            // Not "application/json": pickers hide files whose MIME the provider reports
            // differently (often octet-stream for a .json on external storage), and a
            // backup you can't see in the picker is a backup you don't have.
            setOnClickListener { importFrom.launch(arrayOf("*/*")) }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        })
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    /** Dated so successive backups don't silently overwrite one another in the picker. */
    private fun defaultBackupName(): String =
        "pokebuddy-settings-" +
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".json"

    private fun writeBackup(uri: Uri) {
        runCatching {
            contentResolver.openOutputStream(uri)?.use {
                it.write(SettingsCodec.export(settings.snapshot()).toByteArray())
            } ?: error("could not open $uri for writing")
        }.onSuccess {
            toast("Settings exported")
        }.onFailure {
            Log.w("PokeBuddySettings", "export failed", it)
            toast("Export failed: ${it.message}")
        }
    }

    /**
     * Reads and applies a backup. A version mismatch is reported as such rather than being
     * applied partially — see [SettingsCodec].
     */
    private fun readBackup(uri: Uri) {
        runCatching {
            val text = contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                ?: error("could not open $uri for reading")
            SettingsCodec.import(text)
        }.onSuccess {
            settings.restore(it)
            toast("Settings imported")
            // Rebuild so every row shows the restored value rather than the stale one.
            recreate()
        }.onFailure { e ->
            Log.w("PokeBuddySettings", "import failed", e)
            toast(
                when (e) {
                    is SettingsCodec.IncompatibleBackup ->
                        "That backup is version ${e.foundVersion}; this build reads " +
                            "${SettingsSpec.VERSION}. Not imported."
                    else -> "Import failed: ${e.message}"
                }
            )
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun rowFor(s: SettingsSpec.Setting<*>) = when (s) {
        is SettingsSpec.BoolSetting -> boolRow(s)
        is SettingsSpec.IntSetting -> intRow(s)
    }

    private fun boolRow(s: SettingsSpec.BoolSetting) = section(s.label, s.help).apply {
        addView(Switch(this@SettingsActivity).apply {
            isChecked = settings.get(s)
            setOnCheckedChangeListener { _, v -> settings.set(s, v) }
        })
    }

    /**
     * A SeekBar is zero-based, so the stored value is offset by [SettingsSpec.IntSetting.min]
     * on the way in and out. Getting that wrong silently shifts every numeric setting.
     */
    private fun intRow(s: SettingsSpec.IntSetting) = section(s.label, s.help).apply {
        val value = TextView(this@SettingsActivity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            text = display(s, settings.get(s))
        }
        addView(value)
        addView(SeekBar(this@SettingsActivity).apply {
            max = s.max - s.min
            progress = settings.get(s) - s.min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = s.clamp(p + s.min)
                    value.text = display(s, v)
                    if (fromUser) settings.set(s, v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })
        })
    }

    private fun display(s: SettingsSpec.IntSetting, v: Int): String = when {
        // "0s" would read as "hides instantly", which is the opposite of what it does.
        s.key == SettingsSpec.PANEL_DISMISS_SECONDS.key && v == 0 -> "Stay until dismissed"
        else -> "$v${s.unit}"
    }

    private fun section(label: String, help: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(14), 0, dp(14))
        addView(TextView(this@SettingsActivity).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        if (help.isNotEmpty()) addView(TextView(this@SettingsActivity).apply {
            text = help
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            alpha = 0.7f
            ellipsize = TextUtils.TruncateAt.END
        })
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    private fun resetButton() = Button(this).apply {
        text = "Reset to defaults"
        gravity = Gravity.CENTER
        setOnClickListener {
            settings.resetToDefaults()
            Toast.makeText(context, "Settings reset — reopen to see them", Toast.LENGTH_SHORT).show()
            recreate()
        }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            .apply { topMargin = dp(28) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
