package com.example.celianos

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate

// ============================================================
//  Carrousel de widgets : météo, batteries, citation, habitudes
// ============================================================

/** Widgets disponibles : id -> nom affiché dans les réglages. L'ordre/activation vient de SpotlightState.widgetList. */
val WIDGET_DEFS = listOf(
    "weather" to "Météo",
    "battery" to "Batteries",
    "habits" to "Habitudes",
    "quote" to "Citation du jour",
    "agenda" to "Agenda",
    "notes" to "Notes rapides"
)

@Composable
fun WidgetCarousel() {
    val ids = SpotlightState.widgetList.split(',').map { it.trim() }
        .filter { id -> WIDGET_DEFS.any { it.first == id } }.distinct()
    if (ids.isEmpty()) return
    val off = LocalSpotTheme.current.shadowOffset // ombre dure du Brutaliste
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp + off),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(ids) { id ->
            when (id) {
                "weather" -> WeatherWidget()
                "battery" -> BatteryWidget()
                "habits" -> HabitsWidget()
                "agenda" -> AgendaWidget()
                "notes" -> NotesWidget()
                else -> QuoteWidget()
            }
        }
    }
}

@Composable
private fun WidgetCard(width: Int, content: @Composable ColumnScope.() -> Unit) {
    val shape = spotShape(28)
    Column(
        modifier = Modifier
            .width(width.dp)
            .height(168.dp)
            .spotGlass(shape, 0.14f)
            .pointerInput(Unit) { detectTapGestures { } } // ne ferme pas Spotlight
            .padding(16.dp),
        content = content
    )
}

@Composable
private fun WLabel(text: String) = Text(
    text.uppercase(), color = SpotInk.copy(alpha = 0.55f),
    fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp
)

// ---------------- Météo (Open-Meteo, sans clé) ----------------

class WeatherData(val place: String, val temp: Int, val feels: Int, val min: Int, val max: Int, val code: Int)

object WeatherRepo {
    private val cache = HashMap<String, Pair<Long, WeatherData>>()

    fun cached(city: String) = cache[city]?.second

    suspend fun get(city: String): WeatherData? {
        cache[city]?.let { if (System.currentTimeMillis() - it.first < 15 * 60_000) return it.second }
        return withContext(Dispatchers.IO) {
            runCatching {
                val geo = JSONObject(
                    fetch("https://geocoding-api.open-meteo.com/v1/search?count=1&language=fr&name=" +
                            URLEncoder.encode(city, "UTF-8"))
                ).getJSONArray("results").getJSONObject(0)
                val lat = geo.getDouble("latitude")
                val lon = geo.getDouble("longitude")
                val j = JSONObject(
                    fetch("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                            "&current=temperature_2m,apparent_temperature,weather_code" +
                            "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1")
                )
                val cur = j.getJSONObject("current")
                val day = j.getJSONObject("daily")
                WeatherData(
                    geo.getString("name"),
                    Math.round(cur.getDouble("temperature_2m")).toInt(),
                    Math.round(cur.getDouble("apparent_temperature")).toInt(),
                    Math.round(day.getJSONArray("temperature_2m_min").getDouble(0)).toInt(),
                    Math.round(day.getJSONArray("temperature_2m_max").getDouble(0)).toInt(),
                    cur.getInt("weather_code")
                ).also { cache[city] = System.currentTimeMillis() to it }
            }.getOrNull()
        }
    }

    private fun fetch(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 6000
        return try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }

    fun describe(code: Int): Pair<String, String> = when (code) {
        0 -> "☀️" to "Dégagé"
        1, 2 -> "🌤️" to "Peu nuageux"
        3 -> "☁️" to "Couvert"
        45, 48 -> "🌫️" to "Brouillard"
        51, 53, 55, 56, 57 -> "🌦️" to "Bruine"
        61, 63, 65, 66, 67, 80, 81, 82 -> "🌧️" to "Pluie"
        71, 73, 75, 77, 85, 86 -> "❄️" to "Neige"
        95, 96, 99 -> "⛈️" to "Orage"
        else -> "🌡️" to "—"
    }
}

@Composable
private fun WeatherWidget() {
    val city = SpotlightState.city
    val w by produceState(WeatherRepo.cached(city), city) { value = WeatherRepo.get(city) ?: value }
    WidgetCard(170) {
        WLabel(w?.place ?: city)
        val data = w
        if (data == null) {
            Spacer(Modifier.weight(1f))
            Text("Chargement…", color = SpotInk.copy(alpha = 0.6f), fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
        } else {
            val (emoji, label) = WeatherRepo.describe(data.code)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Text("${data.temp}°", color = SpotInk, fontSize = 44.sp, fontWeight = FontWeight.Light)
                Spacer(Modifier.width(8.dp))
                Text(emoji, fontSize = 30.sp)
            }
            Text(label, color = SpotInk, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                "Min ${data.min}°  ·  Max ${data.max}°",
                color = SpotInk.copy(alpha = 0.6f), fontSize = 12.sp
            )
        }
    }
}

// ---------------- Batteries ----------------

class BatteryItem(val name: String, val level: Int, val charging: Boolean)

object BatteryReader {
    fun read(ctx: Context): List<BatteryItem> {
        val out = ArrayList<BatteryItem>()
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (i != null) {
            val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            if (lvl >= 0) out += BatteryItem(
                "Téléphone", lvl * 100 / scale,
                st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            )
        }
        // Appareils Bluetooth connectés (écouteurs, montre…) : API cachée, via réflexion, peut échouer
        val btOk = Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
        if (btOk) runCatching {
            val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
            adapter?.bondedDevices?.forEach { d ->
                val connected = BluetoothDevice::class.java.getMethod("isConnected").invoke(d) as Boolean
                if (!connected) return@forEach
                val b = BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(d) as Int
                if (b in 0..100) out += BatteryItem(d.name ?: "Bluetooth", b, false)
            }
        }
        return out
    }
}

@Composable
private fun BatteryWidget() {
    val ctx = LocalContext.current
    val list by produceState(emptyList<BatteryItem>()) {
        while (true) {
            value = withContext(Dispatchers.Default) { BatteryReader.read(ctx) }
            delay(15_000)
        }
    }
    WidgetCard(200) {
        WLabel("Batteries")
        Spacer(Modifier.height(8.dp))
        list.take(3).forEach { b ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(b.name, color = SpotInk, fontSize = 13.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("${b.level}%${if (b.charging) " ⚡" else ""}", color = SpotInk, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold)
            }
            LinearProgressIndicator(
                progress = { b.level / 100f },
                modifier = Modifier.fillMaxWidth().padding(top = 3.dp, bottom = 8.dp).height(5.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = when {
                    b.charging -> Color(0xFF30D158)
                    b.level <= 20 -> Color(0xFFFF453A)
                    else -> SpotInk
                },
                trackColor = SpotInk.copy(alpha = 0.18f)
            )
        }
    }
}

// ---------------- Habitudes ----------------

private fun habitNames() = SpotlightState.habits.split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(5)

private fun doneToday(): Set<Int> {
    val raw = SpotlightState.habitsDone
    val today = LocalDate.now().toString()
    if (!raw.startsWith("$today|")) return emptySet()
    return raw.substringAfter('|').split(',').mapNotNull { it.toIntOrNull() }.toSet()
}

@Composable
private fun HabitsWidget() {
    val names = habitNames()
    val raw = SpotlightState.habitsDone          // lu ici pour recomposer au clic
    val done = remember(raw, SpotlightState.habits) { doneToday() }
    WidgetCard(210) {
        Row {
            WLabel("Habitudes")
            Spacer(Modifier.weight(1f))
            Text("${done.count { it < names.size }}/${names.size}", color = SpotInk.copy(alpha = 0.7f), fontSize = 12.sp)
        }
        Spacer(Modifier.height(6.dp))
        if (names.isEmpty()) {
            Text("Ajoute des habitudes dans les réglages.", color = SpotInk.copy(alpha = 0.6f), fontSize = 13.sp)
        }
        names.forEachIndexed { idx, n ->
            val ok = idx in done
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                    val new = if (ok) done - idx else done + idx
                    SpotlightState.habitsDone = LocalDate.now().toString() + "|" + new.sorted().joinToString(",")
                }.padding(vertical = 3.dp)
            ) {
                Icon(
                    if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
                    tint = if (ok) Color(0xFF30D158) else SpotInk.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(n, color = SpotInk, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------------- Citation du jour ----------------

private val QUOTES = listOf(
    "Rien n'est impossible à qui sait attendre et agir." to "Proverbe",
    "Le secret pour avancer, c'est de commencer." to "Mark Twain",
    "La simplicité est la sophistication suprême." to "Léonard de Vinci",
    "Fais de ta vie un rêve, et d'un rêve une réalité." to "Antoine de Saint-Exupéry",
    "Ce qui ne me tue pas me rend plus fort." to "Nietzsche",
    "Le meilleur moyen de prédire l'avenir, c'est de le créer." to "Peter Drucker",
    "On ne voit bien qu'avec le cœur." to "Antoine de Saint-Exupéry",
    "Chaque jour est une page blanche." to "Proverbe",
    "La persévérance vient à bout de tout." to "Virgile",
    "Il faut toujours viser la lune." to "Oscar Wilde",
    "Rien de grand ne s'est accompli sans passion." to "Hegel",
    "Un voyage de mille lieues commence par un pas." to "Lao Tseu",
    "Fais-le avec passion ou ne le fais pas." to "Proverbe",
    "Le talent, c'est d'avoir envie de faire quelque chose." to "Jacques Brel",
    "Les petits ruisseaux font les grandes rivières." to "Proverbe"
)

@Composable
private fun QuoteWidget() {
    val (text, author) = QUOTES[LocalDate.now().dayOfYear % QUOTES.size]
    WidgetCard(260) {
        WLabel("Citation du jour")
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text("« $text »", color = SpotInk, fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 22.sp)
        }
        Text("— $author", color = SpotInk.copy(alpha = 0.6f), fontSize = 12.sp)
    }
}


// ---------------- Agenda (CalendarContract) ----------------

@Composable
private fun AgendaWidget() {
    val ctx = LocalContext.current
    val events by produceState(emptyList<AgendaEvent>()) { value = SpotlightAgenda.upcoming(ctx) }
    val allowed = SpotlightAgenda.hasPermission(ctx)
    WidgetCard(230) {
        WLabel("Agenda")
        Spacer(Modifier.height(8.dp))
        when {
            !allowed -> Text("Autorise l'agenda dans les réglages Spotlight.", color = SpotInk.copy(alpha = 0.6f), fontSize = 13.sp)
            events.isEmpty() -> Text("Rien de prévu dans les prochaines 48 h.", color = SpotInk.copy(alpha = 0.6f), fontSize = 13.sp)
            else -> events.take(4).forEach { e ->
                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(SpotlightAgenda.label(e), color = LocalSpotTheme.current.accent, fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.width(62.dp))
                    Text(e.title, color = SpotInk, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ---------------- Notes rapides ----------------

private fun noteList() = SpotlightState.notes.lines().filter { it.isNotBlank() }

@Composable
private fun NotesWidget() {
    val raw = SpotlightState.notes // lu pour recomposer à chaque changement
    val notes = remember(raw) { noteList() }
    var draft by remember { mutableStateOf("") }
    fun add() {
        val n = draft.trim().replace('\n', ' ')
        if (n.isEmpty()) return
        SpotlightState.notes = (listOf(n) + noteList()).take(30).joinToString("\n")
        draft = ""
    }
    WidgetCard(230) {
        WLabel("Notes rapides")
        Spacer(Modifier.height(6.dp))
        if (notes.isEmpty()) Text("Aucune note.", color = SpotInk.copy(alpha = 0.5f), fontSize = 12.sp)
        notes.take(2).forEachIndexed { idx, n ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(n, color = SpotInk, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.Close, "Supprimer", tint = SpotInk.copy(alpha = 0.5f),
                    modifier = Modifier.size(22.dp).clip(CircleShape).clickable {
                        SpotlightState.notes = noteList().filterIndexed { i, _ -> i != idx }.joinToString("\n")
                    }.padding(3.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.fillMaxWidth().height(38.dp).clip(RoundedCornerShape(19.dp))
                .background(SpotInk.copy(alpha = 0.14f)).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f)) {
                if (draft.isEmpty()) Text("Nouvelle note…", color = SpotInk.copy(alpha = 0.4f), fontSize = 13.sp)
                BasicTextField(
                    value = draft, onValueChange = { draft = it }, singleLine = true,
                    textStyle = TextStyle(color = SpotInk, fontSize = 13.sp),
                    cursorBrush = SolidColor(SpotInk),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Icon(Icons.Rounded.Add, "Ajouter", tint = SpotInk,
                modifier = Modifier.size(30.dp).clip(CircleShape).clickable { add() }.padding(4.dp))
        }
    }
}