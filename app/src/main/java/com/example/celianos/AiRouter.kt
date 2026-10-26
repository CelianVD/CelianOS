package com.example.celianos

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

// ============================================================
//  Choix du fournisseur et du modèle, repli automatique avec délai court,
//  liste des modèles chargée depuis l'API (mise en cache, jamais codée en dur)
// ============================================================

enum class AiTask { PLAN, DRAFT }

/** Toutes les étapes ont échoué : une ligne par tentative (affichée sous « Rédaction indisponible, texte brut »). */
class AiChainException(val lines: List<String>) : Exception(lines.joinToString("\n"))

object SpotlightAiRouter {
    /** alt = « second modèle du même fournisseur » quand aucune IA de secours n'est configurée. */
    class Step(val provider: AiProvider, val alt: Boolean, val quickOk: Boolean = false)

    // Dernier recours seulement, si la liste des modèles est introuvable : (candidats analyse, candidats rédaction),
    // dans l'ordre. Gemini : les deux modèles vérifiés (3.1 flash-lite, 3.5 flash), jamais les alias « -latest ».
    private val LAST_RESORT = mapOf(
        "gemini" to (listOf("gemini-3.1-flash-lite", "gemini-3.5-flash-lite") to listOf("gemini-3.1-flash-lite", "gemini-3.5-flash-lite")),
        "mistral" to (listOf("ministral-8b-2512") to listOf("ministral-8b-2512")),
        // OpenAI : jamais de modèle coûteux ou lent par défaut (la rédaction aussi part sur « mini »)
        "openai" to (listOf("gpt-5-mini") to listOf("gpt-5-mini"))
    )

    // ---------- mémoire de session (en mémoire, perdue au redémarrage du processus) ----------
    /** Modèles qui viennent d'échouer en 503 ou 429 : jamais choisis automatiquement. */
    private val failedModels = ConcurrentHashMap.newKeySet<String>()
    /** Fournisseurs en pause après un 429 : id -> fin de pause (SystemClock.elapsedRealtime). */
    private val pausedUntil = ConcurrentHashMap<String, Long>()
    /** Durée de pause après un 429 (le modèle en cause est en plus écarté du choix automatique). */
    private val PAUSE_ON_429 = mapOf("mistral" to 30_000L)
    /** La liste des modèles n'est jamais rechargée pendant une action (les alias « -latest » suffisent) ;
     *  elle se charge depuis les réglages (bouton « Charger les modèles »). */
    private val NO_INLINE_REFRESH = setOf("mistral")

    /** Couples fournisseur|modèle dont le 429 annonce une limite nulle (quota 0) : jamais rappelés pendant la session. */
    private val zeroQuota = ConcurrentHashMap.newKeySet<String>()
    /** « fournisseur|tâche » -> dernier modèle qui a répondu : essayé en premier ensuite. */
    private val answered = ConcurrentHashMap<String, String>()
    /** Modèle le plus rapide connu, essayé en premier s'il figure dans la liste. */
    private val FASTEST = mapOf("gemini" to "gemini-3.1-flash-lite")

    private fun isZero(pid: String, model: String) = zeroQuota.contains("$pid|$model")
    fun zeroQuotaModels(pid: String): List<String> =
        zeroQuota.filter { it.startsWith("$pid|") }.map { it.substringAfter('|') }.sorted()

    private fun forget(pid: String, model: String) {
        answered.entries.removeIf { it.key.startsWith("$pid|") && it.value == model }
    }

    private fun isFailed(pid: String, model: String) = failedModels.contains("$pid|$model")
    private fun markFailed(pid: String, model: String) { failedModels.add("$pid|$model") }

    private fun pauseLeftMs(pid: String): Long {
        val until = pausedUntil[pid] ?: return 0L
        val left = until - SystemClock.elapsedRealtime()
        if (left <= 0L) { pausedUntil.remove(pid); return 0L }
        return left
    }

    private fun lastResort(pid: String, task: AiTask, avoid: String? = null): String? {
        val (plan, draft) = LAST_RESORT[pid] ?: return null
        return (if (task == AiTask.PLAN) plan else draft).firstOrNull { !isFailed(pid, it) && !isZero(pid, it) && it != avoid }
    }

    private val SMALL_WORDS = listOf("nano", "mini", "lite", "small", "ministral", "haiku")
    private fun isReasoning(id: String) = Regex("^o\\d").containsMatchIn(id) || "reasoning" in id || "thinking" in id

    /** OpenAI : seuls les petits modèles non « raisonnement » sont choisis automatiquement (le choix manuel reste libre). */
    private fun autoSafe(pid: String, id: String): Boolean {
        if (pid != "openai") return true
        val s = id.lowercase()
        return !isReasoning(s) && SMALL_WORDS.any { it in s }
    }

    /** Classement automatique sans les modèles en échec (si tous ont échoué, on ne bloque pas : classement sûr complet). */
    private fun autoOrder(pid: String, task: AiTask, models: List<AiModel>): List<String> {
        val safe = rank(pid, task, models).filter { autoSafe(pid, it) }
        return safe.filter { !isFailed(pid, it) }.ifEmpty { safe }
    }

    /** Ordre d'essai : dernier modèle qui a répondu, choix manuel, modèle le plus rapide connu, puis classement. Jamais un quota nul. */
    private fun fullOrder(pid: String, task: AiTask, models: List<AiModel>): List<String> {
        val ids = models.map { it.id }
        val fast = FASTEST[pid]?.takeIf { it in ids && !isFailed(pid, it) }
        val ok = answered["$pid|$task"]?.takeIf { !isFailed(pid, it) }
        return (listOfNotNull(ok, chosen(pid, task), fast) + autoOrder(pid, task, models)).distinct().filter { !isZero(pid, it) }
    }

    private fun pickModel(pid: String, task: AiTask, models: List<AiModel>, alt: Boolean, avoid: String?): String? {
        val order = fullOrder(pid, task, models)
        val pick = when {
            !alt -> order.firstOrNull()
            avoid != null -> order.firstOrNull { it != avoid }
            else -> order.getOrNull(1)
        }
        return pick ?: if (alt && avoid == null) null else lastResort(pid, task, avoid)
    }

    fun hasKey(p: AiProvider) = SpotlightAiApi.key(p.id).isNotBlank()

    fun steps(): List<Step> {
        val main = AiProviders.byId(SpotlightState.aiMain)?.takeIf { hasKey(it) }
        val backup = AiProviders.byId(SpotlightState.aiBackup)?.takeIf { hasKey(it) && it.id != main?.id }
        val first = main ?: backup ?: AiProviders.all.firstOrNull { hasKey(it) } ?: return emptyList()
        val chain = listOf(first) + listOfNotNull(backup?.takeIf { it.id != first.id })
        val out = ArrayList<Step>()
        for (p in chain) {
            out += Step(p, false)
            // Gemini : un second modèle Gemini avant de passer à l'IA suivante (aussi en rédaction rapide)
            if (p.id == "gemini") out += Step(p, true, quickOk = true)
        }
        // sans IA de secours (et hors Gemini) : second meilleur modèle du même fournisseur, comme avant
        if (chain.size == 1 && first.id != "gemini") out += Step(first, true)
        return out
    }

    fun planTimeoutMs(): Long = (SpotlightState.aiTimeoutSec * 1000).toLong()
    fun draftTimeoutMs(): Long = (SpotlightState.aiTimeoutSec * 1800).toLong()

    /** Rédaction directe d'un message (un seul appel, sans outils) : délai court, sortie courte. */
    const val QUICK_DRAFT_TIMEOUT_MS = 7_000L
    const val QUICK_DRAFT_TOKENS = 300

    // ---------- petites tables « id<TAB>valeur » ----------
    private fun readMap(raw: String): MutableMap<String, String> = raw.lines().mapNotNull {
        val i = it.indexOf('\t'); if (i < 0) null else it.substring(0, i) to it.substring(i + 1)
    }.toMap().toMutableMap()

    private fun writeMap(m: Map<String, String>) = m.entries.joinToString("\n") { "${it.key}\t${it.value}" }
    private fun prefOf(task: AiTask) = if (task == AiTask.PLAN) SpotlightState.aiPlanModels else SpotlightState.aiDraftModels

    /** Modèle choisi à la main pour ce fournisseur et cette tâche ; null = automatique. */
    fun chosen(providerId: String, task: AiTask): String? = readMap(prefOf(task))[providerId]?.takeIf { it.isNotBlank() }

    fun setChosen(providerId: String, task: AiTask, model: String) {
        val m = readMap(prefOf(task))
        if (model.isBlank()) m.remove(providerId) else m[providerId] = model
        if (task == AiTask.PLAN) SpotlightState.aiPlanModels = writeMap(m) else SpotlightState.aiDraftModels = writeMap(m)
        answered.remove("$providerId|$task")   // un choix manuel repart de zéro
    }

    // ---------- cache des modèles ----------
    private fun readCache(): JSONObject = runCatching { JSONObject(SpotlightState.aiModelCache) }.getOrDefault(JSONObject())

    fun cachedModels(providerId: String): List<AiModel> {
        val a = readCache().optJSONObject(providerId)?.optJSONArray("m") ?: return emptyList()
        return (0 until a.length()).map { val o = a.getJSONObject(it); AiModel(o.getString("id"), o.optBoolean("tools", true)) }
    }

    suspend fun refreshModels(p: AiProvider): List<AiModel> {
        val ms = p.listModels(SpotlightAiApi.key(p.id))
        val arr = JSONArray()
        ms.forEach { arr.put(JSONObject().put("id", it.id).put("tools", it.tools)) }
        SpotlightState.aiModelCache = readCache().put(p.id, JSONObject().put("t", System.currentTimeMillis()).put("m", arr)).toString()
        return ms
    }

    /** Un modèle retiré par le fournisseur (404) : on oublie la liste, elle sera rechargée au prochain appel. */
    fun invalidate(providerId: String) {
        SpotlightState.aiModelCache = readCache().also { it.remove(providerId) }.toString()
    }

    // ---------- classement automatique : « lite / rapide » pour l'analyse, « plus costaud » pour la rédaction ----------
    private val BAD_MISTRAL = listOf("magistral", "codestral", "devstral", "voxtral", "pixtral", "ocr", "embed", "moderation", "saba", "mamba")

    /** Génération d'un id Gemini (« gemini-3.5-flash » -> 3.5) ; 0 si l'id n'en porte pas (alias « -latest », exp…). */
    private fun geminiGen(id: String): Double =
        Regex("^gemini-(\\d+(?:\\.\\d+)?)-").find(id)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

    private fun geminiStable(id: String) = !("preview" in id || "exp" in id || id.endsWith("-latest"))

    /** Plus récente génération parmi les lite/flash stables (≥ 3), si au moins deux générations existent ; sinon null. */
    private fun geminiNewestGen(models: List<AiModel>): Double? {
        val gens = models.map { it.id.lowercase() }
            .filter { it.startsWith("gemini-") && geminiStable(it) && ("lite" in it || "flash" in it) }
            .map { geminiGen(it) }.filter { it >= 3.0 }.distinct()
        return if (gens.size >= 2) gens.maxOrNull() else null
    }

    private fun score(provider: String, task: AiTask, m: AiModel, newestGen: Double? = null): Double? {
        val id = m.id.lowercase()
        val ver = Regex("(\\d+(?:\\.\\d+)?)").find(id.substringAfter('-'))?.value?.toDoubleOrNull() ?: 0.0
        val plan = task == AiTask.PLAN
        when (provider) {
            "gemini" -> {
                if (!id.startsWith("gemini-")) return null
                // Même ordre pour l'analyse et la rédaction (messages courts) : flash-lite puis flash STABLES
                // (génération ≥ 3, la plus ancienne d'abord) avant la toute dernière génération, les alias
                // « -latest », les previews/exp, les modèles 1.x/2.x et les « pro ».
                val gen = geminiGen(id)
                val lite = "lite" in id
                val flash = "flash" in id
                val tier = when {
                    !lite && !flash -> 1                                       // pro et autres
                    gen in 0.1..2.99 -> 2                                      // anciens (1.x / 2.x)
                    "preview" in id || "exp" in id -> 3                        // previews
                    id.endsWith("-latest") -> 4                                // alias : pointent vers le plus récent
                    newestGen != null && gen >= newestGen -> 5                 // toute dernière génération
                    lite -> 7
                    else -> 6
                }
                val liteFirst = if (tier <= 5 && lite) 100.0 else 0.0
                val age = if (tier == 2) gen * 5 else -gen * 5
                return tier * 1000.0 + liteFirst + age
            }
            "openai" -> {
                if (!openAiChatLike(id)) return null
                val nano = "nano" in id
                val tier = when {
                    isReasoning(id) -> -2                        // o1/o3/o4, reasoning, thinking : tout en bas
                    SMALL_WORDS.none { it in id } -> -1          // modèles pleins : coûteux ou lents
                    plan -> if (nano) 3 else 2                   // analyse : le plus petit d'abord
                    else -> if (nano) 2 else 3                   // rédaction : mini avant nano
                }
                val snapshot = Regex("-\\d{4}-\\d{2}-\\d{2}$").containsMatchIn(id)
                val alias = "latest" in id
                return tier * 1000.0 + ver * 10 - (if (snapshot) 50 else 0) - (if (alias) 500 else 0)
            }
            else -> {
                if (!m.tools || BAD_MISTRAL.any { id.contains(it) }) return null
                val tier = when {
                    "small" in id -> if (plan) 3 else 1
                    "ministral" in id -> if (plan) 2 else 0
                    "medium" in id -> if (plan) 1 else 3
                    "large" in id -> if (plan) 0 else 2
                    else -> return null
                }
                return tier * 1000.0 + (if (id.endsWith("-latest")) 10 else 0) + ver / 100
            }
        }
    }

    fun rank(providerId: String, task: AiTask, models: List<AiModel>): List<String> {
        val newest = if (providerId == "gemini") geminiNewestGen(models) else null
        return models.mapNotNull { m -> score(providerId, task, m, newest)?.let { m.id to it } }
            .sortedByDescending { it.second }.map { it.first }
    }

    /**
     * Modèle à utiliser : choix manuel, sinon meilleur de la liste (hors modèles en échec dans la session) ;
     * alt = le meilleur autre que `avoid` (le modèle que l'étape précédente vient d'essayer).
     * Chez Mistral la liste n'est jamais rechargée ici (voir NO_INLINE_REFRESH).
     */
    suspend fun resolve(p: AiProvider, task: AiTask, alt: Boolean, avoid: String? = null): String? {
        var models = cachedModels(p.id)
        if (models.isEmpty() && p.id !in NO_INLINE_REFRESH) {
            models = try { refreshModels(p) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
        }
        return pickModel(p.id, task, models, alt, avoid)
    }

    /** Modèle sans aucun appel réseau : choix manuel, sinon meilleur de la liste en cache, sinon dernier recours. */
    private fun resolveFast(p: AiProvider, task: AiTask, alt: Boolean = false, avoid: String? = null): String? =
        pickModel(p.id, task, cachedModels(p.id), alt, avoid)

    /** Modèle qui serait utilisé maintenant pour cette tâche (affichage dans les réglages, aucun appel réseau). */
    fun effectiveModel(p: AiProvider, task: AiTask): String? = resolveFast(p, task)

    // ---------- journal des appels IA (jamais de texte de message ni de clé) ----------
    private fun snippet(raw: String): String {
        val msg = runCatching {
            val j = JSONObject(raw)
            j.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: j.optString("message").takeIf { it.isNotBlank() }
        }.getOrNull() ?: raw
        // tout ce qui ressemble à une clé ou à un long identifiant est masqué
        return msg.replace('\n', ' ').replace(Regex("[A-Za-z0-9_\\-]{20,}"), "…").trim().take(60)
    }

    /** En-têtes de limite d'un 429 (retry-after, x-ratelimit-*, restes…), sans clé ni texte de message. */
    private fun limitInfo(e: AiHttpException): String =
        if (e.headers.isEmpty()) "aucun en-tête de limite dans la réponse"
        else e.headers.entries.joinToString(" ") { "${it.key}=${it.value}" }

    /** error.type / error.code du corps JSON (ex. insufficient_quota, rate_limit_exceeded) ; jamais le message. */
    private fun errorTypeCode(raw: String): String? = runCatching {
        val err = JSONObject(raw).optJSONObject("error") ?: return@runCatching null
        fun field(n: String) = err.opt(n)?.takeIf { it != JSONObject.NULL }?.toString()?.takeIf { it.isNotBlank() }
        listOfNotNull(field("type")?.let { "type=$it" }, field("code")?.let { "code=$it" })
            .joinToString(" ").replace(Regex("[A-Za-z0-9_\\-]{32,}"), "…").take(60).ifBlank { null }
    }.getOrNull()

    /** Limite nulle annoncée par les en-têtes d'un 429 (ex. x-ratelimit-limit-req-minute=0). */
    private fun hasZeroLimit(e: AiHttpException): Boolean = e.code == 429 && e.headers.any { (k, v) ->
        "limit" in k && "remaining" !in k && "reset" !in k && v.substringBefore(',').trim().toDoubleOrNull() == 0.0
    }

    /** Raison en quelques mots, pour l'écran de brouillon. */
    private fun shortReason(e: Exception): String = when {
        e is AiHttpException && e.code == 429 ->
            "HTTP 429" + (if (hasZeroLimit(e)) " (quota 0)" else "") + (errorTypeCode(e.body)?.let { " · $it" } ?: "")
        e is AiHttpException -> "HTTP ${e.code}"
        else -> describe(e).removePrefix("erreur : ")
    }

    /** Ce qu'on retient d'une erreur : 404 = liste à recharger, 429 = pause (sauf quota nul), quota nul, modèle en échec. */
    private fun noteError(p: AiProvider, model: String, e: Exception) {
        if (e !is AiHttpException) return
        if (e.code == 404) invalidate(p.id)
        if (e.code == 429) {
            if (hasZeroLimit(e)) { if (model != "—") zeroQuota.add("${p.id}|$model") }   // pas une question de cadence : pas de pause
            else PAUSE_ON_429[p.id]?.let { pausedUntil[p.id] = SystemClock.elapsedRealtime() + it }
        }
        if (model != "—" && (e.code == 503 || e.code == 429)) markFailed(p.id, model)
    }

    private fun describe(e: Exception): String =
        if (e is AiHttpException && e.code == 429) "HTTP 429 · ${limitInfo(e)}" + (errorTypeCode(e.body)?.let { " · $it" } ?: "")
        else if (e is AiHttpException) "HTTP ${e.code} : ${snippet(e.body)}"
        else "erreur : ${e.javaClass.simpleName} ${snippet(e.message.orEmpty())}".trim()

    private fun logCall(role: String, p: AiProvider, model: String, t0: Long, result: String, max: Int = 150) {
        ActionLog.add("ia", "$role · ${p.label} · $model · ${System.currentTimeMillis() - t0} ms · $result", max)
    }

    /**
     * Essaie chaque étape de la chaîne ; au-delà du délai (ou en cas d'erreur) on passe à la suivante.
     * L'annulation par l'utilisateur interrompt tout (la connexion HTTP est fermée).
     * Chaque tentative laisse une ligne au journal : rôle, fournisseur, modèle, durée, résultat.
     * quick = true : aucun appel réseau pour choisir le modèle (liste en cache) ; seul l'éventuel 2e modèle Gemini
     * est conservé avant l'IA suivante.
     * 429 : Mistral est mis en pause 30 s, sans réessai ; limite nulle : le couple fournisseur/modèle n'est plus appelé ;
     * 503 ou 429 : le modèle n'est plus choisi automatiquement pendant la session.
     * Si tout échoue : AiChainException, une ligne par tentative.
     */
    suspend fun <T : Any> withFallback(
        task: AiTask, onStatus: (String) -> Unit, timeoutMs: Long,
        quick: Boolean = false,
        block: suspend (AiProvider, String, String) -> T
    ): T {
        val steps = steps().let { all -> if (quick) all.filter { !it.alt || it.quickOk } else all }
        if (steps.isEmpty()) throw IllegalStateException("Aucune clé IA configurée (Réglages Spotlight > Fournisseurs IA).")
        val role = if (task == AiTask.PLAN) "analyse" else "rédaction"
        val attempts = ArrayList<String>()
        var tried = 0
        val usedModel = HashMap<String, String>()
        for (st in steps) {
            val p = st.provider
            val waitMs = pauseLeftMs(p.id)
            if (waitMs > 0) {   // pas de requête du tout vers un fournisseur en pause
                val s = (waitMs + 999) / 1000
                logCall(role, p, "—", System.currentTimeMillis(), "en pause encore $s s (limite 429), ignoré")
                val line = "${p.label} : en pause encore $s s (limite 429)"
                if (line !in attempts) attempts += line
                continue
            }
            onStatus(if (tried++ == 0) "Je réfléchis… · ${p.label}" else "Je réessaie avec ${p.label}…")
            var model = "—"
            var t0 = System.currentTimeMillis()
            try {
                val avoid = if (st.alt) usedModel[p.id] else null
                val m = if (quick) resolveFast(p, task, st.alt, avoid)
                else withTimeoutOrNull(6_000) { resolve(p, task, st.alt, avoid) }
                if (m == null) {
                    logCall(role, p, model, t0, "aucun modèle")
                    if (!st.alt) attempts += "${p.label} : aucun modèle utilisable (quota 0 ou liste vide)"
                    continue
                }
                model = m
                usedModel[p.id] = m
                t0 = System.currentTimeMillis()
                val r = withTimeoutOrNull(timeoutMs) { block(p, SpotlightAiApi.key(p.id), m) }
                if (r != null) { answered["${p.id}|$task"] = m; logCall(role, p, m, t0, "OK"); return r }
                logCall(role, p, m, t0, "timeout")
                forget(p.id, m)
                attempts += "${p.label} · $m : délai dépassé (${timeoutMs / 1000} s)"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logCall(role, p, model, t0, describe(e), if (e is AiHttpException && e.code == 429) 400 else 150)
                noteError(p, model, e)
                forget(p.id, model)
                attempts += (if (model == "—") p.label else "${p.label} · $model") + " : " + shortReason(e)
            }
        }
        throw if (attempts.isEmpty()) Exception("Aucune réponse de l'IA") else AiChainException(attempts)
    }

    // ---------- « Tester l'IA » : un court prompt à chaque modèle configuré ----------
    class AiTestResult(val provider: String, val role: String, val model: String, val ok: Boolean, val ms: Long, val detail: String)

    /** Un modèle par fournisseur et par tâche (même requête si le modèle est le même). Passe par le limiteur ; ne sort que sur annulation. */
    suspend fun testAll(): List<AiTestResult> {
        val out = ArrayList<AiTestResult>()
        for (p in AiProviders.all) {
            if (!hasKey(p)) continue
            val byModel = LinkedHashMap<String, MutableList<AiTask>>()
            for (t in listOf(AiTask.PLAN, AiTask.DRAFT)) resolveFast(p, t)?.let { byModel.getOrPut(it) { ArrayList() } += t }
            if (byModel.isEmpty()) { out += AiTestResult(p.label, "—", "—", false, 0, "aucun modèle utilisable (quota 0 ou liste vide)"); continue }
            for ((model, tasks) in byModel) {
                val role = if (tasks.size == 2) "analyse + rédaction" else if (tasks[0] == AiTask.PLAN) "analyse" else "rédaction"
                val wait = pauseLeftMs(p.id)
                if (wait > 0) { out += AiTestResult(p.label, role, model, false, 0, "en pause encore ${(wait + 999) / 1000} s"); continue }
                val t0 = System.currentTimeMillis()
                try {
                    val r = withTimeoutOrNull(15_000) { p.write(SpotlightAiApi.key(p.id), model, "Tu réponds en un mot.", "Réponds exactement : OK", 128) }
                    val ms = System.currentTimeMillis() - t0
                    out += if (r != null) AiTestResult(p.label, role, model, true, ms, "OK · « ${r.replace('\n', ' ').take(24)} »")
                    else AiTestResult(p.label, role, model, false, ms, "délai dépassé (15 s)")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    noteError(p, model, e)
                    out += AiTestResult(p.label, role, model, false, System.currentTimeMillis() - t0, shortReason(e))
                }
            }
        }
        return out
    }
}

// ============================================================
//  Pré-tri local : on n'envoie à l'IA que les actions des 3-4 connecteurs pertinents
// ============================================================
object ConnectorTriage {
    private val HINTS = mapOf(
        "timer" to listOf("minut", "timer", "reveil", "alarm", "chrono", "second", "heure", "dans", "compte"),
        "notes" to listOf("note", "memo", "liste", "noter", "retenir", "souvien"),
        "calendar" to listOf("rdv", "rendez", "agenda", "evenement", "reunion", "planning", "demain", "lundi", "mardi",
            "mercredi", "jeudi", "vendredi", "samedi", "dimanche", "semaine", "calendrier", "ajoute", "cree", "programme", "planifi"),
        "messages" to listOf("envoi", "dis", "dit", "message", "sms", "whatsapp", "mail", "ecri", "appel", "telephon",
            "contact", "maman", "papa", "repond", "previens", "demande", "rappelle")
    )

    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    fun pick(query: String, maxConnectors: Int = 4): List<ActionSpec> {
        val q = norm(query)
        val tokens = q.split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }
        val hasTime = Regex("\\b\\d{1,2}\\s*(h|:)\\s*\\d{0,2}|\\bdans\\s+\\d+").containsMatchIn(q)
        val active = ConnectorRegistry.active()

        val scored = active.mapNotNull { c ->
            val acts = if (c.id == "modules") {
                // modules : seulement ceux dont le nom, le préfixe ou la description ressemblent à la demande
                c.actions.filter { a -> val d = norm(a.description); tokens.any { it in d } }
            } else c.actions
            if (acts.isEmpty()) return@mapNotNull null
            val hay = norm(c.name + " " + c.description + " " + acts.joinToString(" ") { it.name + " " + it.description })
            val hints = HINTS[c.id].orEmpty()
            var s = tokens.count { t -> hints.any { h -> t.startsWith(h) || h.startsWith(t) } || hay.contains(t) }
            if (hasTime && (c.id == "calendar" || c.id == "timer")) s += 1
            if (c.id == "modules") s += 1
            Triple(c, acts, s)
        }
        val best = scored.filter { it.third > 0 }.sortedByDescending { it.third }.take(maxConnectors)
        // rien ne ressort : on envoie tout (au plus 5 connecteurs) plutôt que de bloquer l'utilisateur
        return (best.ifEmpty { scored }).flatMap { it.second }
    }
}