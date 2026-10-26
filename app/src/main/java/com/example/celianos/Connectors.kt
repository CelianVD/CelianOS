package com.example.celianos

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

// ============================================================
//  Connecteurs intégrés. Chacun est isolé et testable seul.
//  Contraintes respectées : aucun SMS envoyé automatiquement (smsto: prérempli),
//  aucune automatisation de WhatsApp par l'accessibilité (lien wa.me prérempli).
// ============================================================

private fun Context.start(i: Intent) = startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

// ---------------- Minuteur et réveil ----------------
object TimerConnector : Connector {
    override val id = "timer"
    override val name = "Minuteur et réveil"
    override val description = "« minuteur 10 min », « réveil 7h30 » (appli Horloge)"
    override val permissions = listOf("com.android.alarm.permission.SET_ALARM")
    override val actions = listOf(
        ActionSpec("set_timer", "Lance un minuteur", listOf(
            ParamSpec("seconds", "Durée en secondes", "integer"),
            ParamSpec("label", "Nom du minuteur", required = false)
        ), Risk.LOCAL),
        ActionSpec("set_alarm", "Crée un réveil à une heure précise", listOf(
            ParamSpec("hour", "Heure 0-23", "integer"),
            ParamSpec("minute", "Minute 0-59", "integer"),
            ParamSpec("label", "Nom du réveil", required = false)
        ), Risk.LOCAL)
    )

    override suspend fun execute(ctx: Context, action: String, args: JSONObject): ActionResult {
        return when (action) {
            "set_timer" -> {
                val s = args.optInt("seconds", 0)
                if (s <= 0) return ActionResult.Error("Durée invalide")
                ctx.start(Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, s)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, args.optString("label"))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                ActionResult.Done("Minuteur de ${CommandRouter.fmtDuration(s)} lancé")
            }
            else -> {
                val h = args.optInt("hour", -1)
                val m = args.optInt("minute", 0)
                if (h !in 0..23 || m !in 0..59) return ActionResult.Error("Heure invalide")
                ctx.start(Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, args.optString("label"))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                ActionResult.Done("Réveil créé à %02d:%02d".format(h, m))
            }
        }
    }
}

// ---------------- Notes rapides (le widget Notes) ----------------
object NotesConnector : Connector {
    override val id = "notes"
    override val name = "Notes rapides"
    override val description = "« note : acheter du pain » — lues dans le widget Notes"
    override val permissions = emptyList<String>()
    override val actions = listOf(
        ActionSpec("create_note", "Ajoute une note rapide", listOf(ParamSpec("text", "Texte de la note")), Risk.LOCAL),
        ActionSpec("list_notes", "Lit les dernières notes rapides", emptyList(), Risk.READ)
    )

    override suspend fun execute(ctx: Context, action: String, args: JSONObject): ActionResult {
        val notes = SpotlightState.notes.lines().filter { it.isNotBlank() }
        return if (action == "create_note") {
            val t = args.optString("text").trim().replace('\n', ' ')
            if (t.isEmpty()) return ActionResult.Error("Note vide")
            SpotlightState.notes = (listOf(t) + notes).take(30).joinToString("\n")
            ActionResult.Done("Note ajoutée")
        } else {
            ActionResult.Text(if (notes.isEmpty()) "Aucune note." else notes.take(5).joinToString("\n") { "• $it" })
        }
    }
}

// ---------------- Agenda ----------------
object CalendarConnector : Connector {
    override val id = "calendar"
    override val name = "Agenda"
    override val description = "Lire tes prochains événements, préparer un rendez-vous"
    override val permissions = listOf(Manifest.permission.READ_CALENDAR)
    override val actions = listOf(
        ActionSpec("list_events", "Liste les événements des 48 prochaines heures", emptyList(), Risk.READ),
        ActionSpec("create_event", "Prépare un événement dans l'agenda (l'utilisateur l'enregistre)", listOf(
            ParamSpec("title", "Titre"),
            ParamSpec("start", "Début, format ISO local ex. 2026-10-23T14:00"),
            ParamSpec("end", "Fin, même format", required = false),
            ParamSpec("location", "Lieu", required = false)
        ), Risk.LOCAL)
    )

    private fun millis(s: String): Long? =
        runCatching { LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()

    // la lecture exige READ_CALENDAR ; préparer un événement n'exige rien (l'appli Agenda s'ouvre)
    override fun permissionsFor(action: String) = if (action == "list_events") permissions else emptyList()

    override suspend fun execute(ctx: Context, action: String, args: JSONObject): ActionResult {
        if (action == "list_events") {
            val ev = SpotlightAgenda.upcoming(ctx)
            return ActionResult.Text(
                if (ev.isEmpty()) "Rien de prévu dans les 48 prochaines heures."
                else ev.joinToString("\n") { "• ${SpotlightAgenda.label(it)}  ${it.title}" }
            )
        }
        val start = millis(args.optString("start")) ?: return ActionResult.Error("Date de début non comprise")
        val end = millis(args.optString("end")) ?: (start + 3_600_000L)
        ctx.start(Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, args.optString("title"))
            .putExtra(CalendarContract.Events.EVENT_LOCATION, args.optString("location"))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end))
        return ActionResult.Done("Événement prêt dans l'agenda — enregistre-le")
    }
}

// ---------------- Messages, e-mails, appels (contacts Android) ----------------
object MessagesConnector : Connector {
    override val id = "messages"
    override val name = "Messages et contacts"
    override val description = "Brouillons SMS / WhatsApp / e-mail (jamais envoyés sans toi), appeler un contact"
    override val permissions = listOf(Manifest.permission.READ_CONTACTS)
    override val actions = listOf(
        ActionSpec("draft_message", "Prépare un message (SMS ou WhatsApp) pour un contact ; le texte sera rédigé ensuite", listOf(
            ParamSpec("contact", "Nom du contact tel que dit par l'utilisateur"),
            ParamSpec("instruction", "Ce que le message doit dire"),
            ParamSpec("channel", "sms ou whatsapp", required = false)
        ), Risk.VISIBLE),
        ActionSpec("draft_email", "Prépare un e-mail pour un contact ou une adresse", listOf(
            ParamSpec("to", "Nom du contact ou adresse e-mail"),
            ParamSpec("instruction", "Ce que l'e-mail doit dire"),
            ParamSpec("subject", "Objet", required = false)
        ), Risk.VISIBLE),
        ActionSpec("call_contact", "Ouvre le composeur avec le numéro d'un contact", listOf(
            ParamSpec("contact", "Nom du contact")
        ), Risk.LOCAL)
    )

    private const val DRAFT_SYSTEM =
        "Rédige uniquement le texte du message à envoyer, en français, ton naturel et bref, à la première " +
                "personne de l'utilisateur, sans guillemets ni introduction ni signature."

    // Message direct (un seul appel, sans outils) : l'utilisateur est l'auteur, le destinataire lit le message.
    private const val QUICK_SYSTEM =
        "Tu écris un message court pour l'utilisateur (l'auteur : « je » = lui). Réponds UNIQUEMENT par le texte " +
                "du message, adressé directement au destinataire (tu lui parles : tutoiement, sauf consigne contraire), " +
                "en français, ton naturel, 1 à 2 phrases. Transforme la consigne en message : « dis à X de venir » " +
                "devient « X, tu peux venir ? », « dis à X que j'arrive » devient « J'arrive ». " +
                "Pas de guillemets, pas d'introduction, pas de signature, pas d'explication."

    // relation connue seulement quand le nom du contact la dit (« Maman », « Papi »…) ; sinon on n'invente rien
    private val RELATIONS = mapOf(
        "maman" to "mère", "mama" to "mère", "mère" to "mère", "mere" to "mère",
        "papa" to "père", "père" to "père", "pere" to "père",
        "mamie" to "grand-mère", "mamy" to "grand-mère", "mémé" to "grand-mère", "meme" to "grand-mère",
        "papi" to "grand-père", "papy" to "grand-père", "pépé" to "grand-père", "pepe" to "grand-père",
        "frère" to "frère", "frere" to "frère", "sœur" to "sœur", "soeur" to "sœur",
        "tonton" to "oncle", "oncle" to "oncle", "tata" to "tante", "tante" to "tante",
        "chéri" to "partenaire", "chérie" to "partenaire", "cheri" to "partenaire", "cherie" to "partenaire"
    )

    private class Person(val name: String, val value: String)

    private fun find(ctx: Context, uri: Uri, valueCol: String, q: String): List<Person> {
        val out = LinkedHashMap<String, Person>()
        ctx.contentResolver.query(
            uri, arrayOf(ContactsContract.Contacts.DISPLAY_NAME, valueCol),
            "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?", arrayOf("%$q%"), null
        )?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(0) ?: continue
                val v = c.getString(1) ?: continue
                val k = n.lowercase() + "|" + if (v.contains('@')) v.lowercase() else v.filter { it.isDigit() }.takeLast(9)
                out.putIfAbsent(k, Person(n, v))
            }
        }
        val all = out.values.toList()
        val exact = all.filter { it.name.equals(q, ignoreCase = true) }
        return (if (exact.isNotEmpty()) exact else all).take(6)
    }

    private fun waNumber(v: String): String {
        val d = v.filter { it.isDigit() }
        return when {
            v.startsWith("+") -> d
            d.startsWith("00") -> d.drop(2)
            d.startsWith("0") -> {
                val cc = mapOf("FR" to "33", "BE" to "32", "CH" to "41", "LU" to "352", "GB" to "44", "CA" to "1")
                (cc[Locale.getDefault().country] ?: "") + d.drop(1)
            }
            else -> d
        }
    }

    private fun reasonOf(e: Exception): String = when {
        e is AiChainException -> e.message.orEmpty()   // une ligne par tentative
        e is AiHttpException -> "HTTP ${e.code}"
        e.message?.contains("n'a pas répondu") == true -> "délai dépassé"
        else -> (e.message ?: e.javaClass.simpleName).take(80)
    }

    /** E-mail : rédaction bloquante comme avant (analyse + repli complets), mais le résultat est maintenant signalé. */
    private suspend fun writeBody(name: String, instruction: String): DraftWrite {
        if (!SpotlightBrain.hasAnyKey()) return DraftWrite.Failed("aucune clé IA") // pas d'IA : on part de la consigne brute
        // seul le nom du contact et la consigne sont envoyés, jamais le numéro ni le répertoire
        return try {
            val (text, by) = SpotlightAiRouter.withFallback(AiTask.DRAFT, {}, SpotlightAiRouter.draftTimeoutMs()) { p, key, model ->
                p.write(key, model, DRAFT_SYSTEM, "Destinataire : $name\nConsigne : $instruction") to p.label
            }
            DraftWrite.Ok(text, by)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DraftWrite.Failed(reasonOf(e))
        }
    }

    /** Combien de contacts avec un numéro correspondent à ce nom (0 sans autorisation). Sert au routeur local. */
    suspend fun contactCount(ctx: Context, who: String): Int = withContext(Dispatchers.IO) {
        if (!SpotlightSearch.hasContactsPermission(ctx)) return@withContext 0
        runCatching {
            find(ctx, ContactsContract.CommonDataKinds.Phone.CONTENT_URI, ContactsContract.CommonDataKinds.Phone.NUMBER, who).size
        }.getOrDefault(0)
    }

    private fun cleanDraft(raw: String): String {
        var s = raw.trim().removePrefix("```").removeSuffix("```").trim()
        val quotes = "\"“”«»"
        while (s.length >= 2 && s.first() in quotes && s.last() in quotes) s = s.substring(1, s.length - 1).trim()
        return s
    }

    /**
     * UN SEUL appel de rédaction : prompt texte simple, sans outils, réflexion minimale, sortie plafonnée.
     * Délai 5 s puis IA de secours (si configurée) ; aucun réessai du même fournisseur.
     * Envoyés : prénom, relation (si le nom la dit), consigne, canal. Jamais le numéro ni le répertoire.
     */
    private suspend fun quickDraft(p: Person, sentence: String, instr: String, channel: String): DraftWrite {
        if (!SpotlightBrain.hasAnyKey()) {
            ActionLog.add("ia", "rédaction · aucun appel (aucune clé IA)", 150)
            return DraftWrite.Failed("aucune clé IA")
        }
        val digits = p.name.count { it.isDigit() } >= 6
        val first = if (digits) "le destinataire" else p.name.trim().substringBefore(' ')
        val relation = if (digits) null else p.name.lowercase().split(' ', '-', '\'', '’')
            .firstNotNullOfOrNull { RELATIONS[it] }
        val prompt = buildString {
            append("Canal : ").append(channel).append('\n')
            append("Destinataire : ").append(first).append('\n')
            if (relation != null) append("Relation : ").append(relation).append(" de l'utilisateur\n")
            append("Consigne de l'utilisateur : ").append(sentence.ifBlank { instr }).append('\n')
            append("Écris le message que l'utilisateur envoie à ").append(first).append('.')
        }
        return try {
            val (text, by) = SpotlightAiRouter.withFallback(
                AiTask.DRAFT, {}, SpotlightAiRouter.QUICK_DRAFT_TIMEOUT_MS, quick = true
            ) { pr, key, model ->
                pr.write(key, model, QUICK_SYSTEM, prompt, SpotlightAiRouter.QUICK_DRAFT_TOKENS) to pr.label
            }
            val clean = cleanDraft(text)
            if (clean.isEmpty()) DraftWrite.Failed("réponse vide") else DraftWrite.Ok(clean, by)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DraftWrite.Failed(reasonOf(e))
        }
    }

    private fun <T> pick(prompt: String, people: List<Person>, label: (Person) -> String, next: suspend (Person) -> ActionResult): ActionResult =
        if (people.size == 1) ActionResult.Error("interne") // jamais appelé avec un seul choix
        else ActionResult.Choice(prompt, people.map { p -> ChoiceOption(label(p)) { next(p) } })

    override suspend fun execute(ctx: Context, action: String, args: JSONObject): ActionResult {
        when (action) {
            "draft_message" -> {
                val who = args.optString("contact").trim()
                val instr = args.optString("instruction").trim()
                val sentence = args.optString("sentence").trim() // phrase d'origine si le routeur local l'a reconnue
                val wantWa = args.optString("channel").lowercase().contains("whats")
                val people = if (who.filter { it.isDigit() }.length >= 6) listOf(Person(who, who))
                else withContext(Dispatchers.IO) {
                    find(ctx, ContactsContract.CommonDataKinds.Phone.CONTENT_URI, ContactsContract.CommonDataKinds.Phone.NUMBER, who)
                }
                // le brouillon s'affiche tout de suite avec la consigne brute ; l'interface lance la rédaction IA ensuite
                suspend fun build(p: Person): ActionResult = ActionResult.Draft(
                    title = "Message à ${p.name}", to = "${p.name} · ${p.value}",
                    channels = listOf("SMS", "WhatsApp"), channel = if (wantWa) "WhatsApp" else "SMS",
                    subject = null, body = instr.replaceFirstChar { it.uppercase() },
                    writer = { quickDraft(p, sentence, instr, if (wantWa) "WhatsApp" else "SMS") }
                ) { ch, _, text ->
                    val i = if (ch == "WhatsApp") Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${waNumber(p.value)}?text=${Uri.encode(text)}"))
                    else Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(p.value)}")).putExtra("sms_body", text)
                    ctx.start(i)
                    ActionResult.Done("Brouillon ouvert dans $ch pour ${p.name} — valide l'envoi là-bas")
                }
                return when {
                    people.isEmpty() -> ActionResult.Error("Aucun contact « $who » avec un numéro")
                    people.size == 1 -> build(people[0])
                    else -> pick<Unit>("Quel contact ?", people, { "${it.name} · ${it.value}" }, ::build)
                }
            }
            "draft_email" -> {
                val to = args.optString("to").trim()
                val instr = args.optString("instruction").trim()
                val subj = args.optString("subject")
                val people = if (to.contains('@')) listOf(Person(to, to))
                else withContext(Dispatchers.IO) {
                    find(ctx, ContactsContract.CommonDataKinds.Email.CONTENT_URI, ContactsContract.CommonDataKinds.Email.ADDRESS, to)
                }
                suspend fun build(p: Person): ActionResult {
                    val w = writeBody(p.name, instr)
                    return ActionResult.Draft(
                        title = "E-mail à ${p.name}", to = p.value, channels = listOf("E-mail"), channel = "E-mail",
                        subject = subj, body = (w as? DraftWrite.Ok)?.text ?: instr, written = w
                    ) { _, s, text ->
                        ctx.start(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).putExtra(Intent.EXTRA_EMAIL, arrayOf(p.value))
                            .putExtra(Intent.EXTRA_SUBJECT, s ?: "").putExtra(Intent.EXTRA_TEXT, text))
                        ActionResult.Done("Brouillon ouvert dans ta messagerie — valide l'envoi là-bas")
                    }
                }
                return when {
                    people.isEmpty() -> ActionResult.Error("Aucun contact « $to » avec une adresse e-mail")
                    people.size == 1 -> build(people[0])
                    else -> pick<Unit>("Quel contact ?", people, { "${it.name} · ${it.value}" }, ::build)
                }
            }
            else -> { // call_contact : ouvre le composeur, ne lance JAMAIS l'appel
                val who = args.optString("contact").trim()
                val people = withContext(Dispatchers.IO) {
                    find(ctx, ContactsContract.CommonDataKinds.Phone.CONTENT_URI, ContactsContract.CommonDataKinds.Phone.NUMBER, who)
                }
                suspend fun dial(p: Person): ActionResult {
                    ctx.start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(p.value)}")))
                    return ActionResult.Done("Composeur ouvert pour ${p.name}")
                }
                return when {
                    people.isEmpty() -> ActionResult.Error("Aucun contact « $who »")
                    people.size == 1 -> dial(people[0])
                    else -> pick<Unit>("Quel contact ?", people, { "${it.name} · ${it.value}" }, ::dial)
                }
            }
        }
    }
}

// ============================================================
//  Modules utilisateur : liens à {q} et webhooks, sans écrire de code
// ============================================================
class UserModule(val id: String, val name: String, val prefix: String, val kind: String, val url: String, val desc: String)

object UserModules {
    /** Catalogue : un toucher dans les réglages ajoute le module. Ce sont des liens profonds (ouvrent l'appli si installée). */
    val PRESETS = listOf(
        UserModule("yt", "YouTube", "yt", "url", "https://www.youtube.com/results?search_query={q}", "Rechercher une vidéo sur YouTube"),
        UserModule("w", "Wikipédia", "w", "url", "https://fr.wikipedia.org/w/index.php?search={q}", "Rechercher sur Wikipédia"),
        UserModule("maps", "Google Maps", "maps", "url", "https://www.google.com/maps/search/?api=1&query={q}", "Chercher un lieu sur la carte"),
        UserModule("go", "Itinéraire", "go", "url", "https://www.google.com/maps/dir/?api=1&destination={q}", "Calculer un itinéraire vers une destination"),
        UserModule("osm", "OpenStreetMap", "osm", "url", "https://www.openstreetmap.org/search?query={q}", "Chercher sur OpenStreetMap"),
        UserModule("sp", "Spotify", "sp", "url", "https://open.spotify.com/search/{q}", "Chercher de la musique sur Spotify"),
        UserModule("dz", "Deezer", "dz", "url", "https://www.deezer.com/search/{q}", "Chercher de la musique sur Deezer"),
        UserModule("nf", "Netflix", "nf", "url", "https://www.netflix.com/search?q={q}", "Chercher un film ou une série sur Netflix"),
        UserModule("tw", "Twitch", "tw", "url", "https://www.twitch.tv/search?term={q}", "Chercher sur Twitch"),
        UserModule("tmdb", "TMDB", "tmdb", "url", "https://www.themoviedb.org/search?query={q}", "Fiche d'un film ou d'une série"),
        UserModule("rd", "Reddit", "rd", "url", "https://www.reddit.com/search/?q={q}", "Chercher sur Reddit"),
        UserModule("hn", "Hacker News", "hn", "url", "https://hn.algolia.com/?q={q}", "Chercher sur Hacker News"),
        UserModule("gh", "GitHub", "gh", "url", "https://github.com/search?q={q}", "Chercher sur GitHub"),
        UserModule("deepl", "DeepL", "tr", "url", "https://www.deepl.com/translator#auto/fr/{q}", "Traduire un texte avec DeepL"),
        UserModule("drive", "Google Drive", "drive", "url", "https://drive.google.com/drive/search?q={q}", "Chercher un fichier dans Drive"),
        UserModule("mail", "Gmail", "mail", "url", "https://mail.google.com/mail/u/0/#search/{q}", "Chercher dans Gmail"),
        UserModule("photos", "Google Photos", "ph", "url", "https://photos.google.com/search/{q}", "Chercher dans Google Photos")
    )

    private val DEFAULT_IDS = setOf("yt", "w", "maps")

    /** Seuls http(s) et spotify: sont acceptés : pas d'intent:, file:, content:… */
    fun safeUrl(u: String) = u.startsWith("https://") || u.startsWith("http://") || u.startsWith("spotify:")

    fun load(): List<UserModule> {
        val raw = SpotlightState.modules
        if (raw.isBlank()) return PRESETS.filter { it.id in DEFAULT_IDS }.also { save(it) }
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun save(list: List<UserModule>) {
        SpotlightState.modules = toJson(list).toString()
    }

    fun toJson(list: List<UserModule>): JSONArray = JSONArray().also { arr ->
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("prefix", it.prefix)
                .put("kind", it.kind).put("url", it.url).put("desc", it.desc))
        }
    }

    /** Lit un module (ex. collé par l'utilisateur) ; renvoie null s'il est invalide ou dangereux. */
    fun fromJson(o: JSONObject): UserModule? {
        val name = o.optString("name").trim()
        val url = o.optString("url").trim()
        if (name.isEmpty() || !url.contains("{q}") || !safeUrl(url)) return null
        val kind = if (o.optString("kind") == "webhook") "webhook" else "url"
        val id = o.optString("id").filter { it.isLetterOrDigit() }.ifEmpty {
            name.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "mod" }
        }
        return UserModule(id, name, o.optString("prefix").trim().lowercase().filter { !it.isWhitespace() }, kind, url, o.optString("desc").trim())
    }

    fun add(m: UserModule) = save(load().filter { it.id != m.id } + m)
    fun remove(id: String) = save(load().filter { it.id != id })
}

object ModulesConnector : Connector {
    override val id = "modules"
    override val name = "Mes modules"
    override val description = "Raccourcis (« yt chats ») et webhooks que tu ajoutes toi-même"
    override val permissions = emptyList<String>()
    override val actions: List<ActionSpec>
        get() = UserModules.load().map {
            ActionSpec(
                "module_${it.id}",
                (it.desc.ifBlank { "Ouvrir ${it.name} avec une recherche" }) + " (module « ${it.name} »)",
                listOf(ParamSpec("q", "Texte à rechercher ou à envoyer")),
                if (it.kind == "webhook") Risk.VISIBLE else Risk.LOCAL
            )
        }

    override suspend fun execute(ctx: Context, action: String, args: JSONObject): ActionResult {
        val m = UserModules.load().firstOrNull { "module_${it.id}" == action } ?: return ActionResult.Error("Module introuvable")
        val q = args.optString("q").trim()
        if (q.isEmpty()) return ActionResult.Error("Texte vide")
        val url = m.url.replace("{q}", Uri.encode(q))
        if (m.kind == "webhook") {
            return ActionResult.Confirm("Appeler le webhook « ${m.name} » avec « $q » ?", Risk.VISIBLE) {
                withContext(Dispatchers.IO) {
                    val c = URL(url).openConnection() as HttpURLConnection
                    try {
                        c.connectTimeout = 10_000; c.readTimeout = 10_000
                        val code = c.responseCode
                        if (code in 200..299) ActionResult.Done("${m.name} : appelé (code $code)")
                        else ActionResult.Error("${m.name} : erreur $code")
                    } finally { c.disconnect() }
                }
            }
        }
        ctx.start(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        return ActionResult.Done("${m.name} ouvert")
    }
}