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
import com.pokebuddy.db.BackupCodec
import com.pokebuddy.db.PokeDatabase
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

    private val exportDbTo = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { writeDbBackup(it) } }

    private val importDbFrom = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { readDbBackup(it) } }

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

        addView(section("Settings backup", "The overlay and scan preferences above. Shareable " +
            "— it carries no personal data."))
        addView(button("Export settings…") { exportTo.launch(backupName("settings")) })
        // Not "application/json": pickers hide files whose MIME the provider reports
        // differently (often octet-stream for a .json on external storage), and a backup you
        // can't see in the picker is a backup you don't have.
        addView(button("Import settings…") { importFrom.launch(arrayOf("*/*")) })

        addView(section("Index backup", "Your whole Pokémon index. This one holds catch " +
            "locations and dates, so keep the file to yourself. Importing REPLACES the " +
            "current index."))
        addView(button("Export index…") { exportDbTo.launch(backupName("index")) })
        addView(button("Import index…") { importDbFrom.launch(arrayOf("*/*")) })

        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    /** Dated so successive backups don't silently overwrite one another in the picker. */
    private fun backupName(kind: String): String =
        "pokebuddy-$kind-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".json"

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

    /**
     * The index backups run their DB and file work on a worker thread — Room refuses main-
     * thread queries, and a full-index read/write shouldn't block the UI regardless. Results
     * are toasted back on the main thread.
     */
    private fun writeDbBackup(uri: Uri) = onWorker("db export") {
        val json = BackupCodec.export(PokeDatabase.get(this).snapshot())
        contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            ?: error("could not open $uri for writing")
        "Index exported"
    }

    private fun readDbBackup(uri: Uri) = onWorker("db import") {
        val text = contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: error("could not open $uri for reading")
        PokeDatabase.get(this).restore(BackupCodec.import(text))
        "Index imported — replaced the previous one"
    }

    /** Runs [work] off the main thread; its return string is toasted on success, and any
     *  failure is logged and shown, with the version mismatch spelled out. */
    private fun onWorker(tag: String, work: () -> String) {
        Thread {
            val message = runCatching(work).fold(
                onSuccess = { it },
                onFailure = { e ->
                    Log.w("PokeBuddySettings", "$tag failed", e)
                    when (e) {
                        is BackupCodec.IncompatibleBackup ->
                            "That index backup is schema version ${e.foundVersion}; this " +
                                "build reads ${BackupCodec.SCHEMA_VERSION}. Not imported."
                        is BackupCodec.MalformedBackup -> "That file isn't a PokeBuddy index backup."
                        else -> "$tag failed: ${e.message}"
                    }
                },
            )
            runOnUiThread { toast(message) }
        }.start()
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
