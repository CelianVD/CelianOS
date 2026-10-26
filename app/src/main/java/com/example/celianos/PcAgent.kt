package com.example.celianos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

// ============================================================
//  Client de l'agent PC (voir pc-agent/README.md) :
//  vrai volume du PC, niveaux audio, musique du PC, lancement d'applis.
// ============================================================

data class PcApp(val id: String, val name: String, val iconVer: Long = 0L)

data class PcMedia(
    val title: String, val artist: String, val playing: Boolean, val paused: Boolean,
    val positionMs: Long, val durationMs: Long, val artId: Int, val receivedAt: Long
)

object PcAgent {
    var connected by mutableStateOf(false)
    var pcVolume by mutableFloatStateOf(0f)        // 0..100
    var pcMuted by mutableStateOf(false)
    var pcName by mutableStateOf("")
    var media by mutableStateOf<PcMedia?>(null)
    var mediaArt by mutableStateOf<Bitmap?>(null)
    var mediaGrid by mutableStateOf<IntArray?>(null)
    var apps by mutableStateOf<List<PcApp>>(emptyList())
    var appIcons by mutableStateOf<Map<String, Bitmap>>(emptyMap())   // icônes envoyées par le PC
    private val iconVersions = HashMap<String, Long>()
    private var lastAppsRefresh = 0L
    private var wasConnected = false
    var lastError by mutableStateOf<String?>(null)

    @Volatile var bass = 0f
    @Volatile var mid = 0f
    @Volatile var treble = 0f
    @Volatile private var lastLevelsAt = 0L

    val levelsLive: Boolean get() = SystemClock.elapsedRealtime() - lastLevelsAt < 1000L

    @Volatile private var running = false
    @Volatile private var lastVolumeSent = 0L
    private var lastArtId = -1

    val configured: Boolean
        get() = DeskDockState.pcEnabled && DeskDockState.pcIp.isNotBlank() && DeskDockState.pcToken.isNotBlank()

    private fun open(path: String, method: String = "GET", body: String? = null, timeout: Int = 2000): HttpURLConnection {
        val c = URL("http://${DeskDockState.pcIp.trim()}:${DeskDockState.pcPort}$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = timeout
        c.readTimeout = timeout
        c.setRequestProperty("X-Token", DeskDockState.pcToken.trim())
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        return c
    }

    private fun getText(path: String): String {
        val c = open(path)
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }

    private fun post(path: String, json: String) {
        thread(isDaemon = true) {
            runCatching { open(path, "POST", json).also { it.responseCode; it.disconnect() } }
        }
    }

    /** Démarre (une seule fois) la surveillance de l'agent. Sans effet si non configuré. */
    @Synchronized
    fun ensureRunning() {
        if (running) return
        running = true
        thread(isDaemon = true, name = "pc-agent-status") { statusLoop() }
        thread(isDaemon = true, name = "pc-agent-levels") { levelsLoop() }
    }

    private fun statusLoop() {
        while (true) {
            if (!configured) {
                connected = false; media = null
                Thread.sleep(2000); continue
            }
            try {
                val j = JSONObject(getText("/status"))
                connected = true
                lastError = null
                pcName = j.optString("name")
                if (SystemClock.elapsedRealtime() - lastVolumeSent > 1500) {
                    pcVolume = j.optInt("volume").toFloat()
                    pcMuted = j.optBoolean("muted")
                }
                val m = j.optJSONObject("media")
                media = m?.let {
                    PcMedia(
                        it.optString("title"), it.optString("artist"),
                        it.optBoolean("playing"), it.optBoolean("paused"),
                        it.optLong("position_ms"), it.optLong("duration_ms"),
                        it.optInt("art_id"), SystemClock.elapsedRealtime()
                    )
                }?.takeIf { it.title.isNotBlank() }
                updateArt(media?.artId ?: 0)
                // Liste d'applis + icônes : à la connexion, puis toutes les 30 s (modifs faites sur le PC)
                val t = SystemClock.elapsedRealtime()
                if (!wasConnected || t - lastAppsRefresh > 30_000L) { lastAppsRefresh = t; refreshApps() }
                wasConnected = true
                PcLink.evaluate(appContext(), allowLaunch = true)
            } catch (e: Exception) {
                connected = false
                wasConnected = false
                media = null
                lastError = e.message ?: e.javaClass.simpleName
                PcLink.evaluate(appContext(), allowLaunch = false)
            }
            Thread.sleep(if (DeskDockState.dockOpen) 1000 else 3000)
        }
    }

    private fun updateArt(artId: Int) {
        if (artId == lastArtId) return
        lastArtId = artId
        if (artId == 0) { mediaArt = null; mediaGrid = null; return }
        runCatching {
            val c = open("/art", timeout = 4000)
            val bytes = c.inputStream.use { it.readBytes() }
            c.disconnect()
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            mediaArt = bmp
            mediaGrid = bmp?.let { bitmapToGrid(it, ART_GRID_SIZE) }
        }.onFailure { mediaArt = null; mediaGrid = null }
    }

    private fun levelsLoop() {
        // Courtes requêtes répétées (~25 Hz) : plus fiable qu'un flux continu à travers le Wi-Fi
        var failures = 0
        while (true) {
            if (!connected || !DeskDockState.dockOpen || !DeskDockState.pcAudio) {
                Thread.sleep(400); continue
            }
            try {
                val p = getText("/levels1").trim().split(' ')
                if (p.size == 3) {
                    bass = p[0].toFloat(); mid = p[1].toFloat(); treble = p[2].toFloat()
                    lastLevelsAt = SystemClock.elapsedRealtime()
                    failures = 0
                }
                Thread.sleep(35)
            } catch (_: Exception) {
                if (++failures > 3) Thread.sleep(1000) else Thread.sleep(100)
            }
        }
    }

    // --- Commandes ---
    fun setVolume(level: Float) {
        pcVolume = level.coerceIn(0f, 100f)
        val now = SystemClock.elapsedRealtime()
        lastVolumeSent = now
        post("/volume", """{"level":${pcVolume.toInt()}}""")
    }

    fun nudgeVolume(delta: Int) = setVolume(pcVolume + delta)

    fun toggleMute() {
        pcMuted = !pcMuted
        lastVolumeSent = SystemClock.elapsedRealtime()
        post("/volume", """{"mute":$pcMuted}""")
    }

    fun mediaCommand(action: String, ms: Long = 0) = post("/media", """{"action":"$action","ms":$ms}""")

    fun launchApp(id: String) = post("/launch", """{"id":"$id"}""")

    fun refreshApps(onDone: () -> Unit = {}) {
        thread(isDaemon = true) {
            runCatching {
                val arr = JSONObject(getText("/apps")).getJSONArray("apps")
                val list = (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    PcApp(o.getString("id"), o.getString("name"), o.optLong("icon"))
                }
                val icons = appIcons.toMutableMap()
                icons.keys.retainAll(list.map { it.id }.toSet())
                for (app in list) {
                    if (app.iconVer == 0L) { icons.remove(app.id); continue }
                    if (iconVersions[app.id] == app.iconVer && icons.containsKey(app.id)) continue
                    runCatching {
                        val c = open("/icon?id=${app.id}", timeout = 4000)
                        val bytes = c.inputStream.use { it.readBytes() }
                        c.disconnect()
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let {
                            icons[app.id] = it; iconVersions[app.id] = app.iconVer
                        }
                    }
                }
                appIcons = icons
                apps = list
            }
            onDone()
        }
    }

    private fun appContext(): android.content.Context = CelianApp.instance
}

/** Source de média utilisée par le dock : musique du PC si disponible, sinon celle du téléphone. */
object DockMedia {
    private val remote: PcMedia?
        get() = PcAgent.media?.takeIf {
            PcAgent.connected && DeskDockState.pcMedia && (it.playing || MediaState.title == null)
        }

    val isRemote get() = remote != null
    val title get() = remote?.title ?: MediaState.title
    val artist get() = remote?.artist ?: MediaState.artist
    val isPlaying get() = remote?.playing ?: MediaState.isPlaying
    val artBitmap get() = if (remote != null) PcAgent.mediaArt else MediaState.artBitmap
    val artGrid get() = if (remote != null) PcAgent.mediaGrid else MediaState.artGrid
    val durationMs get() = remote?.durationMs ?: MediaState.durationMs
    val positionMs get() = remote?.positionMs ?: MediaState.positionMs
    val positionTimeMs get() = remote?.receivedAt ?: MediaState.positionTimeMs
    val speed get() = if (remote != null) 1f else MediaState.speed

    fun togglePlayPause() { if (remote != null) PcAgent.mediaCommand("play_pause") else MediaState.togglePlayPause() }
    fun skipNext() { if (remote != null) PcAgent.mediaCommand("next") else MediaState.skipNext() }
    fun skipPrevious() { if (remote != null) PcAgent.mediaCommand("prev") else MediaState.skipPrevious() }
    fun seekTo(ms: Long) { if (remote != null) PcAgent.mediaCommand("seek", ms) else MediaState.seekTo(ms) }
}