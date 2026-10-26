package com.example.celianos

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

// ============================================================
//  Liaison avec l'ordinateur : détecte « Lien avec Windows » (Link to Windows /
//  Phone Link) et/ou un câble USB, puis lance le Glow Dock tout seul.
//
//  Détection Windows : MediaListenerService surveille les notifications persistantes
//  du service d'arrière-plan de Link to Windows (com.microsoft.appmanager).
//  Détection USB : diffusion système USB_STATE (aussi dans MediaListenerService).
// ============================================================
object PcLink {

    /** Applis dont une notification persistante = liaison Windows active. */
    val windowsPackages = setOf(
        "com.microsoft.appmanager",   // Link to Windows
        "com.samsung.android.mdx"     // Continuité Samsung (Samsung <-> Windows)
    )

    var windowsLinked by mutableStateOf(false)
    var usbLinked by mutableStateOf(false)

    /** Vrai si une liaison est détectée ET que sa méthode de détection est activée. */
    val linked: Boolean
        get() = (windowsLinked && DeskDockState.linkViaWindows) ||
                (usbLinked && DeskDockState.linkViaUsb) ||
                (PcAgent.connected && DeskDockState.linkViaAgent)

    // --- Posture du téléphone (couché / pas vertical) ---
    var postureOk by mutableStateOf(false)
    private var sensorManager: SensorManager? = null
    private var postureCandidate = false
    private var candidateSince = 0L

    private fun postureMatches(x: Float, y: Float, z: Float): Boolean {
        val ax = Math.abs(x); val ay = Math.abs(y); val az = Math.abs(z)
        val portrait = ay >= 7f
        val landscape = ax >= 7f
        val flat = az >= 7.5f
        return when (DeskDockState.postureMode) {
            0 -> landscape
            1 -> flat
            else -> !portrait          // 2 : paysage, à plat ou penché (tout sauf debout)
        }
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val m = postureMatches(e.values[0], e.values[1], e.values[2])
            val now = SystemClock.elapsedRealtime()
            if (m != postureCandidate) { postureCandidate = m; candidateSince = now }
            // Stable depuis 1,5 s : évite les faux départs quand on manipule le téléphone
            if (now - candidateSince >= 1500L && postureOk != m) {
                postureOk = m
                evaluate(CelianApp.instance, allowLaunch = true)
            }
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    private fun updateSensor(context: Context, wanted: Boolean) {
        if (wanted && sensorManager == null) {
            val sm = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (acc != null && sm.registerListener(sensorListener, acc, SensorManager.SENSOR_DELAY_NORMAL)) {
                sensorManager = sm
            }
        } else if (!wanted && sensorManager != null) {
            sensorManager?.unregisterListener(sensorListener)
            sensorManager = null
            postureOk = false
        }
    }

    private val postureGate: Boolean
        get() = DeskDockState.postureMode == 3 || (sensorManager == null) || postureOk

    private var lastLinked = false
    private val handler = Handler(Looper.getMainLooper())

    private const val CHANNEL_ID = "glow_dock_launch"
    private const val NOTIF_ID = 4201

    /**
     * À appeler après chaque changement d'état de liaison.
     * Lance le dock seulement lors du passage « non lié -> lié » (pas au démarrage, pas en boucle).
     */
    @Synchronized
    fun evaluate(context: Context, allowLaunch: Boolean = true) {
        // Le capteur ne tourne que si une liaison existe et que le lancement auto est actif
        updateSensor(context, linked && DeskDockState.autoLaunch && DeskDockState.postureMode != 3)
        // Lancement = liaison PC ET téléphone dans la bonne posture (front montant)
        val now = linked && postureGate
        if (now && !lastLinked && allowLaunch && DeskDockState.autoLaunch && !DeskDockState.dockOpen) {
            launchDock(context)
        }
        lastLinked = now
    }

    private fun dockIntent(context: Context) =
        Intent(context, DeskDockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(DeskDockActivity.EXTRA_AUTO, true)

    /**
     * Android bloque souvent le lancement d'une activité depuis l'arrière-plan : on essaie d'abord
     * directement (possible avec « Afficher par-dessus les autres applis »), puis, si le dock
     * n'est pas apparu au bout de 2,5 s, on poste une notification (plein écran / à toucher).
     */
    fun launchDock(context: Context) {
        val app = context.applicationContext
        val intent = dockIntent(app)
        runCatching { app.startActivity(intent) }
        handler.postDelayed({
            if (!DeskDockState.dockOpen) postFallbackNotification(app, intent)
        }, 2500L)
    }

    @SuppressLint("MissingPermission")
    private fun postFallbackNotification(context: Context, intent: Intent) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName("Lancement du Glow Dock")
                .build()
        )
        val pending = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Glow Dock")
            .setContentText("Ordinateur détecté. Touche pour lancer le dock.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setFullScreenIntent(pending, true)
            .build()
        nm.notify(NOTIF_ID, notification)
    }

    fun cancelNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIF_ID)
    }
}