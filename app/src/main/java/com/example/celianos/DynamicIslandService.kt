package com.example.celianos

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private enum class PillVisibility { HIDDEN, PLAYING, PAUSED, PREVIEW, SOUND }

/** Aperçu forcé depuis les réglages (pour visualiser pendant qu'on déplace un curseur). */
enum class PreviewMode { NONE, COMPACT, EXPANDED }

/** Visuel affiché à gauche du titre en grande version. */
enum class ArtStyle { ICON, PIXEL_GRID, DOT_RING }

/** Changements de profil sonore qui déclenchent une animation. */
enum class SoundKind { SILENT, VIBRATE, NORMAL, DND_ON, DND_OFF }

data class SoundEvent(val kind: SoundKind, val id: Int)

// Durée d'affichage de l'animation de profil sonore
private const val SOUND_EVENT_MS = 2200L

// Durée du fondu de sortie avant le retrait de la fenêtre
internal const val HIDE_ANIM_MS = 300L

/**
 * Service d'accessibilité : seule façon pour une fenêtre d'être dessinée
 * PAR-DESSUS la barre d'état (TYPE_ACCESSIBILITY_OVERLAY).
 * L'interface de la pilule est dans DynamicIslandUi.kt.
 */
class DynamicIslandService : AccessibilityService(), LifecycleOwner, SavedStateRegistryOwner {

    private lateinit var windowManager: WindowManager
    private lateinit var windowParams: WindowManager.LayoutParams
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager
    private var floatingView: ComposeView? = null
    private var scope: CoroutineScope? = null
    private var previewJob: Job? = null
    private var initialized = false
    private var soundReceiverRegistered = false
    private var lastRingerMode = AudioManager.RINGER_MODE_NORMAL
    private var lastDndActive = false

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    // Écoute le mode silencieux/vibreur et le mode Ne pas déranger
    private val soundReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AudioManager.RINGER_MODE_CHANGED_ACTION -> onRingerModeChanged()
                NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> onDndChanged()
            }
        }
    }

    companion object {
        // Après une pause, la pilule reste visible ce temps-là pour pouvoir relancer la musique
        private const val PAUSE_GRACE_MS = 15_000L

        // L'aperçu reste affiché ce temps-là après avoir lâché le curseur
        private const val PREVIEW_HOLD_MS = 1200L

        var instance: DynamicIslandService? = null
            private set

        var verticalOffset by PrefState(40, "island_y")
            private set

        // Espace (en dp) laissé au-dessus du contenu de la grande version, pour passer sous la caméra
        var expandedOffset by PrefState(34, "island_expanded_y")
            private set

        var previewMode by mutableStateOf(PreviewMode.NONE)

        // Incrémenté à chaque clic en dehors de la pilule (sert à la refermer)
        var outsideTouchTick by mutableIntStateOf(0)
            private set

        // Visuel à gauche du titre en grande version
        private var artStyleName by PrefState(ArtStyle.PIXEL_GRID.name, "island_art_style")

        val artStyle: ArtStyle
            get() = runCatching { ArtStyle.valueOf(artStyleName) }.getOrDefault(ArtStyle.PIXEL_GRID)

        // Alertes de changement de profil sonore
        var soundAlertsEnabled by PrefState(true, "island_sound_alerts")
            private set

        var soundEvent by mutableStateOf<SoundEvent?>(null)
            private set

        private var soundEventCounter = 0

        // Vrai pendant le fondu de sortie, avant le retrait de la fenêtre
        var hiding by mutableStateOf(false)

        var enabled by PrefState(true, "island_enabled")
            private set

        var musicEnabled by PrefState(true, "island_music")
            private set

        fun notifyOutsideTouch() {
            outsideTouchTick++
        }

        fun updateVerticalOffset(newOffset: Int) {
            verticalOffset = newOffset
            instance?.updateWindowY(newOffset)
        }

        fun updateExpandedOffset(newOffset: Int) {
            expandedOffset = newOffset
        }

        fun updateArtStyle(style: ArtStyle) {
            artStyleName = style.name
        }

        fun setSoundAlerts(on: Boolean) {
            soundAlertsEnabled = on
        }

        /** Déclenche l'animation de profil sonore (aussi utilisé par le bouton « Tester »). */
        fun postSoundEvent(kind: SoundKind) {
            if (instance == null) return
            soundEventCounter++
            soundEvent = SoundEvent(kind, soundEventCounter)
        }

        fun clearSoundEvent() {
            soundEvent = null
        }

        /** Affiche la pilule (petite ou grande) même sans musique, tant qu'on bouge un curseur. */
        fun showPreview(mode: PreviewMode) {
            val service = instance ?: return
            service.cancelPreviewEnd()
            previewMode = mode
        }

        /** À appeler quand on lâche le curseur : l'aperçu disparaît après un court délai. */
        fun endPreview() {
            instance?.schedulePreviewEnd()
        }

        /** Appelé par l'app quand les switchs changent. */
        fun sync(enabled: Boolean, musicEnabled: Boolean) {
            this.enabled = enabled
            this.musicEnabled = musicEnabled
        }
    }

    private fun currentVisibility(): PillVisibility = when {
        DeskDockState.dockOpen -> PillVisibility.HIDDEN   // le Glow Dock occupe l'écran : pas de pilule
        previewMode != PreviewMode.NONE -> PillVisibility.PREVIEW
        !enabled -> PillVisibility.HIDDEN
        soundEvent != null -> PillVisibility.SOUND
        !musicEnabled -> PillVisibility.HIDDEN
        MediaState.isPlaying -> PillVisibility.PLAYING
        MediaState.isPaused -> PillVisibility.PAUSED
        else -> PillVisibility.HIDDEN
    }

    fun cancelPreviewEnd() {
        previewJob?.cancel()
        previewJob = null
    }

    fun schedulePreviewEnd() {
        previewJob?.cancel()
        previewJob = scope?.launch {
            delay(PREVIEW_HOLD_MS)
            previewMode = PreviewMode.NONE
        }
    }

    // --- Profil sonore -------------------------------------------------------

    private fun onRingerModeChanged() {
        val mode = audioManager.ringerMode
        if (mode == lastRingerMode) return
        lastRingerMode = mode
        if (!soundAlertsEnabled) return
        postSoundEvent(
            when (mode) {
                AudioManager.RINGER_MODE_SILENT -> SoundKind.SILENT
                AudioManager.RINGER_MODE_VIBRATE -> SoundKind.VIBRATE
                else -> SoundKind.NORMAL
            }
        )
    }

    private fun isDndActive(): Boolean {
        val filter = notificationManager.currentInterruptionFilter
        return filter == NotificationManager.INTERRUPTION_FILTER_PRIORITY ||
                filter == NotificationManager.INTERRUPTION_FILTER_NONE ||
                filter == NotificationManager.INTERRUPTION_FILTER_ALARMS
    }

    private fun onDndChanged() {
        val active = isDndActive()
        if (active == lastDndActive) return
        lastDndActive = active
        if (!soundAlertsEnabled) return
        postSoundEvent(if (active) SoundKind.DND_ON else SoundKind.DND_OFF)
    }

    private fun registerSoundReceiver() {
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // Les diffusions « sticky » renvoient l'état courant à l'inscription : on le mémorise pour l'ignorer
        lastRingerMode = audioManager.ringerMode
        lastDndActive = isDndActive()

        val filter = IntentFilter().apply {
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(soundReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(soundReceiver, filter)
        }
        soundReceiverRegistered = true
    }

    // --- Cycle de vie --------------------------------------------------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (initialized) return
        initialized = true
        instance = this

        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = verticalOffset
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        registerSoundReceiver()

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { s ->
            s.launch {
                // Le 2e élément relance la collecte quand un nouvel événement sonore arrive
                snapshotFlow { currentVisibility() to soundEvent?.id }.collectLatest { (visibility, _) ->
                    when (visibility) {
                        PillVisibility.PLAYING, PillVisibility.PREVIEW -> showPill()
                        PillVisibility.SOUND -> {
                            showPill()
                            delay(SOUND_EVENT_MS)
                            clearSoundEvent()
                        }
                        PillVisibility.PAUSED -> {
                            showPill()
                            delay(PAUSE_GRACE_MS)
                            fadeOutAndHide()
                        }
                        PillVisibility.HIDDEN -> fadeOutAndHide()
                    }
                }
            }
        }
    }

    private fun showPill() {
        hiding = false
        if (floatingView != null) return
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@DynamicIslandService)
            setViewTreeSavedStateRegistryOwner(this@DynamicIslandService)
            // Reçoit ACTION_OUTSIDE quand l'utilisateur touche l'écran ailleurs (le clic continue vers l'app dessous)
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) notifyOutsideTouch()
                false
            }
            setContent {
                CelianOSTheme { DynamicIsland() }
            }
        }
        windowParams.x = 0
        windowParams.y = verticalOffset
        windowManager.addView(view, windowParams)
        floatingView = view
    }

    /** Fondu de sortie, puis retrait de la fenêtre. */
    private suspend fun fadeOutAndHide() {
        if (floatingView != null) {
            hiding = true
            delay(HIDE_ANIM_MS)
        }
        hidePill()
        hiding = false
    }

    private fun hidePill() {
        floatingView?.let { runCatching { windowManager.removeView(it) } }
        floatingView = null
    }

    fun updateWindowY(newY: Int) {
        floatingView?.let {
            windowParams.y = newY
            windowManager.updateViewLayout(it, windowParams)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Non utilisé : le service sert uniquement à afficher la fenêtre au-dessus de la barre d'état
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance == this) instance = null
        previewMode = PreviewMode.NONE
        clearSoundEvent()
        hiding = false
        if (soundReceiverRegistered) {
            runCatching { unregisterReceiver(soundReceiver) }
            soundReceiverRegistered = false
        }
        scope?.cancel()
        hidePill()
        if (initialized) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
        super.onDestroy()
    }
}