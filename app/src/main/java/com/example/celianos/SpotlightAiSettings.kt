package com.example.celianos

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Réglages des fournisseurs IA de l'assistant : clés (Keystore), IA principale / de secours, modèles chargés depuis l'API. */
@Composable
fun AiProvidersSettingsSection() {
    val s = SpotlightState
    var tick by remember { mutableIntStateOf(0) }
    val provs = AiProviders.all
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<SpotlightAiRouter.AiTestResult>?>(null) }

    SectionLabel("FOURNISSEURS IA (ASSISTANT)")
    // le modèle qui rédige en premier, mis en évidence
    SpotlightAiRouter.steps().firstOrNull()?.let { st ->
        LinkCard(
            "Rédaction des brouillons",
            "${st.provider.label} · ${SpotlightAiRouter.effectiveModel(st.provider, AiTask.DRAFT) ?: "aucun modèle utilisable"}",
            true
        ) { }
    }
    val main = provs.firstOrNull { it.id == s.aiMain } ?: provs.first()
    DropdownCard("IA principale", "Analyse des commandes et rédaction des brouillons", main.label, provs.map { it.label }) {
        s.aiMain = provs[it].id
        if (s.aiBackup == provs[it].id) s.aiBackup = ""
    }
    val backups = listOf<AiProvider?>(null) + provs.filter { it.id != s.aiMain }
    DropdownCard(
        "IA de secours", "Prend le relais si la principale est lente ou en erreur",
        backups.firstOrNull { it?.id == s.aiBackup }?.label ?: "Aucune", backups.map { it?.label ?: "Aucune" }
    ) { s.aiBackup = backups[it]?.id ?: "" }
    SliderCard(
        "Délai avant repli", "${s.aiTimeoutSec.toInt()} s — au-delà, on passe automatiquement à l'étape suivante",
        s.aiTimeoutSec, { s.aiTimeoutSec = it }, 4f..15f
    )
    GlassNote("Sans IA de secours, l'assistant retente avec le deuxième meilleur modèle du même fournisseur ; Gemini essaie toujours un second modèle avant de passer à l'IA suivante. L'analyse utilise un modèle « lite » (réflexion minimale, sortie courte) ; seule la rédaction des brouillons utilise un modèle plus costaud.")

    LinkCard(
        if (testing) "Test en cours…" else "Tester l'IA",
        results?.let { r -> "${r.count { it.ok }}/${r.size} modèle(s) répondent" } ?: "Envoie un court message à chaque modèle configuré",
        results?.all { it.ok } == true
    ) {
        if (!testing) {
            testing = true
            scope.launch {
                try { results = SpotlightAiRouter.testAll(); tick++ } finally { testing = false }
            }
        }
    }
    results?.forEach { r -> GlassNote("${if (r.ok) "✓" else "✗"} ${r.provider} · ${r.model} · ${r.role} · ${r.ms} ms · ${r.detail}") }

    provs.forEach { p -> ProviderBlock(p, tick) { tick++ } }
}

@Composable
private fun ProviderBlock(p: AiProvider, tick: Int, onChange: () -> Unit) {
    val scope = rememberCoroutineScope()
    var key by remember(p.id) { mutableStateOf(SpotlightAiApi.key(p.id)) }
    TextCard("Clé ${p.label}", "Chiffrée avec l'Android Keystore. Partagée avec les réponses rapides.", key, password = true) {
        key = it; SpotlightAiApi.setKey(p.id, it); onChange()
    }
    if (key.isBlank()) return

    val zero = SpotlightAiRouter.zeroQuotaModels(p.id)
    if (zero.isNotEmpty()) {
        GlassNote(
            "${p.label} : ce modèle n'est pas disponible avec ta clé (quota 0). Choisis un autre modèle ou vérifie ton plan.\n" +
                    "Modèle(s) concerné(s) : ${zero.joinToString(", ")}" +
                    (if (p.id == "mistral") "\nLes modèles disponibles pour ta clé sont listés sur https://admin.mistral.ai/plateforme/limits" else "")
        )
    }

    val models = remember(tick, p.id) { SpotlightAiRouter.cachedModels(p.id) }
    var loading by remember(p.id) { mutableStateOf(false) }
    var err by remember(p.id) { mutableStateOf<String?>(null) }
    LinkCard(
        if (loading) "Chargement…" else "Charger les modèles ${p.label}",
        err ?: if (models.isEmpty()) "Aucune liste en mémoire : sera chargée au premier appel" else "${models.size} modèle(s) disponibles",
        err == null
    ) {
        if (!loading) {
            loading = true; err = null
            scope.launch {
                try { SpotlightAiRouter.refreshModels(p); onChange() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { err = e.message ?: "Erreur" }
                finally { loading = false }
            }
        }
    }
    if (models.isEmpty()) return

    ModelPicker(p, AiTask.PLAN, "Modèle d'analyse (rapide)", "Lite / mini : lit la commande, sans réflexion", models, tick, onChange)
    ModelPicker(p, AiTask.DRAFT, "Modèle de rédaction", "Plus costaud : écrit les brouillons de messages", models, tick, onChange)
}

@Composable
private fun ModelPicker(p: AiProvider, task: AiTask, title: String, subtitle: String, models: List<AiModel>, tick: Int, onChange: () -> Unit) {
    val ranked = remember(tick, models) { SpotlightAiRouter.rank(p.id, task, models) }
    val rest = remember(tick, models) { models.map { it.id }.filter { it !in ranked }.sorted() }
    val options = remember(ranked, rest) { listOf("Auto") + (ranked + rest).take(40) }
    val chosen = SpotlightAiRouter.chosen(p.id, task)
    val label = chosen ?: "Auto : ${SpotlightAiRouter.effectiveModel(p, task) ?: ranked.firstOrNull() ?: "—"}"
    DropdownCard(title, subtitle, label, options) { i ->
        SpotlightAiRouter.setChosen(p.id, task, if (i == 0) "" else options[i])
        onChange()
    }
}