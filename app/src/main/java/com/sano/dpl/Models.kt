package com.sano.dpl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.IgnoreExtraProperties
import java.util.TreeMap
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

fun now(): Long = System.currentTimeMillis()

val ADMIN_EMAILS = listOf("albifahriefendi@gmail.com", "yapik12345ce@gmail.com")

class UnitDef(val id: String, val name: String, val gold: Long, val food: Long, val res: Long, val pow: Int, val speed: Int, val icon: Int)
class BDef(val id: String, val name: String, val land: Long, val gold: Long, val food: Long, val res: Long,
           val rg: Long, val rr: Long, val rf: Long, val icon: Int, val note: String)

val UNITS = listOf(
    UnitDef("soldier", "Soldier", 20, 20, 50, 1, 10, R.drawable.u_soldier),
    UnitDef("elite", "Elite Soldier", 50, 50, 100, 2, 12, R.drawable.u_elite),
    UnitDef("tank", "Tank", 100, 100, 100, 5, 15, R.drawable.u_tank),
    UnitDef("warship", "Warship", 125, 100, 150, 7, 20, R.drawable.u_warship),
    UnitDef("plane", "War Plane", 200, 200, 250, 10, 60, R.drawable.u_plane),
    UnitDef("robot", "Robot", 500, 300, 200, 15, 18, R.drawable.u_robot),
    UnitDef("super", "Super Robot", 1000, 500, 350, 25, 25, R.drawable.u_super)
)
val BLDS = listOf(
    BDef("city", "City", 30, 1500, 500, 500, 500, 0, 100, R.drawable.b_city, ""),
    BDef("village", "Village", 10, 300, 100, 100, 100, 50, 50, R.drawable.b_village, ""),
    BDef("farm", "Farm", 8, 200, 50, 100, 0, 0, 300, R.drawable.b_farm, ""),
    BDef("mine", "Mine", 8, 300, 100, 50, 0, 200, 0, R.drawable.b_mine, ""),
    BDef("bank", "Bank", 20, 3000, 300, 500, 400, 0, 0, R.drawable.b_bank, ""),
    BDef("fort", "Fortress", 15, 1500, 300, 800, 0, 0, 0, R.drawable.b_fort, "+20 defense, drops items"),
    BDef("watchtower", "Watchtower", 5, 500, 100, 200, 0, 0, 0, R.drawable.b_tower, "+2 vision (fog)"),
    BDef("radar", "Radar", 12, 2500, 300, 800, 0, 0, 0, R.drawable.b_radar, "+6 vision (fog)"),
    BDef("workshop", "Workshop", 15, 1500, 200, 600, 0, 0, 0, R.drawable.b_work, "Needed to craft items"),
    BDef("factory", "Factory", 25, 3000, 300, 1000, 0, 0, 0, R.drawable.b_factory, "+2 items/hour")
)
val CRAFT_UNITS = listOf(
    UnitDef("drone", "Drone", 0, 0, 0, 12, 70, R.drawable.u_plane),
    UnitDef("missile", "Missile", 0, 0, 0, 20, 90, R.drawable.ic_nuke),
    UnitDef("sub", "Submarine", 0, 0, 0, 18, 22, R.drawable.u_warship),
    UnitDef("railgun", "Railgun Tank", 0, 0, 0, 22, 16, R.drawable.u_tank),
    UnitDef("bomber", "Stealth Bomber", 0, 0, 0, 32, 80, R.drawable.u_plane),
    UnitDef("mech", "Titan Mech", 0, 0, 0, 45, 20, R.drawable.u_super),
    UnitDef("icbm", "ICBM", 0, 0, 0, 60, 120, R.drawable.ic_nuke)
)
val ALL_UNITS = UNITS + CRAFT_UNITS
fun unitDef(id: String): UnitDef? = ALL_UNITS.firstOrNull { it.id == id }

@IgnoreExtraProperties
class Player(
    var nick: String = "", var country: String = "", var color: Int = 0,
    var x: Int = 0, var y: Int = 0, var size: Int = 32,
    var gold: Long = 0, var res: Long = 0, var food: Long = 0,
    var units: HashMap<String, Long> = HashMap(),
    var blds: HashMap<String, Long> = HashMap(),
    var lastTick: Long = 0, var lastSeen: Long = 0, var online: Boolean = false, var daily: Long = 0,
    var cells: String = "", var land: Long = 1000,
    var sk: HashMap<String, Long> = HashMap(), var dm: Long = 0, var nukes: Long = 0,
    var gram: Long = 0, var ex: Int = -1, var ey: Int = -1, var elast: Long = 0,
    var inv: HashMap<String, Long> = HashMap(), var flag: String = "", var cprog: Long = 0,
    var job: String = "", var workAt: Long = 0, var auto: Boolean = false
) {
    private var segCache: ArrayList<IntArray>? = null
    private var boxCache: FloatArray? = null

    fun landTotal(): Long = land
    fun resetCache() { segCache = null; boxCache = null }
    fun u(id: String): Long = units[id] ?: 0L
    fun b(id: String): Long = blds[id] ?: 0L
    fun unitPow(id: String): Long { val d = unitDef(id) ?: return 0L; return d.pow + (sk[id] ?: 0L) }
    fun powOf(m: Map<String, Long>): Long { var s = 0L; for ((k, v) in m) s += v * unitPow(k); return s * (100 + dm) / 100 }
    fun power(): Long = powOf(units)
    fun landUsed(): Long { var s = 0L; for (d in BLDS) s += b(d.id) * d.land; return s }

    /** Potongan wilayah: [y, x1, x2] */
    fun segs(): List<IntArray> {
        var c = segCache
        if (c == null) {
            c = ArrayList()
            for (row in cells.split(";")) {
                if (row.isEmpty()) continue
                val p = row.split(":")
                val y = p[0].toInt()
                for (r in p[1].split(",")) { val q = r.split("-"); c.add(intArrayOf(y, q[0].toInt(), q[1].toInt())) }
            }
            segCache = c
        }
        return c
    }
    fun hit(wx: Int, wy: Int): Boolean { for (s in segs()) if (s[0] == wy && wx >= s[1] && wx <= s[2]) return true; return false }
    /** [minX, minY, maxX, maxY, centerX, centerY] dalam satuan sel */
    fun box(): FloatArray {
        var b = boxCache
        if (b == null) {
            var x0 = 9999; var y0 = 9999; var x1 = -1; var y1 = -1; var sx = 0.0; var sy = 0.0; var n = 0
            for (s in segs()) {
                x0 = min(x0, s[1]); x1 = maxOf(x1, s[2]); y0 = min(y0, s[0]); y1 = maxOf(y1, s[0])
                val len = s[2] - s[1] + 1; sx += (s[1] + s[2]) / 2.0 * len; sy += s[0].toDouble() * len; n += len
            }
            b = if (n == 0) floatArrayOf(0f, 0f, 1f, 1f, 0.5f, 0.5f)
            else floatArrayOf(x0.toFloat(), y0.toFloat(), x1 + 1f, y1 + 1f, (sx / n).toFloat() + 0.5f, (sy / n).toFloat() + 0.5f)
            boxCache = b
        }
        return b
    }
}

@IgnoreExtraProperties
class Shop(var seller: String = "", var sname: String = "", var type: String = "", var amt: Long = 0, var price: Long = 0)
@IgnoreExtraProperties
class Msg(var t: Long = 0, var role: String = "member", var nick: String = "", var country: String = "",
          var msg: String = "", var to: String = "", var from: String = "",
          var badge: String = "", var title: String = "", var cc: Int = 0)
@IgnoreExtraProperties
class News(var t: Long = 0, var msg: String = "")
@IgnoreExtraProperties
class Req(var type: String = "", var from: String = "", var fromName: String = "")
@IgnoreExtraProperties
class Idx(var name: String = "", var port: String = "", var type: String = "", var players: Long = 0, var owner: String = "")
@IgnoreExtraProperties
class Atk(var from: String = "", var to: String = "", var fromName: String = "", var toName: String = "",
          var state: String = "prep", var prepEnd: Long = 0, var travelEnd: Long = 0, var watchEnd: Long = 0,
          var units: HashMap<String, Long> = HashMap())
@IgnoreExtraProperties
class War(var a: String = "", var d: String = "", var aName: String = "", var dName: String = "",
          var aPow: Long = 0, var dPow: Long = 0, var aCommit: Long = 0, var dCommit: Long = 0,
          var aU: HashMap<String, Long> = HashMap(), var dU: HashMap<String, Long> = HashMap(),
          var last: Long = 0, var status: String = "active", var winner: String = "")
class Fx(val x: Float, val y: Float, val t: Long)

// ---------- data akun (global, bukan per server) ----------
class UserData {
    var uid = ""; var nick = ""; var pid = ""; var email = ""; var pgold = 0L; var role = "member"
    var title = ""; var cc = 0
    var titles = HashSet<String>(); var skins = HashSet<String>(); var colors = HashSet<String>()
    var equip = HashMap<String, String>()
    var vipUntil = 0L

    fun load(s: DataSnapshot) {
        nick = s.child("nick").getValue(String::class.java) ?: ""
        pid = s.child("pid").getValue(String::class.java) ?: ""
        email = s.child("email").getValue(String::class.java) ?: ""
        pgold = s.child("pgold").getValue(Long::class.javaObjectType) ?: 0L
        vipUntil = s.child("vipUntil").getValue(Long::class.javaObjectType) ?: 0L
        title = s.child("title").getValue(String::class.java) ?: ""
        cc = (s.child("cc").getValue(Long::class.javaObjectType) ?: 0L).toInt()
        titles = HashSet(); for (c in s.child("titles").children) c.key?.let { titles.add(it) }
        skins = HashSet(); for (c in s.child("skins").children) c.key?.let { skins.add(it) }
        colors = HashSet(); for (c in s.child("colors").children) c.key?.let { colors.add(it) }
        equip = HashMap()
        for (c in s.child("equip").children) { val k = c.key; val v = c.getValue(String::class.java); if (k != null && v != null) equip[k] = v }
    }
    fun badge(): String = when (role) { "admin" -> "✔DEV"; "mod" -> "MOD"; else -> "" }
    fun skinMap(): HashMap<String, Long> {
        val m = HashMap<String, Long>()
        for ((u, sid) in equip) { val i = itemById(sid); if (i != null) m[u] = i.a }
        return m
    }
    fun dmg(): Long { var d = 0L; for ((_, sid) in equip) d += itemById(sid)?.b ?: 0L; return min(20L, d) }
}

// ---------- gacha ----------
class Item(val id: String, val cat: String, val name: String, val rar: Int, val a: Long = 0, val b: Long = 0, val c: Int = 0)
val RARITY = listOf("Common", "Elite", "Epic", "Legend")
val RARCOLOR = intArrayOf(0xFFB0BEC5.toInt(), 0xFF4FC3F7.toInt(), 0xFFB388FF.toInt(), 0xFFFFD54F.toInt())
val ITEMS = listOf(
    Item("t_rookie", "title", "Rookie", 0), Item("t_cmd", "title", "Commander", 0),
    Item("t_dip", "title", "Diplomat", 1), Item("t_war", "title", "Warlord", 1),
    Item("t_emp", "title", "Emperor", 2), Item("t_over", "title", "Overlord", 2), Item("t_leg", "title", "Legend of Diploma", 3),
    Item("c_red", "color", "Red", 0, c = 0xFFEF5350.toInt()), Item("c_grn", "color", "Green", 0, c = 0xFF66BB6A.toInt()),
    Item("c_cyan", "color", "Cyan", 1, c = 0xFF26C6DA.toInt()), Item("c_pink", "color", "Pink", 1, c = 0xFFFF4FA3.toInt()),
    Item("c_org", "color", "Orange", 2, c = 0xFFFF9800.toInt()), Item("c_pur", "color", "Purple", 2, c = 0xFFB388FF.toInt()),
    Item("c_gold", "color", "Gold", 3, c = 0xFFFFD54F.toInt()),
    Item("s_desert", "skin", "Desert", 0, 1, 1), Item("s_xmas", "skin", "Christmas", 1, 2, 2),
    Item("s_neon", "skin", "Neon", 2, 3, 3), Item("s_dragon", "skin", "Dragon", 3, 5, 5),
    Item("g_5", "gold", "+5 Premium Gold", 0, 5), Item("g_10", "gold", "+10 Premium Gold", 1, 10),
    Item("g_25", "gold", "+25 Premium Gold", 2, 25), Item("g_100", "gold", "+100 Premium Gold", 3, 100)
)
fun itemById(id: String): Item? = ITEMS.firstOrNull { it.id == id }

val PACKS = listOf(Triple(10L, "5K", 5000L), Triple(50L, "7K", 7000L), Triple(100L, "12K", 12000L), Triple(150L, "15K", 15000L),
    Triple(200L, "20K", 20000L), Triple(300L, "27K", 27000L), Triple(1000L, "75K", 75000L), Triple(1000000L, "200K", 200000L))

// ---------- dunia ----------
object World {
    const val W = 360
    const val H = 180
    const val CELL = 10 // skala waktu tempuh
    const val TERR = 10 // sel awal = 1.000 km (1 sel = 100 km)
    val mask: BooleanArray by lazy { WorldData.buildMask(W, H) }

    fun land(x: Int, y: Int): Boolean = x in 0 until W && y in 0 until H && mask[y * W + x]

    /** Tumbuh dari titik awal ke sel daratan kosong terdekat sampai n sel. null kalau tidak muat. */
    fun grow(sx: Int, sy: Int, occ: BooleanArray, n: Int): ArrayList<Int>? {
        val seen = BooleanArray(W * H); val q = IntArray(W * H); var h = 0; var t = 0
        val out = ArrayList<Int>()
        q[t++] = sy * W + sx; seen[sy * W + sx] = true
        val dx = intArrayOf(1, -1, 0, 0); val dy = intArrayOf(0, 0, 1, -1)
        while (h < t && out.size < n) {
            val c = q[h++]; out.add(c)
            val cx = c % W; val cy = c / W
            for (k in 0 until 4) {
                val a = cx + dx[k]; val b = cy + dy[k]
                if (a < 0 || b < 0 || a >= W || b >= H) continue
                val i = b * W + a
                if (seen[i] || occ[i] || !land(a, b)) continue
                seen[i] = true; q[t++] = i
            }
        }
        return if (out.size >= n) out else null
    }

    fun encode(cells: List<Int>): String {
        val rows = TreeMap<Int, ArrayList<Int>>()
        for (i in cells) rows.getOrPut(i / W) { ArrayList() }.add(i % W)
        val sb = StringBuilder()
        for ((y, xs) in rows) {
            xs.sort()
            val parts = ArrayList<String>()
            var s = xs[0]; var p = xs[0]
            for (k in 1 until xs.size) { if (xs[k] == p + 1) p = xs[k] else { parts.add("$s-$p"); s = xs[k]; p = xs[k] } }
            parts.add("$s-$p")
            sb.append(y).append(':').append(parts.joinToString(",")).append(';')
        }
        return sb.toString()
    }

    fun bitmap(px: Int): Bitmap {
        val bm = Bitmap.createBitmap(W * px, H * px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bm); val p = Paint()
        c.drawColor(0xFF05080F.toInt())
        for (x in 0 until W) for (y in 0 until H) {
            if (land(x, y)) {
                p.color = if ((x * 7 + y * 13) % 5 == 0) 0xFF27352A.toInt() else 0xFF2B3A2E.toInt()
                c.drawRect((x * px).toFloat(), (y * px).toFloat(), ((x + 1) * px).toFloat(), ((y + 1) * px).toFloat(), p)
            }
        }
        return bm
    }
}

/** Harga emas per gram (uang server), sama untuk semua pemain, berubah tiap menit. */
fun goldPrice(min: Long): Long {
    val t = min.toDouble()
    return (1000.0 * (1 + 0.10 * sin(t / 37.0) + 0.06 * sin(t / 11.0 + 1) + 0.03 * sin(t / 3.7 + 2))).toLong()
}

// ---------- item (21 bahan x 10 jenis x 10 tingkat = 2100 item) ----------
val MATS = listOf("Iron", "Copper", "Bronze", "Steel", "Silver", "Gold", "Titanium", "Carbon", "Quartz", "Jade", "Obsidian",
    "Coral", "Amber", "Ruby", "Sapphire", "Emerald", "Mithril", "Cobalt", "Plasma", "Void", "Neon")
val FORMS = listOf("Ore", "Ingot", "Plate", "Gear", "Core", "Module", "Blade", "Armor", "Charm", "Relic")
val TIERS = listOf("Rough", "Basic", "Refined", "Polished", "Superior", "Masterwork", "Elite", "Epic", "Mythic", "Legendary")
val ITEM_COUNT = MATS.size * FORMS.size * TIERS.size
fun itemId(m: Int, f: Int, t: Int): String = "m${m}f${f}t$t"
fun parseItem(id: String): IntArray? {
    val r = Regex("m(\\d+)f(\\d+)t(\\d+)").matchEntire(id) ?: return null
    val m = r.groupValues[1].toInt(); val f = r.groupValues[2].toInt(); val t = r.groupValues[3].toInt()
    if (m >= MATS.size || f >= FORMS.size || t >= TIERS.size) return null
    return intArrayOf(m, f, t)
}
fun itemName(id: String): String { val p = parseItem(id) ?: return id; return "${TIERS[p[2]]} ${MATS[p[0]]} ${FORMS[p[1]]}" }
fun itemValue(id: String): Long { val p = parseItem(id) ?: return 0L; return 20L * (1L shl p[2]) * (1 + p[0] % 5) }

// ---------- filter kata kasar ----------
val BAD_WORDS = listOf("kontol", "anjing", "bangsat", "memek", "ngentot", "jancok", "jancuk", "bajingan", "asu", "babi", "tai",
    "brengsek", "goblok", "tolol", "pantek", "pepek", "kampret", "sialan", "pelacur", "bego", "fuck", "shit", "bitch",
    "asshole", "dick", "pussy", "bastard", "cunt", "nigger", "nigga", "fag", "whore", "slut")
private val BAD_RE = Regex("(?i)\\b(?:(?:" + BAD_WORDS.filter { it.length > 4 }.joinToString("|") + ")\\w*|(?:" +
        BAD_WORDS.filter { it.length <= 4 }.joinToString("|") + "))\\b")
fun findBad(t: String): String? = BAD_RE.find(t)?.value?.lowercase()
fun censor(t: String): String = BAD_RE.replace(t, "#####")
fun hasBad(t: String): Boolean = findBad(t) != null

// ---------- resep unit dari item ----------
class Recipe(val unitId: String, val items: List<Pair<String, Int>>, val gold: Long)
fun ri(m: String, f: String, t: Int): String = itemId(MATS.indexOf(m), FORMS.indexOf(f), t)
val RECIPES = listOf(
    Recipe("drone", listOf(Pair(ri("Carbon", "Module", 1), 3), Pair(ri("Copper", "Gear", 1), 2)), 1500L),
    Recipe("missile", listOf(Pair(ri("Steel", "Core", 2), 3), Pair(ri("Titanium", "Module", 2), 2)), 3000L),
    Recipe("sub", listOf(Pair(ri("Steel", "Plate", 3), 4), Pair(ri("Cobalt", "Core", 2), 2)), 4000L),
    Recipe("railgun", listOf(Pair(ri("Titanium", "Gear", 3), 3), Pair(ri("Plasma", "Core", 2), 2)), 5000L),
    Recipe("bomber", listOf(Pair(ri("Carbon", "Plate", 4), 3), Pair(ri("Neon", "Module", 3), 2), Pair(ri("Titanium", "Core", 3), 2)), 9000L),
    Recipe("mech", listOf(Pair(ri("Mithril", "Armor", 4), 3), Pair(ri("Cobalt", "Gear", 4), 3)), 15000L),
    Recipe("icbm", listOf(Pair(ri("Plasma", "Core", 5), 3), Pair(ri("Void", "Module", 4), 2), Pair(ri("Mithril", "Core", 4), 2)), 25000L)
)

// ---------- pabrik (kerja) ----------
@IgnoreExtraProperties
class Factory(var name: String = "", var owner: String = "", var level: Long = 1, var created: Long = 0) {
    fun maxEmp(): Long = 10 + 5 * (level - 1)
    fun salGold(): Long = 500 + 125 * (level - 1)
    fun salFood(): Long = 200 + 50 * (level - 1)
    fun salRes(): Long = 200 + 50 * (level - 1)
    fun refillSec(): Long = maxOf(300L, 600L - 30L * (level - 1))
}
val VIPS = listOf(Triple(7L, "10K", 10000L), Triple(30L, "30K", 30000L), Triple(365L, "250K", 250000L))
