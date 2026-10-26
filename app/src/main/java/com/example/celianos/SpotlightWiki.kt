package com.example.celianos

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// ============================================================
//  Wikipédia : « ce mot a-t-il une page ? » + ouverture dans l'appli Wikipédia
// ============================================================
object SpotlightWiki {

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 5000
        c.setRequestProperty("User-Agent", "CelianOS/1.0 (application Android personnelle)")
        return try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }

    /** Renvoie la page Wikipédia correspondant au mot (ou null). Appelé avec un délai, à part des autres résultats. */
    suspend fun lookup(raw: String): Hit.WikiHit? = withContext(Dispatchers.IO) {
        val q = raw.trim()
        if (q.length < 3 || q.length > 40 || q.split(' ').size > 4) return@withContext null
        val lang = SpotlightState.wikiLang
        runCatching {
            val os = JSONArray(
                get("https://$lang.wikipedia.org/w/api.php?action=opensearch&format=json&limit=1" +
                        "&redirects=resolve&search=" + URLEncoder.encode(q, "UTF-8"))
            )
            val title = os.getJSONArray(1).optString(0)
            if (title.isEmpty()) return@withContext null

            // Le titre trouvé doit vraiment ressembler à ce qui est tapé (évite les pages hors sujet)
            val a = SpotlightSearch.norm(q)
            val b = SpotlightSearch.norm(title)
            if (!(b.startsWith(a) || a.startsWith(b))) return@withContext null

            val sum = JSONObject(
                get("https://$lang.wikipedia.org/api/rest_v1/page/summary/" + Uri.encode(title.replace(' ', '_')))
            )
            val url = sum.getJSONObject("content_urls").getJSONObject("desktop").getString("page")
            val desc = if (sum.optString("type") == "disambiguation") "Page d'homonymie"
            else sum.optString("description").ifEmpty { sum.optString("extract").take(80) }
            Hit.WikiHit(sum.optString("title", title), desc, url)
        }.getOrNull()
    }

    /** Ouvre dans l'appli Wikipédia si elle est installée, sinon dans le navigateur. */
    fun open(ctx: Context, url: String) {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(Intent(view).setPackage("org.wikipedia"))
        } catch (_: ActivityNotFoundException) {
            try { ctx.startActivity(view) } catch (_: ActivityNotFoundException) {}
        }
    }
}