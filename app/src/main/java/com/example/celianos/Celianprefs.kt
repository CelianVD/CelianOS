package com.example.celianos

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import kotlin.reflect.KProperty

// ============================================================
//  Réglages persistants.
//  Utilisation : `var monReglage by PrefState(true, "ma_cle")`
//  La valeur est un état Compose (l'UI se met à jour) et est écrite
//  automatiquement dans SharedPreferences à chaque modification.
// ============================================================

/** Application : charge les réglages au démarrage du processus (appli, services, activités). */
class CelianApp : Application() {
    companion object {
        lateinit var instance: CelianApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        CelianPrefs.init(this)
        PcAgent.ensureRunning()   // surveille l'agent PC (inactif tant qu'il n'est pas configuré)
    }
}

object CelianPrefs {
    private var prefs: SharedPreferences? = null
    private val states = mutableListOf<PrefState<*>>()

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("celianos_settings", Context.MODE_PRIVATE)
        states.toList().forEach { it.load() }
    }

    /** Les objets d'état sont créés à la demande : on charge la valeur dès l'enregistrement si possible. */
    @Synchronized
    internal fun register(state: PrefState<*>) {
        states.add(state)
        if (prefs != null) state.load()
    }

    @Suppress("UNCHECKED_CAST")
    internal fun <T : Any> get(key: String, default: T): T {
        val p = prefs ?: return default
        return when (default) {
            is Boolean -> p.getBoolean(key, default)
            is Float -> p.getFloat(key, default)
            is Int -> p.getInt(key, default)
            is String -> p.getString(key, default) ?: default
            else -> default
        } as T
    }

    internal fun put(key: String, value: Any) {
        val editor = prefs?.edit() ?: return
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Float -> editor.putFloat(key, value)
            is Int -> editor.putInt(key, value)
            is String -> editor.putString(key, value)
        }
        editor.apply()
    }
}

/** Réglages généraux de l'app (hors modules). */
object AppState {
    var onboardingSeen by PrefState(false, "onboarding_seen")   // introduction déjà vue (ou passée)
}

class PrefState<T : Any>(private val default: T, private val key: String) {
    private val state = mutableStateOf(default)

    init {
        CelianPrefs.register(this)
    }

    fun load() {
        state.value = CelianPrefs.get(key, default)
    }

    operator fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        state.value = value
        CelianPrefs.put(key, value)
    }
}