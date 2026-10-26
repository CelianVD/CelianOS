package com.example.celianos

import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// ============================================================
//  Fournisseurs IA pour l'appel de fonctions : interface commune + 3 implémentations
//  (Gemini natif, Mistral et OpenAI via une seule implémentation « chat completions »).
//
//  Paramètres vérifiés dans la documentation officielle (oct. 2026) :
//   Gemini  : generationConfig.maxOutputTokens (compte les jetons de réflexion),
//             generationConfig.thinkingConfig.thinkingLevel (minimal|low|medium|high) pour Gemini 3+,
//             thinkingBudget (0 = désactivé) pour Gemini 2.5, GET /v1beta/models (pageSize, pageToken),
//             toolConfig.functionCallingConfig.mode. Gemini 3 : garder la température par défaut (1.0).
//   OpenAI  : max_completion_tokens (max_tokens obsolète), reasoning_effort (none|minimal|low|…),
//             les outils en Chat Completions exigent reasoning_effort=none sur les modèles récents,
//             GET /v1/models.
//   Mistral : max_tokens, tools, tool_choice (auto|any|none), GET /v1/models (capabilities.function_calling),
//             reasoning_effort none|high sur Mistral Small 4.
// ============================================================

class AiModel(val id: String, val tools: Boolean = true)

/** headers : seulement les en-têtes de limite (retry-after, ratelimit…), noms en minuscules, remplis hors 2xx. */
class HttpRes(val code: Int, val text: String, val headers: Map<String, String> = emptyMap())

class AiHttpException(val code: Int, val body: String, val headers: Map<String, String> = emptyMap()) :
    Exception("Erreur $code : ${body.replace('\n', ' ').take(160)}")

/**
 * Limiteur par fournisseur (une « voie » par serveur) : une seule requête à la fois, et au moins
 * MIN_GAP_MS entre le DÉBUT de deux requêtes vers le même serveur. Toutes les requêtes IA passent par
 * AiHttp.call, donc aussi les rechargements de liste de modèles et les réessais de paramètres.
 * L'attente est annulable (annulation de l'utilisateur ou délai de l'étape).
 */
object AiLimiter {
    const val MIN_GAP_MS = 1_100L

    private class Lane { val mutex = Mutex(); var lastStart = 0L }
    private val lanes = ConcurrentHashMap<String, Lane>()

    suspend fun <T> run(host: String, block: suspend () -> T): T {
        val lane = lanes.getOrPut(host) { Lane() }
        return lane.mutex.withLock {
            val wait = lane.lastStart + MIN_GAP_MS - SystemClock.elapsedRealtime()
            if (lane.lastStart != 0L && wait > 0) delay(wait)
            lane.lastStart = SystemClock.elapsedRealtime()
            block()
        }
    }
}

/** HTTP annulable : annuler la coroutine ferme la connexion immédiatement. */
object AiHttp {
    suspend fun call(
        method: String, url: String, headers: Map<String, String>, body: String?, readMs: Int = 30_000
    ): HttpRes = AiLimiter.run(runCatching { URL(url).host }.getOrDefault(url)) {
        send(method, url, headers, body, readMs)
    }

    /** En-têtes liés aux limites (jamais de clé ni de message) ; les longs identifiants sont masqués. */
    private fun limitHeaders(c: HttpURLConnection): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((k, v) in c.headerFields) {
            if (k == null) continue
            val name = k.lowercase()
            val keep = name == "retry-after" || "ratelimit" in name || "rate-limit" in name ||
                    "remaining" in name || "limit" in name || name.endsWith("-reset")
            if (keep) out[name] = v.joinToString(",").replace(Regex("[A-Za-z0-9_\\-]{20,}"), "…").take(40)
        }
        return out
    }

    private suspend fun send(
        method: String, url: String, headers: Map<String, String>, body: String?, readMs: Int
    ): HttpRes = suspendCancellableCoroutine { cont ->
        val c = URL(url).openConnection() as HttpURLConnection
        cont.invokeOnCancellation { runCatching { c.disconnect() } }
        thread(isDaemon = true) {
            try {
                c.requestMethod = method
                c.connectTimeout = 8_000
                c.readTimeout = readMs
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                if (body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = c.responseCode
                val hdr = if (code in 200..299) emptyMap() else limitHeaders(c)
                val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
                if (cont.isActive) cont.resume(HttpRes(code, txt, hdr))
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWithException(e)
            } finally {
                runCatching { c.disconnect() }
            }
        }
    }

    fun check(r: HttpRes) { if (r.code !in 200..299) throw AiHttpException(r.code, r.text, r.headers) }
}

interface AiProvider {
    val id: String
    val label: String

    /** Modèles réellement disponibles pour cette clé (chargés depuis l'API, jamais codés en dur). */
    suspend fun listModels(key: String): List<AiModel>

    /** Analyse d'une commande : renvoie des appels de fonctions (ou un texte si rien ne convient). */
    suspend fun plan(key: String, model: String, system: String, query: String, nowIso: String, tools: List<ActionSpec>): BrainPlan

    /** Rédaction de texte libre (brouillons). maxTokens plafonne la sortie (les jetons de réflexion comptent pour Gemini). */
    suspend fun write(key: String, model: String, system: String, prompt: String, maxTokens: Int = 1024): String
}

object AiProviders {
    val all: List<AiProvider> by lazy {
        listOf(GeminiProvider, ChatCompletionsProvider.MISTRAL, ChatCompletionsProvider.OPENAI)
    }
    fun byId(id: String): AiProvider? = all.firstOrNull { it.id == id }
}

/** Schéma JSON des paramètres d'une ActionSpec (types en majuscules pour Gemini, minuscules sinon). */
internal fun paramsSchema(a: ActionSpec, upper: Boolean): JSONObject? {
    if (a.params.isEmpty()) return null
    fun t(s: String) = if (upper) s.uppercase() else s.lowercase()
    val props = JSONObject()
    val req = JSONArray()
    a.params.forEach {
        props.put(it.name, JSONObject().put("type", t(it.type)).put("description", it.description))
        if (it.required) req.put(it.name)
    }
    return JSONObject().put("type", t("object")).put("properties", props).put("required", req)
}

private fun parseArgs(raw: String?): JSONObject =
    runCatching { JSONObject(if (raw.isNullOrBlank()) "{}" else raw) }.getOrDefault(JSONObject())

// ============================================================
//  Gemini (API native generateContent)
// ============================================================
object GeminiProvider : AiProvider {
    override val id = "gemini"
    override val label = "Gemini"
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"

    private val NOT_CHAT = listOf(
        "image", "tts", "embedding", "audio", "live", "robotics", "computer-use", "veo", "imagen",
        "lyria", "gemma", "learnlm", "deep-research", "antigravity", "omni", "aqa"
    )

    private fun legacy(model: String) = Regex("gemini-(1|2)[.-]").containsMatchIn(model.lowercase())

    /** Réflexion minimale selon la famille (voir le tableau thinkingLevel de la doc). */
    private fun thinking(model: String): JSONObject? {
        val m = model.lowercase()
        return when {
            Regex("gemini-2\\.5").containsMatchIn(m) -> if ("pro" in m) null else JSONObject().put("thinkingBudget", 0)
            legacy(m) -> null
            "lite" in m -> JSONObject().put("thinkingLevel", "minimal")
            // 3.8 Flash et Gemini 4 refusent « minimal » : « low » est le plus bas accepté partout
            else -> JSONObject().put("thinkingLevel", "low")
        }
    }

    private fun genConfig(model: String, maxTokens: Int, legacyTemp: Double): JSONObject {
        val g = JSONObject().put("maxOutputTokens", maxTokens)
        // Gemini 3+ : la doc déconseille de baisser la température (boucles, résultats dégradés)
        if (legacy(model)) g.put("temperature", legacyTemp)
        thinking(model)?.let { g.put("thinkingConfig", it) }
        return g
    }

    private suspend fun generate(key: String, model: String, body: JSONObject): JSONObject {
        val url = "$BASE/models/${model.removePrefix("models/")}:generateContent"
        val hdr = mapOf("x-goog-api-key" to key)
        var res = AiHttp.call("POST", url, hdr, body.toString())
        val gen = body.optJSONObject("generationConfig")
        // un niveau de réflexion refusé par ce modèle : on réessaie une fois sans le réglage
        if (res.code == 400 && gen?.has("thinkingConfig") == true && res.text.contains("thinking", ignoreCase = true)) {
            gen.remove("thinkingConfig")
            res = AiHttp.call("POST", url, hdr, body.toString())
        }
        AiHttp.check(res)
        return JSONObject(res.text)
    }

    private fun sys(text: String) = JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))
    private fun user(text: String) =
        JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text))))

    private fun partsOf(r: JSONObject): JSONArray {
        val cand = r.optJSONArray("candidates")?.optJSONObject(0) ?: throw Exception("Réponse vide de Gemini")
        return cand.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
    }

    override suspend fun listModels(key: String): List<AiModel> {
        val out = ArrayList<AiModel>()
        var token: String? = null
        var pages = 0
        do {
            val url = "$BASE/models?pageSize=200" + (token?.let { "&pageToken=${Uri.encode(it)}" } ?: "")
            val res = AiHttp.call("GET", url, mapOf("x-goog-api-key" to key), null)
            AiHttp.check(res)
            val j = JSONObject(res.text)
            val arr = j.optJSONArray("models") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val methods = o.optJSONArray("supportedGenerationMethods") ?: continue
                if ((0 until methods.length()).none { methods.getString(it) == "generateContent" }) continue
                val id = o.optString("name").removePrefix("models/")
                if (id.isNotEmpty() && NOT_CHAT.none { id.contains(it) }) out += AiModel(id)
            }
            token = j.optString("nextPageToken").ifEmpty { null }
            pages++
        } while (token != null && pages < 5)
        return out.distinctBy { it.id }
    }

    override suspend fun plan(key: String, model: String, system: String, query: String, nowIso: String, tools: List<ActionSpec>): BrainPlan {
        val decl = JSONArray()
        tools.forEach { a ->
            val o = JSONObject().put("name", a.name).put("description", a.description)
            paramsSchema(a, true)?.let { o.put("parameters", it) }
            decl.put(o)
        }
        val body = JSONObject()
            .put("systemInstruction", sys(system))
            .put("contents", user("Date et heure actuelles : $nowIso\n$query"))
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", decl)))
            .put("toolConfig", JSONObject().put("functionCallingConfig", JSONObject().put("mode", "AUTO")))
            .put("generationConfig", genConfig(model, 1024, 0.1))
        val r = generate(key, model, body)
        val parts = partsOf(r)
        val calls = ArrayList<Pair<String, JSONObject>>()
        val text = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            p.optJSONObject("functionCall")?.let { calls += it.getString("name") to (it.optJSONObject("args") ?: JSONObject()) }
            if (p.has("text") && !p.optBoolean("thought")) text.append(p.getString("text"))
        }
        if (calls.isEmpty() && text.isBlank()) throw Exception("Réponse vide ou tronquée de $model")
        return BrainPlan(calls, text.toString().trim().ifEmpty { null })
    }

    override suspend fun write(key: String, model: String, system: String, prompt: String, maxTokens: Int): String {
        val body = JSONObject()
            .put("systemInstruction", sys(system))
            .put("contents", user(prompt))
            .put("generationConfig", genConfig(model, maxTokens, 0.7))
        val parts = partsOf(generate(key, model, body))
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (p.has("text") && !p.optBoolean("thought")) sb.append(p.getString("text"))
        }
        return sb.toString().trim().ifEmpty { throw Exception("Réponse vide de $model") }
    }
}

// ============================================================
//  « Chat completions » : une seule implémentation pour Mistral et OpenAI
//  (adresse de base, clé et modèle configurables ; les paramètres refusés par un modèle
//   sont retirés automatiquement : on essaie plusieurs jeux de paramètres, du plus rapide au plus sûr)
// ============================================================

internal fun openAiChatLike(id: String): Boolean {
    val s = id.lowercase()
    val ok = s.startsWith("gpt-") || s.startsWith("chatgpt-") || Regex("^o\\d").containsMatchIn(s)
    val bad = listOf("audio", "realtime", "image", "transcribe", "tts", "search", "instruct", "embedding",
        "moderation", "whisper", "dall", "codex", "deep-research", "computer-use", "sora", "davinci")
    return ok && bad.none { s.contains(it) }
}

class ChatCompletionsProvider(
    override val id: String,
    override val label: String,
    private val base: String,
    private val mistral: Boolean
) : AiProvider {
    companion object {
        val MISTRAL = ChatCompletionsProvider("mistral", "Mistral", "https://api.mistral.ai/v1", true)
        val OPENAI = ChatCompletionsProvider("openai", "OpenAI", "https://api.openai.com/v1", false)
    }

    private fun headers(key: String) = mapOf("Authorization" to "Bearer $key")

    override suspend fun listModels(key: String): List<AiModel> {
        val res = AiHttp.call("GET", "$base/models", headers(key), null)
        AiHttp.check(res)
        val t = res.text.trim()
        val arr = if (t.startsWith("[")) JSONArray(t) else JSONObject(t).optJSONArray("data") ?: JSONArray()
        val out = ArrayList<AiModel>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val mid = o.optString("id")
            if (mid.isEmpty()) continue
            if (mistral) {
                val caps = o.optJSONObject("capabilities")
                if (caps != null && !caps.optBoolean("completion_chat", true)) continue
                if (o.optBoolean("archived", false)) continue
                out += AiModel(mid, caps?.optBoolean("function_calling", true) ?: true)
            } else if (openAiChatLike(mid)) out += AiModel(mid)
        }
        return out.distinctBy { it.id }
    }

    /** Jeux de paramètres à essayer dans l'ordre (sortie plafonnée, réflexion minimale, température basse si permise). */
    private fun variants(model: String, plan: Boolean, maxTokens: Int? = null): List<JSONObject> {
        val n = maxTokens ?: if (plan) 600 else 900
        val t = if (plan) 0.1 else 0.7
        val m = model.lowercase()
        return if (mistral) {
            val hybrid = "small" in m && ("latest" in m || "2603" in m || "-4" in m)
            listOfNotNull(
                if (hybrid) JSONObject().put("max_tokens", n).put("temperature", t).put("reasoning_effort", "none") else null,
                JSONObject().put("max_tokens", n).put("temperature", t),
                JSONObject().put("max_tokens", n * 2)
            )
        } else {
            val reasoning = "gpt-5" in m || "gpt-6" in m || Regex("^o\\d").containsMatchIn(m)
            if (reasoning) listOf(
                JSONObject().put("max_completion_tokens", n).put("reasoning_effort", "none"),
                JSONObject().put("max_completion_tokens", n).put("reasoning_effort", "minimal"),
                JSONObject().put("max_completion_tokens", n * 3)
            ) else listOf(
                JSONObject().put("max_completion_tokens", n).put("temperature", t),
                JSONObject().put("max_tokens", n).put("temperature", t),
                JSONObject().put("max_completion_tokens", n * 2)
            )
        }
    }

    /** Mistral : jeu de paramètres accepté par chaque modèle (en mémoire) pour ne pas redemander les refus 400/422. */
    private val variantMemo = ConcurrentHashMap<String, Int>()

    private suspend fun request(key: String, base0: JSONObject, vars: List<JSONObject>): JSONObject {
        var last: AiHttpException? = null
        val model = base0.optString("model")
        val start = if (mistral) (variantMemo[model] ?: 0).coerceIn(0, vars.lastIndex) else 0
        for (i in start..vars.lastIndex) {
            val v = vars[i]
            val body = JSONObject(base0.toString())
            v.keys().forEach { body.put(it, v.get(it)) }
            val res = AiHttp.call("POST", "$base/chat/completions", headers(key), body.toString())
            if (res.code in 200..299) {
                if (mistral) variantMemo[model] = i
                return JSONObject(res.text)
            }
            last = AiHttpException(res.code, res.text, res.headers)
            if (res.code != 400 && res.code != 422) throw last // seuls les paramètres refusés justifient un nouvel essai
        }
        throw last ?: Exception("Aucune réponse")
    }

    private fun msg(r: JSONObject): JSONObject =
        r.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: throw Exception("Réponse vide")

    private fun textOf(m: JSONObject): String {
        val c = m.opt("content")
        return when (c) {
            is String -> c
            is JSONArray -> (0 until c.length()).joinToString("") { c.optJSONObject(it)?.optString("text") ?: "" }
            else -> ""
        }.trim()
    }

    override suspend fun plan(key: String, model: String, system: String, query: String, nowIso: String, tools: List<ActionSpec>): BrainPlan {
        val toolsJson = JSONArray()
        tools.forEach { a ->
            toolsJson.put(JSONObject().put("type", "function").put("function",
                JSONObject().put("name", a.name).put("description", a.description)
                    .put("parameters", paramsSchema(a, false) ?: JSONObject().put("type", "object").put("properties", JSONObject()))))
        }
        val body = JSONObject().put("model", model)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", "Date et heure actuelles : $nowIso\n$query")))
            .put("tools", toolsJson).put("tool_choice", "auto")
        val m = msg(request(key, body, variants(model, true)))
        val calls = ArrayList<Pair<String, JSONObject>>()
        val tc = m.optJSONArray("tool_calls")
        if (tc != null) for (i in 0 until tc.length()) {
            val f = tc.getJSONObject(i).optJSONObject("function") ?: continue
            val args = f.opt("arguments")
            calls += f.getString("name") to (if (args is JSONObject) args else parseArgs(args as? String))
        }
        val text = textOf(m)
        if (calls.isEmpty() && text.isEmpty()) throw Exception("Réponse vide ou tronquée de $model")
        return BrainPlan(calls, text.ifEmpty { null })
    }

    override suspend fun write(key: String, model: String, system: String, prompt: String, maxTokens: Int): String {
        val body = JSONObject().put("model", model).put("messages", JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", prompt)))
        return textOf(msg(request(key, body, variants(model, false, maxTokens)))).ifEmpty { throw Exception("Réponse vide de $model") }
    }
}