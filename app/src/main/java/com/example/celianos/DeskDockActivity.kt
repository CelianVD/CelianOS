package com.example.celianos

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay

/**
 * Plein écran pour le téléphone posé sur un support : écran toujours allumé,
 * barres système masquées, portrait ou paysage.
 */
class DeskDockActivity : ComponentActivity() {

    private var askedMic = false

    // Lancé automatiquement par la détection de l'ordinateur (donc fermé à la déconnexion)
    private var launchedAuto = false

    companion object {
        const val EXTRA_AUTO = "launched_by_pc_link"
    }

    private val micLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) AudioReactor.start(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchedAuto = intent?.getBooleanExtra(EXTRA_AUTO, false) == true

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.also {
                it.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        if (DeskDockState.showWhenLocked) {
            if (Build.VERSION.SDK_INT >= 27) {
                setShowWhenLocked(true)
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            }
        }

        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent {
            CelianOSTheme {
                // Lancé par la liaison : on se ferme quand l'ordinateur n'est plus détecté (après 4 s)
                val linked = PcLink.linked
                LaunchedEffect(linked) {
                    if (launchedAuto && !linked && DeskDockState.closeOnUnlink) {
                        delay(4000)
                        if (!PcLink.linked) finish()
                    }
                }

                DeskDockScreen(
                    onExit = { finish() },
                    onOpenApp = { runCatching { MediaState.controller?.sessionActivity?.send() } },
                    onDim = { level ->
                        window.attributes = window.attributes.also { it.screenBrightness = level }
                    }
                )
            }
        }
    }

    // Touches volume du téléphone = volume réel du PC quand l'agent est connecté
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (PcAgent.connected) {
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_VOLUME_UP -> { PcAgent.nudgeVolume(+3); return true }
                android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> { PcAgent.nudgeVolume(-3); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_AUTO, false)) launchedAuto = true
    }

    override fun onStart() {
        super.onStart()
        DeskDockState.dockOpen = true          // masque la Dynamic Island
        PcLink.cancelNotification(this)
        PcAgent.ensureRunning()
        startReactor()
    }

    override fun onStop() {
        DeskDockState.dockOpen = false
        AudioReactor.stop()
        super.onStop()
    }

    private fun startReactor() {
        if (DeskDockState.useSimulation) return
        if (AudioReactor.hasPermission(this)) {
            AudioReactor.start(this)
        } else if (!askedMic) {
            askedMic = true
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}