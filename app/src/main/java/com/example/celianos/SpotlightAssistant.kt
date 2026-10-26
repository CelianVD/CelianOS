package com.example.celianos

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.ZonedDateTime

// ============================================================
//  2. Cerveau IA : appel de fonctions via le fournisseur choisi (Gemini, Mistral ou OpenAI)
//   - pré-tri local : seules les actions des connecteurs pertinents sont envoyées
//   - réflexion minimale, sortie plafonnée (voir AiProviders)
//   - délai court puis repli automatique (voir SpotlightAiRouter)
// ============================================================

class BrainPlan(val calls: List<Pair<String, JSONObject>>, val text: String?)

object SpotlightBrain {
    private const val SYSTEM =
        "Tu pilotes un lanceur Android. Exécute la demande en appelant les fonctions fournies " +
                "(plusieurs appels possibles pour une phrase complexe). N'invente ni numéro ni adresse : " +
                "donne seulement le nom du contact tel que dit. Pour un message ou un e-mail, appelle " +
                "draft_message / draft_email avec la consigne de l'utilisateur (le texte sera rédigé ensuite). " +
                "Les dates sont au format ISO local (ex. 2026-10-23T14:00). " +
                "Si aucune fonction ne convient, réponds en une phrase en français."

    fun hasAnyKey(): Boolean = SpotlightAiRouter.steps().isNotEmpty()

    fun available(): Boolean = SpotlightState.brainEnabled && hasAnyKey() && ConnectorRegistry.activeActions().isNotEmpty()

    /** onStatus reçoit l'état affiché à l'utilisateur (« Je réfléchis… », « Je réessaie avec … »). */
    suspend fun plan(query: String, onStatus: (String) -> Unit = {}): BrainPlan {
        val tools = ConnectorTriage.pick(query)
        val now = ZonedDateTime.now().toString()
        val plan = SpotlightAiRouter.withFallback(AiTask.PLAN, onStatus, SpotlightAiRouter.planTimeoutMs()) { p, key, model ->
            p.plan(key, model, SYSTEM, query, now, tools)
        }
        // le modèle ne peut rien inventer : seules les actions connues et activées passent
        return BrainPlan(plan.calls.filter { ConnectorRegistry.findAction(it.first) != null }, plan.text)
    }
}

// ============================================================
//  Session : file d'actions, attend l'utilisateur quand il faut (brouillon, choix, permission)
// ============================================================
class AssistantSession(private val ctx: Context, private val scope: CoroutineScope) {
    var result by mutableStateOf<ActionResult?>(null)
    var loading by mutableStateOf<String?>(null)
    val done = mutableStateListOf<String>()
    var keepOpen by mutableStateOf(false)   // vrai si du texte à lire a été affiché
    var finished by mutableStateOf(false)

    private val queue = ArrayDeque<Pair<String, JSONObject>>()
    private var current: Pair<String, JSONObject>? = null
    private var pumping = false
    private var brainJob: Job? = null
    private var pumpJob: Job? = null

    fun runAction(action: String, args: JSONObject) { queue.addLast(action to args); pump() }

    fun runBrain(query: String) {
        loading = "Je réfléchis…"          // état immédiat, annulable
        brainJob = scope.launch {
            try {
                val plan = SpotlightBrain.plan(query) { loading = it }
                loading = null
                if (plan.calls.isEmpty()) {
                    done += plan.text ?: "Je n'ai pas compris cette demande."
                    keepOpen = true
                    finished = true
                } else {
                    plan.calls.forEach { queue.addLast(it) }
                    pump()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loading = null
                result = ActionResult.Error((e.message ?: "Erreur de l'assistant") + "\nLes commandes rapides (minuteur, note, préfixes) restent disponibles sans IA.")
            }
        }
    }

    /** Annule l'attente (la requête réseau en cours est interrompue). */
    fun cancel() {
        brainJob?.cancel()
        pumpJob?.cancel()
        queue.clear()
        loading = null
        pumping = false
        finished = true
    }

    /** Après l'octroi d'une permission : on relance l'action en attente. */
    fun resume() { current?.let { queue.addFirst(it); result = null; pump() } }

    /** Exécute ce que l'utilisateur vient de valider (brouillon, confirmation, choix). */
    fun interact(block: suspend () -> ActionResult) {
        scope.launch {
            loading = "…"
            val r = try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                ActionResult.Error(e.message ?: "Erreur")
            }
            loading = null
            if (absorb(r)) pump()
        }
    }

    private fun pump() {
        if (pumping) return
        pumping = true
        pumpJob = scope.launch {
            try {
                while (true) {
                    val next = queue.removeFirstOrNull() ?: break
                    current = next
                    loading = "…"
                    val r = ActionExecutor.run(ctx, next.first, next.second)
                    loading = null
                    if (!absorb(r)) return@launch   // on attend l'utilisateur
                }
                finished = true
            } finally {
                pumping = false
            }
        }
    }

    /** true = étape terminée, on peut passer à la suivante. */
    private fun absorb(r: ActionResult): Boolean = when (r) {
        is ActionResult.Done -> { done += "✓ " + r.message; ActionLog.add("fait", r.message); result = null; true }
        is ActionResult.Text -> { done += r.text; keepOpen = true; result = null; true }
        else -> { result = r; false }
    }
}