package com.example.celianos

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.InputStream
import java.net.URL
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// Résolution de la pochette en matrice de LED (ART_GRID_SIZE x ART_GRID_SIZE)
const val ART_GRID_SIZE = 8

object MediaState {
    var title by mutableStateOf<String?>(null)
    var artist by mutableStateOf<String?>(null)
    var isPlaying by mutableStateOf(false)
    var isPaused by mutableStateOf(false)

    // Couleurs ARGB de la pochette réduite en ART_GRID_SIZE x ART_GRID_SIZE (null si indisponible)
    var artGrid by mutableStateOf<IntArray?>(null)

    // Pochette complète (max 600 px) pour le Glow Dock
    var artBitmap by mutableStateOf<Bitmap?>(null)

    // Progression de la piste (position valable à l'instant positionTimeMs, en elapsedRealtime)
    var durationMs by mutableLongStateOf(0L)
    var positionMs by mutableLongStateOf(0L)
    var positionTimeMs by mutableLongStateOf(0L)
    var speed by mutableFloatStateOf(1f)

    // Session active, utilisée pour envoyer les commandes (play/pause/suivant/précédent)
    var controller: MediaController? = null

    fun togglePlayPause() {
        val controls = controller?.transportControls ?: return
        if (isPlaying) controls.pause() else controls.play()
    }

    fun skipNext() {
        controller?.transportControls?.skipToNext()
    }

    fun skipPrevious() {
        controller?.transportControls?.skipToPrevious()
    }

    fun seekTo(ms: Long) {
        controller?.transportControls?.seekTo(ms)
    }
}

/**
 * Réduit une image en grille n x n : recadrage carré au centre, puis réductions successives
 * par moitiés (équivalent d'une moyenne par bloc, sans crénelage).
 */
fun bitmapToGrid(source: Bitmap, n: Int): IntArray? = runCatching {
    val soft = if (source.config == Bitmap.Config.HARDWARE) {
        source.copy(Bitmap.Config.ARGB_8888, false)
    } else {
        source
    }
    if (soft == null) return@runCatching null

    val side = minOf(soft.width, soft.height)
    var bmp = Bitmap.createBitmap(soft, (soft.width - side) / 2, (soft.height - side) / 2, side, side)
    while (bmp.width > n * 4) {
        bmp = Bitmap.createScaledBitmap(bmp, bmp.width / 2, bmp.height / 2, true)
    }
    val small = Bitmap.createScaledBitmap(bmp, n, n, true)
    IntArray(n * n).also { small.getPixels(it, 0, n, 0, 0, n, n) }
}.getOrNull()

/** Copie logicielle de la pochette, réduite à `maxSide` pixels, pour l'affichage plein format. */
fun bitmapForDisplay(source: Bitmap, maxSide: Int = 1400): Bitmap? = runCatching {
    val soft = if (source.config == Bitmap.Config.HARDWARE) {
        source.copy(Bitmap.Config.ARGB_8888, false)
    } else {
        source
    }
    if (soft == null) return@runCatching null
    val scale = maxSide.toFloat() / maxOf(soft.width, soft.height)
    if (scale >= 1f) soft else Bitmap.createScaledBitmap(
        soft,
        (soft.width * scale).toInt().coerceAtLeast(1),
        (soft.height * scale).toInt().coerceAtLeast(1),
        true
    )
}.getOrNull()

class MediaListenerService : NotificationListenerService() {

    private var sessionManager: MediaSessionManager? = null
    private var controllers: List<MediaController> = emptyList()
    private var lastArtKey: String? = null
    private var hdTriedKey: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
    }

    // --- Liaison avec l'ordinateur (voir PcLink.kt) ---
    private var usbRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != "android.hardware.usb.action.USB_STATE") return
            PcLink.usbLinked = intent.getBooleanExtra("connected", false)
            // L'état déjà présent à l'inscription ne doit pas lancer le dock
            PcLink.evaluate(this@MediaListenerService, allowLaunch = !isInitialStickyBroadcast)
        }
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> bind(list ?: emptyList()) }

    override fun onListenerConnected() {
        val component = ComponentName(this, MediaListenerService::class.java)
        sessionManager = getSystemService(MediaSessionManager::class.java).also {
            it.addOnActiveSessionsChangedListener(sessionsListener, component)
            bind(it.getActiveSessions(component))
        }
        registerUsbReceiver()
        scanWindowsLink(initial = true)
        SpotlightEdge.refresh(this)
    }

    override fun onListenerDisconnected() {
        cleanup()
    }

    // Certaines applis publient la pochette dans la notification après les métadonnées
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null && isLinkNotification(sbn)) scanWindowsLink()
        if (sbn != null &&
            MediaState.artGrid == null &&
            sbn.packageName == MediaState.controller?.packageName
        ) {
            refresh()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null && isLinkNotification(sbn)) scanWindowsLink(excludeKey = sbn.key)
    }

    /** Notification persistante de « Lien avec Windows » (Link to Windows / Phone Link) ? */
    private fun isLinkNotification(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName) return false
        val n = sbn.notification
        val persistent = (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE)) != 0
        if (!persistent) return false
        if (sbn.packageName in PcLink.windowsPackages) return true
        // Autre appli : titre ou texte qui mentionne Windows (ex. variante constructeur)
        val extras = n.extras
        val text = "${extras.getCharSequence(Notification.EXTRA_TITLE) ?: ""} ${extras.getCharSequence(Notification.EXTRA_TEXT) ?: ""}"
        return text.contains("windows", ignoreCase = true)
    }

    private fun scanWindowsLink(initial: Boolean = false, excludeKey: String? = null) {
        val linked = runCatching {
            (activeNotifications ?: emptyArray()).any { it.key != excludeKey && isLinkNotification(it) }
        }.getOrDefault(false)
        PcLink.windowsLinked = linked
        PcLink.evaluate(this, allowLaunch = !initial)
    }

    private fun registerUsbReceiver() {
        if (usbRegistered) return
        val filter = IntentFilter("android.hardware.usb.action.USB_STATE")
        val sticky = if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
        PcLink.usbLinked = sticky?.getBooleanExtra("connected", false) == true
        usbRegistered = true
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        if (usbRegistered) {
            runCatching { unregisterReceiver(usbReceiver) }
            usbRegistered = false
        }
        PcLink.windowsLinked = false
        PcLink.usbLinked = false
        controllers.forEach { it.unregisterCallback(callback) }
        controllers = emptyList()
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        sessionManager = null
        lastArtKey = null
        MediaState.controller = null
        MediaState.title = null
        MediaState.artist = null
        MediaState.artGrid = null
        MediaState.artBitmap = null
        MediaState.durationMs = 0L
        MediaState.positionMs = 0L
        MediaState.isPlaying = false
        MediaState.isPaused = false
    }

    private fun bind(list: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(callback) }
        controllers = list
        controllers.forEach { it.registerCallback(callback) }
        refresh()
    }

    // Lecture en cours, y compris pendant le chargement / changement de piste (évite que la pilule clignote)
    private fun PlaybackState?.isActivelyPlaying(): Boolean = when (this?.state) {
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> true
        else -> false
    }

    private fun refresh() {
        val active = controllers.firstOrNull { it.playbackState.isActivelyPlaying() }
            ?: controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED }
            ?: controllers.firstOrNull()

        val md = active?.metadata
        MediaState.controller = active
        MediaState.title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        MediaState.artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                    ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        MediaState.isPlaying = active?.playbackState.isActivelyPlaying()
        MediaState.isPaused = active?.playbackState?.state == PlaybackState.STATE_PAUSED
        MediaState.durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val ps = active?.playbackState
        MediaState.positionMs = (ps?.position ?: 0L).coerceAtLeast(0L)
        MediaState.positionTimeMs = ps?.lastPositionUpdateTime ?: 0L
        MediaState.speed = ps?.playbackSpeed ?: 1f

        updateArt(active, md)
    }

    /** Recalcule la grille de la pochette seulement quand la piste change (ou si elle manquait). */
    private fun updateArt(active: MediaController?, md: MediaMetadata?) {
        val key = "${MediaState.title}|${MediaState.artist}|${active?.packageName}"
        if (key == lastArtKey && MediaState.artGrid != null) return

        val bitmap = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: active?.packageName?.let { artFromNotification(it) }

        val grid = bitmap?.let { bitmapToGrid(it, ART_GRID_SIZE) }
        // Nouvelle piste sans pochette : on efface l'ancienne pour ne pas afficher la mauvaise
        if (grid != null || key != lastArtKey) {
            MediaState.artGrid = grid
            MediaState.artBitmap = bitmap?.let { bitmapForDisplay(it) }
        }
        lastArtKey = key

        // Les métadonnées ne contiennent souvent qu'une miniature : on charge la version HD via l'URI
        val uri = md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        if (uri != null && hdTriedKey != key && (bitmap == null || maxOf(bitmap.width, bitmap.height) < 900)) {
            hdTriedKey = key
            Thread {
                val hd = loadBitmapFromUri(uri)
                if (hd != null) mainHandler.post {
                    if (lastArtKey == key) {
                        MediaState.artBitmap = bitmapForDisplay(hd)
                        if (MediaState.artGrid == null) MediaState.artGrid = bitmapToGrid(hd, ART_GRID_SIZE)
                    }
                }
            }.start()
        }
    }

    private fun loadBitmapFromUri(uriStr: String): Bitmap? = runCatching {
        val uri = Uri.parse(uriStr)
        fun open(): InputStream? =
            if (uri.scheme == "http" || uri.scheme == "https") {
                URL(uriStr).openConnection().apply { connectTimeout = 4000; readTimeout = 4000 }.getInputStream()
            } else contentResolver.openInputStream(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2400) sample *= 2
        open()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }.getOrNull()

    /** Pochette de secours : grande icône de la notification média de l'appli. */
    private fun artFromNotification(packageName: String): Bitmap? = runCatching {
        (activeNotifications ?: emptyArray())
            .filter { it.packageName == packageName }
            .firstNotNullOfOrNull { sbn ->
                sbn.notification.getLargeIcon()?.loadDrawable(this)?.let { drawable ->
                    val w = drawable.intrinsicWidth.coerceIn(1, 512)
                    val h = drawable.intrinsicHeight.coerceIn(1, 512)
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
                        drawable.setBounds(0, 0, w, h)
                        drawable.draw(Canvas(bmp))
                    }
                }
            }
    }.getOrNull()
}