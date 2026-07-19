package com.pokebuddy.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pokebuddy.automation.AutoAppraiser
import com.pokebuddy.automation.GestureService
import com.pokebuddy.db.MegaEnergy
import com.pokebuddy.db.OwnedPokemon
import com.pokebuddy.db.PokeDatabase
import com.pokebuddy.db.FamilyResource
import com.pokebuddy.iv.BaseStats
import com.pokebuddy.iv.CpResolver
import com.pokebuddy.iv.DecodeResult
import com.pokebuddy.iv.Iv
import com.pokebuddy.iv.IvDecoder
import com.pokebuddy.iv.MoveTable
import com.pokebuddy.iv.SpeciesTable
import com.pokebuddy.ocr.AppraisalReader
import com.pokebuddy.ocr.DetailParser
import com.pokebuddy.ocr.EncounterParser
import com.pokebuddy.ocr.GridParser
import com.pokebuddy.ocr.OcrEngine
import com.pokebuddy.ocr.OcrResult
import com.pokebuddy.ocr.asPixelSource
import com.pokebuddy.overlay.OverlayController
import java.io.File
import java.io.FileOutputStream

/**
 * Foreground service that owns the MediaProjection session for the FLAG_SECURE spike.
 *
 * Android version notes baked in here:
 *  - Android 10+ (this app's minSdk 29): MediaProjection must be driven from a foreground service.
 *  - Android 14 (API 34): the service must already be in the foreground with type mediaProjection
 *    BEFORE getMediaProjection() is called, and a MediaProjection.Callback must be registered
 *    before creating the VirtualDisplay, or the framework throws.
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "PokeBuddyCapture"
        private const val CHANNEL_ID = "capture_spike"
        private const val NOTIF_ID = 1001

        // A burst rides out the animating sprite that occludes the detail-screen CP.
        private const val BURST_FRAMES = 6
        private const val BURST_GAP_MS = 300L

        const val ACTION_START = "com.pokebuddy.capture.START"
        const val ACTION_CAPTURE = "com.pokebuddy.capture.CAPTURE"
        const val ACTION_STOP = "com.pokebuddy.capture.STOP"
        const val ACTION_RESULT = "com.pokebuddy.capture.RESULT"

        // Exported trigger so a capture can be fired (e.g. via `adb shell am broadcast`)
        // WITHOUT touching the screen — essential for capturing another foreground app,
        // since opening the notification shade would cover it.
        const val ACTION_CAPTURE_NOW = "com.pokebuddy.CAPTURE_NOW"

        // Opt-in: taps through the appraisal, then reads it. Requires GestureService.
        const val ACTION_AUTO_APPRAISE = "com.pokebuddy.AUTO_APPRAISE"

        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_SUMMARY = "summary"
        const val EXTRA_SAVED_PATH = "savedPath"
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var width = 0
    private var height = 0
    private var density = 0

    private lateinit var bgThread: HandlerThread
    private lateinit var bgHandler: Handler

    // The appraisal screen is reached FROM the detail screen and may not repeat CP/HP,
    // so the last confident detail scan is kept to supply them.
    private var lastDetail: DetailScan? = null
    private var lastDetailRowId: Long? = null

    private val ocr = OcrEngine()
    private val overlay by lazy { OverlayController(this) }
    private val db by lazy { PokeDatabase.get(this) }

    private val captureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_AUTO_APPRAISE -> {
                    Log.i(TAG, "Auto-appraise triggered via broadcast")
                    bgHandler.post { handleAutoAppraise() }
                }
                else -> {
                    Log.i(TAG, "Capture triggered via broadcast")
                    bgHandler.post { handleCapture() }
                }
            }
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped by system/user")
            teardown()
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Load the full base-stat table from the bundled asset (offline).
        runCatching {
            SpeciesTable.load(assets.open("base_stats.csv").bufferedReader().use { it.readText() })
            MoveTable.load(assets.open("moves.csv").bufferedReader().use { it.readText() })
            Log.i(TAG, "Loaded ${SpeciesTable.size} species, ${MoveTable.size} moves")
        }.onFailure { Log.e(TAG, "Failed to load game data assets", it) }
        bgThread = HandlerThread("capture").apply { start() }
        bgHandler = Handler(bgThread.looper)
        // RECEIVER_EXPORTED so `adb shell am broadcast` (a different uid) can reach it.
        ContextCompat.registerReceiver(
            this, captureReceiver,
            IntentFilter(ACTION_CAPTURE_NOW).apply { addAction(ACTION_AUTO_APPRAISE) },
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_CAPTURE -> bgHandler.post { handleCapture() }
            ACTION_AUTO_APPRAISE -> bgHandler.post { handleAutoAppraise() }
            ACTION_STOP -> { teardown(); stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        // Go foreground FIRST (Android 14 requires this before getMediaProjection).
        startForegroundCompat("Capture armed — open Pokémon GO, then tap CAPTURE NOW")

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
        if (data == null) {
            Log.e(TAG, "No projection data in START intent")
            stopSelf()
            return
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(resultCode, data).also {
            it.registerCallback(projectionCallback, bgHandler)   // required on Android 14
        }

        computeScreenSize()
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay(
            "pokebuddy-capture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, bgHandler
        )
        Log.i(TAG, "VirtualDisplay up: ${width}x$height @ $density dpi")
    }

    /**
     * Taps through the appraisal flow, then reads it with the normal capture path.
     *
     * Refuses to inject input unless Pokémon GO is actually in front — a stray tap into
     * whatever else happened to be foregrounded is both useless and rude.
     */
    private fun handleAutoAppraise() {
        if (imageReader == null) {
            broadcast("Capture not armed — tap Start Capture first.", null)
            return
        }
        if (!GestureService.isConnected) {
            val msg = "Auto-appraise needs the accessibility service:\n" +
                "Settings → Accessibility → PokeBuddy auto-appraise"
            overlay.show(msg)
            broadcast(msg, null)
            return
        }
        if (!GestureService.isPogoForeground) {
            broadcast("Auto-appraise skipped — Pokémon GO isn't in front.", null)
            return
        }

        val appraiser = AutoAppraiser(
            tap = { x, y -> GestureService.tap(x, y) },
            probe = { probeOcr() },
            sleep = { Thread.sleep(it) },
        )
        val outcome = appraiser.run()
        Log.i(TAG, "Auto-appraise outcome: $outcome")
        when (outcome) {
            AutoAppraiser.Outcome.REACHED_APPRAISAL, AutoAppraiser.Outcome.ALREADY_THERE ->
                handleCapture()
            else -> {
                val msg = "Auto-appraise didn't reach the bars ($outcome)"
                overlay.show(msg)
                broadcast(msg, null)
            }
        }
    }

    /** One frame, OCR'd — the automation's eyes between taps. Cheaper than a full burst
     *  and deliberately overlay-blanked so we never read our own panel as game UI. */
    private fun probeOcr(): OcrResult? {
        val reader = imageReader ?: return null
        overlay.hideForCapture()
        Thread.sleep(200)
        val frame = grabBitmap(reader) ?: return null
        return try {
            ocr.recognizeBlocking(frame)
        } finally {
            frame.recycle()
        }
    }

    private fun handleCapture() {
        val reader = imageReader
        if (reader == null) {
            broadcast("Capture not armed — tap Start Capture first.", null)
            return
        }
        // Blank our own overlay so we don't OCR it, and let a clean frame render.
        overlay.hideForCapture()
        Thread.sleep(250)

        // Burst several frames: the detail-screen sprite animates, so CP is visible in
        // only some frames. We OCR each and resolve the CP across the whole burst.
        val frames = ArrayList<OcrResult>()
        val barReads = ArrayList<Triple<Int, Int, Int>>()
        var savedPath: String? = null
        var verdictSummary = ""
        repeat(BURST_FRAMES) { i ->
            val frame = grabBitmap(reader) ?: return@repeat
            if (i == 0) {
                verdictSummary = FrameAnalysis.analyze(frame).summary()
                savedPath = savePng(frame)
            }
            val res = ocr.recognizeBlocking(frame)
            frames.add(res)
            // Appraisal bars are pixels, not text, so they must be measured while we still
            // hold the frame — after this the bitmap is recycled.
            if (AppraisalReader.isAppraisalScreen(res)) {
                AppraisalReader.measure(frame.asPixelSource(), res)?.let { barReads.add(it) }
            }
            if (i == 0) savedPath?.let {
                File(it.removeSuffix(".png") + ".ocr.json").writeText(res.toJson(File(it).name))
            }
            frame.recycle()
            if (i < BURST_FRAMES - 1) Thread.sleep(BURST_GAP_MS)
        }
        if (frames.isEmpty()) {
            broadcast("No frame available yet — try CAPTURE NOW again.", null)
            return
        }

        val bars = resolveBars(barReads)
        // Persist BEFORE building the panel: the panel reports this Pokémon's rank among
        // its species, which has to count the Pokémon we're looking at.
        runCatching { persistScan(frames, bars) }.onFailure { Log.e(TAG, "persist failed", it) }
        val panel = buildResultPanel(frames, bars)
        overlay.show(panel)
        val summary = verdictSummary +
            "\nSaved: $savedPath\nframes: ${frames.size}\n--- panel ---\n$panel"
        Log.i(TAG, summary)
        updateNotification("READABLE ✅ · burst ${frames.size}")
        broadcast(summary, savedPath)
    }

    /** Converts the latest mirrored frame to a cropped Bitmap (null if none ready). */
    private fun grabBitmap(reader: ImageReader): Bitmap? {
        val image = reader.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * width
            val padded = Bitmap.createBitmap(
                width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
            )
            padded.copyPixelsFromBuffer(plane.buffer)
            val frame = Bitmap.createBitmap(padded, 0, 0, width, height)
            padded.recycle()
            frame
        } catch (t: Throwable) {
            Log.e(TAG, "grabBitmap failed", t); null
        } finally {
            image.close()
        }
    }

    private data class DetailScan(
        val name: String?, val cp: Int?, val hpCur: Int?, val hpMax: Int?,
        val base: BaseStats?, val decode: DecodeResult?,
    )

    /** Aggregates a burst of detail-screen frames: mode-vote name/HP, resolve CP across
     *  frames with the feasibility guard, decode IV. Null if it isn't a detail screen. */
    private fun aggregateDetail(frames: List<OcrResult>): DetailScan? {
        val details = frames.map { DetailParser.parse(it) }
        if (details.none { it.name != null && it.hpMax != null }) return null
        val named = details.filter { it.name != null && it.hpMax != null }
        val name = mode(named.mapNotNull { it.name })
        val hpMax = mode(named.mapNotNull { it.hpMax })
        val hpCur = mode(named.mapNotNull { it.hpCurrent })
        // Resolve with the TYPES from the same screen: the detail screen can show a bare
        // "Raichu" for an Alolan Raichu, and plain Raichu's stats would give a confidently
        // wrong IV. Null here means the forms were indistinguishable, which the panel
        // reports rather than papering over.
        val types = mode(details.map { it.types }.filter { it.isNotEmpty() }).orEmpty()
        val resolved = name?.let { SpeciesTable.resolve(it, types) }
        val base = resolved?.stats
        val cp = CpResolver.resolve(details.mapNotNull { it.cp }, base, hpMax)
        val decode = if (base != null && cp != null && hpMax != null)
            IvDecoder.decode(base, cp, hpMax) else null
        // Store the RESOLVED name ("Raichu (Alolan)"), so a form is indexed and ranked as
        // its own species rather than pooled with the base form.
        return DetailScan(resolved?.name ?: name, cp, hpCur, hpMax, base, decode)
    }

    /** Mode-votes each bar across the burst. The bars don't animate, so agreement here is
     *  just noise rejection. Null if this wasn't a readable appraisal screen. */
    private fun resolveBars(reads: List<Triple<Int, Int, Int>>): Triple<Int, Int, Int>? {
        if (reads.isEmpty()) return null
        val a = mode(reads.map { it.first }) ?: return null
        val d = mode(reads.map { it.second }) ?: return null
        val s = mode(reads.map { it.third }) ?: return null
        return Triple(a, d, s)
    }

    /**
     * Decodes with the appraisal bars folded in when we have them, else CP+HP alone.
     * Null when the scan lacks the species/CP/HP the decoder needs.
     */
    private fun decodeFor(d: DetailScan, bars: Triple<Int, Int, Int>?): DecodeResult? {
        if (d.base == null || d.cp == null || d.hpMax == null) return null
        val iv = bars?.let { Iv(it.first, it.second, it.third) } ?: return d.decode
        return IvDecoder.decodeCorroborated(d.base, d.cp, d.hpMax, iv)
    }

    /**
     * Renders an IV verdict PERCENT-FIRST, because the percentage is what's actually being
     * judged and it resolves far more often than the split does — % depends only on the sum
     * of the three stats, so candidates that disagree about attack-vs-defense can still
     * agree on the answer that matters.
     *
     * Never claims more precision than we have: an unresolved percentage is shown as the
     * honest span, not a midpoint.
     */
    private fun ivSummary(r: DecodeResult): String = when {
        r.isEmpty -> "IV: no match — recheck CP/HP"
        r.isExact -> "IV ${r.exactPercent}%  (${fmt(r.exactIv!!)})"
        r.isPercentExact -> "IV ${r.exactPercent}%  (split unresolved)"
        else -> {
            val range = r.percentRange!!
            "IV ${range.first}–${range.last}%  (${r.distinctIvs.size} candidates)"
        }
    }

    /** "· 3rd of 5 Pikachu" — rank by IV% within the species, blank when unknown. */
    private fun rankLine(species: String?, percent: Int?): String {
        if (species == null || percent == null) return ""
        val dao = db.ownedDao()
        val total = dao.countOfSpeciesWithIv(species)
        if (total <= 1) return ""
        return "\n· ${ordinal(dao.ivRankInSpecies(species, percent))} of $total $species by IV"
    }

    private fun ordinal(n: Int): String {
        val suffix = if (n % 100 in 11..13) "th" else when (n % 10) {
            1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th"
        }
        return "$n$suffix"
    }

    /**
     * In-game the appraisal opens as a card OVER the detail screen, so CP/HP are usually
     * still on screen — prefer this frame's own read, and fall back to the last detail scan
     * for layouts (or occlusions) where they aren't.
     */
    private fun appraisalPanel(bars: Triple<Int, Int, Int>, detail: DetailScan?): String {
        val (a, d, s) = bars
        val head = "★ ${detail?.name ?: "?"} — appraisal\nbars $a/$d/$s"
        val r = detail?.let { decodeFor(it, bars) }
            ?: return "$head\nScan the detail screen first (need CP + HP)"
        return "$head\nCP ${detail.cp}   HP ${detail.hpMax}\n${ivSummary(r)}" +
            rankLine(detail.name, r.exactPercent)
    }

    /**
     * Wild encounter: the catch/skip signal. Only CP is readable pre-catch — no HP and no
     * appraisal — so we compare CP against the best of that species we already own and say
     * plainly that the wild IV is unknown rather than implying a comparison we can't make.
     */
    private fun encounterPanel(frames: List<OcrResult>): String? {
        val e = frames.firstNotNullOfOrNull { f ->
            EncounterParser.parse(f) { SpeciesTable[it] != null }
        } ?: return null
        val dao = db.ownedDao()
        val owned = dao.countOfSpecies(e.species)
        if (owned == 0) return "★ ${e.species} (wild)\nCP ${e.cp}\nNEW — none owned yet"

        val bestCp = dao.bestCp(e.species)
        val bestIv = dao.bestIvPercent(e.species)
        val verdict = when {
            bestCp == null -> ""
            e.cp > bestCp -> "\n✅ Beats your best CP ($bestCp)"
            else -> "\n· below your best CP ($bestCp)"
        }
        val best = "Best owned: CP ${bestCp ?: "?"}" + (bestIv?.let { " · IV $it%" } ?: "")
        return "★ ${e.species} (wild)\nCP ${e.cp}\n$best  ($owned owned)$verdict\nWild IV unknown until caught"
    }

    private fun buildResultPanel(frames: List<OcrResult>, bars: Triple<Int, Int, Int>?): String {
        if (bars != null) return appraisalPanel(bars, aggregateDetail(frames) ?: lastDetail)
        aggregateDetail(frames)?.let { s ->
            // A plain detail screen can't pin an IV on its own, but we may have appraised
            // this exact Pokémon before. Prefer what the index already knows over
            // re-reporting it as unknown — otherwise a Pokémon appraised minutes ago reads
            // as "42 candidates" the next time it's scanned.
            val stored = if (s.name != null && s.cp != null)
                db.ownedDao().findMatch(s.name, s.cp, s.hpMax) else null
            val storedPercent = stored?.ivPercent.takeIf { s.decode?.isPercentExact != true }

            // Distinguish "we don't have this species" from "we can't tell which form" —
            // the second is a read we could still complete, so saying it plainly tells you
            // the type row was missed rather than implying the Pokédex is incomplete.
            val forms = s.name?.let { SpeciesTable.formsFor(it) }.orEmpty()
            val ivText = when {
                s.base == null && forms.size > 1 ->
                    "Which form? ${forms.joinToString(" / ") { it.name }}\n(type row unreadable)"
                s.base == null -> "IV: '${s.name ?: "?"}' not in base-stat table"
                s.cp == null -> "IV: waiting for a clean CP read"
                storedPercent != null -> {
                    val split = stored?.ivAtk?.let { " (${it}/${stored.ivDef}/${stored.ivSta})" } ?: ""
                    "IV $storedPercent%$split  · from earlier appraisal"
                }
                else -> ivSummary(s.decode!!) +
                    if (s.decode.isPercentExact) "" else " — appraise to narrow"
            }
            val rank = when {
                s.base == null || s.cp == null -> ""
                storedPercent != null -> rankLine(s.name, storedPercent)
                else -> rankLine(s.name, s.decode?.exactPercent)
            }
            return "★ ${s.name ?: "?"}\nCP ${s.cp ?: "?"}   HP ${s.hpCur ?: "?"}/${s.hpMax ?: "?"}\n$ivText$rank"
        }
        encounterPanel(frames)?.let { return it }
        val tiles = GridParser.parse(frames.first().lines)
        if (tiles.isNotEmpty()) {
            val top = tiles.first()
            return "Box scan: ${tiles.size} shown\nTop: ${top.name} CP ${top.cp}"
        }
        return "No Pokémon screen detected"
    }

    /** Persist a confident detail scan into the local index, or refine the row a previous
     *  detail scan created once an appraisal pins its IV. */
    private fun persistScan(frames: List<OcrResult>, bars: Triple<Int, Int, Int>?) {
        val s = aggregateDetail(frames)
        // Independent of whether the Pokémon itself is indexable — the resource panel is
        // readable even on frames where CP is occluded.
        runCatching { persistResources(frames) }.onFailure { Log.e(TAG, "resources failed", it) }
        // Bars with no readable detail in this frame: the appraisal card is covering a
        // Pokémon we already indexed, so narrow that row instead of adding another.
        if (s?.name == null || s.cp == null) {
            if (bars != null) refineFromAppraisal(bars)
            return
        }
        val decode = decodeFor(s, bars) ?: s.decode
        val exact = decode?.exactIv
        // The percentage can be pinned while the split isn't (several triples sharing one
        // total), and % is what ranking uses — so store it whenever it's known, not only
        // when the full triple resolves.
        val percent = decode?.exactPercent
        val candidates = decode?.distinctIvs?.size ?: 0
        val dao = db.ownedDao()

        val existing = dao.findMatch(s.name, s.cp, s.hpMax)
        val id: Long
        val action: String
        if (existing == null) {
            id = dao.insert(
                OwnedPokemon(
                    species = s.name, cp = s.cp, hpMax = s.hpMax,
                    ivAtk = exact?.attack, ivDef = exact?.defense, ivSta = exact?.stamina,
                    ivPercent = percent, ivCandidates = candidates,
                )
            )
            action = "indexed"
        } else {
            id = existing.id
            // Only ever write back a read that knows MORE than the stored one: a later
            // scan without the appraisal open must not erase an exact IV we already have.
            if (exact != null || percent != null || existing.ivAtk == null) {
                dao.updateIv(id, exact?.attack, exact?.defense, exact?.stamina,
                    percent, candidates)
            }
            action = "updated"
        }
        lastDetail = s
        lastDetailRowId = id
        Log.i(TAG, "$action #$id ${s.name} CP${s.cp} iv=${exact?.let { fmt(it) } ?: "?"}; " +
            "index total=${dao.count()}")
    }

    /** Writes back an exact IV that only the appraisal could resolve. */
    private fun refineFromAppraisal(bars: Triple<Int, Int, Int>) {
        val last = lastDetail ?: return
        val rowId = lastDetailRowId ?: return
        val iv = decodeFor(last, bars)?.exactIv ?: return  // still ambiguous — leave it alone
        db.ownedDao().updateIv(rowId, iv.attack, iv.defense, iv.stamina, iv.percent, 1)
        Log.i(TAG, "refined #$rowId ${last.name} -> ${fmt(iv)} (${iv.percent}%) via appraisal")
    }

    /**
     * Upserts the per-species resources visible on a detail screen.
     *
     * Both are keyed by the species named in their OWN label, not the Pokémon on screen:
     * candy is shared across an evolution family, and mega energy belongs to the mega
     * (a Pikachu's screen reports Raichu energy).
     */
    private fun persistResources(frames: List<OcrResult>) {
        val infos = frames.map { DetailParser.parse(it) }
        val candySpecies = mode(infos.mapNotNull { it.candySpecies })
        val candy = mode(infos.mapNotNull { it.candy })
        // The label names the family's base species ("PIKACHU CANDY"); candy itself is
        // shared across the whole family, so store it under the family id.
        val family = candySpecies?.let { SpeciesTable.species(it)?.family }
        if (family != null && candy != null) {
            val existing = db.familyDao().get(family)
            db.familyDao().upsert(
                FamilyResource(
                    family = family,
                    candy = candy,
                    // Keep a previously-read XL count rather than zeroing it: the detail
                    // screen only shows XL candy once the family has any.
                    candyXl = mode(infos.mapNotNull { it.candyXl }) ?: existing?.candyXl ?: 0,
                )
            )
        }
        // One row per variant seen; a species with no mega simply writes nothing.
        val megas = mode(infos.map { it.megaEnergy }.filter { it.isNotEmpty() }) ?: return
        megas.forEach { db.megaEnergyDao().upsert(MegaEnergy(it.species, it.variant, it.amount)) }
        Log.i(TAG, "resources: $family candy=$candy; mega=" +
            megas.joinToString { "${it.species}${it.variant} ${it.amount}" })
    }

    private fun <T> mode(list: List<T>): T? =
        list.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    private fun fmt(iv: Iv) = "${iv.attack}/${iv.defense}/${iv.stamina}"

    private fun savePng(bmp: Bitmap): String {
        val dir = getExternalFilesDir(null) ?: filesDir
        val file = File(dir, "capture_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file.absolutePath
    }

    private fun computeScreenSize() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        density = resources.configuration.densityDpi
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            width = bounds.width()
            height = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val dm = android.util.DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
            width = dm.widthPixels
            height = dm.heightPixels
        }
    }

    private fun broadcast(summary: String, savedPath: String?) {
        val i = Intent(ACTION_RESULT).apply {
            setPackage(packageName)
            putExtra(EXTRA_SUMMARY, summary)
            putExtra(EXTRA_SAVED_PATH, savedPath)
        }
        sendBroadcast(i)
    }

    // --- Foreground notification plumbing ---

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL_ID, "Capture spike", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val captureIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_CAPTURE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 2,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PokeBuddy capture spike")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .addAction(0, "Capture now", captureIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun startForegroundCompat(text: String) {
        val notif = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun teardown() {
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        projection?.unregisterCallback(projectionCallback)
        projection?.stop(); projection = null
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(captureReceiver) }
        teardown()
        ocr.close()
        overlay.destroy()
        if (::bgThread.isInitialized) bgThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
