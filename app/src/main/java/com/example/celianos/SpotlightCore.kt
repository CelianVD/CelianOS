package com.example.celianos

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

// ============================================================
//  Spotlight : réglages persistants, bord tactile, tuile rapide
// ============================================================

object SpotlightState {
    // Déclenchement
    var edgeEnabled by PrefState(false, "spot_edge_on")
    var edgeSide by PrefState(1, "spot_edge_side")          // 0 gauche, 1 droite
    var edgeHeightPct by PrefState(35f, "spot_edge_h")      // hauteur de la zone (% de l'écran)
    var edgeHandle by PrefState(true, "spot_edge_handle")   // petit trait visible
    var edgePos by PrefState(50f, "spot_edge_pos")          // position verticale : 0 = haut, 100 = bas
    var edgeWidthDp by PrefState(10f, "spot_edge_w")        // largeur de la zone tactile (dp) : plus fin = moins gênant
    var edgeScope by PrefState(1, "spot_edge_scope")        // 0 partout, 1 écran d'accueil uniquement
    var pillEnabled by PrefState(false, "spot_pill_on")     // double toucher la barre blanche du bas
    var edgeDir by PrefState(0, "spot_edge_dir")            // balayage vertical : 0 vers le bas, 1 vers le haut, 2 les deux

    // Recherche
    var searchApps by PrefState(true, "spot_s_apps")
    var searchContacts by PrefState(true, "spot_s_contacts")
    var searchCalc by PrefState(true, "spot_s_calc")
    var searchWeb by PrefState(true, "spot_s_web")
    var askAi by PrefState(true, "spot_s_gemini")           // « Demander à une IA » (clé conservée)
    var aiEnabled by PrefState("gemini", "spot_ai_enabled") // ids des IA actives, séparés par des virgules
    var searchWiki by PrefState(true, "spot_s_wiki")        // propose la page Wikipédia du mot
    var wikiLang by PrefState("fr", "spot_wiki_lang")
    var searchDict by PrefState(true, "spot_s_dict")        // définitions Wiktionnaire
    var searchSettings by PrefState(true, "spot_s_settings") // raccourcis vers les réglages Android
    var aiInline by PrefState(false, "spot_ai_inline")      // réponse IA affichée dans Spotlight (API)
    var aiProvider by PrefState(0, "spot_ai_provider")
    var aiKeys by PrefState("", "spot_ai_keys")             // lignes « id<TAB>clé »
    var aiModels by PrefState("", "spot_ai_models")         // lignes « id<TAB>modèle »
    var widgetList by PrefState("weather,battery,habits,quote", "spot_widget_list") // ids, dans l'ordre d'affichage
    var notes by PrefState("", "spot_notes")                // notes rapides, une par ligne
    var cmdEnabled by PrefState(true, "spot_cmd")            // routeur local de commandes
    var brainEnabled by PrefState(false, "spot_brain")       // assistant IA (Gemini + function calling)
    var connectors by PrefState("timer,notes,calendar,messages,modules", "spot_connectors")
    var modules by PrefState("", "spot_modules")             // modules utilisateur (JSON)
    var actionLog by PrefState("", "spot_action_log")
    var aiMain by PrefState("gemini", "spot_ai_main")        // IA principale (appel de fonctions + rédaction)
    var aiBackup by PrefState("", "spot_ai_backup")          // IA de secours ("" = aucune)
    var aiPlanModels by PrefState("", "spot_ai_plan_models") // modèle d'analyse choisi par fournisseur (vide = auto)
    var aiDraftModels by PrefState("", "spot_ai_draft_models")
    var aiModelCache by PrefState("", "spot_ai_model_cache") // listes de modèles chargées depuis les API
    var aiTimeoutSec by PrefState(7f, "spot_ai_timeout")     // délai avant repli (secondes)
    var searchFiles by PrefState(true, "spot_s_files")      // photos, vidéos, documents (par titre)
    var engine by PrefState(0, "spot_engine")               // voir WEB_ENGINES

    // Widgets
    var wWeather by PrefState(true, "spot_w_weather")
    var wBattery by PrefState(true, "spot_w_battery")
    var wQuote by PrefState(true, "spot_w_quote")
    var wHabits by PrefState(true, "spot_w_habits")
    var city by PrefState("Belfort", "spot_city")
    var habits by PrefState("Boire de l'eau,Lire 10 min,Sport", "spot_habits")
    var habitsDone by PrefState("", "spot_habits_done")     // "2026-10-21|0,2"

    // Apparence
    var blurRadius by PrefState(50f, "spot_blur")
    var glassStyle by PrefState(0, "spot_style")            // 0 Translucide, 1 Matériel, 2 Brutaliste. Nouvelle clé : l'ancien "spot_glass_style" (dont Liquid Glass = 1) est ignoré, donc retour à Translucide sans migration
    var themeMode by PrefState(2, "spot_theme_mode")        // 0 automatique, 1 clair, 2 sombre (défaut = rendu d'origine)
    var accentFromWallpaper by PrefState(false, "spot_accent_wall") // accent = couleur dominante du fond d'écran
    var accentIndex by PrefState(0, "spot_accent_idx")      // couleur d'accent : index dans ACCENT_PRESETS
    var cornerPct by PrefState(100f, "spot_corner_pct")     // rayon des coins, % des rayons d'origine
    var glassOpacityPct by PrefState(100f, "spot_glass_opacity") // opacité du verre, % de l'opacité d'origine
    var autoKeyboard by PrefState(true, "spot_kbd")
}

val WEB_ENGINES = listOf(
    "Google" to "https://www.google.com/search?q=",
    "DuckDuckGo" to "https://duckduckgo.com/?q=",
    "Qwant" to "https://www.qwant.com/?q=",
    "Brave" to "https://search.brave.com/search?q=",
    "Lilo" to "https://search.lilo.org/?q="
)

fun launchSpotlight(ctx: Context) {
    ctx.startActivity(
        Intent(ctx, SpotlightActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

/**
 * Fine bande tactile transparente collée au bord de l'écran (fenêtre « overlay »).
 * Balayer vers l'intérieur OU vers le bas le long du bord ouvre Spotlight.
 * Nécessite « Afficher par-dessus les autres applis ». Rafraîchie depuis
 * MediaListenerService (processus permanent) et depuis la page de réglages.
 */
object SpotlightEdge {
    private var view: View? = null
    private var wm: WindowManager? = null
    private var lp: WindowManager.LayoutParams? = null
    private var pill: View? = null
    private var pillLp: WindowManager.LayoutParams? = null
    private var lastPkg: String? = null
    private var touchable = true
    private val handler = Handler(Looper.getMainLooper())

    // Assistant de configuration, écran d'essai : la zone reste cliquable même hors de l'accueil
    @Volatile private var forceTouchable = false

    fun setDemo(on: Boolean) {
        forceTouchable = on
        if (on) setTouchable(true)
    }

    private val poll = object : Runnable {
        override fun run() {
            val v = view ?: pill ?: return
            if (SpotlightState.edgeScope == 1) {
                val ctx = v.context
                if (hasUsageAccess(ctx)) {
                    foregroundPackage(ctx)?.let { lastPkg = it }
                    setTouchable(forceTouchable || lastPkg == null || lastPkg in homePackages(ctx))
                } else setTouchable(true) // sans autorisation : on ne cache rien
                handler.postDelayed(this, 1200)
            }
        }
    }

    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun homePackages(ctx: Context): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val out = HashSet<String>()
        ctx.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName?.let { out += it }
        @Suppress("DEPRECATION")
        ctx.packageManager.queryIntentActivities(home, 0).forEach { out += it.activityInfo.packageName }
        out += "com.android.systemui"
        return out
    }

    private fun foregroundPackage(ctx: Context): String? = runCatching {
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val ev = usm.queryEvents(now - (if (lastPkg == null) 3_600_000L else 20_000L), now)
        val e = UsageEvents.Event()
        var last: String? = null
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) last = e.packageName
        }
        last
    }.getOrNull()

    /** Hors de l'écran d'accueil, la zone devient « transparente aux touches » : plus rien n'est bloqué. */
    private fun setTouchable(on: Boolean) {
        if (on == touchable) return
        touchable = on
        listOf(view to lp, pill to pillLp).forEach { (v, p) ->
            if (v == null || p == null) return@forEach
            p.flags = if (on) p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            runCatching { wmOf(v).updateViewLayout(v, p) }
        }
    }

    private fun wmOf(v: View): WindowManager = v.context.getSystemService(WindowManager::class.java)

    private var pillRetries = 0

    /** Navigation par gestes (la « barre blanche ») : Settings.Secure navigation_mode == 2. */
    fun isGestureNav(ctx: Context): Boolean =
        runCatching { Settings.Secure.getInt(ctx.contentResolver, "navigation_mode", 0) == 2 }.getOrDefault(false)

    fun refresh(context: Context) {
        val app = context.applicationContext
        val w = app.getSystemService(WindowManager::class.java) ?: return
        handler.removeCallbacks(poll)
        view?.let { runCatching { wmOf(it).removeView(it) } }
        pill?.let { runCatching { wmOf(it).removeView(it) } }
        view = null; pill = null
        touchable = true

        val canDraw = Settings.canDrawOverlays(app)
        val dm = app.resources.displayMetrics
        wm = w

        if (SpotlightState.edgeEnabled && canDraw) {
            val left = SpotlightState.edgeSide == 0
            val v = EdgeView(app, left, SpotlightState.edgeHandle, SpotlightState.edgeDir)
            val h = (dm.heightPixels * SpotlightState.edgeHeightPct / 100f).toInt()
            val params = WindowManager.LayoutParams(
                (SpotlightState.edgeWidthDp * dm.density).toInt().coerceAtLeast(1),
                h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = (if (left) Gravity.START else Gravity.END) or Gravity.TOP
                // 0 % = zone collée en haut, 100 % = collée en bas
                y = ((dm.heightPixels - h) * SpotlightState.edgePos / 100f).toInt().coerceAtLeast(0)
            }
            runCatching { w.addView(v, params); view = v; lp = params }
        }

        if (SpotlightState.pillEnabled && isGestureNav(app)) {
            // Fenêtre d'accessibilité (celle de la Dynamic Island) : elle passe AU-DESSUS de la barre de navigation,
            // contrairement à une fenêtre « overlay » classique que la barre du système recouvre.
            val svc = DynamicIslandService.instance
            if (svc != null || canDraw) {
                val host: Context = svc ?: app
                val hw = host.getSystemService(WindowManager::class.java)
                val v = PillView(host)
                val params = WindowManager.LayoutParams(
                    (dm.widthPixels * 0.4f).toInt(),
                    (32 * dm.density).toInt(),
                    if (svc != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                    else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }
                runCatching { hw.addView(v, params); pill = v; pillLp = params }
            }
            // Le service d'accessibilité peut démarrer après nous : on réessaie quelques secondes
            if (svc == null && pillRetries < 10) {
                pillRetries++
                handler.postDelayed({ refresh(app) }, 3000)
            } else if (svc != null) pillRetries = 0
        }

        if ((view != null || pill != null) && SpotlightState.edgeScope == 1) handler.post(poll)
    }
}

/** Zone transparente sur la barre de geste du bas : un double toucher ouvre Spotlight. */
private class PillView(ctx: Context) : View(ctx) {
    private val detector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onDoubleTap(e: MotionEvent): Boolean {
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            launchSpotlight(context)
            return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean = detector.onTouchEvent(e)
}

private class EdgeView(
    ctx: Context,
    private val left: Boolean,
    private val showHandle: Boolean,
    private val dir: Int
) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF }
    private var downX = 0f
    private var downY = 0f
    private var fired = false

    override fun onDraw(canvas: Canvas) {
        if (!showHandle) return
        val w = 4 * d
        val h = height * 0.5f
        val top = (height - h) / 2f
        val l = if (left) 2 * d else width - 2 * d - w
        canvas.drawRoundRect(RectF(l, top, l + w, top + h), w / 2, w / 2, paint)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // Empêche le geste « retour » du système de voler le balayage depuis le bord
        if (Build.VERSION.SDK_INT >= 29) {
            systemGestureExclusionRects = listOf(Rect(0, 0, r - l, b - t))
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; fired = false }
            MotionEvent.ACTION_MOVE -> if (!fired) {
                val dx = if (left) e.rawX - downX else downX - e.rawX
                val dy = e.rawY - downY
                val vertical = when (dir) {
                    0 -> dy > 56 * d
                    1 -> -dy > 56 * d
                    else -> kotlin.math.abs(dy) > 56 * d
                }
                if (dx > 36 * d || vertical) {
                    fired = true
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    launchSpotlight(context)
                }
            }
        }
        return true
    }
}

/** Tuile des réglages rapides : alternative sans zone tactile sur le bord. */
class SpotlightTileService : TileService() {
    override fun onStartListening() {
        qsTile?.let { it.state = Tile.STATE_INACTIVE; it.updateTile() }
    }

    override fun onClick() {
        val i = Intent(this, SpotlightActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}