package com.example.celianos

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ============================================================
//  Assistant à commandes : interface Connector, registre, exécuteur, routeur local
//
//   Texte -> 1. CommandRouter (local, instantané, hors ligne)
//         -> 2. SpotlightBrain (Gemini + function calling) si rien ne correspond
//         -> 3. ConnectorRegistry (actions des connecteurs ACTIVÉS seulement)
//         -> 4. ActionExecutor (permissions, garde-fou « visible par autrui »)
//         -> 5. interface (SpotlightAssistantUi)
//
//  Ajouter un connecteur = écrire un `object MonConnecteur : Connector` et
//  l'ajouter à ConnectorRegistry.builtin. Rien d'autre à toucher.
// ============================================================

/** READ = lecture seule, LOCAL = effet sur le téléphone, VISIBLE = visible par une autre personne. */
enum class Risk { READ, LOCAL, VISIBLE }

class ParamSpec(
    val name: String,
    val description: String,
    val type: String = "string",   // string | integer | number | boolean
    val required: Boolean = true
)

class ActionSpec(val name: String, val description: String, val params: List<ParamSpec>, val risk: Risk)

class ChoiceOption(val label: String, val next: suspend () -> ActionResult)

/** Chaque résultat dit à l'interface comment il doit être affiché. */
sealed interface ActionResult {
    class Text(val text: String) : ActionResult
    class Done(val message: String) : ActionResult
    class Error(val message: String) : ActionResult
    class NeedPermission(val permissions: List<String>) : ActionResult
    /** Ambiguïté (ex. deux « Paul ») : l'utilisateur choisit. */
    class Choice(val prompt: String, val options: List<ChoiceOption>) : ActionResult
    /**
     * Brouillon modifiable : RIEN n'est envoyé tant que l'utilisateur ne valide pas (send).
     * body = texte affiché tout de suite. writer (facultatif) = rédaction IA lancée par l'interface pendant que
     * le texte brut est déjà affiché. written = résultat connu d'une rédaction déjà faite (e-mail).
     */
    class Draft(
        val title: String,
        val to: String,
        val channels: List<String>,
        val channel: String,
        val subject: String?,
        val body: String,
        val writer: (suspend () -> DraftWrite)? = null,
        val written: DraftWrite? = null,
        val send: suspend (channel: String, subject: String?, body: String) -> ActionResult
    ) : ActionResult
    class Confirm(val summary: String, val risk: Risk, val run: suspend () -> ActionResult) : ActionResult
}

/** Résultat d'une rédaction IA : texte + fournisseur, ou échec (raison courte, sans contenu de message). */
sealed interface DraftWrite {
    class Ok(val text: String, val by: String) : DraftWrite
    class Failed(val reason: String) : DraftWrite
}

interface Connector {
    val id: String
    val name: String
    val description: String
    val permissions: List<String>
    val actions: List<ActionSpec>

    /** Permissions requises pour une action précise (par défaut : celles du connecteur). */
    fun permissionsFor(action: String): List<String> = permissions

    /**
     * Les actions VISIBLE ne doivent JAMAIS agir ici : elles renvoient Draft ou Confirm,
     * l'effet réel est dans la fermeture appelée après validation de l'utilisateur.
     */
    suspend fun execute(ctx: Context, action: String, args: JSONObject): ActionResult
}

object ConnectorRegistry {
    val builtin: List<Connector> by lazy {
        listOf(TimerConnector, NotesConnector, CalendarConnector, MessagesConnector, ModulesConnector)
    }

    fun enabledIds(): Set<String> =
        SpotlightState.connectors.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun setEnabled(id: String, on: Boolean) {
        SpotlightState.connectors = (if (on) enabledIds() + id else enabledIds() - id).joinToString(",")
    }

    fun active(): List<Connector> = builtin.filter { it.id in enabledIds() }
    fun activeActions(): List<Pair<Connector, ActionSpec>> = active().flatMap { c -> c.actions.map { c to it } }
    fun findAction(name: String): Pair<Connector, ActionSpec>? = activeActions().firstOrNull { it.second.name == name }

    /** Déclarations « functionDeclarations » pour Gemini : uniquement les actions activées. */
    fun toolDeclarations(): JSONArray {
        val arr = JSONArray()
        activeActions().forEach { (_, a) ->
            val o = JSONObject().put("name", a.name).put("description", a.description)
            if (a.params.isNotEmpty()) {
                val props = JSONObject()
                val req = JSONArray()
                a.params.forEach {
                    props.put(it.name, JSONObject().put("type", it.type.uppercase()).put("description", it.description))
                    if (it.required) req.put(it.name)
                }
                o.put("parameters", JSONObject().put("type", "OBJECT").put("properties", props).put("required", req))
            }
            arr.put(o)
        }
        return arr
    }
}

/** Journal local des actions (jamais de clé ni de contenu de message). */
object ActionLog {
    fun add(kind: String, text: String, max: Int = 80) {
        val line = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date()) +
                " · $kind · " + text.take(max).replace('\n', ' ')
        SpotlightState.actionLog = (listOf(line) + lines()).take(50).joinToString("\n")
    }

    fun lines(): List<String> = SpotlightState.actionLog.lines().filter { it.isNotBlank() }
    fun clear() { SpotlightState.actionLog = "" }
}

/** Le garde-fou : permissions, risque, erreurs, journal. */
object ActionExecutor {
    suspend fun run(ctx: Context, action: String, args: JSONObject): ActionResult {
        val (c, spec) = ConnectorRegistry.findAction(action)
            ?: return ActionResult.Error("Action indisponible : $action (connecteur désactivé ?)")

        val missing = c.permissionsFor(action).filter {
            ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) return ActionResult.NeedPermission(missing)

        val r = try {
            c.execute(ctx, action, args)
        } catch (e: Exception) {
            ActionResult.Error(e.message ?: "Erreur")
        }

        // Une action visible par autrui doit passer par un brouillon / une confirmation.
        if (spec.risk == Risk.VISIBLE && (r is ActionResult.Done || r is ActionResult.Text)) {
            ActionLog.add("REFUSÉ", "$action : action visible sans confirmation")
            return ActionResult.Error("Action visible par d'autres sans confirmation : refusée.")
        }
        ActionLog.add(spec.risk.name.lowercase(), action)
        return r
    }
}

// ============================================================
//  1. Routeur local : instantané, gratuit, privé, hors ligne
// ============================================================
object CommandRouter {
    private val timerRe = Regex("^(?:minuteur|timer)\\s+(\\d{1,4})\\s*(h|heures?|min|minutes?|m|s|sec|secondes?)?$", RegexOption.IGNORE_CASE)
    private val alarmRe = Regex("^(?:r[ée]veil|alarme)\\s+(\\d{1,2})\\s*[h:]\\s*(\\d{2})?$", RegexOption.IGNORE_CASE)
    private val noteRe = Regex("^note\\s*:?\\s+(.+)$", RegexOption.IGNORE_CASE)

    private val COMMAND_VERBS = listOf(
        "envoie", "envoi ", "dis ", "dit ", "écris", "ecris", "rappelle", "rappel ", "ajoute", "crée", "cree",
        "mets ", "met ", "programme", "planifie", "appelle", "demande", "prépare", "prepare", "réponds", "reponds"
    )

    /** « dis à William de dire bonjour », « écris à Maman que j'arrive » : nom du contact + suite de la phrase. */
    class MessageCmd(val who: String, val instruction: String, val sentence: String)

    private val messageRe = Regex(
        """^(?:dis|écris|ecris)\s+[àa]\s+(\p{L}[\p{L}'’-]*(?:\s+\p{L}[\p{L}'’-]*){0,2}?)\s+(?:(?:de|que)\s+|(?:d|qu)['’])(\S.*)$""",
        RegexOption.IGNORE_CASE
    )

    /** Reconnaissance locale, sans réseau. La résolution du contact se fait ensuite (MessagesConnector.contactCount). */
    fun parseMessage(raw: String): MessageCmd? {
        val t = raw.trim()
        val m = messageRe.matchEntire(t) ?: return null
        val who = m.groupValues[1].trim()
        val instr = m.groupValues[2].trim()
        return if (who.isEmpty() || instr.isEmpty()) null else MessageCmd(who, instr, t)
    }

    /** La phrase ressemble à une commande en langage naturel → l'IA peut être proposée. */
    fun looksLikeCommand(raw: String): Boolean {
        val s = raw.trim().lowercase()
        return s.split(' ').size >= 2 && COMMAND_VERBS.any { s.startsWith(it) }
    }

    fun fmtDuration(secs: Int): String = when {
        secs % 3600 == 0 -> "${secs / 3600} h"
        secs % 60 == 0 -> "${secs / 60} min"
        else -> "$secs s"
    }

    fun match(raw: String): List<Hit.CommandHit> {
        val t = raw.trim()
        if (t.length < 2) return emptyList()
        val enabled = ConnectorRegistry.enabledIds()
        val out = ArrayList<Hit.CommandHit>()

        if ("timer" in enabled) {
            timerRe.matchEntire(t)?.let { m ->
                val n = m.groupValues[1].toInt()
                val u = m.groupValues[2].lowercase()
                val secs = when {
                    u.startsWith("h") -> n * 3600
                    u.startsWith("s") -> n
                    else -> n * 60
                }
                out += Hit.CommandHit("Minuteur de ${fmtDuration(secs)}", "Lancer le minuteur", "set_timer",
                    JSONObject().put("seconds", secs).put("label", ""))
            }
            alarmRe.matchEntire(t)?.let { m ->
                val h = m.groupValues[1].toInt()
                val mi = m.groupValues[2].ifEmpty { "0" }.toInt()
                if (h in 0..23 && mi in 0..59) {
                    out += Hit.CommandHit("Réveil à %02d:%02d".format(h, mi), "Créer l'alarme", "set_alarm",
                        JSONObject().put("hour", h).put("minute", mi).put("label", ""))
                }
            }
        }
        if ("notes" in enabled) {
            noteRe.matchEntire(t)?.let { m ->
                out += Hit.CommandHit("Note : « ${m.groupValues[1]} »", "Ajouter aux notes rapides", "create_note",
                    JSONObject().put("text", m.groupValues[1]))
            }
        }
        if ("modules" in enabled) {
            UserModules.load().forEach { mod ->
                if (mod.prefix.isNotBlank() && t.startsWith(mod.prefix + " ", ignoreCase = true)) {
                    val q = t.substring(mod.prefix.length + 1).trim()
                    if (q.isNotEmpty()) {
                        out += Hit.CommandHit("${mod.name} : « $q »",
                            if (mod.kind == "webhook") "Module (demande une confirmation)" else "Module",
                            "module_${mod.id}", JSONObject().put("q", q))
                    }
                }
            }
        }
        return out
    }
}