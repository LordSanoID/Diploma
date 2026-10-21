package com.sano.dpl

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.widget.*
import com.google.firebase.database.ServerValue

// ================= ACTION MENU =================
fun MainActivity.actionMenu(g: Game) {
    var d: Dialog? = null
    fun go(f: () -> Unit): () -> Unit = { d?.dismiss(); f() }
    val b = col(
        two(btn(L.t("BUILD"), R.drawable.ic_build, go { buildPopup(g) }), btn(L.t("CREATE SOLDIER"), R.drawable.ic_soldier, go { soldierPopup(g) })),
        two(btn(L.t("SHOP"), R.drawable.ic_shop, go { shopPopup(g) }), btn(L.t("TRADE"), R.drawable.ic_trade, go { tradePopup(g) })),
        two(btn(L.t("ATTACK"), R.drawable.ic_attack, go { attackPopup(g) }), btn(L.t("WAR"), R.drawable.ic_war, go { warPopup(g) })),
        two(btn("LEADERBOARD", R.drawable.ic_rank, go { rankPopup(g) }), btn("DAILY REWARD", R.drawable.ic_gift, go {
            val e = g.daily(); if (e == null) Snd.play("coin"); toast(e ?: "Daily reward: +1000 gold, +200 food, +200 resource") })),
        two(btn("CONVERT GOLD", R.drawable.ic_gem, go { convertPopup(g) }), btn("BUY NUKE", R.drawable.ic_nuke, go { nukePopup(g) })),
        two(btn("SAHAM", R.drawable.ic_chart, go { sahamPopup(g) }), btn("ITEMS", R.drawable.ic_gift, go { itemsPopup(g) })),
        two(btn("CRAFT", R.drawable.b_work, go { craftPopup(g) }), btn("CRAFT UNITS", R.drawable.ic_nuke, go { craftUnitsPopup(g) })),
        btn("FACTORY", R.drawable.b_factory, go { factoryPopup(g) }))
    d = popup(L.t("Action").uppercase(), b)
}
fun MainActivity.convertPopup(g: Game) {
    val amt = edit("Premium Gold (multiple of 50)", num = true); val res = tv("", 13f, C.GOLD, true)
    val upd = { val n = amt.text.toString().toLongOrNull() ?: 0L; res.text = "= ${fmt(n / 50 * 1000)} server gold" }
    amt.onText(upd); upd()
    var d: Dialog? = null
    d = popup("CONVERT GOLD", col(tv("Premium Gold : ${fmt(ud.pgold)}", 14f, 0xFFE040FB.toInt(), true),
        tv("50 Premium Gold = 1000 server gold", 12f, C.GRAY), amt, res,
        btn("CONVERT", R.drawable.ic_gem) {
            val n = amt.text.toString().toLongOrNull() ?: 0L
            if (n < 50 || n % 50 != 0L) toast("Use a multiple of 50")
            else if (n > ud.pgold) { Snd.play("error"); toast("Not enough Premium Gold") }
            else {
                Net.db.child("users/${ud.uid}/pgold").setValue(ServerValue.increment(-n))
                g.addGold(n / 50 * 1000); Snd.play("coin"); toast("Converted!"); d?.dismiss()
            }
        }))
}

fun MainActivity.nukePopup(g: Game) {
    val amt = edit("Amount", num = true); amt.setText("1")
    var d: Dialog? = null
    d = popup("BUY NUKE", col(tv("Premium Gold : ${fmt(ud.pgold)}", 14f, 0xFFE040FB.toInt(), true),
        tv("100 Premium Gold = 1 nuke", 12f, C.GRAY),
        tv(if (g.nuke) "Nuke is ENABLED on this server" else "Nuke is DISABLED on this server", 12f, if (g.nuke) 0xFF66BB6A.toInt() else 0xFFEF5350.toInt()),
        amt, btn("BUY", R.drawable.ic_nuke) {
            val n = amt.text.toString().toLongOrNull() ?: 0L
            if (n < 1) toast("Invalid amount")
            else if (n * 100 > ud.pgold) { Snd.play("error"); toast("Not enough Premium Gold") }
            else {
                Net.db.child("users/${ud.uid}/pgold").setValue(ServerValue.increment(-n * 100))
                g.buyNuke(n); Snd.play("coin"); toast("Bought $n nuke"); d?.dismiss()
            }
        }))
}

// ================= ATTACK =================
fun MainActivity.attackPopup(g: Game) {
    val me = g.me ?: return
    val box = col(); var d: Dialog? = null
    box.addView(tv("My strength : ${fmt(me.power())}⚡", 14f, C.GOLD, true))
    val en = g.players.entries.filter { it.key != g.uid && g.relOf(it.key) == "war" }
    if (en.isEmpty()) box.addView(tv("No country is at war with you. Long-press a territory on the map, then declare war.", 13f, C.GRAY))
    else {
        val pct = edit("1-100", num = true); pct.setText("100")
        val to = Cycle(this, "TO", en.map { it.value.country })
        box.addView(lab("UNIT (%) :", pct)); box.addView(to.view)
        box.addView(tv("Prep 5 min -> travel (by distance) -> monitoring 2-7 min -> battle", 11f, C.GRAY))
        box.addView(btn("ATTACK", R.drawable.ic_attack) {
            val e = g.launch(en[to.i].key, pct.text.toString().toIntOrNull() ?: 0)
            if (e != null) { Snd.play("error"); toast(e) } else { toast("Troops are preparing"); d?.dismiss() } })
    }
    for ((id, a) in g.atks) if (a.from == g.uid) {
        val mins = { t: Long -> maxOf(0L, (t - now()) / 60000L + 1) }
        val st = when (a.state) { "prep" -> "Preparing (${mins(a.prepEnd)} min)"; "travel" -> "Traveling (~${mins(a.travelEnd)} min)"; else -> "Monitoring" }
        val r = LinearLayout(this); r.gravity = Gravity.CENTER_VERTICAL
        r.addW(tv("-> ${a.toName} | ${me.powOf(a.units)}⚡ | $st", 12f))
        if (a.state != "watch") r.addView(btn("CANCEL") { g.cancelAttack(id); d?.dismiss() })
        box.addView(panel(r))
    }
    d = popup("ATTACK", box)
}

// ================= CHAT =================
fun MainActivity.chatBubble(m: Msg): View {
    val dev = m.badge.contains("DEV")
    val role = if (dev) "dev" else if (m.badge == "MOD") "mod" else m.role
    val roleC = if (dev) 0xFF00E676.toInt() else if (m.badge == "MOD") 0xFFFFB300.toInt() else if (m.role == "admin") C.GOLD else 0xFF90CAF9.toInt()
    val gray = 0xFF7A8294.toInt()
    val sb = SpannableStringBuilder()
    fun add(t: String, c: Int, bold: Boolean = false) {
        val s0 = sb.length; sb.append(t)
        sb.setSpan(ForegroundColorSpan(c), s0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (bold) sb.setSpan(StyleSpan(Typeface.BOLD), s0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    add(hm(m.t) + " ", gray)
    if (dev) {
        val s0 = sb.length; sb.append(" ")
        val dr = getDrawable(R.drawable.ic_verified)
        if (dr != null) { dr.setBounds(0, 0, dp(14), dp(14)); sb.setSpan(ImageSpan(dr, ImageSpan.ALIGN_BOTTOM), s0, s0 + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        sb.append(" ")
    }
    add("{", gray); add(role, roleC, true); add(" | ", gray)
    if (m.title.isNotEmpty()) add("<" + m.title + "> ", 0xFFFFD54F.toInt())
    add(m.nick, if (m.cc != 0) m.cc else C.TXT, true); add(" | ", gray); add(m.country, 0xFF4DD0E1.toInt()); add("} ", gray)
    if (m.to.isNotEmpty()) add("[PM] ", 0xFFE040FB.toInt(), true)
    add(m.msg, if (m.cc != 0) m.cc else C.TXT)
    val t = TextView(this); t.text = sb; t.textSize = 13f
    t.setPadding(dp(10), dp(6), dp(10), dp(6))
    t.background = grad(0xFF1B212C.toInt(), 0xFF141820.toInt(), if (dev) 0xFF1D5FE8.toInt() else 0xFF2C3341.toInt(), 10, 1)
    return t
}

fun MainActivity.chatPopup(g: Game) {
    val hist = LinearLayout(this); hist.orientation = LinearLayout.VERTICAL
    val sv = ScrollView(this); sv.addView(hist)
    val input = edit("Message or /help")
    val body = LinearLayout(this); body.orientation = LinearLayout.VERTICAL
    body.addView(sv, LinearLayout.LayoutParams(-1, dp(200)))
    fun renderAll() {
        hist.removeAllViews()
        val rows = ArrayList<Pair<Long, View>>()
        for (m in g.chat) { if (m.to.isNotEmpty() && m.to != g.uid && m.from != g.uid) continue; rows.add(Pair(m.t, chatBubble(m))) }
        for (l in localChat) {
            val t = tv(hm(l.first) + "  " + l.second, 12f, 0xFFFFD54F.toInt()); t.setPadding(dp(10), dp(4), dp(10), dp(4)); rows.add(Pair(l.first, t))
        }
        rows.sortBy { it.first }
        for (r in rows) hist.addView(r.second, LinearLayout.LayoutParams(-1, -2).also { it.setMargins(0, dp(3), 0, dp(3)) })
        sv.post { sv.fullScroll(View.FOCUS_DOWN) }
    }
    val r = LinearLayout(this); r.gravity = Gravity.CENTER_VERTICAL
    r.addW(input)
    r.addView(btn("Send", R.drawable.ic_send) {
        g.send(input.text.toString()) { s -> for (ln in s.split("\n")) localChat.add(Pair(now(), ln)); renderAll() }
        input.setText("")
    })
    body.addView(r, LinearLayout.LayoutParams(-1, -2))
    renderAll(); live = { renderAll() }
    chatDlg = popup(L.t("Global Chat").uppercase(), body, true) { live = null; chatDlg = null }
}
// ================= DIPLOMASI =================
fun MainActivity.diplomacy(g: Game, u: String) {
    val p = g.players[u] ?: return
    val st = when (g.relOf(u)) { "war" -> "WAR"; "ally" -> "ALLIANCE"; "peace" -> "PEACE TREATY"; else -> "NEUTRAL" }
    var d: Dialog? = null
    fun msg(e: String?, ok: String) { if (e != null) Snd.play("error"); toast(e ?: ok) }
    val b = col(tv("${p.country} | ${p.nick}  -  $st", 14f, C.TXT, true),
        two(btn("MENYATAKAN PERANG", R.drawable.ic_swords) { msg(g.declareWar(u), "War declared!"); d?.dismiss() },
            btn("Make peace", R.drawable.ic_shield) {
                if (g.relOf(u) != "war") toast("You are not at war") else { g.sendReq(u, "peace"); toast("Peace proposal sent") } }),
        two(btn("send gold", R.drawable.ic_gold) { giveDialog(g, u, "gold") }, btn("send food", R.drawable.ic_food) { giveDialog(g, u, "food") },
            btn("send resource", R.drawable.ic_res) { giveDialog(g, u, "res") }),
        two(btn("Perjanjian Perdamaian", R.drawable.ic_shield) {
            val r = g.relOf(u)
            if (r == "war") toast("End the war first") else if (r != "") toast("You already have a treaty") else { g.sendReq(u, "pact"); toast("Treaty proposal sent") } },
            btn("Memutuskan Perdamaian", R.drawable.ic_exit) { msg(g.breakPact(u), "Treaty broken"); d?.dismiss() }),
        two(btn("ALIANSI", R.drawable.ic_shield) {
            val r = g.relOf(u)
            if (r == "war") toast("End the war first") else if (r == "ally") toast("Already allied") else { g.sendReq(u, "ally"); toast("Alliance proposal sent") } },
            btn("Kirim Pesan", R.drawable.ic_msg) { d?.dismiss(); pmDialog(g, u) }),
        btn("Luncurkan Nuke", R.drawable.ic_nuke) { msg(g.nuke(u), "NUKE LAUNCHED!"); d?.dismiss() })
    d = popup("Diplomasi", b)
}

fun MainActivity.pmDialog(g: Game, u: String) {
    val t = edit("Message"); var d: Dialog? = null
    d = popup("KIRIM PESAN - ${g.cn(u)}", col(t, btn("SEND", R.drawable.ic_send) {
        if (t.text.toString().isBlank()) toast("Empty message") else { g.pm(u, t.text.toString()); toast("Message sent"); d?.dismiss() } }))
}

// ================= TUTORIAL =================
fun MainActivity.tutorial(step: Int) {
    val tips = listOf(
        "Halo! Aku Dippy, maskotmu. Aku bantu kamu memimpin negara!",
        "Geser dan cubit peta untuk zoom. Tekan lama wilayah negara lain untuk diplomasi.",
        "Buka Action > BUILD. Bangun City dan Village supaya gold, food, resource terus masuk.",
        "Buat pasukan di CREATE SOLDIER. Saat perang, kirim lewat ATTACK. Perang butuh persiapan dan perjalanan.",
        "Chat pakai /help untuk lihat command. Selamat bermain!")
    var d: Dialog? = null
    val last = step >= tips.size - 1
    d = popup("TUTORIAL ${step + 1}/${tips.size}", col(mascot(tips[step], 100), two(
        btn("SKIP") { getSharedPreferences("dpl", 0).edit().putBoolean("tut", true).apply(); d?.dismiss() },
        btn(if (last) "DONE" else "NEXT", R.drawable.ic_play) {
            d?.dismiss()
            if (last) getSharedPreferences("dpl", 0).edit().putBoolean("tut", true).apply() else tutorial(step + 1)
        })))
}

// ================= (tidak berubah) =================
fun MainActivity.buildPopup(g: Game) {
    liveDialog(L.t("BUILD")) { box ->
        val me = g.me ?: return@liveDialog
        box.addView(tv("${L.t("Land")} free: ${me.landTotal() - me.landUsed()} / ${me.landTotal()} km", 13f, C.GRAY))
        for (b in BLDS) {
            val inc = StringBuilder()
            if (b.rg > 0) inc.append("+${b.rg} gold/h  "); if (b.rr > 0) inc.append("+${b.rr} resource/h  ")
            if (b.rf > 0) inc.append("+${b.rf} food/h  "); inc.append(b.note)
            val info = col(tv("${b.name}  (x${me.b(b.id)})", 15f, C.GOLD, true), tv(inc.toString(), 12f),
                tv("Cost: ${b.land} km | ${fmt(b.gold)} gold | ${b.food} food | ${b.res} resource", 11f, C.GRAY))
            val r = LinearLayout(this); r.gravity = Gravity.CENTER_VERTICAL
            r.addView(img(b.icon, 40)); r.addW(info)
            r.addView(btn(L.t("BUILD")) { val e = g.build(b); if (e != null) toast(e) })
            box.addView(panel(r))
        }
    }
}

fun MainActivity.soldierPopup(g: Game) {
    liveDialog("SOLDIER", true) { box ->
        val me = g.me ?: return@liveDialog
        var total = 0L; for (u in UNITS) total += me.u(u.id)
        val pw = me.power(); val comp = ArrayList<String>()
        for (u in UNITS) { val n = me.u(u.id); if (n > 0 && pw > 0) comp.add("${u.name} ${n * u.pow * 100 / pw}%") }
        box.addView(panel(tv("MY SOLDIER : ${fmt(total)}", 14f, C.TXT, true), tv("STRENGTH (⚡) : ${fmt(pw)}", 14f, C.GOLD, true),
            tv(if (comp.isEmpty()) "-" else comp.joinToString(" | "), 12f, C.GRAY)))
        box.addView(tv("CREATE SOLDIER", 15f, C.GOLD, true))
        for (u in UNITS) {
            val info = col(tv(u.name.uppercase(), 15f, C.GOLD, true),
                tv("-${u.gold}gold/unit  -${u.food}food/unit  -${u.res}resource/unit", 11f, C.GRAY), tv("+${u.pow}⚡", 13f, C.TXT, true))
            val r = LinearLayout(this); r.gravity = Gravity.CENTER_VERTICAL
            r.addView(img(u.icon, 40)); r.addW(info)
            r.addView(btn(L.t("BUILD")) { amountPopup(g, u) })
            box.addView(panel(r))
        }
    }
}

fun MainActivity.amountPopup(g: Game, u: UnitDef) {
    val amt = edit("0", num = true); val cost = tv("", 13f, C.GOLD, true)
    val upd = { val n = amt.text.toString().toLongOrNull() ?: 0L
        cost.text = "Total Biaya : ${fmt(u.gold * n)} gold, ${fmt(u.food * n)} food, ${fmt(u.res * n)} resource" }
    amt.onText(upd); upd()
    var d: Dialog? = null
    val b = col(tv("NAME : ${u.name}", 14f, C.TXT, true), lab("AMOUNT :", amt), cost, btn(L.t("BUILD"), R.drawable.ic_build) {
        val e = g.train(u, amt.text.toString().toLongOrNull() ?: 0L); if (e != null) toast(e) else d?.dismiss() })
    d = popup("CREATE SOLDIER", b)
}

fun typeName(t: String): String = unitDef(t)?.name ?: if (t == "food") "Food" else "Resource"

fun MainActivity.shopPopup(g: Game) {
    liveDialog("SHOP", true) { box ->
        val me = g.me ?: return@liveDialog
        box.addView(tv("gold : ${fmt(me.gold)}", 14f, C.GOLD, true))
        if (g.shop.isEmpty()) box.addView(tv("Nothing for sale yet.", 13f, C.GRAY))
        for ((id, s) in g.shop) {
            val own = s.seller == g.uid
            val r = LinearLayout(this); r.gravity = Gravity.CENTER_VERTICAL
            r.addW(tv("${s.sname} Selling ${fmt(s.amt)} ${typeName(s.type)} For ${fmt(s.price)}Gold", 13f))
            r.addView(if (own) btn("Cancel") { g.cancelSale(id) } else btn("Buy") { val e = g.buy(id); if (e != null) toast(e) })
            box.addView(panel(r))
        }
        box.addView(btn("SELL", R.drawable.ic_shop) { sellPopup(g) })
    }
}

fun MainActivity.sellPopup(g: Game) {
    val ids = ArrayList<String>(); val names = ArrayList<String>()
    for (u in UNITS) { ids.add(u.id); names.add(u.name.lowercase()) }
    ids.add("food"); names.add("food"); ids.add("res"); names.add("resource")
    val type = Cycle(this, "TYPE", names); val amt = edit("0", num = true); val price = edit("0", num = true)
    var d: Dialog? = null
    val b = col(type.view, lab("AMOUNT :", amt), lab("HARGA :", price), btn("SELL", R.drawable.ic_shop) {
        val e = g.sell(ids[type.i], amt.text.toString().toLongOrNull() ?: 0L, price.text.toString().toLongOrNull() ?: 0L)
        if (e != null) toast(e) else d?.dismiss() })
    d = popup("SELLING", b)
}

fun MainActivity.tradePopup(g: Game) {
    val pairs = listOf("gold" to "food", "gold" to "res", "food" to "gold", "food" to "res", "res" to "gold", "res" to "food")
    val nm = { s: String -> if (s == "res") "RESOURCE" else s.uppercase() }
    val box = col()
    for ((a, b) in pairs) {
        val amt = edit("amount", num = true); val res = tv("| 0", 13f, C.GOLD, true)
        amt.onText { res.text = "| " + fmt(g.rate(a, b, amt.text.toString().toLongOrNull() ?: 0L)) }
        val r = LinearLayout(this); r.gravity = Gravity.CENTER_VERTICAL
        r.addView(tv("${nm(a)} -> ${nm(b)} :", 12f, C.GRAY, true), LinearLayout.LayoutParams(dp(140), -2))
        r.addW(amt); r.addView(res, LinearLayout.LayoutParams(dp(80), -2))
        r.addView(btn("CONTINUE") { val e = g.trade(a, b, amt.text.toString().toLongOrNull() ?: 0L); if (e != null) toast(e) else { amt.setText("") } })
        box.addView(r)
    }
    box.addView(tv("Fee 10%. Rates: gold 1 | food 0.8 | resource 1.25", 11f, C.GRAY))
    popup("TRADE", box, true)
}

fun MainActivity.warPopup(g: Game) {
    liveDialog("LIST WAR", true) { box ->
        var n = 0
        for ((_, w) in g.wars) {
            if (w.status == "ended" || (w.a != g.uid && w.d != g.uid)) continue
            n++
            val mine = if (w.a == g.uid) w.aPow else w.dPow; val en = if (w.a == g.uid) w.dPow else w.aPow
            val tot = maxOf(1L, mine + en)
            box.addView(panel(tv("${w.aName} vs ${w.dName}" + (if (w.status == "frozen") "  (STALEMATE)" else ""), 15f, C.GOLD, true),
                tv("Pasukan Saya : ${mine * 100 / tot}% (${fmt(mine)}⚡) | Pasukan Musuh : ${en * 100 / tot}% (${fmt(en)}⚡)", 12f)))
        }
        for ((_, a) in g.atks) if (a.to == g.uid) box.addView(tv("Incoming: ${a.fromName} (${a.state})", 12f, 0xFFEF5350.toInt()))
        if (n == 0) box.addView(tv("No active wars.", 13f, C.GRAY))
    }
}

fun MainActivity.newsPopup(g: Game) {
    liveDialog("NEWS", true) { box ->
        if (g.news.isEmpty()) box.addView(tv("No news yet.", 13f, C.GRAY))
        for (x in g.news.reversed()) box.addView(tv("(${hm(x.t)}) ${x.msg}", 13f))
    }
}

fun MainActivity.rankPopup(g: Game) {
    liveDialog("LEADERBOARD") { box ->
        var claimed = 0L; for (pp in g.players.values) claimed += pp.landTotal()
        box.addView(tv("World total: 19,000,000,000 km | Claimed: ${fmt(claimed)} km", 12f, C.GRAY))
        var i = 0
        for (e in g.players.entries.sortedByDescending { it.value.power() }) {
            i++
            box.addView(tv("#$i  ${e.value.country} (${e.value.nick})  ${fmt(e.value.power())}⚡  ${e.value.landTotal()} km", 13f,
                if (e.key == g.uid) C.GOLD else C.TXT))
        }
    }
}

fun MainActivity.giveDialog(g: Game, u: String, kind: String) {
    val amt = edit("amount", num = true); var d: Dialog? = null
    d = popup("SEND ${kind.uppercase()}", col(amt, btn("SEND", R.drawable.ic_send) {
        val e = g.give(u, kind, amt.text.toString().toLongOrNull() ?: 0L); if (e != null) toast(e) else { toast("Sent!"); d?.dismiss() } }))
}

fun MainActivity.reqPopup(g: Game, id: String, r: Req) {
    Snd.play("notif")
    val what = when (r.type) { "ally" -> "an alliance"; "pact" -> "a peace treaty"; else -> "peace (end of war)" }
    var d: Dialog? = null
    d = popup("DIPLOMACY", col(tv("${g.cn(r.from)} (${r.fromName}) proposes $what.", 14f),
        two(btn("ACCEPT", R.drawable.ic_shield) { g.answer(id, r, true); d?.dismiss() },
            btn("REJECT", R.drawable.ic_exit) { g.answer(id, r, false); d?.dismiss() })))
}

// ================= EKSPANSI WILAYAH KOSONG =================
fun MainActivity.tapCell(g: Game, x: Int, y: Int) {
    val me = g.me ?: return
    for ((_, p) in g.players) if (p.hit(x, y)) { toast("${p.country} | ${p.nick}"); return }
    if (!World.land(x, y)) return
    if (me.elast > 0) { toast("Already expanding. Stop it first."); return }
    var d: Dialog? = null
    d = popup("EXPAND TERRITORY", col(tv("Claim this empty land?", 14f, C.TXT, true),
        tv("Every 5 seconds you gain +5 km. 100 km makes the cell yours. Land must be next to your territory. Needs at least 1 power.", 12f, C.GRAY),
        btn("START", R.drawable.ic_attack) {
            val e = g.startExpand(x, y)
            if (e != null) { Snd.play("error"); toast(e) } else { Snd.play("war"); toast("Claiming: +5 km every 5 seconds"); d?.dismiss(); pollExpand(g) }
        }))
}

// ================= SAHAM EMAS =================
class PriceChart(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(cv: Canvas) {
        val n = 60; val t0 = now() / 60000L
        val v = LongArray(n) { goldPrice(t0 - (n - 1 - it)) }
        var mn = v[0]; var mx = v[0]
        for (x in v) { if (x < mn) mn = x; if (x > mx) mx = x }
        val w = width.toFloat(); val h = height.toFloat()
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = 0xFF2C3341.toInt()
        for (i in 1..3) cv.drawLine(0f, h * i / 4, w, h * i / 4, p)
        p.strokeWidth = 4f; p.color = 0xFFFFD54F.toInt()
        val path = Path()
        for (i in 0 until n) {
            val x = w * i / (n - 1)
            val y = h - 10f - (h - 20f) * (v[i] - mn).toFloat() / maxOf(1L, mx - mn).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        cv.drawPath(path, p)
        p.style = Paint.Style.FILL; p.textSize = 22f; p.color = 0xFF8A8F98.toInt()
        cv.drawText(fmt(mx), 6f, 22f, p); cv.drawText(fmt(mn), 6f, h - 4f, p)
    }
}

fun MainActivity.sahamPopup(g: Game) {
    liveDialog("SAHAM", true) { box ->
        val me = g.me ?: return@liveDialog
        val pr = goldPrice(now() / 60000L); val old = goldPrice(now() / 60000L - 10)
        val ch = (pr - old) * 100.0 / old
        val chart = PriceChart(this); chart.minimumHeight = dp(120)
        box.addView(panel(row(img(R.drawable.ic_gold, 20), tv("Money : ${fmt(me.gold)}", 15f, C.GOLD, true)),
            row(img(R.drawable.ic_gem, 20), tv("My Gold : ${fmt(me.gram)} g", 15f, 0xFFFFD54F.toInt(), true))))
        box.addView(panel(tv("Gold price now : ${fmt(pr)} / g", 15f, C.TXT, true),
            tv(String.format("%+.1f%% (10 min)", ch), 13f, if (ch >= 0) 0xFF66BB6A.toInt() else 0xFFEF5350.toInt(), true), chart))
        box.addView(two(btn("BELI", R.drawable.ic_trade) { gramDialog(g, true) }, btn("JUAL", R.drawable.ic_trade) { gramDialog(g, false) }))
    }
}

fun MainActivity.gramDialog(g: Game, buy: Boolean) {
    val amt = edit("grams", num = true); val tot = tv("", 13f, C.GOLD, true)
    val upd = { val n = amt.text.toString().toLongOrNull() ?: 0L; tot.text = "TOTAL : ${fmt(n * g.gramPrice())} gold" }
    amt.onText(upd); upd()
    var d: Dialog? = null
    d = popup(if (buy) "BUY" else "SELL", col(lab("AMOUNT :", amt), tot, btn(if (buy) "BUY" else "SELL", R.drawable.ic_trade) {
        val n = amt.text.toString().toLongOrNull() ?: 0L
        val e = if (buy) g.buyGram(n) else g.sellGram(n)
        if (e != null) { Snd.play("error"); toast(e) } else { toast("Done!"); d?.dismiss() }
    }))
}

// ================= ITEM & CRAFT =================
fun MainActivity.itemsPopup(g: Game) {
    liveDialog("ITEMS", true) { box ->
        val me = g.me ?: return@liveDialog
        val owned = me.inv.entries.filter { it.value > 0 }.sortedByDescending { itemValue(it.key) * it.value }
        box.addView(tv("${owned.size} kinds owned | $ITEM_COUNT items exist", 12f, C.GRAY))
        if (owned.isEmpty()) box.addView(tv("No items yet. Build Mines, Factories and Fortresses to produce materials.", 13f, C.GRAY))
        for (e in owned.take(60)) {
            val r = LinearLayout(this); r.gravity = Gravity.CENTER_VERTICAL
            r.addW(tv("${itemName(e.key)}  x${fmt(e.value)}\nvalue ${fmt(itemValue(e.key))} each", 12f))
            r.addView(btn("SELL 1") { val er = g.sellItem(e.key, 1); if (er != null) toast(er) })
            r.addView(btn("ALL") { val er = g.sellItem(e.key, e.value); if (er != null) toast(er) })
            box.addView(panel(r))
        }
    }
}

fun MainActivity.craftPopup(g: Game) {
    val mat = Cycle(this, "Material", MATS, 0) { live?.invoke() }
    val form = Cycle(this, "Type", FORMS, 0) { live?.invoke() }
    liveDialog("CRAFT", true) { box ->
        val me = g.me ?: return@liveDialog
        box.addView(tv("Workshops: ${me.b("workshop")} (needed to craft)", 12f, C.GRAY))
        box.addView(two(mat.view, form.view))
        for (t in 1 until TIERS.size) {
            val prev = itemId(mat.i, form.i, t - 1); val cur = itemId(mat.i, form.i, t)
            val have = me.inv[prev] ?: 0L
            val r = LinearLayout(this); r.gravity = Gravity.CENTER_VERTICAL
            r.addW(tv("${itemName(cur)}  (own ${me.inv[cur] ?: 0})\n2x ${itemName(prev)} (have $have) + ${100 * t} gold", 12f, if (have >= 2) C.TXT else C.GRAY))
            r.addView(btn("CRAFT") { val e = g.craft(mat.i, form.i, t); if (e != null) { Snd.play("error"); toast(e) } })
            box.addView(panel(r))
        }
    }
}

fun MainActivity.craftUnitsPopup(g: Game) {
    liveDialog("CRAFT UNITS", true) { box ->
        val me = g.me ?: return@liveDialog
        box.addView(tv("Workshops: ${me.b("workshop")} (needed). Units are made from crafted items.", 12f, C.GRAY))
        for (rc in RECIPES) {
            val u = unitDef(rc.unitId) ?: continue
            val need = StringBuilder()
            for ((item, n) in rc.items) need.append("${n}x ${itemName(item)} (have ${me.inv[item] ?: 0})\n")
            val r = LinearLayout(this); r.gravity = Gravity.CENTER_VERTICAL
            r.addView(img(u.icon, 36))
            r.addW(tv("${u.name}  +${u.pow} power | own ${me.u(u.id)}\n$need${fmt(rc.gold)} gold", 12f))
            r.addView(btn("CRAFT") { val e = g.craftUnit(rc.unitId); if (e != null) { Snd.play("error"); toast(e) } })
            box.addView(panel(r))
        }
    }
}

fun MainActivity.pollExpand(g: Game) {
    h.postDelayed(object : Runnable {
        override fun run() {
            val m = g.me
            if (gameShown && game === g && m != null && m.elast > 0L) { g.expandTick(); h.postDelayed(this, 5200L) }
        }
    }, 5200L)
}

fun MainActivity.factoryPopup(g: Game) {
    liveDialog("FACTORY", true) { box ->
        val me = g.me ?: return@liveDialog
        val f = g.myFactory()
        if (f != null) {
            val id = me.job
            val own = f.owner == g.uid
            val ownerP = g.players[f.owner]
            val n = g.fwork[id]?.size ?: 0
            val en = g.energy()
            box.addView(panel(tv(f.name, 17f, C.GOLD, true), tv("Owner : ${ownerP?.nick ?: "?"}", 13f),
                tv("Location : ${ownerP?.country ?: "?"}", 13f),
                tv("Salary : ${fmt(f.salGold())} Money, ${fmt(f.salFood())} Food, ${fmt(f.salRes())} Resource", 13f),
                tv("Level ${f.level} | Workers $n/${f.maxEmp()}", 12f, C.GRAY),
                tv("Energy : $en%", 15f, if (en >= 100) 0xFF66BB6A.toInt() else 0xFFFFB300.toInt(), true)))
            box.addView(btn("WORK", R.drawable.b_factory) { val e = g.work(); if (e != null) { Snd.play("error"); toast(e) } else toast("Paid!") })
            box.addView(btn("Automatic Work : " + (if (me.auto) "ON" else "OFF"), R.drawable.ic_verified) {
                if (!g.vip) toast("VIP only. Buy VIP in the purchase menu.") else g.setAuto(!me.auto) })
            if (!own) box.addView(btn("Leave job", R.drawable.ic_exit) { g.leaveJob() })
            else {
                val cost = "${fmt(5000L * f.level)} gold, ${fmt(500L * f.level)} food, ${fmt(1000L * f.level)} resource"
                box.addView(panel(tv("UPGRADE to Lv ${f.level + 1}", 14f, C.GOLD, true),
                    tv("+25% salary, +5 max workers, faster energy refill", 12f, C.GRAY), tv(cost, 12f),
                    btn("UPGRADE", R.drawable.ic_build) { val e = g.upgradeFactory(id); if (e != null) { Snd.play("error"); toast(e) } }))
                box.addView(tv("WORKERS", 13f, C.GOLD, true))
                for ((wu, wn) in g.fwork[id] ?: HashMap<String, String>()) {
                    val r = LinearLayout(this); r.gravity = Gravity.CENTER_VERTICAL
                    r.addW(tv(wn + (if (wu == f.owner) " (owner)" else ""), 13f))
                    if (wu != f.owner) r.addView(btn("FIRE", R.drawable.ic_exit) { g.fire(id, wu) })
                    box.addView(panel(r))
                }
            }
        } else {
            if (g.factories.isEmpty()) box.addView(tv("No factories yet. Create the first one!", 13f, C.GRAY))
            for ((id, f) in g.factories) {
                val ownerP = g.players[f.owner]
                val r = LinearLayout(this); r.gravity = Gravity.CENTER_VERTICAL
                r.addW(tv("${f.name} | Workers : ${g.fwork[id]?.size ?: 0}/${f.maxEmp()}\n${ownerP?.country ?: "?"}  |  Salary ${fmt(f.salGold())} Money", 12f))
                r.addView(btn("Join", R.drawable.ic_play) { val e = g.joinFactory(id); if (e != null) { Snd.play("error"); toast(e) } })
                box.addView(panel(r))
            }
            box.addView(btn("Create Factory", R.drawable.ic_plus) { createFactoryPopup(g) })
        }
    }
}

fun MainActivity.createFactoryPopup(g: Game) {
    val nm = edit("Factory name"); var d: Dialog? = null
    d = popup("Create Factory", col(tv("Cost: 10,000 gold, 2,000 food, 4,000 resource", 13f, C.GOLD, true), nm,
        btn("Create Factory", R.drawable.ic_plus) {
            val e = g.createFactory(nm.text.toString())
            if (e != null) { Snd.play("error"); toast(e) } else { Snd.play("build"); d?.dismiss() }
        }))
}
