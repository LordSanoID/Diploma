package com.sano.dpl

import android.app.Dialog
import android.content.Context
import android.net.ConnectivityManager
import android.view.Gravity
import android.widget.*
import kotlin.math.hypot

class LAttack(val bot: String, val units: HashMap<String, Long>, val arrive: Long)

val BOT_COUNTRIES = listOf("Valoria", "Kharzun", "Northmark", "Solaris", "Draknor", "Eldoria", "Zephyria", "Ironhold", "Aurelia", "Tyrnia", "Vostok", "Mirelia")
val LV_ADJ = listOf("Silent", "Iron", "Crimson", "Frozen", "Golden", "Shadow", "Storm", "Ancient", "Burning", "Hidden")
val LV_PLC = listOf("Border", "Valley", "Coast", "Fortress", "Desert", "Capital", "Strait", "Highland", "Archipelago", "Frontier")
fun storyName(l: Int): String = "${LV_ADJ[(l * 7) % LV_ADJ.size]} ${LV_PLC[(l * 3) % LV_PLC.size]}"

/** Game lokal tanpa internet. Memakai semua popup ekonomi yang sama dengan mode online. */
class LocalGame : Game("offline") {
    override val uid: String = "me"
    var diff = 1; var goal = 0; var goalLand = 0L; var limitMin = 0; var level = 0; var sandbox = false
    var ended = false; var strongest = ""; var campaign = false
    val t0 = now(); private val rnd = java.util.Random()
    private val pending = ArrayList<LAttack>()
    var onEnd: ((Boolean, String) -> Unit)? = null

    fun goalText(): String = when {
        sandbox -> "Sandbox: build and experiment freely."
        goal == 0 -> "Conquer every enemy country."
        goal == 1 -> "Reach ${fmt(goalLand)} km of land within $limitMin min."
        goal == 2 -> "Survive for $limitMin min."
        goal == 4 -> "Control ${fmt(goalLand)} km (25% of the world's land)."
        else -> "Eliminate the strongest enemy."
    }
    fun minutesLeft(): Long = maxOf(0L, limitMin - (now() - t0) / 60000L)

    fun setup(nick: String, bots: Int, difficulty: Int, startGold: Long) {
        diff = difficulty; exists = true; name = "Offline"
        val me = Player(nick = nick, country = "$nick Land", color = PALETTE[0], gold = startGold, res = startGold, food = startGold,
            lastTick = now(), lastSeen = now(), online = true)
        place(me); players[uid] = me
        for (i in 1..bots) {
            val b = Player(nick = "Bot$i", country = BOT_COUNTRIES[(i + level) % BOT_COUNTRIES.size], color = PALETTE[i % PALETTE.size],
                gold = 500L + 300L * diff, res = 500L, food = 500L, online = true)
            b.units["soldier"] = 8L + 6L * diff + level / 10
            place(b); players["bot$i"] = b
            rel[Net.pk(uid, "bot$i")] = "war"
        }
        var bp = -1L
        for ((id, p) in players) if (id != uid && p.power() > bp) { bp = p.power(); strongest = id }
    }

    fun setupCampaign(playerIdx: Int, nick: String, difficulty: Int) {
        campaign = true; fogOn = false; diff = difficulty; goal = 4; exists = true; name = "Campaign"
        val cd = CountryData.built
        var total = 0L
        for (ci in cd.names.indices) {
            val cells = cd.cells[ci]
            if (cells.isEmpty()) continue
            val isMe = ci == playerIdx
            val hue = (ci * 47 % 360).toFloat()
            val p = Player(nick = if (isMe) nick else "AI", country = cd.names[ci],
                color = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.65f, 0.95f)),
                gold = 800L + cells.size * 4L, res = 600L + cells.size * 3L, food = 600L + cells.size * 3L,
                lastTick = now(), lastSeen = now(), online = true)
            p.units["soldier"] = 5L + cells.size
            if (cells.size > 60) p.units["tank"] = (cells.size / 30).toLong()
            if (isMe) { p.gold = 6000L; p.res = 4000L; p.food = 4000L }
            setCells(p, cells)
            total += p.landTotal()
            players[if (isMe) uid else "c$ci"] = p
        }
        goalLand = total / 4
    }

    val gifts = HashMap<String, Long>()
    fun setRelLocal(u: String, v: String) { val k = Net.pk(uid, u); if (v.isEmpty()) rel.remove(k) else rel[k] = v }
    fun propose(u: String, type: String): String {
        val b = players[u] ?: return "Target is gone"
        val chance = (if (type == "peace") 0.55 else 0.35) + (gifts[u] ?: 0L) / 4000.0 - 0.1 * diff
        return if (rnd.nextDouble() < chance) {
            setRelLocal(u, if (type == "peace") "peace" else "ally")
            news("${b.country} accepted ${if (type == "peace") "peace" else "an alliance"} with ${players[uid]?.country ?: "you"}")
            "${b.country} accepted!"
        } else "${b.country} refused."
    }
    fun gift(u: String, amt: Long): String? {
        val m = me ?: return "Not ready"; val b = players[u] ?: return "Target is gone"
        if (amt <= 0 || m.gold < amt) return "Not enough gold"
        m.gold -= amt; b.gold += amt; gifts[u] = (gifts[u] ?: 0L) + amt; onUpdate?.invoke(); return null
    }

    private fun place(p: Player) {
        val occ = BooleanArray(World.W * World.H)
        for (o in players.values) for (sg in o.segs()) for (x in sg[1]..sg[2]) occ[sg[0] * World.W + x] = true
        for (i in 0 until 800) {
            val sx = rnd.nextInt(World.W); val sy = rnd.nextInt(World.H)
            if (occ[sy * World.W + sx] || !World.land(sx, sy)) continue
            val c = World.grow(sx, sy, occ, World.TERR) ?: continue
            setCells(p, c); return
        }
    }
    private fun setCells(p: Player, c: List<Int>) {
        var ax = 0.0; var ay = 0.0
        for (i in c) { ax += i % World.W; ay += i / World.W }
        p.cells = World.encode(c); p.land = c.size * 100L
        p.x = (ax / c.size).toInt(); p.y = (ay / c.size).toInt(); p.resetCache()
    }
    private fun cellsOf(p: Player): ArrayList<Int> {
        val l = ArrayList<Int>()
        for (sg in p.segs()) for (x in sg[1]..sg[2]) l.add(sg[0] * World.W + x)
        return l
    }

    override fun upd(m: Map<String, Any>) {
        val p = players[uid] ?: return
        for ((k, v) in m) applyTo(p, k, v)
        onUpdate?.invoke()
    }
    override fun news(msg: String) { this.news.add(News(now(), msg)); if (this.news.size > 80) this.news.removeAt(0) }
    override fun goOnline() {}
    override fun stop() { Net.local = false }

    private fun applyTo(p: Player, key: String, v: Any) {
        val inc = (v as? Inc)?.v
        val parts = key.split("/")
        fun num(): Long = (v as Number).toLong()
        when (parts[0]) {
            "gold" -> p.gold = if (inc != null) p.gold + inc else num()
            "res" -> p.res = if (inc != null) p.res + inc else num()
            "food" -> p.food = if (inc != null) p.food + inc else num()
            "nukes" -> p.nukes = if (inc != null) p.nukes + inc else num()
            "gram" -> p.gram = if (inc != null) p.gram + inc else num()
            "lastTick" -> p.lastTick = num()
            "daily" -> p.daily = num()
            "elast" -> p.elast = num()
            "cprog" -> p.cprog = num()
            "land" -> p.land = num()
            "dm" -> p.dm = num()
            "ex" -> p.ex = num().toInt()
            "ey" -> p.ey = num().toInt()
            "x" -> p.x = num().toInt()
            "y" -> p.y = num().toInt()
            "cells" -> { p.cells = v as String; p.resetCache() }
            "units" -> p.units[parts[1]] = (p.units[parts[1]] ?: 0L) + (inc ?: 0L)
            "blds" -> p.blds[parts[1]] = (p.blds[parts[1]] ?: 0L) + (inc ?: 0L)
            "inv" -> p.inv[parts[1]] = (p.inv[parts[1]] ?: 0L) + (inc ?: 0L)
        }
    }

    // ---------- pertempuran ----------
    private fun scale(p: Player, loss: Double) { for ((k, v) in p.units.toMap()) p.units[k] = (v * (1 - loss)).toLong() }

    private fun transfer(fromId: String, toId: String, n: Int) {
        val from = players[fromId] ?: return; val to = players[toId] ?: return
        val fc = cellsOf(from); if (fc.isEmpty()) return
        val tb = to.box(); val tcx = tb[4]; val tcy = tb[5]
        fc.sortBy { val dx = (it % World.W) - tcx; val dy = (it / World.W) - tcy; dx * dx + dy * dy }
        val take = minOf(n, fc.size)
        val moved = fc.subList(0, take).toList(); val rest = fc.subList(take, fc.size).toList()
        val tc = cellsOf(to); tc.addAll(moved); setCells(to, tc)
        if (rest.isEmpty()) {
            val c = from.country
            players.remove(fromId); rel.remove(Net.pk(uid, fromId)); news("$c was eliminated!")
        } else setCells(from, rest)
    }

    private fun battle(aId: String, dId: String, aPow: Double, sent: Boolean): Boolean {
        val d = players[dId] ?: return true
        val dp = d.power() * (1 + 0.05 * d.b("fort"))
        val win = aPow * (0.85 + rnd.nextDouble() * 0.3) > dp
        scale(d, if (win) 0.4 else 0.15)
        val a = players[aId]
        if (a != null && !sent) scale(a, if (win) 0.2 else 0.4)
        val an = a?.country ?: "?"; val dn = d.country
        if (win) {
            val sz = cellsOf(d).size
            val n = if (sz <= 1) sz else maxOf(1, Math.ceil(sz * 0.3).toInt())
            transfer(dId, aId, n); news("$an conquered ${n * 100} km from $dn")
        } else news("$an failed to break $dn")
        return win
    }

    fun attack(botId: String, pct: Int): String? {
        val m = me ?: return "Not ready"; val b = players[botId] ?: return "Target is gone"
        val rr = relOf(botId)
        if (rr == "peace" || rr == "ally") return "Break the treaty first"
        val send = HashMap<String, Long>()
        for (u in ALL_UNITS) { val n = m.u(u.id) * pct / 100; if (n > 0) send[u.id] = n }
        if (send.isEmpty()) return "No units to send"
        for ((k, v) in send) m.units[k] = (m.units[k] ?: 0L) - v
        val dist = hypot((m.x - b.x).toDouble(), (m.y - b.y).toDouble())
        pending.add(LAttack(botId, send, now() + 8000L + (dist * 150).toLong()))
        news("Your army marches on ${b.country}"); onUpdate?.invoke(); return null
    }
    fun incoming(): Int = pending.size

    private fun resolve(a: LAttack) {
        val m = players[uid] ?: return
        val win = battle(uid, a.bot, m.powOf(a.units).toDouble(), true)
        val back = if (win) 0.7 else 0.25
        for ((k, v) in a.units) m.units[k] = (m.units[k] ?: 0L) + (v * back).toLong()
        Snd.play(if (win) "win" else "war")
    }

    private fun grab(p: Player, n: Int) {
        val occ = BooleanArray(World.W * World.H)
        for (o in players.values) for (sg in o.segs()) for (x in sg[1]..sg[2]) occ[sg[0] * World.W + x] = true
        val mine = cellsOf(p); val have = HashSet<Int>(mine)
        val front = ArrayList<Int>()
        for (c in mine) {
            val cx = c % World.W; val cy = c / World.W
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val a = cx + dx; val b = cy + dy
                if (a < 0 || b < 0 || a >= World.W || b >= World.H) continue
                val i = b * World.W + a
                if (!occ[i] && !have.contains(i) && World.land(a, b)) front.add(i)
            }
        }
        if (front.isEmpty()) return
        for (k in 0 until n) { val c = front[rnd.nextInt(front.size)]; if (have.add(c)) mine.add(c) }
        setCells(p, mine)
    }

    override fun pump() {
        if (ended) return
        val t = now()
        for (a in pending.toList()) if (t >= a.arrive) { pending.remove(a); resolve(a) }
        val agg = 0.04 + 0.05 * diff
        for ((id, b) in players.toList()) {
            if (id == uid) continue
            val inc = (6 + b.landTotal() / 150.0) * (1 + 0.5 * diff)
            b.gold += inc.toLong() * 3; b.res += inc.toLong() * 2; b.food += inc.toLong() * 2
            if (b.gold > 400 && b.res > 300 && b.food > 300 && rnd.nextInt(3) == 0) {
                val u = UNITS[minOf(UNITS.size - 1, rnd.nextInt(2 + diff * 2))]
                val cnt = maxOf(1L, minOf(b.gold / u.gold, minOf(b.res / u.res, b.food / u.food)) / 3)
                b.gold -= u.gold * cnt; b.res -= u.res * cnt; b.food -= u.food * cnt
                b.units[u.id] = (b.units[u.id] ?: 0L) + cnt
            }
            if (rnd.nextInt(100) < 6 + 4 * diff) grab(b, 1 + diff)
            if (rnd.nextDouble() < agg && b.power() > 3) {
                var tId = ""; var tp = Double.MAX_VALUE
                for ((oid, o) in players) { if (oid == id) continue; if (oid == uid && (relOf(id) == "peace" || relOf(id) == "ally")) continue; if (campaign && hypot((b.x - o.x).toDouble(), (b.y - o.y).toDouble()) > 40.0) continue; val op = o.power().toDouble(); if (op < tp) { tp = op; tId = oid } }
                if (tId.isNotEmpty() && b.power() * 0.6 > tp * 0.9) battle(id, tId, b.power() * 0.6, false)
            }
        }
        checkEnd(t)
        onUpdate?.invoke()
    }

    private fun checkEnd(t: Long) {
        if (sandbox || ended) return
        val m = players[uid]
        if (m == null || m.landTotal() <= 0) { finish(false, "Your country was conquered."); return }
        val mins = (t - t0) / 60000.0
        val bots = players.keys.filter { it != uid }
        when (goal) {
            0 -> if (bots.isEmpty()) finish(true, "All enemies conquered!")
            1 -> { if (m.landTotal() >= goalLand) finish(true, "You reached ${fmt(goalLand)} km!") else if (mins > limitMin) finish(false, "Time is up.") }
            2 -> if (mins >= limitMin) finish(true, "You survived!")
            4 -> if (m.landTotal() >= goalLand) finish(true, "You control a quarter of the world!")
            else -> if (!players.containsKey(strongest)) finish(true, "The strongest enemy has fallen!")
        }
    }
    private fun finish(win: Boolean, msg: String) { ended = true; Snd.play(if (win) "win" else "error"); onEnd?.invoke(win, msg) }
}

// ================= LAYAR OFFLINE =================
@Suppress("DEPRECATION")
fun MainActivity.isOnline(): Boolean {
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val ni = cm.activeNetworkInfo
    return ni != null && ni.isConnected
}

fun MainActivity.offlineMenu() {
    game = null; map = null; gameShown = false
    val done = getSharedPreferences("dpl", 0).getInt("story", 0)
    val t = tv("OFFLINE MODE", 26f, C.GOLD, true); t.gravity = Gravity.CENTER
    val c = col(panel(t), mascot("Tidak ada internet? Main offline saja!", 70),
        btn("BOT MATCH", R.drawable.ic_soldier) { botSetup() },
        btn("STORY MODE  ($done/200)", R.drawable.ic_rank) { storyMenu() },
        btn("CAMPAIGN (real world)", R.drawable.ic_globe) { campaignMenu() },
        btn("SANDBOX", R.drawable.ic_build) { startSandbox() },
        btn("Check connection", R.drawable.ic_globe) {
            if (isOnline()) { if (auth.currentUser != null) afterAuth() else showWelcome() } else toast("Still offline") })
    val sv = ScrollView(this); sv.addView(c)
    val root = center(sv, 420)
    root.addView(btn("", R.drawable.ic_back) { back?.invoke() }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).also { it.setMargins(dp(8), dp(8), 0, 0) })
    screen(root) { if (isOnline()) { if (auth.currentUser != null) afterAuth() else showWelcome() } else finish() }
}

fun MainActivity.botSetup() {
    val bots = Cycle(this, "Bots", (1..10).map { it.toString() }, 2)
    val dif = Cycle(this, "Difficulty", listOf("Easy", "Normal", "Hard"), 1)
    val nm = edit("Your name"); nm.setText(if (ud.nick.isNotEmpty()) ud.nick else "Player")
    var d: Dialog? = null
    d = popup("BOT MATCH", col(lab("Name :", nm), bots.view, dif.view, tv("Conquer all bots to win. Fog of war is on.", 12f, C.GRAY),
        btn("START", R.drawable.ic_play) {
            val n = nm.text.toString().trim().ifEmpty { "Player" }
            if (hasBad(n)) toast("Inappropriate name") else {
                d?.dismiss()
                val lg = LocalGame(); lg.goal = 0; lg.setup(n, bots.i + 1, dif.i, 2500L); startOffline(lg)
            }
        }))
}

fun MainActivity.startSandbox() {
    val lg = LocalGame(); lg.sandbox = true; lg.setup(if (ud.nick.isNotEmpty()) ud.nick else "Player", 0, 1, 20000L)
    lg.players["me"]?.units?.put("soldier", 50L)
    startOffline(lg)
}

fun MainActivity.storyMenu() {
    val done = getSharedPreferences("dpl", 0).getInt("story", 0)
    val page = Cycle(this, "Page", (1..10).map { "${(it - 1) * 20 + 1}-${it * 20}" }, minOf(9, done / 20)) { live?.invoke() }
    liveDialog("STORY MODE", true) { box ->
        box.addView(tv("Completed: $done / 200", 13f, C.GOLD, true)); box.addView(page.view)
        val start = page.i * 20
        for (row in 0 until 4) {
            val l = LinearLayout(this)
            for (cc in 0 until 5) {
                val lvl = start + row * 5 + cc + 1
                val label = if (lvl <= done) "OK $lvl" else if (lvl == done + 1) ">> $lvl" else "# $lvl"
                l.addW(btn(label) { if (lvl <= done + 1) storyIntro(lvl) else toast("Locked. Clear level ${lvl - 1} first") })
            }
            box.addView(l)
        }
    }
}

fun storyConfig(l: Int, lg: LocalGame) {
    lg.level = l; lg.diff = if (l <= 60) 0 else if (l <= 140) 1 else 2
    lg.goal = (l - 1) % 4; lg.goalLand = 1000L + l * 60L; lg.limitMin = 8 + l / 8
}

fun MainActivity.storyIntro(lvl: Int) {
    val probe = LocalGame(); storyConfig(lvl, probe)
    val bots = minOf(8, 1 + (lvl - 1) / 25)
    var d: Dialog? = null
    d = popup("LEVEL $lvl", col(tv(storyName(lvl), 18f, C.GOLD, true), tv("Enemies: $bots | " + listOf("Easy", "Normal", "Hard")[probe.diff], 13f),
        tv("Goal: " + probe.goalText(), 13f, C.TXT, true),
        btn("START", R.drawable.ic_play) { d?.dismiss(); startStory(lvl) }))
}

fun MainActivity.startStory(lvl: Int) {
    val lg = LocalGame(); storyConfig(lvl, lg)
    lg.setup(if (ud.nick.isNotEmpty()) ud.nick else "Player", minOf(8, 1 + (lvl - 1) / 25), lg.diff, 1500L + lvl * 20L)
    startOffline(lg)
}

fun MainActivity.startOffline(lg: LocalGame) {
    Net.local = true; game = lg; seenReq.clear(); newsSeen = -1
    lg.onUpdate = { refresh() }
    lg.onEnd = { win, msg -> runOnUiThread { offlineResult(lg, win, msg) } }
    showGame(lg)
}

fun MainActivity.offlineResult(lg: LocalGame, win: Boolean, msg: String) {
    if (win && lg.level > 0) {
        val p = getSharedPreferences("dpl", 0)
        if (p.getInt("story", 0) < lg.level) p.edit().putInt("story", lg.level).apply()
    }
    var d: Dialog? = null
    val items = ArrayList<android.view.View>()
    items.add(tv(msg, 14f, C.TXT, true))
    if (win && lg.level in 1..199) items.add(btn("NEXT LEVEL", R.drawable.ic_play) { d?.dismiss(); leaveGame(); startStory(lg.level + 1) })
    if (lg.level > 0) items.add(btn("RETRY", R.drawable.ic_attack) { d?.dismiss(); leaveGame(); startStory(lg.level) })
    items.add(btn("MENU", R.drawable.ic_exit) { d?.dismiss(); leaveGame() })
    d = popup(if (win) "VICTORY!" else "DEFEAT", col(*items.toTypedArray()))
}

fun MainActivity.offlineAction(g: LocalGame) {
    var d: Dialog? = null
    fun go(f: () -> Unit): () -> Unit = { d?.dismiss(); f() }
    d = popup(L.t("Action").uppercase(), col(
        two(btn(L.t("BUILD"), R.drawable.ic_build, go { buildPopup(g) }), btn(L.t("CREATE SOLDIER"), R.drawable.ic_soldier, go { soldierPopup(g) })),
        two(btn(L.t("TRADE"), R.drawable.ic_trade, go { tradePopup(g) }), btn("SAHAM", R.drawable.ic_chart, go { sahamPopup(g) })),
        two(btn("ITEMS", R.drawable.ic_gift, go { itemsPopup(g) }), btn("CRAFT", R.drawable.b_work, go { craftPopup(g) })),
        two(btn("CRAFT UNITS", R.drawable.ic_nuke, go { craftUnitsPopup(g) }), btn("LEADERBOARD", R.drawable.ic_rank, go { rankPopup(g) }))))
}

fun MainActivity.offlineAttack(g: LocalGame) {
    val me = g.me ?: return
    val box = col(); var d: Dialog? = null
    box.addView(tv("Goal: " + g.goalText(), 13f, C.GOLD, true))
    if (g.limitMin > 0 && !g.sandbox) box.addView(tv("Time left: ${g.minutesLeft()} min", 12f, C.GRAY))
    box.addView(tv("My strength: ${fmt(me.power())}", 13f))
    val en = g.players.entries.filter { it.key != g.uid }
    if (en.isEmpty()) box.addView(tv("No enemies left.", 13f, C.GRAY))
    else {
        val pct = edit("1-100", num = true); pct.setText("60")
        val to = Cycle(this, "TARGET", en.map { it.value.country + " (" + fmt(it.value.landTotal()) + " km)" })
        box.addView(lab("UNIT (%) :", pct)); box.addView(to.view)
        box.addView(tv("Marching time depends on distance. Tap empty land near you to expand.", 11f, C.GRAY))
        box.addView(btn("ATTACK", R.drawable.ic_attack) {
            val e = g.attack(en[to.i].key, (pct.text.toString().toIntOrNull() ?: 0).coerceIn(1, 100))
            if (e != null) { Snd.play("error"); toast(e) } else { Snd.play("war"); toast("Army on the move"); d?.dismiss() }
        })
    }
    if (g.incoming() > 0) box.addView(tv("Armies marching: ${g.incoming()}", 12f, 0xFFFFD54F.toInt()))
    d = popup("CONQUER", box)
}

fun MainActivity.campaignMenu() {
    val dif = Cycle(this, "Difficulty", listOf("Easy", "Normal", "Hard"), 1)
    var dlg: Dialog? = null
    dlg = liveDialog("CAMPAIGN - pick your country", true) { box ->
        box.addView(tv("Real-world countries. Bots fight each other too. No fog. Goal: control 25% of the world.", 12f, C.GRAY))
        box.addView(dif.view)
        val cd = CountryData.built
        val order = cd.names.indices.filter { cd.cells[it].isNotEmpty() }.sortedByDescending { cd.cells[it].size }
        for (ci in order) {
            val r = LinearLayout(this); r.gravity = Gravity.CENTER_VERTICAL
            r.addW(tv("${cd.names[ci]}  -  ${fmt(cd.cells[ci].size * 100L)} km", 13f))
            r.addView(btn("PLAY", R.drawable.ic_play) { dlg?.dismiss(); startCampaign(ci, dif.i) })
            box.addView(panel(r))
        }
    }
}

fun MainActivity.startCampaign(ci: Int, diff: Int) {
    val lg = LocalGame()
    lg.setupCampaign(ci, if (ud.nick.isNotEmpty()) ud.nick else "Player", diff)
    startOffline(lg)
}

fun MainActivity.offlineDiplomacy(g: LocalGame, u: String) {
    val p = g.players[u] ?: return
    val st = when (g.relOf(u)) { "war" -> "WAR"; "ally" -> "ALLIANCE"; "peace" -> "PEACE TREATY"; else -> "NEUTRAL" }
    var d: Dialog? = null
    val amt = edit("gold amount", num = true)
    d = popup("Diplomasi", col(tv("${p.country}  -  $st  |  ${fmt(p.landTotal())} km", 14f, C.TXT, true),
        two(btn("MENYATAKAN PERANG", R.drawable.ic_swords) { g.setRelLocal(u, "war"); Snd.play("war"); toast("War declared!"); d?.dismiss() },
            btn("Make peace", R.drawable.ic_shield) { toast(g.propose(u, "peace")) }),
        two(btn("ALIANSI", R.drawable.ic_shield) { toast(g.propose(u, "ally")) },
            btn("Memutuskan Perdamaian", R.drawable.ic_exit) {
                if (g.relOf(u) == "peace" || g.relOf(u) == "ally") { g.setRelLocal(u, ""); toast("Treaty broken") } else toast("No treaty to break") }),
        lab("Gold :", amt),
        btn("send gold", R.drawable.ic_gold) {
            val e = g.gift(u, amt.text.toString().toLongOrNull() ?: 0L)
            if (e != null) { Snd.play("error"); toast(e) } else { Snd.play("coin"); toast("Sent!") } },
        btn("ATTACK", R.drawable.ic_attack) { d?.dismiss(); offlineAttack(g) }))
}
