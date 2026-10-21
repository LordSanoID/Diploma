package com.sano.dpl

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.graphics.BitmapFactory
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.*
import com.google.firebase.database.*

val MAGENTA = 0xFFE040FB.toInt()

// ================= PROFILE =================
fun MainActivity.profilePopup() {
    val nick = edit("Nickname"); nick.setText(ud.nick)
    val lang = Cycle(this, "Language", L.names, maxOf(0, L.names.indexOf(L.lang)))
    val mus = Cycle(this, "Music", listOf("on", "off"), if (Snd.music) 0 else 1)
    val sfx = Cycle(this, "Sound FX", listOf("on", "off"), if (Snd.sfx) 0 else 1)
    var d: Dialog? = null
    val idRow = row(tv("ID : ${ud.pid}", 17f, C.GOLD, true))
    if (ud.role == "admin") { idRow.addView(img(R.drawable.ic_verified, 22)); idRow.addView(img(R.drawable.ic_dev, 22)); idRow.addView(tv("DEV", 13f, 0xFF00E676.toInt(), true)) }
    if (ud.role == "mod") { idRow.addView(img(R.drawable.ic_mod, 22)); idRow.addView(tv("MOD", 13f, 0xFFFFB300.toInt(), true)) }
    val note = tv(if (ud.role == "admin") "Admin: free nickname change" else "Changing nickname costs 100 Premium Gold", 11f, C.GRAY)
    val done = {
        L.lang = lang.value(); Snd.music = mus.i == 0; Snd.sfx = sfx.i == 0; Snd.save(this)
        Net.db.child("users/${ud.uid}/lang").setValue(L.lang)
        if (Snd.music) Snd.startMusic(this) else Snd.stopMusic()
        d?.dismiss(); showMenu()
    }
    val b = col(panel(idRow, tv("Premium Gold : ${fmt(ud.pgold)}", 13f, MAGENTA, true),
        tv(if (ud.vipUntil > now()) "VIP until " + java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.US).format(java.util.Date(ud.vipUntil)) else "VIP : -", 12f, C.GRAY)),
        panel(tv("Nickname :", 13f, C.GRAY), nick, note), lang.view, mus.view, sfx.view,
        btn(L.t("APPLY"), R.drawable.ic_build) {
            val n = nick.text.toString().trim()
            if (n != ud.nick) {
                val err = checkNick(n)
                if (err != null) { toast(err) }
                else if (ud.role != "admin" && ud.pgold < 100) { Snd.play("error"); toast("You need 100 Premium Gold") }
                else claimNick(n, ud.nick) { ok ->
                    if (ok) {
                        if (ud.role != "admin") Net.db.child("users/${ud.uid}/pgold").setValue(ServerValue.increment(-100))
                        Net.db.child("users/${ud.uid}/nick").setValue(n); ud.nick = n; Snd.play("coin"); done()
                    } else { Snd.play("error"); toast("Nickname already used by another player") }
                }
            } else done()
        },
        btn("LOGOUT", R.drawable.ic_exit) { d?.dismiss(); doLogout() })
    d = popup("PROFILE", b)
}

// ================= FOTO & PESAN ORDER =================
fun MainActivity.renderMsgs(hist: LinearLayout, s: DataSnapshot) {
    hist.removeAllViews()
    for (m in s.children) {
        val by = m.child("by").getValue(String::class.java) ?: ""
        val text = m.child("text").getValue(String::class.java) ?: ""
        val photo = m.child("photo").getValue(String::class.java)
        val t = m.child("t").getValue(Long::class.javaObjectType) ?: 0L
        val who = if (by == "admin") "ADMIN" else "MEMBER"
        if (text.isNotEmpty()) hist.addView(tv("${hm(t)} $who: $text", 12f, if (by == "admin") C.GOLD else C.TXT))
        if (photo != null) {
            hist.addView(tv("${hm(t)} $who sent a photo:", 12f, if (by == "admin") C.GOLD else C.TXT))
            try {
                val bytes = Base64.decode(photo, Base64.DEFAULT)
                val bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                val iv = ImageView(this); iv.setImageBitmap(bm); iv.adjustViewBounds = true
                hist.addView(iv, LinearLayout.LayoutParams(dp(200), -2))
            } catch (e: Exception) { }
        }
    }
}

fun MainActivity.sendOrderMsg(buyer: String, by: String, text: String, photo: String?) {
    val m = HashMap<String, Any>(); m["by"] = by; m["text"] = text; m["t"] = now()
    if (photo != null) m["photo"] = photo
    Net.db.child("ochat/$buyer").push().setValue(m)
    val up = HashMap<String, Any>(); up["lastBy"] = by; up["t"] = now()
    Net.db.child("orders/$buyer").updateChildren(up)
}

fun chatHolder(a: MainActivity, hist: LinearLayout, hdp: Int): Pair<ScrollView, LinearLayout> {
    val sv = ScrollView(a); sv.addView(hist)
    val holder = LinearLayout(a); holder.addView(sv, LinearLayout.LayoutParams(-1, a.dp(hdp)))
    return Pair(sv, holder)
}

// ================= BELI GOLD PREMIUM (pembeli) =================
fun MainActivity.premiumBuyer() {
    val uid = ud.uid
    val status = tv("Status Admin : ...", 14f, C.GOLD, true)
    val hist = col()
    val (sv, holder) = chatHolder(this, hist, 150)
    val input = edit("Message"); var allow = false
    val inputRow = LinearLayout(this); inputRow.gravity = Gravity.CENTER_VERTICAL
    inputRow.addView(btn("", R.drawable.ic_camera) {
        if (!allow) toast("Wait for admin to allow chat") else pickPhoto { p -> sendOrderMsg(uid, "buyer", "", p) } })
    inputRow.addW(input)
    inputRow.addView(btn("Send", R.drawable.ic_send) {
        val t = input.text.toString().trim()
        if (!allow) { Snd.play("error"); toast("Wait for admin to allow chat") } else if (t.isNotEmpty()) { sendOrderMsg(uid, "buyer", t, null); input.setText("") } })
    inputRow.visibility = View.GONE
    val hint = tv("Tap a package below, then wait for the admin's reply.", 11f, C.GRAY)
    val rows = col()
    var i = 0
    while (i < PACKS.size) {
        val a = PACKS[i]; val b = if (i + 1 < PACKS.size) PACKS[i + 1] else null
        fun pk(p: Triple<Long, String, Long>): Button = btn("${fmt(p.first)} GOLD / ${p.second}", R.drawable.ic_gem) { orderPack(p) }
        rows.addView(if (b != null) two(pk(a), pk(b)) else two(pk(a)))
        i += 2
    }
    rows.addView(tv("VIP (Automatic Work in Factory)", 13f, C.GOLD, true))
    for (v in VIPS) rows.addView(btn("VIP " + (if (v.first == 7L) "7 Hari" else if (v.first == 30L) "1 Bulan" else "1 Tahun") + " / " + v.second, R.drawable.ic_verified) { orderVip(v) })
    val body = col(status, panel(tv("CHAT", 13f, C.GRAY, true), holder, inputRow, hint),
        panel(tv("BUY PREMIUM GOLD", 14f, C.GOLD, true), rows))
    val refs = ArrayList<Pair<DatabaseReference, ValueEventListener>>()
    fun add(r: DatabaseReference, l: ValueEventListener) { r.addValueEventListener(l); refs.add(Pair(r, l)) }
    add(Net.db.child("adminOnline"), object : ValueEventListener {
        override fun onCancelled(e: DatabaseError) {}
        override fun onDataChange(s: DataSnapshot) {
            val on = s.childrenCount > 0
            status.text = "Status Admin : " + (if (on) "ONLINE" else "OFFLINE")
            status.setTextColor(if (on) 0xFF66BB6A.toInt() else 0xFFEF5350.toInt())
        }
    })
    add(Net.db.child("ochat/$uid"), object : ValueEventListener {
        override fun onCancelled(e: DatabaseError) {}
        override fun onDataChange(s: DataSnapshot) { renderMsgs(hist, s); sv.post { sv.fullScroll(View.FOCUS_DOWN) } }
    })
    add(Net.db.child("orders/$uid"), object : ValueEventListener {
        override fun onCancelled(e: DatabaseError) {}
        override fun onDataChange(s: DataSnapshot) {
            val open = s.child("status").getValue(String::class.java) == "open"
            allow = open && (s.child("allow").getValue(Boolean::class.javaObjectType) ?: false)
            inputRow.visibility = if (allow) View.VISIBLE else View.GONE
        }
    })
    popup("PURCHASE PREMIUM GOLD", body, true) { for (p in refs) p.first.removeEventListener(p.second) }
}

fun MainActivity.orderPack(p: Triple<Long, String, Long>) {
    val uid = ud.uid
    val r = Net.db.child("orders/$uid")
    r.once { s ->
        val st = s.child("status").getValue(String::class.java)
        val up = HashMap<String, Any>()
        up["status"] = "open"; up["gold"] = p.first; up["vip"] = 0L; up["price"] = p.third; up["name"] = ud.nick; up["pid"] = ud.pid
        up["lastBy"] = "buyer"; up["t"] = now()
        if (st != "open") up["allow"] = false
        r.updateChildren(up)
        sendOrderMsg(uid, "buyer", "Mau beli ${fmt(p.first)} Premium Gold (Rp ${fmt(p.third)})", null)
        Snd.play("notif"); toast("Order sent. Wait for the admin's reply")
    }
}

// ================= ORDER (admin) =================
fun MainActivity.adminOrders() {
    val box = col()
    val ref = Net.db.child("orders")
    val l = object : ValueEventListener {
        override fun onCancelled(e: DatabaseError) {}
        override fun onDataChange(s: DataSnapshot) {
            box.removeAllViews(); var n = 0
            for (o in s.children) {
                if (o.child("status").getValue(String::class.java) != "open") continue
                val k = o.key ?: continue
                n++
                val pid = o.child("pid").getValue(String::class.java) ?: "?"
                val name = o.child("name").getValue(String::class.java) ?: "?"
                val gold = o.child("gold").getValue(Long::class.javaObjectType) ?: 0L
                val price = o.child("price").getValue(Long::class.javaObjectType) ?: 0L
                val vip = o.child("vip").getValue(Long::class.javaObjectType) ?: 0L
                val fresh = o.child("lastBy").getValue(String::class.java) == "buyer"
                val r = LinearLayout(this@adminOrders); r.gravity = Gravity.CENTER_VERTICAL
                r.addW(tv("ID $pid | $name\n${if (vip > 0L) "VIP $vip days" else fmt(gold) + " GOLD"} / Rp ${fmt(price)}" + (if (fresh) "   (NEW)" else ""), 13f, if (fresh) 0xFFFFD54F.toInt() else C.TXT))
                r.addView(btn("OPEN", R.drawable.ic_msg) { adminChat(k) })
                box.addView(panel(r))
            }
            if (n == 0) box.addView(tv("No open orders.", 13f, C.GRAY))
        }
    }
    ref.addValueEventListener(l)
    popup("ORDERS", box, true) { ref.removeEventListener(l) }
}

fun MainActivity.adminChat(buyer: String) {
    Net.db.child("orders/$buyer").once { o ->
        val pid = o.child("pid").getValue(String::class.java) ?: "?"
        val gold = o.child("gold").getValue(Long::class.javaObjectType) ?: 0L
        val vip = o.child("vip").getValue(Long::class.javaObjectType) ?: 0L
        val allowNow = o.child("allow").getValue(Boolean::class.javaObjectType) ?: false
        val hist = col()
        val (sv, holder) = chatHolder(this, hist, 170)
        val input = edit("Message")
        var d: Dialog? = null
        val cyc = Cycle(this, "Allow Chat", listOf("on", "off"), if (allowNow) 0 else 1, null)
        cyc.view.setOnClickListener {
            Snd.play("click"); cyc.i = (cyc.i + 1) % 2
            cyc.view.text = "Allow Chat: ${cyc.opts[cyc.i]}"
            Net.db.child("orders/$buyer/allow").setValue(cyc.i == 0)
        }
        val inRow = LinearLayout(this); inRow.gravity = Gravity.CENTER_VERTICAL
        inRow.addView(btn("kirim foto", R.drawable.ic_camera) { pickPhoto { p -> sendOrderMsg(buyer, "admin", "", p) } })
        inRow.addW(input)
        inRow.addView(btn("kirim", R.drawable.ic_send) {
            val t = input.text.toString().trim(); if (t.isNotEmpty()) { sendOrderMsg(buyer, "admin", t, null); input.setText("") } })
        val ok = btn("SUCCESS", R.drawable.ic_verified) {
            Net.db.child("orders/$buyer/status").runTransaction(object : Transaction.Handler {
                override fun doTransaction(m: MutableData): Transaction.Result {
                    if (m.getValue(String::class.java) == "open") { m.setValue("done"); return Transaction.success(m) }
                    return Transaction.abort()
                }
                override fun onComplete(e: DatabaseError?, committed: Boolean, s: DataSnapshot?) {
                    if (committed) {
                        if (vip > 0L) {
                            Net.db.child("users/$buyer/vipUntil").once { vu ->
                                val cur = vu.getValue(Long::class.javaObjectType) ?: 0L
                                Net.db.child("users/$buyer/vipUntil").setValue(maxOf(now(), cur) + vip * 86400000L)
                            }
                            sendOrderMsg(buyer, "admin", "Transaksi selesai. VIP $vip hari sudah aktif.", null)
                        } else {
                            Net.db.child("users/$buyer/pgold").setValue(ServerValue.increment(gold))
                            sendOrderMsg(buyer, "admin", "Transaksi selesai. +${fmt(gold)} Premium Gold sudah masuk.", null)
                        }
                        Snd.play("win"); toast("Success! Gold sent"); d?.dismiss()
                    } else toast("Order already completed")
                }
            })
        }
        val body = col(tv("Id : $pid", 15f, C.GOLD, true), panel(tv("MEMBER CHAT", 13f, C.GRAY, true), holder),
            inRow, panel(tv("Chat Rules", 13f, C.GOLD, true), cyc.view), ok)
        val l = object : ValueEventListener {
            override fun onCancelled(e: DatabaseError) {}
            override fun onDataChange(s: DataSnapshot) { renderMsgs(hist, s); sv.post { sv.fullScroll(View.FOCUS_DOWN) } }
        }
        val ref = Net.db.child("ochat/$buyer"); ref.addValueEventListener(l)
        d = popup("MEMBER CHAT", body, true) { ref.removeEventListener(l) }
    }
}

// ================= ADMIN PANEL =================
fun MainActivity.lookupUid(q: String, cb: (String?) -> Unit) {
    val s = q.trim()
    if (s.isEmpty()) { cb(null); return }
    val ref = if (Regex("\\d{5}").matches(s)) Net.db.child("ids/$s") else Net.db.child("nicks/${s.lowercase()}")
    ref.once { x -> cb(x.getValue(String::class.java)) }
}

fun MainActivity.adminPanel() {
    val admin = ud.role == "admin"
    var d: Dialog? = null
    fun go(f: () -> Unit): () -> Unit = { d?.dismiss(); f() }
    val list = ArrayList<View>()
    list.add(two(btn("BAN USER", R.drawable.ic_swords, go { banForm() }), btn("UNBAN USER", R.drawable.ic_shield, go { unbanForm() })))
    list.add(btn("MAINTANCE", R.drawable.ic_tool, go { maintForm() }))
    if (admin) list.add(two(btn("ADD MODERATOR", R.drawable.ic_mod, go { modForm(true) }), btn("REMOVE MODERATOR", R.drawable.ic_exit, go { modForm(false) })))
    list.add(btn("LIST MODERATOR", R.drawable.ic_mod, go { modList() }))
    list.add(btn("REPORTS", R.drawable.ic_bell, go { reportsPopup() }))
    if (admin) list.add(btn("UPDATE LINK", R.drawable.ic_send, go { updateLinkForm() }))
    if (admin) list.add(btn("DELETE ALL SERVERS", R.drawable.ic_trash, go { deleteAllServers() }))
    d = popup("ADMIN PANEL", col(*list.toTypedArray()))
}

fun MainActivity.banForm() {
    val who = edit("Id / Nickname"); val days = edit("Days (0 = permanent)", num = true); val reason = edit("Reason")
    var d: Dialog? = null
    d = popup("BAN USER", col(lab("Id/Nick :", who), lab("Waktu :", days), lab("Reason :", reason), btn("BAN", R.drawable.ic_swords) {
        val dv = days.text.toString().toLongOrNull() ?: -2L
        if (dv < 0) toast("Days: 0 = permanent") else lookupUid(who.text.toString()) { t ->
            if (t == null) { Snd.play("error"); toast("User not found") }
            else if (t == ud.uid) toast("You cannot ban yourself")
            else Net.db.child("users/$t/email").once { em ->
                val email = em.getValue(String::class.java) ?: ""
                Net.db.child("mods/$t").once { md ->
                    if (email in ADMIN_EMAILS) toast("You cannot ban an admin")
                    else if (md.exists() && ud.role != "admin") toast("Only admin can ban a moderator")
                    else {
                        val m = HashMap<String, Any>()
                        m["until"] = if (dv == 0L) -1L else now() + dv * 86400000L
                        m["reason"] = reason.text.toString().ifBlank { "-" }; m["by"] = ud.nick
                        Net.db.child("bans/$t").setValue(m); Snd.play("coin"); toast("User banned"); d?.dismiss()
                    }
                }
            }
        }
    }))
}

fun MainActivity.unbanForm() {
    val who = edit("Id / Nickname"); var d: Dialog? = null
    d = popup("UNBAN USER", col(lab("Id/Nick :", who), btn("UNBAN", R.drawable.ic_shield) {
        lookupUid(who.text.toString()) { t ->
            if (t == null) toast("User not found") else { Net.db.child("bans/$t").removeValue(); toast("User unbanned"); d?.dismiss() } }
    }))
}

fun MainActivity.maintForm() {
    val on = Cycle(this, "Maintance", listOf("on", "off"), 1); val reason = edit("Reason")
    var d: Dialog? = null
    d = popup("MAINTANCE", col(on.view, lab("Reason :", reason), btn("APPLY", R.drawable.ic_tool) {
        val m = HashMap<String, Any>()
        m["on"] = on.i == 0; m["reason"] = reason.text.toString().ifBlank { "Be right back" }; m["by"] = ud.nick
        Net.db.child("maintenance").setValue(m); toast("Maintenance ${on.value()}"); d?.dismiss()
    }))
}

fun MainActivity.modForm(add: Boolean) {
    val who = edit("Id / Nickname"); var d: Dialog? = null
    d = popup(if (add) "ADD MODERATOR" else "REMOVE MODERATOR", col(lab("Id/Nick :", who), btn(if (add) "ADD" else "REMOVE", R.drawable.ic_mod) {
        lookupUid(who.text.toString()) { t ->
            if (t == null) { Snd.play("error"); toast("User not found") }
            else if (add) Net.db.child("users/$t/nick").once { n ->
                Net.db.child("mods/$t").setValue(n.getValue(String::class.java) ?: "mod"); Snd.play("coin"); toast("Moderator added"); d?.dismiss() }
            else { Net.db.child("mods/$t").removeValue(); toast("Moderator removed"); d?.dismiss() }
        }
    }))
}

fun MainActivity.modList() {
    val box = col()
    Net.db.child("mods").once { s ->
        if (!s.exists()) box.addView(tv("No moderators.", 13f, C.GRAY))
        for (c in s.children) {
            val k = c.key ?: continue
            val nm = c.getValue(String::class.java) ?: "?"
            val t = tv("• $nm", 14f, 0xFFFFB300.toInt(), true); box.addView(t)
            Net.db.child("users/$k/pid").once { p -> t.text = "• $nm  (ID ${p.getValue(String::class.java) ?: "?"})" }
        }
    }
    popup("LIST MODERATOR", box)
}

// ================= GACHA =================
fun MainActivity.gachaPopup() {
    val gem = tv("", 15f, MAGENTA, true); val res = col()
    val cap = ImageView(this); cap.setImageResource(R.drawable.capsule); cap.bob()
    val upd = { gem.text = "Premium Gold : ${fmt(ud.pgold)}" }
    upd()
    val old = onUser
    onUser = { old?.invoke(); upd() }
    var busy = false
    fun icon(cat: String): Int = when (cat) { "title" -> R.drawable.ic_rank; "color" -> R.drawable.ic_chat; "skin" -> R.drawable.ic_soldier; else -> R.drawable.ic_gem }
    fun pull(n: Int) {
        if (busy) return
        val cost = if (n == 1) 10L else 90L
        if (ud.pgold < cost) { Snd.play("error"); toast("Not enough Premium Gold. Buy from admin."); return }
        busy = true
        val rnd = java.util.Random(); val up = HashMap<String, Any>()
        val tS = HashSet(ud.titles); val cS = HashSet(ud.colors); val sS = HashSet(ud.skins)
        var plus = 0L; var best = 0
        val rows = ArrayList<Triple<String, Int, Int>>()
        for (i in 0 until n) {
            val r = rnd.nextInt(100)
            val rar = if (r < 60) 0 else if (r < 88) 1 else if (r < 98) 2 else 3
            val pool = ITEMS.filter { x -> x.rar == rar }
            val item = pool[rnd.nextInt(pool.size)]
            best = maxOf(best, rar)
            var line = "${RARITY[rar]}: ${item.name}"
            if (item.cat == "gold") plus += item.a
            else {
                val set = if (item.cat == "title") tS else if (item.cat == "color") cS else sS
                val folder = if (item.cat == "title") "titles" else if (item.cat == "color") "colors" else "skins"
                if (set.contains(item.id)) { plus += 3; line += "  (duplicate +3)" }
                else { set.add(item.id); up["$folder/${item.id}"] = true; line += "  (${item.cat.uppercase()})" }
            }
            rows.add(Triple(line, rar, icon(item.cat)))
        }
        up["pgold"] = Net.inc(-cost + plus)
        res.removeAllViews()
        Snd.play("gacha")
        val sh = ObjectAnimator.ofFloat(cap, "rotation", 0f, -20f, 20f, -16f, 16f, -10f, 10f, 0f); sh.duration = 1000
        val sc = ObjectAnimator.ofPropertyValuesHolder(cap, PropertyValuesHolder.ofFloat("scaleX", 1f, 1.3f, 1f), PropertyValuesHolder.ofFloat("scaleY", 1f, 1.3f, 1f))
        sc.duration = 1000
        sh.start(); sc.start()
        cap.postDelayed({
            Net.db.child("users/${ud.uid}").updateChildren(up)
            Snd.play(if (best >= 2) "win" else "coin")
            for ((i, r) in rows.withIndex()) cap.postDelayed({
                val row = LinearLayout(this); row.gravity = Gravity.CENTER_VERTICAL
                val ic = ImageView(this); ic.setImageResource(r.third); ic.setColorFilter(RARCOLOR[r.second])
                row.addView(ic, LinearLayout.LayoutParams(dp(30), dp(30)))
                row.addView(tv(r.first, 14f, RARCOLOR[r.second], true), LinearLayout.LayoutParams(-2, -2).also { it.leftMargin = dp(8) })
                row.background = grad(0xFF222836.toInt(), 0xFF161A22.toInt(), RARCOLOR[r.second], 10, 2); row.setPadding(dp(10), dp(6), dp(10), dp(6))
                res.addView(row, LinearLayout.LayoutParams(-1, -2).also { it.setMargins(0, dp(3), 0, dp(3)) }); row.popIn()
                if (i == rows.size - 1) busy = false
            }, i * 150L)
        }, 1050)
    }
    val head = LinearLayout(this); head.gravity = Gravity.CENTER
    head.addView(cap, LinearLayout.LayoutParams(dp(110), dp(110)))
    val body = col(head, gem, tv("Win titles, chat colors, unit skins and Premium Gold!", 12f, C.GRAY),
        two(btn("PULL x1  (10)", R.drawable.ic_gacha) { pull(1) }, btn("PULL x10  (90)", R.drawable.ic_gacha) { pull(10) }),
        btn("MY ITEMS", R.drawable.ic_gift) { inventoryPopup() }, panel(res),
        tv("Common 60% | Elite 28% | Epic 10% | Legend 2%", 11f, C.GRAY))
    popup("GACHA", body) { onUser = old }
}

fun MainActivity.inventoryPopup() {
    val tIds = arrayListOf(""); val tNames = arrayListOf("none")
    for (id in ud.titles) { val x = itemById(id); if (x != null) { tIds.add(id); tNames.add(x.name) } }
    val cIds = arrayListOf(""); val cNames = arrayListOf("none")
    for (id in ud.colors) { val x = itemById(id); if (x != null) { cIds.add(id); cNames.add(x.name) } }
    var curC = 0
    for (k in cIds.indices) if (cIds[k].isNotEmpty() && itemById(cIds[k])?.c == ud.cc) curC = k
    val tc = Cycle(this, "Title", tNames, maxOf(0, tIds.indexOf(ud.title)))
    val cc = Cycle(this, "Chat color", cNames, curC)
    val sIds = arrayListOf(""); val sNames = arrayListOf("none")
    for (id in ud.skins) { val x = itemById(id); if (x != null) { sIds.add(id); sNames.add("${x.name} +${x.a}⚡ +${x.b}%") } }
    val cycles = ArrayList<Cycle>()
    val box = col(tc.view, cc.view, tv("UNIT SKINS (equip a skin to a unit)", 13f, C.GOLD, true))
    for (u in UNITS) {
        val cy = Cycle(this, u.name, sNames, maxOf(0, sIds.indexOf(ud.equip[u.id] ?: "")))
        cycles.add(cy); box.addView(cy.view)
    }
    var d: Dialog? = null
    box.addView(btn(L.t("APPLY"), R.drawable.ic_build) {
        val up = HashMap<String, Any>()
        up["title"] = tIds[tc.i]
        up["cc"] = if (cIds[cc.i].isEmpty()) 0L else (itemById(cIds[cc.i])?.c ?: 0).toLong()
        Net.db.child("users/${ud.uid}").updateChildren(up)
        for ((k, u) in UNITS.withIndex()) {
            val sid = sIds[cycles[k].i]
            if (sid.isEmpty()) Net.db.child("users/${ud.uid}/equip/${u.id}").removeValue()
            else Net.db.child("users/${ud.uid}/equip/${u.id}").setValue(sid)
        }
        Snd.play("coin"); toast("Equipped!"); d?.dismiss()
    })
    d = popup("MY ITEMS", box)
}

// ================= INBOX, LAPORAN, HAPUS SERVER =================
fun MainActivity.adminInbox() {
    var d: Dialog? = null
    fun go(f: () -> Unit): () -> Unit = { d?.dismiss(); f() }
    d = popup("INBOX", col(btn("ORDERS (buy gold)", R.drawable.ic_gem, go { adminOrders() }), btn("REPORTS (rude chat)", R.drawable.ic_bell, go { reportsPopup() })))
}

fun MainActivity.reportsPopup() {
    val box = col(); val ref = Net.db.child("reports")
    val l = object : ValueEventListener {
        override fun onCancelled(e: DatabaseError) {}
        override fun onDataChange(s: DataSnapshot) {
            box.removeAllViews(); var n = 0
            for (r in s.children) {
                val id = r.key ?: continue; n++
                val uid = r.child("uid").getValue(String::class.java) ?: ""
                val nick = r.child("nick").getValue(String::class.java) ?: "?"
                val word = r.child("word").getValue(String::class.java) ?: "?"
                val key = r.child("key").getValue(String::class.java) ?: ""
                val sname = r.child("sname").getValue(String::class.java) ?: "?"
                val line = LinearLayout(this@reportsPopup); line.gravity = Gravity.CENTER_VERTICAL
                line.addW(tv("$nick berkata \"$word\" di server $sname", 13f))
                line.addView(btn("ban") { quickBan(uid, nick, id) })
                line.addView(btn("mute") {
                    Net.db.child("servers/$key/mutes/$uid").setValue(now() + 30 * 60000L)
                    Net.db.child("reports/$id").removeValue(); toast("$nick muted for 30 min")
                })
                box.addView(panel(line))
            }
            if (n == 0) box.addView(tv("No reports.", 13f, C.GRAY))
        }
    }
    ref.addValueEventListener(l)
    popup("REPORTS", box, true) { ref.removeEventListener(l) }
}

fun MainActivity.quickBan(uid: String, nick: String, reportId: String) {
    val days = edit("Days (0 = permanent)", num = true); val reason = edit("Reason"); reason.setText("Rude chat")
    var d: Dialog? = null
    d = popup("BAN $nick", col(lab("Days :", days), lab("Reason :", reason), btn("BAN", R.drawable.ic_swords) {
        val dv = days.text.toString().toLongOrNull() ?: -2L
        if (dv < 0) toast("Days: 0 = permanent") else Net.db.child("users/$uid/email").once { em ->
            if ((em.getValue(String::class.java) ?: "") in ADMIN_EMAILS) toast("You cannot ban an admin") else {
                val m = HashMap<String, Any>()
                m["until"] = if (dv == 0L) -1L else now() + dv * 86400000L
                m["reason"] = reason.text.toString().ifBlank { "-" }; m["by"] = ud.nick
                Net.db.child("bans/$uid").setValue(m); Net.db.child("reports/$reportId").removeValue()
                Snd.play("coin"); toast("$nick banned"); d?.dismiss()
            }
        }
    }))
}

fun MainActivity.deleteAllServers() {
    var d: Dialog? = null
    d = popup("DELETE ALL SERVERS?", col(tv("This permanently deletes EVERY server and all its data.", 13f, 0xFFEF5350.toInt()),
        two(btn("DELETE", R.drawable.ic_trash) {
            Net.db.child("servers").removeValue(); Net.db.child("index").removeValue()
            Snd.play("nuke"); toast("All servers deleted"); d?.dismiss()
        }, btn("CANCEL") { d?.dismiss() })))
}

fun MainActivity.orderVip(p: Triple<Long, String, Long>) {
    val uid = ud.uid
    val r = Net.db.child("orders/$uid")
    r.once { s ->
        val st = s.child("status").getValue(String::class.java)
        val up = HashMap<String, Any>()
        up["status"] = "open"; up["gold"] = 0L; up["vip"] = p.first; up["price"] = p.third; up["name"] = ud.nick; up["pid"] = ud.pid
        up["lastBy"] = "buyer"; up["t"] = now()
        if (st != "open") up["allow"] = false
        r.updateChildren(up)
        sendOrderMsg(uid, "buyer", "Mau beli VIP ${p.first} hari (Rp ${fmt(p.third)})", null)
        Snd.play("notif"); toast("Order sent. Wait for the admin's reply")
    }
}

@Suppress("DEPRECATION")
fun MainActivity.curCode(): Long = try { packageManager.getPackageInfo(packageName, 0).versionCode.toLong() } catch (ex: Exception) { 0L }

fun MainActivity.updatePopup(link: String) {
    val body = col(tv("VERSI YANG KAMU PAKAI SAAT INI SUDAH KADALWARSA SILAHKAN UPDATE UNTUK BERMAIN GAME INI KEMBALI", 14f, C.TXT, true),
        two(btn("UPDATE", R.drawable.ic_play) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) } catch (ex: Exception) { toast("Cannot open link") } },
            btn("OKE", R.drawable.ic_exit) { finish() }))
    popup("UPDATE", body, false, false)
}

fun MainActivity.updateLinkForm() {
    val code = edit("Latest version code", num = true); code.setText(curCode().toString())
    val name = edit("Version name"); name.setText("5.4.1")
    val link = edit("Link (mediafire / catbox.moe)")
    var d: Dialog? = null
    d = popup("UPDATE LINK", col(lab("Code :", code), lab("Version :", name), lab("Link :", link), btn("PUBLISH", R.drawable.ic_send) {
        val l = link.text.toString().trim(); val cv = code.text.toString().toLongOrNull() ?: -1L
        if (cv < 1L) toast("Invalid version code")
        else if (!(l.startsWith("https://") && (l.contains("mediafire.com") || l.contains("catbox.moe")))) toast("Link must be mediafire.com or catbox.moe")
        else {
            val m = HashMap<String, Any>()
            m["latestCode"] = cv; m["latestName"] = name.text.toString().ifBlank { "?" }; m["link"] = l; m["by"] = ud.nick
            Net.db.child("appconfig").setValue(m); Snd.play("coin"); toast("Update published"); d?.dismiss()
        }
    }))
}
