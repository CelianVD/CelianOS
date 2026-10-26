package com.example.celianos

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri

// ============================================================
//  Assistants IA : détection des applis installées + envoi de la question
// ============================================================

/**
 * @param urlTemplate lien « question dans l'adresse » : si l'appli le prend en charge,
 *        la question part directement. null = pas de lien connu → partage Android.
 * @param webFallback site ouvert si l'appli ne réagit à rien.
 */
class AiAssistant(
    val id: String,
    val name: String,
    val pkg: String,
    val urlTemplate: String?,
    val webFallback: String
)

object SpotlightAi {

    // Noms de paquets et liens à vérifier : certains sont des estimations (surtout Lumo, Euria).
    val ALL = listOf(
        AiAssistant("gemini", "Gemini", "com.google.android.apps.bard", null, "https://gemini.google.com/app"),
        AiAssistant("chatgpt", "ChatGPT", "com.openai.chatgpt", "https://chatgpt.com/?q=", "https://chatgpt.com"),
        AiAssistant("perplexity", "Perplexity", "ai.perplexity.app.android", "https://www.perplexity.ai/search?q=", "https://www.perplexity.ai"),
        AiAssistant("mistral", "Le Chat (Mistral)", "ai.mistral.chat", null, "https://chat.mistral.ai"),
        AiAssistant("claude", "Claude", "com.anthropic.claude", "https://claude.ai/new?q=", "https://claude.ai"),
        AiAssistant("deepseek", "DeepSeek", "com.deepseek.chat", null, "https://chat.deepseek.com"),
        AiAssistant("lumo", "Lumo (Proton)", "me.proton.android.lumo", null, "https://lumo.proton.me"),
        AiAssistant("euria", "Euria (Infomaniak)", "com.infomaniak.euria", null, "https://euria.infomaniak.com"),
        AiAssistant("copilot", "Copilot", "com.microsoft.copilot", null, "https://copilot.microsoft.com"),
        AiAssistant("grok", "Grok", "ai.x.grok", "https://grok.com/?q=", "https://grok.com")
    )

    fun installed(ctx: Context): List<AiAssistant> =
        ALL.filter { ctx.packageManager.getLaunchIntentForPackage(it.pkg) != null }

    fun enabledIds(): Set<String> =
        SpotlightState.aiEnabled.split(',').map { it.trim() }.filter { it.isNotEmpty() }.takeLast(MAX_ACTIVE).toSet()

    const val MAX_ACTIVE = 2

    /** 2 IA maximum : en cocher une troisième retire la plus ancienne. */
    fun setEnabled(id: String, on: Boolean) {
        val rest = enabledIds() - id
        SpotlightState.aiEnabled = (if (on) rest + id else rest).toList().takeLast(MAX_ACTIVE).joinToString(",")
    }

    fun active(ctx: Context): List<AiAssistant> = installed(ctx).filter { it.id in enabledIds() }

    /** 1) lien direct dans l'appli (envoi automatique si supporté) 2) partage 3) copie + site web. */
    fun ask(ctx: Context, ai: AiAssistant, query: String) {
        val nt = Intent.FLAG_ACTIVITY_NEW_TASK
        ai.urlTemplate?.let { tpl ->
            try {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(tpl + Uri.encode(query))).setPackage(ai.pkg).addFlags(nt)
                )
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, query)
                    .setPackage(ai.pkg).addFlags(nt)
            )
            return
        } catch (_: ActivityNotFoundException) {
        }
        ctx.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("question", query))
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ai.webFallback)).addFlags(nt))
        } catch (_: ActivityNotFoundException) {
        }
    }
}