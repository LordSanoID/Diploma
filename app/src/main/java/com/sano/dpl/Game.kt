package com.sano.dpl

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

class Inc(val v: Long)

private val DX = intArrayOf(1, -1, 0, 0)
private val DY = intArrayOf(0, 0, 1, -1)

object Net {
    // Cek URL ini di Firebase Console > Realtime Database (bagian atas halaman)
    const val DB_URL = "https://diplomagame-ee7f8-default-rtdb.asia-southeast1.firebasedatabase.app"
    val db: DatabaseReference by lazy { FirebaseDatabase.getInstance(DB_URL).reference }
    fun uid(): String = FirebaseAuth.getInstance().currentUser?.uid ?: ""
    fun key(s: String): String = s.lowercase().replace(Regex("[^a-z0-9_-]"), "")
    fun pk(a: String, b: String): String = if (a < b) a + "_" + b else b + "_" + a
    var local = false
    fun inc(v: Long): Any = if (local) Inc(v) else ServerValue.increment(v)

    fun createServer(name: String, port: String, type: String, extra: Map<String, Any>, admin: Boolean, done: (String?, String?) -> Unit) {
        val uid = uid(); val key = key(name)
        if (key.isEmpty()) { done("Invalid name", null); return }
        fun create() {
            db.child("index/$key").once { x ->
                if (x.exists()) { done("Server name already used", null); return@once }
                val cfg = HashMap<String, Any>(extra)
                cfg["name"] = name; cfg["port"] = port; cfg["type"] = type; cfg["owner"] = uid
                val idx = hashMapOf<String, Any>("name" to name, "port" to port, "type" to type, "players" to 0L, "owner" to uid)
                val up = hashMapOf<String, Any>("servers/$key/cfg" to cfg, "index/$key" to idx)
                if (!admin) up["users/$uid/created"] = key
                db.updateChildren(up).addOnCompleteListener { t ->
                    if (t.isSuccessful) done(null, key) else done(t.exception?.message ?: "Failed", null)
                }
            }
        }
        if (admin) { create(); return }
        db.child("users/$uid/created").once { s ->
            if (!s.exists()) { create(); return@once }
            val old = s.getValue(String::class.java) ?: ""
            db.child("index/$old").once { o -> if (o.exists()) done("You can only create 1 server", null) else create() }
        }
    }
}

val PALETTE = intArrayOf(0xFFFF4FA3.toInt(), 0xFF3DDC84.toInt(), 0xFF4FC3F7.toInt(), 0xFFFFB300.toInt(), 0xFFB388FF.toInt(),
    0xFFFF7043.toInt(), 0xFF26C6DA.toInt(), 0xFFD4E157.toInt(), 0xFFEF5350.toInt(), 0xFF8D6E63.toInt())

open class Game(val key: String) {
    val ref: DatabaseReference = Net.db.child("servers").child(key)
    open val uid: String = Net.uid()
    var players = LinkedHashMap<String, Player>()
    var rel = HashMap<String, String>()
    var shop = LinkedHashMap<String, Shop>()
    var chat = ArrayList<Msg>()
    var news = ArrayList<News>()
    var atks = LinkedHashMap<String, Atk>()
    var wars = LinkedHashMap<String, War>()
    var reqs = LinkedHashMap<String, Req>()
    var mutes = HashMap<String, Long>()
    var fx = ArrayList<Fx>()
    var badge = ""
    var title = ""
    var cc = 0
    var evtT = 0L
    var evtMsg = ""
    var bans = HashSet<String>()
    var exists = false
    var name = key
    var owner = ""
    var gChat = true
    var nuke = false
    var offp = false
    var delay = 1L
    var g0 = 500L
    var r0 = 500L
    var f0 = 500L
    var fogOn = true
    var land0 = 100L
    var warDelay = true
    var maxP = 50
    var incX = 1L
    var sold0 = 0L
    var factories = LinkedHashMap<String, Factory>()
    var fwork = HashMap<String, HashMap<String, String>>()
    var vip = false
    var onUpdate: (() -> Unit)? = null
    var onKick: (() -> Unit)? = null
    val me: Player? get() = players[uid]

    private val lis = object : ValueEventListener {
        override fun onCancelled(e: DatabaseError) {}
        override fun onDataChange(s: DataSnapshot) {
            if (s.child("kick").hasChild(uid)) { ref.child("kick/$uid").removeValue(); onKick?.invoke(); return }
            val c = s.child("cfg")
            exists = c.exists()
            name = c.child("name").getValue(String::class.java) ?: key
            owner = c.child("owner").getValue(String::class.java) ?: ""
            gChat = c.child("chat").getValue(Boolean::class.javaObjectType) ?: true
            nuke = c.child("nuke").getValue(Boolean::class.javaObjectType) ?: false
            offp = c.child("off").getValue(Boolean::class.javaObjectType) ?: false
            delay = c.child("delay").getValue(Long::class.javaObjectType) ?: 1L
            g0 = c.child("gold0").getValue(Long::class.javaObjectType) ?: 500L
            r0 = c.child("res0").getValue(Long::class.javaObjectType) ?: 500L
            f0 = c.child("food0").getValue(Long::class.javaObjectType) ?: 500L
            fogOn = c.child("fog").getValue(Boolean::class.javaObjectType) ?: true
            land0 = c.child("land0").getValue(Long::class.javaObjectType) ?: 100L
            warDelay = c.child("wardelay").getValue(Boolean::class.javaObjectType) ?: true
            maxP = (c.child("maxp").getValue(Long::class.javaObjectType) ?: 50L).toInt()
            incX = c.child("incx").getValue(Long::class.javaObjectType) ?: 1L
            sold0 = c.child("sold0").getValue(Long::class.javaObjectType) ?: 0L
            players = LinkedHashMap()
            for (p in s.child("players").children) p.getValue(Player::class.java)?.let { players[p.key!!] = it }
            rel = HashMap()
            for (r in s.child("rel").children) rel[r.key!!] = r.getValue(String::class.java) ?: ""
            shop = LinkedHashMap()
            for (x in s.child("shop").children) x.getValue(Shop::class.java)?.let { shop[x.key!!] = it }
            chat = ArrayList()
            for (x in s.child("chat").children) x.getValue(Msg::class.java)?.let { chat.add(it) }
            if (chat.size > 150) chat = ArrayList(chat.takeLast(150))
            news = ArrayList()
            for (x in s.child("news").children) x.getValue(News::class.java)?.let { news.add(it) }
            if (news.size > 100) news = ArrayList(news.takeLast(100))
            atks = LinkedHashMap()
            for (x in s.child("attacks").children) x.getValue(Atk::class.java)?.let { atks[x.key!!] = it }
            wars = LinkedHashMap()
            for (x in s.child("wars").children) x.getValue(War::class.java)?.let { wars[x.key!!] = it }
            reqs = LinkedHashMap()
            for (x in s.child("req").child(uid).children) x.getValue(Req::class.java)?.let { reqs[x.key!!] = it }
            mutes = HashMap()
            for (x in s.child("mutes").children) mutes[x.key!!] = x.getValue(Long::class.javaObjectType) ?: 0L
            bans = HashSet()
            for (x in s.child("bans").children) bans.add(x.key!!)
            factories = LinkedHashMap(); fwork = HashMap()
            for (f in s.child("factories").children) {
                val fk = f.key ?: continue
                f.getValue(Factory::class.java)?.let { factories[fk] = it }
                val wm = HashMap<String, String>()
                for (w in f.child("workers").children) { val wk = w.key; if (wk != null) wm[wk] = w.getValue(String::class.java) ?: "?" }
                fwork[fk] = wm
            }
            fx = ArrayList()
            for (x in s.child("fx").children) {
                val fa = (x.child("x").value as? Number)?.toFloat()
                val fb = (x.child("y").value as? Number)?.toFloat()
                val ft = (x.child("t").value as? Number)?.toLong()
                if (fa != null && fb != null && ft != null) fx.add(Fx(fa, fb, ft))
            }
            evtT = s.child("evt/t").getValue(Long::class.javaObjectType) ?: 0L
            evtMsg = s.child("evt/msg").getValue(String::class.java) ?: ""
            onUpdate?.invoke()
        }
    }

    fun listen() { ref.addValueEventListener(lis) }
    open fun stop() { ref.removeEventListener(lis); if (me != null) ref.child("players/$uid").updateChildren(hashMapOf<String, Any>("online" to false, "lastSeen" to now())) }
    open fun goOnline() {
        if (me == null) return
        val m = ref.child("players/$uid")
        m.updateChildren(hashMapOf<String, Any>("online" to true, "lastSeen" to now()))
        m.onDisconnect().updateChildren(hashMapOf<String, Any>("online" to false, "lastSeen" to ServerValue.TIMESTAMP))
    }
    open fun upd(m: Map<String, Any>) { ref.child("players/$uid").updateChildren(m) }
    fun isAdmin(): Boolean = uid == owner

    fun createPlayer(nick: String, country: String, flag: String, done: (Boolean) -> Unit) {
        val occ = BooleanArray(World.W * World.H)
        for (o in players.values) for (sg in o.segs()) for (x in sg[1]..sg[2]) occ[sg[0] * World.W + x] = true
        val rnd = java.util.Random()
        var cells: ArrayList<Int>? = null
        for (i in 0 until 500) {
            val sx = rnd.nextInt(World.W); val sy = rnd.nextInt(World.H)
            if (occ[sy * World.W + sx] || !World.land(sx, sy)) continue
            val r = World.grow(sx, sy, occ, maxOf(1, (land0 / 100).toInt()))
            if (r != null) { cells = r; break }
        }
        if (cells == null) { done(false); return }
        var ax = 0.0; var ay = 0.0
        for (c in cells) { ax += c % World.W; ay += c / World.W }
        val p = Player(nick = nick, country = country, color = PALETTE[players.size % PALETTE.size],
            x = (ax / cells.size).toInt(), y = (ay / cells.size).toInt(), size = 32, gold = g0, res = r0, food = f0,
            units = if (sold0 > 0L) hashMapOf("soldier" to sold0) else HashMap(),
            lastTick = now(), lastSeen = now(), online = true, cells = World.encode(cells), land = cells.size * 100L, flag = flag)
        ref.child("players/$uid").setValue(p).addOnCompleteListener { t ->
            if (t.isSuccessful) {
                Net.db.child("index/$key/players").setValue(ServerValue.increment(1))
                news("$country has been founded by $nick")
                goOnline()
            }
            done(t.isSuccessful)
        }
    }

    // ---------- ekonomi ----------
    fun tick() {
        val m = me ?: return
        var dt = now() - m.lastTick
        if (dt < 30000) return
        if (dt > 259200000L) dt = 259200000L
        var g = 0.0; var r = 0.0; var f = 0.0
        for (b in BLDS) { val n = m.b(b.id); g += n * b.rg; r += n * b.rr; f += n * b.rf }
        val k = dt / 3600000.0 * incX
        val up = HashMap<String, Any>()
        up["gold"] = Net.inc((g * k).roundToLong()); up["res"] = Net.inc((r * k).roundToLong())
        up["food"] = Net.inc((f * k).roundToLong()); up["lastTick"] = now()
        val rnd = java.util.Random(); val got = HashMap<String, Long>()
        fun drop(rate: Double, forms: IntArray) {
            var c = (rate * k + rnd.nextDouble()).toInt()
            while (c-- > 0) {
                val id = itemId(minOf(rnd.nextInt(MATS.size), rnd.nextInt(MATS.size)), forms[rnd.nextInt(forms.size)], 0)
                got[id] = (got[id] ?: 0L) + 1L
            }
        }
        drop(m.b("mine") * 1.0, intArrayOf(0, 1)); drop(m.b("factory") * 2.0, intArrayOf(2, 3, 4, 5, 6)); drop(m.b("fort") * 0.5, intArrayOf(7, 8, 9))
        for ((id, c) in got) up["inv/$id"] = Net.inc(c)
        upd(up)
    }

    fun build(b: BDef): String? {
        val m = me ?: return "Not ready"
        if (m.landTotal() - m.landUsed() < b.land) return "Not enough land"
        if (m.gold < b.gold || m.food < b.food || m.res < b.res) return "Not enough resources"
        upd(hashMapOf("gold" to Net.inc(-b.gold), "food" to Net.inc(-b.food), "res" to Net.inc(-b.res), "blds/${b.id}" to Net.inc(1)))
        Snd.play("build"); return null
    }

    fun train(u: UnitDef, n: Long): String? {
        val m = me ?: return "Not ready"
        if (n <= 0) return "Invalid amount"
        if (m.gold < u.gold * n || m.food < u.food * n || m.res < u.res * n) return "Not enough resources"
        upd(hashMapOf("gold" to Net.inc(-u.gold * n), "food" to Net.inc(-u.food * n), "res" to Net.inc(-u.res * n), "units/${u.id}" to Net.inc(n)))
        Snd.play("build"); return null
    }

    private fun itemPath(type: String): String = when (type) { "food" -> "food"; "res" -> "res"; else -> "units/$type" }
    private fun have(m: Player, type: String): Long = when (type) { "food" -> m.food; "res" -> m.res; else -> m.u(type) }

    fun sell(type: String, amt: Long, price: Long): String? {
        val m = me ?: return "Not ready"
        if (amt <= 0 || price <= 0) return "Invalid"
        if (have(m, type) < amt) return "Not enough"
        upd(hashMapOf(itemPath(type) to Net.inc(-amt)))
        ref.child("shop").push().setValue(Shop(uid, m.nick, type, amt, price)); return null
    }

    fun buy(id: String): String? {
        val s = shop[id] ?: return "Item gone"
        val m = me ?: return "Not ready"
        if (s.seller == uid) return "Your own item"
        if (m.gold < s.price) return "Not enough gold"
        ref.child("shop/$id").runTransaction(object : Transaction.Handler {
            override fun doTransaction(d: MutableData): Transaction.Result {
                if (d.getValue(Shop::class.java) == null) return Transaction.abort()
                d.setValue(null); return Transaction.success(d)
            }
            override fun onComplete(e: DatabaseError?, ok: Boolean, sn: DataSnapshot?) {
                if (ok) {
                    upd(hashMapOf("gold" to Net.inc(-s.price), itemPath(s.type) to Net.inc(s.amt)))
                    ref.child("players/${s.seller}/gold").setValue(ServerValue.increment(s.price))
                    Snd.play("build")
                }
            }
        }); return null
    }

    fun cancelSale(id: String) {
        val s = shop[id] ?: return
        if (s.seller != uid) return
        ref.child("shop/$id").removeValue()
        upd(hashMapOf(itemPath(s.type) to Net.inc(s.amt)))
    }

    private val val_ = mapOf("gold" to 1.0, "food" to 0.8, "res" to 1.25)
    fun rate(from: String, to: String, amt: Long): Long = (amt * val_[from]!! / val_[to]!! * 0.9).toLong()
    fun trade(from: String, to: String, amt: Long): String? {
        val m = me ?: return "Not ready"
        val h = when (from) { "gold" -> m.gold; "food" -> m.food; else -> m.res }
        if (amt <= 0 || h < amt) return "Not enough $from"
        upd(hashMapOf(from to Net.inc(-amt), to to Net.inc(rate(from, to, amt))))
        Snd.play("build"); return null
    }

    fun daily(): String? {
        val m = me ?: return "Not ready"
        if (now() - m.daily < 86400000L) return "Come back in " + ((86400000L - (now() - m.daily)) / 3600000L + 1) + "h"
        upd(hashMapOf("gold" to Net.inc(1000), "food" to Net.inc(200), "res" to Net.inc(200), "daily" to now()))
        return null
    }

    // ---------- diplomasi ----------
    fun relOf(u: String): String = rel[Net.pk(uid, u)] ?: ""
    fun setRel(u: String, v: String) {
        val r = ref.child("rel/" + Net.pk(uid, u))
        if (v.isEmpty()) r.removeValue() else r.setValue(v)
    }
    open fun news(msg: String) { ref.child("news").push().setValue(News(now(), msg)) }
    fun cn(u: String): String = players[u]?.country ?: "?"
    fun sendReq(to: String, type: String) { ref.child("req/$to").push().setValue(Req(type, uid, me?.nick ?: "")) }
    fun answer(id: String, r: Req, ok: Boolean) {
        ref.child("req/$uid/$id").removeValue()
        if (!ok) return
        when (r.type) {
            "ally" -> { setRel(r.from, "ally"); news("${cn(uid)} and ${cn(r.from)} formed an alliance") }
            "pact" -> { setRel(r.from, "peace"); news("${cn(uid)} and ${cn(r.from)} signed a peace treaty") }
            "peace" -> { setRel(r.from, ""); news("${cn(uid)} and ${cn(r.from)} made peace"); peaceWars(r.from) }
        }
    }
    fun declareWar(u: String): String? {
        val r = relOf(u)
        if (r == "peace" || r == "ally") return "Break the treaty first"
        if (r == "war") return "Already at war"
        setRel(u, "war"); news("${cn(uid)} declared war on ${cn(u)}"); Snd.play("war"); return null
    }
    fun breakPact(u: String): String? {
        val r = relOf(u)
        if (r != "peace" && r != "ally") return "No treaty to break"
        setRel(u, ""); news("${cn(uid)} broke the treaty with ${cn(u)}"); return null
    }
    fun give(to: String, kind: String, amt: Long): String? {
        val m = me ?: return "Not ready"
        val h = when (kind) { "gold" -> m.gold; "food" -> m.food; else -> m.res }
        if (amt <= 0 || h < amt) return "Not enough $kind"
        upd(hashMapOf(kind to Net.inc(-amt)))
        ref.child("players/$to/$kind").setValue(ServerValue.increment(amt)); return null
    }

    // ---------- perang ----------
    fun prot(p: Player?): Boolean {
        if (p == null || !offp || p.online) return false
        val off = now() - p.lastSeen
        return off >= delay * 60000L && off < 3 * 86400000L // hancur kalau offline 3 hari
    }

    fun launch(to: String, pct: Int): String? {
        val m = me ?: return "Not ready"
        val t = players[to] ?: return "Target not found"
        if (relOf(to) != "war") return "Not at war with them"
        if (prot(t)) return "Target has offline protection"
        if (pct < 1 || pct > 100) return "Percent 1-100"
        val send = HashMap<String, Long>(); var slow = 999
        for (u in ALL_UNITS) { val n = m.u(u.id) * pct / 100; if (n > 0) { send[u.id] = n; slow = min(slow, u.speed) } }
        if (send.isEmpty()) return "No units to send"
        val dist = hypot((m.x - t.x).toDouble(), (m.y - t.y).toDouble()) * World.CELL
        val trav = max(1.0, ceil(dist / slow)).toLong()
        val up = HashMap<String, Any>()
        for ((k, v) in send) up["units/$k"] = Net.inc(-v)
        upd(up)
        val n = now(); val prep = n + (if (warDelay) 5 * 60000L else 0L); val tr = prep + trav * 60000L
        val watch = tr + (if (warDelay) (2 + java.util.Random().nextInt(6)) * 60000L else 0L)
        ref.child("attacks").push().setValue(Atk(uid, to, m.country, t.country, "prep", prep, tr, watch, send))
        news("${m.country} is sending ${m.powOf(send)} power to ${t.country} (arrival in ~${5 + trav} min)")
        return null
    }

    fun cancelAttack(id: String) {
        val a = atks[id] ?: return
        if (a.from != uid) return
        val f = when (a.state) { "prep" -> 1.0; "travel" -> 0.5; else -> -1.0 }
        if (f < 0) return
        val up = HashMap<String, Any>()
        for ((k, v) in a.units) up["units/$k"] = Net.inc((v * f).toLong())
        upd(up); ref.child("attacks/$id").removeValue()
    }

    private fun trans(id: String, from: String, to: String, then: (() -> Unit)?) {
        ref.child("attacks/$id/state").runTransaction(object : Transaction.Handler {
            override fun doTransaction(d: MutableData): Transaction.Result {
                if (d.getValue(String::class.java) == from) { d.setValue(to); return Transaction.success(d) }
                return Transaction.abort()
            }
            override fun onComplete(e: DatabaseError?, ok: Boolean, s: DataSnapshot?) { if (ok) then?.invoke() }
        })
    }

    open fun pump() {
        val t = now()
        for ((id, a) in atks.toList()) {
            when (a.state) {
                "prep" -> if (t >= a.prepEnd) trans(id, "prep", "travel", null)
                "travel" -> if (t >= a.travelEnd) trans(id, "travel", "watch", null)
                "watch" -> if (t >= a.watchEnd) trans(id, "watch", "war") { startWar(id, a) }
            }
        }
        for ((id, w) in wars.toList()) if (w.status == "active") tickWar(id)
        val mm = me
        if (vip && mm != null && mm.auto && mm.job.isNotEmpty() && energy() >= 100) work()
    }

    private fun startWar(id: String, a: Atk) {
        val ex = wars.entries.firstOrNull { it.value.status != "ended" && Net.pk(it.value.a, it.value.d) == Net.pk(a.from, a.to) }
        val ap = players[a.from]?.powOf(a.units) ?: 0L
        if (ex != null) {
            val w = ex.value; val side = if (w.a == a.from) "a" else "d"
            val up = HashMap<String, Any>()
            up[side + "Pow"] = Net.inc(ap); up[side + "Commit"] = Net.inc(ap)
            for ((k, v) in a.units) up["${side}U/$k"] = Net.inc(v)
            up["status"] = "active"
            ref.child("wars/${ex.key}").updateChildren(up)
            news("${a.fromName} reinforced the front with $ap power")
        } else {
            val dp = players[a.to]; val du = HashMap<String, Long>()
            if (dp != null) for (d in ALL_UNITS) { val n = dp.u(d.id); if (n > 0) du[d.id] = n }
            val dpow = (dp?.powOf(du) ?: 0L) + (dp?.b("fort") ?: 0L) * 20
            val w = War(a.from, a.to, a.fromName, a.toName, ap, dpow, ap, dpow, a.units, du, now(), "active", "")
            ref.child("wars").push().setValue(w)
            val up = HashMap<String, Any>()
            for ((k, v) in du) up["players/${a.to}/units/$k"] = Net.inc(-v)
            if (up.isNotEmpty()) ref.updateChildren(up)
            news("War begins: ${a.fromName} vs ${a.toName}")
        }
        Snd.play("war")
        ref.child("attacks/$id").removeValue()
    }

    private fun tickWar(id: String) {
        val w0 = wars[id] ?: return
        var ended = false
        var froze = false
        ref.child("wars/$id").runTransaction(object : Transaction.Handler {
            override fun doTransaction(d: MutableData): Transaction.Result {
                ended = false; froze = false
                val w = d.getValue(War::class.java) ?: return Transaction.abort()
                if (w.status != "active") return Transaction.abort()
                val m = (now() - w.last) / 60000L
                if (m <= 0) return Transaction.abort()
                val dmg = min(10 * m, min(w.aPow, w.dPow))
                w.aPow -= dmg; w.dPow -= dmg; w.last += m * 60000L
                if (w.aPow <= 0 || w.dPow <= 0) {
                    w.status = "ended"
                    w.winner = if (w.aPow > w.dPow) w.a else if (w.dPow > w.aPow) w.d else ""
                    ended = true
                } else if (w.aPow * 2 <= w.aCommit && w.dPow * 2 <= w.dCommit) {
                    w.status = "frozen"; froze = true
                }
                d.setValue(w); return Transaction.success(d)
            }
            override fun onComplete(e: DatabaseError?, ok: Boolean, s: DataSnapshot?) {
                if (e == null && ok && ended) s?.getValue(War::class.java)?.let { settle(it) }
                if (e == null && ok && froze) {
                    val w = s?.getValue(War::class.java)
                    if (w != null) news("War ${w.aName} vs ${w.dName} is at a stalemate (50% left each). Troops hold position.")
                }
            }
        })
    }

    private fun settle(w: War) {
        val up = HashMap<String, Any>()
        val ar = if (w.aCommit > 0) w.aPow.toDouble() / w.aCommit else 0.0
        val dr = if (w.dCommit > 0) w.dPow.toDouble() / w.dCommit else 0.0
        for ((k, v) in w.aU) up["players/${w.a}/units/$k"] = Net.inc((v * ar).toLong())
        for ((k, v) in w.dU) up["players/${w.d}/units/$k"] = Net.inc((v * dr).toLong())
        if (w.winner.isNotEmpty()) {
            val loser = if (w.winner == w.a) w.d else w.a
            val lp = players[loser]
            if (lp != null) {
                val lg = lp.gold / 4; val lf = lp.food / 4; val lr = lp.res / 4
                up["players/$loser/gold"] = Net.inc(-lg); up["players/$loser/food"] = Net.inc(-lf); up["players/$loser/res"] = Net.inc(-lr)
                up["players/${w.winner}/gold"] = Net.inc(lg); up["players/${w.winner}/food"] = Net.inc(lf); up["players/${w.winner}/res"] = Net.inc(lr)
            }
            val taken = takeLand(w.winner, loser)
            news("${cn(w.winner)} won the war against ${cn(loser)}" + (if (taken.isNotEmpty()) " and took $taken of land" else ""))
        } else news("The war between ${w.aName} and ${w.dName} ended in a draw")
        ref.updateChildren(up)
        Snd.play("war")
    }

    fun peaceWars(other: String) {
        for ((id, w) in wars.toList()) {
            if (w.status == "ended") continue
            if (Net.pk(w.a, w.d) != Net.pk(uid, other)) continue
            ref.child("wars/$id/status").runTransaction(object : Transaction.Handler {
                override fun doTransaction(d: MutableData): Transaction.Result {
                    val st = d.getValue(String::class.java)
                    if (st == null || st == "ended") return Transaction.abort()
                    d.setValue("ended"); return Transaction.success(d)
                }
                override fun onComplete(e: DatabaseError?, ok: Boolean, sn: DataSnapshot?) {
                    if (!ok) return
                    val up = HashMap<String, Any>()
                    val ar = if (w.aCommit > 0) w.aPow.toDouble() / w.aCommit else 0.0
                    val dr = if (w.dCommit > 0) w.dPow.toDouble() / w.dCommit else 0.0
                    for ((k, v) in w.aU) up["players/${w.a}/units/$k"] = Net.inc((v * ar).toLong())
                    for ((k, v) in w.dU) up["players/${w.d}/units/$k"] = Net.inc((v * dr).toLong())
                    if (up.isNotEmpty()) ref.updateChildren(up)
                }
            })
        }
    }

    fun addGold(v: Long) { upd(hashMapOf<String, Any>("gold" to Net.inc(v))) }
    fun buyNuke(n: Long) { upd(hashMapOf<String, Any>("nukes" to Net.inc(n))) }
    fun syncSkins(sk: HashMap<String, Long>, dm: Long) { if (me != null) upd(hashMapOf<String, Any>("sk" to sk, "dm" to dm)) }

    fun nuke(to: String): String? {
        val m = me ?: return "Not ready"
        val t = players[to] ?: return "Target not found"
        if (!nuke) return "Nuke is disabled on this server"
        if (m.nukes < 1) return "You have no nuke"
        if (relOf(to) != "war") return "Not at war with them"
        if (prot(t)) return "Target has offline protection"
        val up = HashMap<String, Any>()
        for ((k, v) in t.units) if (v > 0) up["players/$to/units/$k"] = Net.inc(-(v * 30 / 100))
        for ((k, v) in t.blds) if (v > 0) up["players/$to/blds/$k"] = Net.inc(-(v * 30 / 100))
        up["players/$to/gold"] = Net.inc(-(t.gold / 5)); up["players/$to/food"] = Net.inc(-(t.food / 5)); up["players/$to/res"] = Net.inc(-(t.res / 5))
        up["players/$uid/nukes"] = Net.inc(-1)
        ref.updateChildren(up)
        val b = t.box()
        ref.child("fx").push().setValue(hashMapOf<String, Any>("x" to b[4], "y" to b[5], "t" to now()))
        news("NUKE! ${m.country} launched a nuke at ${t.country}")
        Snd.play("nuke"); return null
    }

    fun pm(to: String, text: String) {
        val m = me ?: return
        if (text.isBlank()) return
        ref.child("chat").push().setValue(Msg(now(), if (isAdmin()) "admin" else "member", m.nick, m.country, censor(text.trim()), to, uid, badge, title, cc))
    }

    fun reportBad(w: String) {
        val m = me ?: return
        Net.db.child("reports").push().setValue(hashMapOf<String, Any>("uid" to uid, "nick" to m.nick, "word" to w, "key" to key, "sname" to name, "t" to now()))
    }

    // ---------- klaim wilayah kosong: tiap 5 detik dapat +5 km, 100 km = 1 sel ----------
    fun startExpand(tx: Int, ty: Int): String? {
        val m = me ?: return "Not ready"
        if (m.power() < 1) return "You need at least 1 power of army"
        if (!World.land(tx, ty)) return "That is not land"
        for (o in players.values) if (o.hit(tx, ty)) return "That land is already owned"
        var near = false
        for (s in m.segs()) if (ty >= s[0] - 1 && ty <= s[0] + 1 && tx >= s[1] - 1 && tx <= s[2] + 1) { near = true; break }
        if (!near) return "Too far. The land must be next to your territory"
        val up = HashMap<String, Any>()
        up["ex"] = tx; up["ey"] = ty; up["elast"] = now()
        if (m.ex != tx || m.ey != ty) up["cprog"] = 0L
        upd(up)
        return null
    }
    fun stopExpand() { upd(hashMapOf<String, Any>("elast" to 0L)) }
    fun expandTick() {
        val m = me ?: return
        if (m.elast <= 0L) return
        var steps = (now() - m.elast) / 5000L
        if (steps <= 0L) return
        var free = World.land(m.ex, m.ey)
        for (o in players.values) if (o.hit(m.ex, m.ey)) free = false
        if (!free) { stopExpand(); return }
        steps = minOf(steps, (100L - m.cprog) / 5L)
        if (steps <= 0L) { stopExpand(); return }
        val total = m.cprog + steps * 5L
        val up = HashMap<String, Any>()
        if (total >= 100L) {
            val cs = ArrayList<Int>()
            for (sg in m.segs()) for (x in sg[1]..sg[2]) cs.add(sg[0] * World.W + x)
            cs.add(m.ey * World.W + m.ex)
            var ax = 0.0; var ay = 0.0
            for (c in cs) { ax += c % World.W; ay += c / World.W }
            up["cells"] = World.encode(cs); up["land"] = cs.size * 100L
            up["x"] = (ax / cs.size).toInt(); up["y"] = (ay / cs.size).toInt()
            up["cprog"] = 0L; up["elast"] = 0L
            upd(up)
            news("${m.country} annexed new land (+100 km)")
        } else {
            up["land"] = m.land + steps * 5L; up["cprog"] = total; up["elast"] = m.elast + steps * 5000L
            upd(up)
        }
    }

    // ---------- saham emas ----------
    fun gramPrice(): Long = goldPrice(now() / 60000L)
    fun buyGram(n: Long): String? {
        val m = me ?: return "Not ready"; val p = gramPrice()
        if (n <= 0) return "Invalid amount"
        if (m.gold < n * p) return "Not enough gold"
        upd(hashMapOf<String, Any>("gold" to Net.inc(-n * p), "gram" to Net.inc(n))); Snd.play("coin"); return null
    }
    fun sellGram(n: Long): String? {
        val m = me ?: return "Not ready"; val p = gramPrice()
        if (n <= 0) return "Invalid amount"
        if (m.gram < n) return "Not enough gold grams"
        upd(hashMapOf<String, Any>("gold" to Net.inc(n * p), "gram" to Net.inc(-n))); Snd.play("coin"); return null
    }

    // ---------- item & craft ----------
    fun craft(mi: Int, fi: Int, t: Int): String? {
        val m = me ?: return "Not ready"
        if (m.b("workshop") < 1) return "Build a Workshop first"
        val prev = itemId(mi, fi, t - 1); val cost = 100L * t
        if ((m.inv[prev] ?: 0L) < 2) return "Need 2x ${itemName(prev)}"
        if (m.gold < cost) return "Not enough gold"
        upd(hashMapOf<String, Any>("gold" to Net.inc(-cost), "inv/$prev" to Net.inc(-2), "inv/${itemId(mi, fi, t)}" to Net.inc(1)))
        Snd.play("build"); return null
    }
    fun sellItem(id: String, n: Long): String? {
        val m = me ?: return "Not ready"
        if (n <= 0 || (m.inv[id] ?: 0L) < n) return "Not enough items"
        upd(hashMapOf<String, Any>("inv/$id" to Net.inc(-n), "gold" to Net.inc(itemValue(id) * n))); Snd.play("coin"); return null
    }
    fun announce(msg: String) { ref.child("evt").setValue(hashMapOf<String, Any>("t" to now(), "msg" to msg)) }

    // ---------- unit dari craft & rebut wilayah ----------
    fun craftUnit(id: String): String? {
        val m = me ?: return "Not ready"
        val r = RECIPES.firstOrNull { it.unitId == id } ?: return "Unknown recipe"
        if (m.b("workshop") < 1) return "Build a Workshop first"
        for ((item, n) in r.items) if ((m.inv[item] ?: 0L) < n) return "Need ${n}x ${itemName(item)}"
        if (m.gold < r.gold) return "Not enough gold"
        val up = HashMap<String, Any>()
        up["gold"] = Net.inc(-r.gold); up["units/$id"] = Net.inc(1)
        for ((item, n) in r.items) up["inv/$item"] = Net.inc(-n.toLong())
        upd(up); Snd.play("build"); return null
    }

    /** Pemenang ambil 30% wilayah musuh; kalau musuh tinggal 1 sel (~100 km) langsung 100%. */
    private fun takeLand(winner: String, loser: String): String {
        val wp = players[winner] ?: return ""; val lp = players[loser] ?: return ""
        val lc = ArrayList<Int>()
        for (sg in lp.segs()) for (x in sg[1]..sg[2]) lc.add(sg[0] * World.W + x)
        if (lc.isEmpty()) return ""
        val take = if (lc.size <= 1) lc.size else maxOf(1, Math.ceil(lc.size * 0.3).toInt())
        val b = wp.box(); val cx = b[4]; val cy = b[5]
        lc.sortBy { val dx = (it % World.W) - cx; val dy = (it / World.W) - cy; dx * dx + dy * dy }
        val moved = lc.subList(0, take).toList(); val rest = lc.subList(take, lc.size).toList()
        val wc = ArrayList<Int>()
        for (sg in wp.segs()) for (x in sg[1]..sg[2]) wc.add(sg[0] * World.W + x)
        wc.addAll(moved)
        val up = HashMap<String, Any>()
        fun put(id: String, cs: List<Int>) {
            var ax = 0.0; var ay = 0.0
            for (i in cs) { ax += i % World.W; ay += i / World.W }
            up["players/$id/cells"] = World.encode(cs); up["players/$id/land"] = cs.size * 100L
            up["players/$id/x"] = (ax / cs.size).toInt(); up["players/$id/y"] = (ay / cs.size).toInt(); up["players/$id/cprog"] = 0L
        }
        put(winner, wc)
        if (rest.isNotEmpty()) put(loser, rest)
        ref.updateChildren(up)
        if (rest.isEmpty()) ref.child("players/$loser").removeValue()
        return "${fmt(take * 100L)} km"
    }

    // ---------- pabrik & kerja ----------
    fun myFactory(): Factory? { val j = me?.job ?: ""; return if (j.isEmpty()) null else factories[j] }
    fun energy(): Int {
        val m = me ?: return 0; val f = myFactory() ?: return 0
        return (minOf(100L, (now() - m.workAt) * 100L / (f.refillSec() * 1000L))).toInt().coerceAtLeast(0)
    }
    fun leaveJob() {
        val m = me ?: return
        if (m.job.isNotEmpty()) ref.child("factories/${m.job}/workers/$uid").removeValue()
        upd(hashMapOf<String, Any>("job" to "", "auto" to false))
    }
    fun createFactory(fname: String): String? {
        val m = me ?: return "Not ready"
        val n = fname.trim()
        if (n.length < 3) return "Factory name min 3 chars"
        if (hasBad(n)) return "Inappropriate name"
        if (m.gold < 10000L || m.food < 2000L || m.res < 4000L) return "Need 10000 gold, 2000 food, 4000 resource"
        val id = ref.child("factories").push().key ?: return "Failed"
        leaveJob()
        val up = HashMap<String, Any>()
        up["factories/$id"] = hashMapOf<String, Any>("name" to n, "owner" to uid, "level" to 1L, "created" to now(), "workers" to hashMapOf<String, Any>(uid to m.nick))
        up["players/$uid/gold"] = Net.inc(-10000L); up["players/$uid/food"] = Net.inc(-2000L); up["players/$uid/res"] = Net.inc(-4000L)
        up["players/$uid/job"] = id; up["players/$uid/workAt"] = 0L
        ref.updateChildren(up); return null
    }
    fun joinFactory(id: String): String? {
        val m = me ?: return "Not ready"; val f = factories[id] ?: return "Factory is gone"
        if (m.job == id) return "You already work here"
        if ((fwork[id]?.size ?: 0).toLong() >= f.maxEmp()) return "Factory is full"
        leaveJob()
        ref.child("factories/$id/workers/$uid").setValue(m.nick)
        upd(hashMapOf<String, Any>("job" to id, "workAt" to 0L)); return null
    }
    fun fire(id: String, wuid: String) {
        if (factories[id]?.owner != uid || wuid == uid) return
        ref.child("factories/$id/workers/$wuid").removeValue(); ref.child("players/$wuid/job").setValue("")
    }
    fun upgradeFactory(id: String): String? {
        val m = me ?: return "Not ready"; val f = factories[id] ?: return "Factory is gone"
        if (f.owner != uid) return "Only the owner can upgrade"
        val gg = 5000L * f.level; val rr = 1000L * f.level; val ff = 500L * f.level
        if (m.gold < gg || m.res < rr || m.food < ff) return "Need $gg gold, $ff food, $rr resource"
        upd(hashMapOf<String, Any>("gold" to Net.inc(-gg), "res" to Net.inc(-rr), "food" to Net.inc(-ff)))
        ref.child("factories/$id/level").setValue(f.level + 1); Snd.play("build"); return null
    }
    fun work(): String? {
        val f = myFactory() ?: return "You have no job"
        if (energy() < 100) return "Not enough energy"
        upd(hashMapOf<String, Any>("gold" to Net.inc(f.salGold()), "food" to Net.inc(f.salFood()), "res" to Net.inc(f.salRes()), "workAt" to now()))
        Snd.play("coin"); return null
    }
    fun setAuto(on: Boolean) { upd(hashMapOf<String, Any>("auto" to on)) }

    // ---------- chat & command ----------
    fun uidOf(n: String): String? = players.entries.firstOrNull { it.value.nick.equals(n, true) }?.key

    fun send(text: String, say: (String) -> Unit) {
        val m = me ?: return
        val t = text.trim(); if (t.isEmpty()) return
        if ((mutes[uid] ?: 0L) > now()) { say("You are muted"); return }
        if (t.startsWith("/")) { cmd(t, say); return }
        if (!gChat && !isAdmin()) { say("Global chat is off"); return }
        val bad = findBad(t)
        if (bad != null) reportBad(bad)
        ref.child("chat").push().setValue(Msg(now(), if (isAdmin()) "admin" else "member", m.nick, m.country, censor(t), "", "", badge, title, cc))
    }

    private fun cmd(t: String, say: (String) -> Unit) {
        val m = me ?: return
        val p = t.split(" ")
        val c = p[0].lowercase()
        val adm = isAdmin()
        fun target(i: Int): String? { val n = p.getOrNull(i) ?: return null; return uidOf(n) }
        fun rest(from: Int): String = p.drop(from).joinToString(" ")
        when (c) {
            "/help" -> say("/help, /msg <player> <msg>, /tf <player> <amount>" +
                if (adm) "\nAdmin: /ban /unban /kick /mute /unmute /delchat /bansos <amt> /announce <msg>" else "")
            "/msg" -> {
                val to = target(1); if (to == null || p.size < 3) { say("Usage: /msg <player> <msg>"); return }
                ref.child("chat").push().setValue(Msg(now(), if (adm) "admin" else "member", m.nick, m.country, censor(rest(2)), to, uid, badge, title, cc))
            }
            "/tf" -> {
                val to = target(1); val a = p.getOrNull(2)?.toLongOrNull()
                if (to == null || a == null) { say("Usage: /tf <player> <amount>"); return }
                say(give(to, "gold", a) ?: "Sent $a gold")
            }
            else -> {
                if (!adm) { say("Unknown command. /help"); return }
                when (c) {
                    "/ban" -> { val u = target(1); if (u == null) { say("Player not found"); return }
                        ref.child("bans/$u").setValue(rest(2).ifEmpty { "-" }); ref.child("kick/$u").setValue(true); say("Banned") }
                    "/unban" -> { val u = target(1); if (u == null) { say("Player not found"); return }; ref.child("bans/$u").removeValue(); say("Unbanned") }
                    "/kick" -> { val u = target(1); if (u == null) { say("Player not found"); return }; ref.child("kick/$u").setValue(true); say("Kicked") }
                    "/mute" -> { val u = target(1); val mn = p.getOrNull(2)?.toLongOrNull()
                        if (u == null || mn == null) { say("Usage: /mute <player> <minutes> <reason>"); return }
                        ref.child("mutes/$u").setValue(now() + mn * 60000L); say("Muted $mn min") }
                    "/unmute" -> { val u = target(1); if (u == null) { say("Player not found"); return }; ref.child("mutes/$u").removeValue(); say("Unmuted") }
                    "/delchat" -> { ref.child("chat").removeValue(); say("Chat cleared") }
                    "/bansos" -> { val a = p.getOrNull(1)?.toLongOrNull(); if (a == null || a <= 0) { say("Usage: /bansos <amount>"); return }
                        val up = HashMap<String, Any>()
                        for ((k, v) in players) if (v.online) up["players/$k/gold"] = Net.inc(a)
                        ref.updateChildren(up); news("Admin gave $a gold to all online players"); say("Done") }
                    "/announce" -> { news("[ADMIN] " + rest(1)); say("Announced") }
                    else -> say("Unknown command. /help")
                }
            }
        }
    }
}
