package com.sano.dpl

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.*
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.OnCompleteListener
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.database.*
import java.io.ByteArrayOutputStream
import java.util.Locale

const val WEB_ID = "240737190558-1u75aenb0q39qr5cjffe0ip82cqt7eli.apps.googleusercontent.com"

var dbError: ((String) -> Unit)? = null

fun DatabaseReference.once(f: (DataSnapshot) -> Unit) {
    addListenerForSingleValueEvent(object : ValueEventListener {
        override fun onCancelled(e: DatabaseError) { dbError?.invoke(e.message) }
        override fun onDataChange(s: DataSnapshot) { f(s) }
    })
}

fun EditText.onText(f: () -> Unit) {
    addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(s: Editable?) { f() }
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    })
}

class MainActivity : Activity() {
    val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    var back: (() -> Unit)? = null
    var ud = UserData()
    var game: Game? = null
    var map: MapView? = null
    var live: (() -> Unit)? = null
    val ls = ArrayList<Pair<DatabaseReference, ValueEventListener>>()
    val gl = ArrayList<Pair<DatabaseReference, ValueEventListener>>()
    var onUser: (() -> Unit)? = null
    val seenReq = HashSet<String>()
    val h = Handler(Looper.getMainLooper())
    var cnt = 0
    var newsSeen = -1L
    var chatCount = -1
    var chatDlg: Dialog? = null
    var gameShown = false
    var blocked = false
    var tts: TextToSpeech? = null
    var photoCb: ((String) -> Unit)? = null
    var photoMax = 480
    lateinit var tG: TextView
    lateinit var tR: TextView
    lateinit var tF: TextView
    lateinit var tL: TextView
    lateinit var tN: TextView
    lateinit var stopBtn: Button
    var gameRoot: FrameLayout? = null
    var evtSeen = 0L
    val localChat = ArrayList<Pair<Long, String>>()

    val authDone = OnCompleteListener<AuthResult> { t ->
        if (t.isSuccessful) afterAuth() else { Snd.play("error"); toast("Login failed: " + (t.exception?.message ?: "")) }
    }

    val loop = object : Runnable {
        override fun run() {
            val g = game
            if (g != null && g.me != null && gameShown) {
                g.vip = ud.vipUntil > now(); g.pump(); g.expandTick(); live?.invoke()
                if (cnt % 6 == 0) { g.tick(); g.goOnline() }
                cnt++
            }
            h.postDelayed(this, if (g is LocalGame) 2000L else 10000L)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (FirebaseApp.getApps(this).isEmpty()) {
            FirebaseApp.initializeApp(this, FirebaseOptions.Builder()
                .setApplicationId("1:240737190558:android:907c96058fb751ac9ab18f")
                .setApiKey("AIzaSyAYWVpdsXU5nrMpA4VeZK-i2mfkfoNI8Zw")
                .setProjectId("diplomagame-ee7f8")
                .setDatabaseUrl(Net.DB_URL).build())
        }
        Snd.init(this); immersive()
        dbError = { m -> runOnUiThread { toast("DB error: $m") } }
        showLoading("Loading...")
        h.postDelayed({ if (isOnline() && auth.currentUser != null) afterAuth() else showWelcome() }, 1700)
    }

    @Suppress("DEPRECATION")
    fun immersive() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }
    override fun onWindowFocusChanged(f: Boolean) { super.onWindowFocusChanged(f); if (f) immersive() }
    override fun onResume() { super.onResume(); Snd.startMusic(this); game?.goOnline(); presence(true) }
    override fun onPause() {
        super.onPause(); Snd.stopMusic(); presence(false)
        game?.let { if (it.me != null) it.upd(hashMapOf<String, Any>("online" to false, "lastSeen" to now())) }
    }
    override fun onDestroy() { super.onDestroy(); tts?.shutdown(); h.removeCallbacksAndMessages(null) }
    @Suppress("DEPRECATION")
    override fun onBackPressed() { val b = back; if (b != null) b() else finish() }

    fun presence(on: Boolean) {
        if (ud.role != "admin" || ud.uid.isEmpty()) return
        val r = Net.db.child("adminOnline/${ud.uid}")
        if (on) { r.setValue(true); r.onDisconnect().removeValue() } else r.removeValue()
    }

    fun listenScreen(r: DatabaseReference, l: ValueEventListener) { r.addValueEventListener(l); ls.add(Pair(r, l)) }
    fun clearScreen() { for (p in ls) p.first.removeEventListener(p.second); ls.clear() }
    fun listenGlobal(r: DatabaseReference, l: ValueEventListener) { r.addValueEventListener(l); gl.add(Pair(r, l)) }
    fun clearGlobal() { for (p in gl) p.first.removeEventListener(p.second); gl.clear() }

    fun screen(v: View, b: (() -> Unit)?) {
        clearScreen(); live = null; onUser = null; back = b; setContentView(v); v.fadeIn()
    }
    fun lab(t: String, v: View): LinearLayout {
        val r = LinearLayout(this); r.gravity = Gravity.CENTER_VERTICAL
        r.addView(tv(t, 13f, C.GRAY), LinearLayout.LayoutParams(dp(110), -2))
        r.addView(v, LinearLayout.LayoutParams(0, -2, 1f)); return r
    }
    fun two(vararg v: View): LinearLayout {
        val r = LinearLayout(this); for (x in v) r.addW(x); return r
    }
    fun liveDialog(title: String, wide: Boolean = false, render: (LinearLayout) -> Unit): Dialog {
        val box = col()
        val r = { box.removeAllViews(); render(box) }
        live = r; r()
        return popup(title, box, wide) { live = null }
    }
    fun center(v: View, w: Int = 340): FrameLayout {
        val root = FrameLayout(this); root.addView(Backdrop(this))
        root.addView(v, FrameLayout.LayoutParams(dp(w), -2, Gravity.CENTER)); return root
    }
    fun logo(s: Int): ImageView {
        val i = ImageView(this); i.setImageResource(R.drawable.mascot); i.bob()
        i.layoutParams = LinearLayout.LayoutParams(dp(s), dp(s)); return i
    }
    fun speak(t: String) {
        if (tts == null) {
            tts = TextToSpeech(this) { st ->
                if (st == TextToSpeech.SUCCESS) { tts?.setLanguage(Locale.US); tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "w") }
            }
        } else tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "w")
    }

    // ================= LOADING =================
    fun showLoading(msg: String) {
        val tips = listOf("Tip: Tekan lama wilayah musuh untuk diplomasi.", "Tip: Ketuk tanah kosong dekat wilayahmu untuk ekspansi.",
            "Tip: Bangun Workshop untuk membuat item.", "Tip: Beli emas saat harga turun di menu Saham.", "Tip: Ketik /help di chat untuk daftar command.")
        val t = tv("DIPLOMA", 30f, C.GOLD, true); t.gravity = Gravity.CENTER; t.pulse()
        val globe = ImageView(this); globe.setImageResource(R.drawable.ic_globe)
        val spin = android.animation.ObjectAnimator.ofFloat(globe, "rotation", 0f, 360f)
        spin.duration = 3000; spin.repeatCount = android.animation.ValueAnimator.INFINITE
        spin.interpolator = android.view.animation.LinearInterpolator(); spin.start()
        val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        pb.isIndeterminate = true; pb.indeterminateTintList = ColorStateList.valueOf(C.GOLD)
        val tip = tv(msg, 13f, C.TXT); tip.gravity = Gravity.CENTER
        val hint = tv(tips.random(), 12f, C.GOLD); hint.gravity = Gravity.CENTER
        val gr = LinearLayout(this); gr.gravity = Gravity.CENTER
        gr.addView(globe, LinearLayout.LayoutParams(dp(34), dp(34)))
        gr.addView(tip, LinearLayout.LayoutParams(-2, -2).also { it.leftMargin = dp(8) })
        val c = LinearLayout(this); c.orientation = LinearLayout.VERTICAL; c.gravity = Gravity.CENTER
        c.addView(logo(120)); c.addView(t, LinearLayout.LayoutParams(-1, -2)); c.addView(gr, LinearLayout.LayoutParams(-1, -2))
        c.addView(pb, LinearLayout.LayoutParams(-1, dp(14)).also { it.setMargins(0, dp(10), 0, dp(8)) })
        c.addView(hint, LinearLayout.LayoutParams(-1, -2))
        screen(center(c, 320), null)
        h.postDelayed(object : Runnable {
            override fun run() { if (hint.isAttachedToWindow) { hint.text = tips.random(); h.postDelayed(this, 2200) } }
        }, 2200)
    }
    // ================= LOGIN =================
    fun showWelcome() {
        clearGlobal()
        val t = tv("WELCOME TO DIPLOMA", 28f, C.GOLD, true); t.gravity = Gravity.CENTER; t.pulse()
        val i1 = tv("INFORMATION", 13f, C.GRAY, true); val i2 = tv("DEV : SANO", 16f, C.TXT, true)
        val items = ArrayList<View>()
        items.add(panel(t)); items.add(panel(i1, i2))
        if (isOnline()) items.add(btn("Login To Continue", R.drawable.ic_play) { loginPopup() })
        else {
            items.add(tv("No internet connection", 13f, 0xFFEF5350.toInt()))
            items.add(btn("OFFLINE MODE", R.drawable.ic_soldier) { offlineMenu() })
            items.add(btn("Retry connection", R.drawable.ic_globe) {
                if (isOnline()) { if (auth.currentUser != null) afterAuth() else showWelcome() } else toast("Still offline") })
        }
        val c = col(*items.toTypedArray()); c.gravity = Gravity.CENTER
        c.addView(logo(100), 0)
        val sv = ScrollView(this); sv.addView(c)
        screen(center(sv, 420), null)
        speak("Welcome to Diploma. Login to continue.")
    }
    fun loginPopup() {
        var d: Dialog? = null
        val b = col(
            btn("GOOGLE", R.drawable.ic_google) { d?.dismiss(); googleLogin() },
            btn("GUEST", R.drawable.ic_guest) { d?.dismiss(); auth.signInAnonymously().addOnCompleteListener(authDone) })
        d = popup("LOGIN NOW", b)
    }
    fun googleLogin() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestIdToken(WEB_ID).requestEmail().build()
        @Suppress("DEPRECATION")
        startActivityForResult(GoogleSignIn.getClient(this, gso).signInIntent, 9001)
    }

    fun pickPhoto(max: Int = 480, cb: (String) -> Unit) {
        photoCb = cb; photoMax = max
        val i = Intent(Intent.ACTION_GET_CONTENT); i.type = "image/*"
        @Suppress("DEPRECATION")
        startActivityForResult(Intent.createChooser(i, "Select photo"), 9002)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(rq: Int, rs: Int, data: Intent?) {
        super.onActivityResult(rq, rs, data)
        if (rq == 9001) {
            try {
                val acc = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
                val cred = GoogleAuthProvider.getCredential(acc.idToken, null)
                auth.signInWithCredential(cred).addOnCompleteListener(authDone)
            } catch (e: ApiException) { Snd.play("error"); toast("Google failed (code ${e.statusCode}). Check SHA-1 in Firebase.") }
        } else if (rq == 9002 && rs == RESULT_OK && data?.data != null) {
            try {
                val uri = data.data!!
                val o = BitmapFactory.Options(); o.inJustDecodeBounds = true
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                var ss = 1; while (o.outWidth / ss > 900) ss *= 2
                val o2 = BitmapFactory.Options(); o2.inSampleSize = ss
                val bm = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) } ?: return
                val k = photoMax.toFloat() / maxOf(bm.width, bm.height)
                val sm = if (k < 1f) android.graphics.Bitmap.createScaledBitmap(bm, (bm.width * k).toInt(), (bm.height * k).toInt(), true) else bm
                val bo = ByteArrayOutputStream(); sm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 55, bo)
                photoCb?.invoke(Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP))
            } catch (e: Exception) { toast("Photo failed") }
        }
    }

    // ================= SETELAH LOGIN =================
    fun afterAuth() {
        val u = auth.currentUser ?: return
        blocked = false
        showLoading("Signing in...")
        val email = (u.email ?: "").lowercase()
        Net.db.child("users/${u.uid}").once { s ->
            ud = UserData(); ud.load(s); ud.uid = u.uid; ud.email = email
            ud.role = if (email in ADMIN_EMAILS) "admin" else "member"
            L.lang = s.child("lang").getValue(String::class.java) ?: "English"
            Net.db.child("mods/${u.uid}").once { m ->
                if (ud.role != "admin" && m.exists()) ud.role = "mod"
                ensurePid {
                    Net.db.child("users/${u.uid}/email").setValue(email)
                    checkBan {
                        checkMaint {
                            attachGlobal(); presence(true)
                            if (ud.role == "admin") {
                                if (ud.nick.isEmpty()) { ud.nick = "Sano"; Net.db.child("users/${u.uid}/nick").setValue("Sano") }
                                showMenu()
                            } else if (ud.nick.isEmpty()) nickSetup() else showMenu()
                        }
                    }
                }
            }
        }
    }

    fun ensurePid(done: () -> Unit) {
        if (ud.pid.isNotEmpty()) { done(); return }
        claimPid(0, done)
    }
    fun claimPid(tries: Int, done: () -> Unit) {
        val cand = (10000 + java.util.Random().nextInt(90000)).toString()
        Net.db.child("ids/$cand").runTransaction(object : Transaction.Handler {
            override fun doTransaction(d: MutableData): Transaction.Result {
                if (d.getValue(String::class.java) == null) { d.setValue(ud.uid); return Transaction.success(d) }
                return Transaction.abort()
            }
            override fun onComplete(e: DatabaseError?, ok: Boolean, s: DataSnapshot?) {
                if (ok || tries >= 25) { ud.pid = cand; Net.db.child("users/${ud.uid}/pid").setValue(cand); done() }
                else claimPid(tries + 1, done)
            }
        })
    }

    fun checkBan(next: () -> Unit) {
        if (ud.role == "admin") { next(); return }
        Net.db.child("bans/${ud.uid}").once { s ->
            if (s.exists()) {
                val until = s.child("until").getValue(Long::class.javaObjectType) ?: -1L
                val reason = s.child("reason").getValue(String::class.java) ?: "-"
                if (until == -1L || until > now()) {
                    showBlocked("YOU ARE BANNED", "Reason: $reason\n" + (if (until == -1L) "Permanent" else "Until " + java.util.Date(until)))
                    return@once
                }
                Net.db.child("bans/${ud.uid}").removeValue()
            }
            next()
        }
    }

    fun checkMaint(next: () -> Unit) {
        Net.db.child("maintenance").once { s ->
            val on = s.child("on").getValue(Boolean::class.javaObjectType) ?: false
            if (on && ud.role == "member") {
                showBlocked("MAINTENANCE", s.child("reason").getValue(String::class.java) ?: "Be right back")
                return@once
            }
            next()
        }
    }

    fun attachGlobal() {
        clearGlobal()
        val l1 = object : ValueEventListener {
            override fun onCancelled(e: DatabaseError) {}
            override fun onDataChange(s: DataSnapshot) {
                val role = ud.role; val uid = ud.uid; val em = ud.email; val pid = ud.pid
                ud.load(s); ud.role = role; ud.uid = uid
                if (ud.email.isEmpty()) ud.email = em
                if (ud.pid.isEmpty()) ud.pid = pid
                onUser?.invoke()
            }
        }
        listenGlobal(Net.db.child("users/${ud.uid}"), l1)
        if (ud.role != "admin") {
            val l2 = object : ValueEventListener {
                override fun onCancelled(e: DatabaseError) {}
                override fun onDataChange(s: DataSnapshot) {
                    if (!s.exists() || blocked) return
                    val until = s.child("until").getValue(Long::class.javaObjectType) ?: -1L
                    if (until == -1L || until > now()) showBlocked("YOU ARE BANNED", "Reason: " + (s.child("reason").getValue(String::class.java) ?: "-"))
                }
            }
            listenGlobal(Net.db.child("bans/${ud.uid}"), l2)
            if (ud.role == "member") {
                val l3 = object : ValueEventListener {
                    override fun onCancelled(e: DatabaseError) {}
                    override fun onDataChange(s: DataSnapshot) {
                        val on = s.child("on").getValue(Boolean::class.javaObjectType) ?: false
                        if (on && !blocked) showBlocked("MAINTENANCE", s.child("reason").getValue(String::class.java) ?: "Be right back")
                    }
                }
                listenGlobal(Net.db.child("maintenance"), l3)
            }
        }
    }

    fun showBlocked(title: String, reason: String) {
        blocked = true
        h.removeCallbacks(loop); game?.stop(); game = null; gameShown = false
        val t = tv(title, 24f, 0xFFEF5350.toInt(), true); t.gravity = Gravity.CENTER
        val r = tv(reason, 14f); r.gravity = Gravity.CENTER
        val c = col(logo(90), panel(t, r), btn("LOGOUT", R.drawable.ic_exit) { doLogout() }, btn("EXIT", R.drawable.ic_exit) { finish() })
        screen(center(c, 400)) { finish() }
    }

    fun doLogout() {
        presence(false); clearGlobal(); game?.stop(); game = null; gameShown = false
        auth.signOut()
        try { GoogleSignIn.getClient(this, GoogleSignInOptions.DEFAULT_SIGN_IN).signOut() } catch (e: Exception) { }
        ud = UserData(); showWelcome()
    }

    // ---------- nickname ----------
    fun checkNick(n: String): String? {
        if (n.length < 3 || n.length > 16) return "Nickname 3-16 characters"
        if (!Regex("[A-Za-z0-9_]+").matches(n)) return "Letters, numbers and _ only"
        if (hasBad(n)) return "Inappropriate nickname"
        if (n.lowercase() == "sano" && ud.role != "admin") return "That nickname is reserved"
        return null
    }

    fun claimNick(n: String, old: String, cb: (Boolean) -> Unit) {
        val low = n.lowercase()
        Net.db.child("nicks/$low").runTransaction(object : Transaction.Handler {
            override fun doTransaction(d: MutableData): Transaction.Result {
                val v = d.getValue(String::class.java)
                if (v == null || v == ud.uid) { d.setValue(ud.uid); return Transaction.success(d) }
                return Transaction.abort()
            }
            override fun onComplete(e: DatabaseError?, ok: Boolean, s: DataSnapshot?) {
                if (ok && old.isNotEmpty() && old.lowercase() != low) Net.db.child("nicks/${old.lowercase()}").removeValue()
                cb(ok)
            }
        })
    }

    fun nickSetup() {
        val nick = edit("Nickname (3-16)")
        val c = col(mascot("Ayo Kita Mulai!", 100), nick, btn("START", R.drawable.ic_play) {
            val n = nick.text.toString().trim()
            val err = checkNick(n)
            if (err != null) { toast(err) } else claimNick(n, "") { ok ->
                if (ok) { ud.nick = n; Net.db.child("users/${ud.uid}/nick").setValue(n); showMenu() }
                else { Snd.play("error"); toast("Nickname already used by another player") }
            }
        })
        screen(center(c, 440), null)
    }

    // ================= MENU =================
    fun showMenu() {
        game = null; map = null; gameShown = false
        Snd.startMusic(this, "menu")
        val t = tv("DIPLOMA", 30f, C.GOLD, true); t.gravity = Gravity.CENTER; t.pulse()
        val items = ArrayList<View>()
        items.add(panel(t))
        items.add(two(btn(L.t("PLAY"), R.drawable.ic_play) { showPlay() }, btn(L.t("PROFILE"), R.drawable.ic_profile) { profilePopup() }))
        items.add(two(btn("GACHA", R.drawable.ic_gacha) { gachaPopup() }, btn("Beli Gold Premium", R.drawable.ic_gem) { premiumBuyer() }))
        if (ud.role == "admin" || ud.role == "mod") items.add(two(btn("Admin Panel", R.drawable.ic_admin) { adminPanel() }, btn(L.t("EXIT"), R.drawable.ic_exit) { finish() }))
        else items.add(btn(L.t("EXIT"), R.drawable.ic_exit) { finish() })
        if (!isOnline()) items.add(btn("OFFLINE MODE", R.drawable.ic_soldier) { offlineMenu() })
        val c = col(*items.toTypedArray()); c.addView(logo(64), 0)
        val sv = ScrollView(this); sv.addView(c)
        val root = center(sv, 400)
        val nm = tv(ud.nick, 14f, C.TXT, true)
        val chip = row()
        if (ud.role == "admin") { chip.addView(img(R.drawable.ic_verified, 18)); chip.addView(img(R.drawable.ic_dev, 18)) }
        if (ud.role == "mod") chip.addView(img(R.drawable.ic_mod, 18))
        chip.addView(nm)
        chip.background = rect(0xCC0A0C10.toInt(), C.GOLD, 10, 1); chip.setPadding(dp(8), dp(4), dp(8), dp(4))
        root.addView(chip, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).also { it.setMargins(dp(10), dp(10), 0, 0) })
        val gem = tv(fmt(ud.pgold), 14f, 0xFFE040FB.toInt(), true)
        val top = row(img(R.drawable.ic_gem, 20), gem)
        top.background = rect(0xCC0A0C10.toInt(), C.GOLD, 10, 1); top.setPadding(dp(8), dp(4), dp(8), dp(4))
        val badge = tv("", 12f, Color.WHITE, true)
        badge.background = rect(0xFFD32F2F.toInt(), 0xFFD32F2F.toInt(), 10, 1); badge.setPadding(dp(6), 0, dp(6), 0); badge.visibility = View.GONE
        if (ud.role == "admin") {
            val bell = img(R.drawable.ic_bell, 26)
            bell.setOnClickListener { Snd.play("click"); adminInbox() }
            top.addView(bell); top.addView(badge)
        }
        root.addView(top, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).also { it.setMargins(0, dp(10), dp(10), 0) })
        val v = tv("V5.4.1 | By Sano", 11f, C.GRAY)
        root.addView(v, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).also { it.bottomMargin = dp(10) })
        screen(root) { finish() }
        onUser = { gem.text = fmt(ud.pgold); nm.text = ud.nick }
        if (isOnline()) Net.db.child("appconfig").once { s ->
            val latest = s.child("latestCode").getValue(Long::class.javaObjectType) ?: 0L
            if (latest > curCode()) updatePopup(s.child("link").getValue(String::class.java) ?: "")
        }
        if (ud.role == "admin") {
            var nO = 0; var nR = 0
            fun upBadge() { val n = nO + nR; badge.text = n.toString(); badge.visibility = if (n > 0) View.VISIBLE else View.GONE; if (n > 0) Snd.play("notif") }
            listenScreen(Net.db.child("orders"), object : ValueEventListener {
                override fun onCancelled(e: DatabaseError) {}
                override fun onDataChange(s: DataSnapshot) {
                    var n = 0
                    for (o in s.children) if (o.child("status").getValue(String::class.java) == "open" && o.child("lastBy").getValue(String::class.java) == "buyer") n++
                    nO = n; upBadge()
                }
            })
            listenScreen(Net.db.child("reports"), object : ValueEventListener {
                override fun onCancelled(e: DatabaseError) {}
                override fun onDataChange(s: DataSnapshot) { nR = s.childrenCount.toInt(); upBadge() }
            })
        }
    }
    // ================= SERVER LIST =================
    fun showPlay() {
        val t = tv("SELECT SERVER", 22f, C.GOLD, true); t.gravity = Gravity.CENTER
        val list = col()
        val sv = ScrollView(this); sv.addView(list)
        val main = LinearLayout(this); main.orientation = LinearLayout.VERTICAL; main.setPadding(dp(80), dp(12), dp(28), dp(12))
        main.addView(panel(t), LinearLayout.LayoutParams(-1, -2))
        main.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        main.addView(two(btn("JOIN PRIVATE SERVER", R.drawable.ic_server) { joinPrivate() },
            btn("CREATE SERVER", R.drawable.ic_plus) { createPopup() }), LinearLayout.LayoutParams(-1, -2))
        val root = FrameLayout(this); root.addView(Backdrop(this)); root.addView(main, FrameLayout.LayoutParams(-1, -1))
        root.addView(btn("", R.drawable.ic_back) { back?.invoke() }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).also { it.setMargins(dp(8), dp(8), 0, 0) })
        screen(root) { showMenu() }
        listenScreen(Net.db.child("index"), object : ValueEventListener {
            override fun onCancelled(e: DatabaseError) { toast(e.message) }
            override fun onDataChange(s: DataSnapshot) {
                list.removeAllViews()
                val own = ArrayList<Pair<String, Idx>>(); val pub = ArrayList<Pair<String, Idx>>()
                for (c in s.children) {
                    val x = c.getValue(Idx::class.java) ?: continue
                    val k = c.key ?: continue
                    if (x.owner == ud.uid) own.add(Pair(k, x)) else if (x.type == "public") pub.add(Pair(k, x))
                }
                for ((k, x) in own + pub) {
                    val mine = x.owner == ud.uid
                    val title = tv(x.name + (if (mine) "  (YOURS" + (if (x.type == "private") ", private)" else ")") else ""), 16f, if (mine) 0xFFFFD54F.toInt() else C.GOLD, true)
                    val info = col(title, tv("Port ${x.port} | Players ${x.players}", 12f, C.GRAY))
                    val r = LinearLayout(this@MainActivity); r.gravity = Gravity.CENTER_VERTICAL
                    r.addView(img(R.drawable.ic_server, 32)); r.addW(info)
                    r.addView(btn("JOIN") { enterServer(k) })
                    list.addView(panel(r))
                }
                if (own.size + pub.size == 0) list.addView(tv("No public servers yet. Create one!", 14f, C.GRAY))
            }
        })
    }

    fun joinPrivate() {
        val ip = edit("play.<name>.my.dpl"); val port = edit("PORT", num = true)
        var d: Dialog? = null
        val b = col(lab("IP :", ip), lab("PORT :", port), btn("JOIN", R.drawable.ic_play) {
            val m = Regex("^play\\.(.+)\\.my\\.dpl$").find(ip.text.toString().trim().lowercase())
            if (m == null) { toast("Invalid IP") } else {
                val key = m.groupValues[1]
                Net.db.child("index/$key").once { s ->
                    val x = s.getValue(Idx::class.java)
                    if (x == null || x.port != port.text.toString().trim()) { Snd.play("error"); toast("Server not found / wrong port") }
                    else { d?.dismiss(); enterServer(key) }
                }
            }
        })
        d = popup("JOIN PRIVATE SERVER", b)
    }

    fun createPopup() {
        val name = edit("Server Name"); val port = edit("5 digits", num = true)
        port.filters = arrayOf(InputFilter.LengthFilter(5))
        val type = Cycle(this, "Type", listOf("public", "private"))
        val g = edit("500 - 10000000", num = true); g.setText("1000")
        val r = edit("500 - 10000000", num = true); r.setText("1000")
        val f = edit("500 - 10000000", num = true); f.setText("1000")
        val land = edit("100 - 100000", num = true); land.setText("100")
        val sold = edit("0 - 1000", num = true); sold.setText("0")
        val maxp = edit("2 - 200", num = true); maxp.setText("50")
        val delay = edit("1 - 5", num = true); delay.setText("2")
        val dRow = lab("Delay :", delay); dRow.visibility = View.GONE
        val chat = Cycle(this, "Global Chat", listOf("on", "off"))
        val nuke = Cycle(this, "Allow Nuke", listOf("on", "off"), 1)
        val fog = Cycle(this, "Fog of War", listOf("on", "off"))
        val wdel = Cycle(this, "War Delay (prep)", listOf("on", "off"))
        val incx = Cycle(this, "Income", listOf("x1", "x2", "x3", "x5"))
        lateinit var off: Cycle
        off = Cycle(this, "Offline Protection", listOf("on", "off"), 1) { dRow.visibility = if (off.value() == "on") View.VISIBLE else View.GONE }
        var d: Dialog? = null
        val setup = panel(tv("SETUP SERVER", 15f, C.GOLD, true), lab("Server Name :", name), lab("Server Port :", port), type.view,
            lab("Starter gold :", g), lab("Starter Resource :", r), lab("Starter Food :", f),
            lab("Starter Land (km) :", land), lab("Starter Soldiers :", sold), lab("Max Players :", maxp))
        val setting = panel(tv("SETTING", 15f, C.GOLD, true), chat.view, nuke.view, fog.view, wdel.view, incx.view, off.view, dRow)
        val b = col(setup, setting, btn("DEPLOY SERVER", R.drawable.ic_server) {
            val gv = g.text.toString().toLongOrNull() ?: -1; val rv = r.text.toString().toLongOrNull() ?: -1; val fv = f.text.toString().toLongOrNull() ?: -1
            val lv = land.text.toString().toLongOrNull() ?: -1; val sv = sold.text.toString().toLongOrNull() ?: -1
            val mv = maxp.text.toString().toLongOrNull() ?: -1; val dv = delay.text.toString().toLongOrNull() ?: 1
            val ok = { v: Long -> v in 500L..10000000L }
            val offOn = off.value() == "on"
            if (name.text.toString().trim().length < 3) toast("Server name min 3 chars")
            else if (port.text.length != 5) toast("Port must be 5 digits")
            else if (!ok(gv) || !ok(rv) || !ok(fv)) toast("Starter values: 500 - 10000000")
            else if (lv !in 100L..100000L) toast("Starter land: 100 - 100000 km")
            else if (sv !in 0L..1000L) toast("Starter soldiers: 0 - 1000")
            else if (mv !in 2L..200L) toast("Max players: 2 - 200")
            else if (offOn && dv !in 1L..5L) toast("Delay 1-5 minutes")
            else {
                val ex = HashMap<String, Any>()
                ex["gold0"] = gv; ex["res0"] = rv; ex["food0"] = fv; ex["land0"] = lv; ex["sold0"] = sv; ex["maxp"] = mv
                ex["chat"] = chat.value() == "on"; ex["nuke"] = nuke.value() == "on"; ex["fog"] = fog.value() == "on"
                ex["wardelay"] = wdel.value() == "on"; ex["incx"] = incx.value().substring(1).toLong()
                ex["off"] = offOn; ex["delay"] = if (offOn) dv else 1L
                Net.createServer(name.text.toString().trim(), port.text.toString(), type.value(), ex, ud.role == "admin") { err, key ->
                    if (err != null || key == null) { Snd.play("error"); toast(err ?: "Failed") } else {
                        d?.dismiss()
                        if (type.value() == "private") {
                            var d2: Dialog? = null
                            val info = col(tv("SERVER DEPLOYED!", 16f, C.GOLD, true), tv("IP : play.$key.my.dpl", 14f),
                                tv("Port : " + port.text.toString(), 14f),
                                btn("OK & JOIN NOW", R.drawable.ic_play) { d2?.dismiss(); enterServer(key) })
                            d2 = popup("INFORMATION", info)
                        } else toast("Server deployed!")
                    }
                }
            }
        })
        d = popup("CREATE SERVER", b)
    }
    // ================= MASUK SERVER =================
    fun enterServer(key: String) {
        val g = Game(key); game = g; gameShown = false
        g.badge = ud.badge(); g.title = itemById(ud.title)?.name ?: ""; g.cc = ud.cc
        var first = true; val t0 = now()
        showLoading("Entering server...")
        back = { leaveGame() }
        g.onKick = { toast("You were kicked from the server"); leaveGame() }
        g.onUpdate = {
            if (first) { first = false; h.postDelayed({ if (game === g) boot(g) }, maxOf(0L, 1400L - (now() - t0))) }
            else refresh()
        }
        g.listen()
    }

    fun leaveGame() {
        h.removeCallbacks(loop); gameShown = false
        val off = game is LocalGame
        game?.stop(); game = null; map = null; live = null; chatDlg = null; gameRoot = null
        newsSeen = -1; chatCount = -1; seenReq.clear()
        if (off) { Net.local = false; offlineMenu() } else showPlay()
    }
    fun boot(g: Game) {
        if (!g.exists) { toast("Server not found"); leaveGame(); return }
        if (g.bans.contains(g.uid)) { toast("You are banned from this server"); leaveGame(); return }
        if (g.me == null && g.players.size >= g.maxP) { toast("Server is full"); leaveGame(); return }
        if (g.me != null) { g.goOnline(); showGame(g); return }
        val cn = edit("Country name")
        var created = false
        var d: Dialog? = null
        val b = col(mascot("Beri nama negaramu!", 90), tv("President : ${ud.nick}", 14f, C.TXT, true), cn,
            btn("FOUND COUNTRY", R.drawable.ic_shield) {
                val n = cn.text.toString().trim()
                if (n.length < 3 || n.length > 20) toast("Country name 3-20 chars")
                else if (hasBad(n)) toast("Inappropriate country name")
                else g.createPlayer(ud.nick, n, "") { ok ->
                    if (ok) { created = true; d?.dismiss(); showGame(g) } else toast("No free land left on this server")
                }
            })
        d = popup("YOUR COUNTRY", b) { if (!created) leaveGame() }
    }

    // ================= GAMEPLAY =================
    fun showGame(g: Game) {
        val off = g is LocalGame
        val root = FrameLayout(this); root.setBackgroundColor(Color.BLACK); gameRoot = root
        val m = MapView(this); m.game = g; map = m
        m.onLong = { u -> if (u != g.uid) { if (g is LocalGame) offlineDiplomacy(g, u) else diplomacy(g, u) } }
        m.onTap = { x, y -> tapCell(g, x, y) }
        root.addView(m, FrameLayout.LayoutParams(-1, -1))
        tG = tv("", 13f, C.TXT, true); tR = tv("", 13f, C.TXT, true); tF = tv("", 13f, C.TXT, true); tL = tv("", 13f, C.TXT, true)
        tN = tv("", 13f, 0xFFFFEA00.toInt(), true)
        val top = row(img(R.drawable.ic_gold, 20), tG, img(R.drawable.ic_res, 20), tR, img(R.drawable.ic_food, 20), tF,
            img(R.drawable.ic_land, 20), tL, img(R.drawable.ic_nuke, 20), tN)
        top.background = rect(0xCC0A0C10.toInt(), C.GOLD, 10, 1); top.setPadding(dp(8), dp(4), dp(8), dp(4))
        root.addView(top, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).also { it.setMargins(dp(8), dp(8), 0, 0) })
        val bottom = if (g is LocalGame) two(btn(L.t("Action"), R.drawable.ic_action) { offlineAction(g) },
            btn("Conquer", R.drawable.ic_attack) { offlineAttack(g) }, btn(L.t("News"), R.drawable.ic_news) { newsPopup(g) })
        else two(btn(L.t("Action"), R.drawable.ic_action) { actionMenu(g) },
            btn(L.t("Global Chat"), R.drawable.ic_chat) { chatPopup(g) }, btn(L.t("News"), R.drawable.ic_news) { newsPopup(g) })
        root.addView(bottom, FrameLayout.LayoutParams(dp(560), -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).also { it.bottomMargin = dp(6) })
        val zc = LinearLayout(this); zc.orientation = LinearLayout.VERTICAL
        zc.addView(btn("", R.drawable.ic_plus) { m.zoom(1.3f) }); zc.addView(btn("", R.drawable.ic_minus) { m.zoom(0.77f) })
        zc.addView(btn("", R.drawable.ic_land) { m.centerOnMe() })
        root.addView(zc, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).also { it.rightMargin = dp(8) })
        stopBtn = btn("STOP EXPANSION", R.drawable.ic_swords) { g.stopExpand(); toast("Expansion stopped") }
        stopBtn.visibility = View.GONE
        root.addView(stopBtn, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).also { it.topMargin = dp(52) })
        screen(root) { confirmLeave() }
        Snd.startMusic(this, "game")
        if (!off) g.syncSkins(ud.skinMap(), ud.dmg())
        evtSeen = g.evtT
        gameShown = true
        h.removeCallbacks(loop); h.postDelayed(loop, 3000)
        refresh()
        if (!off) {
            if (ud.role == "admin") g.announce("ADMIN JOINED SERVER")
            if (!getSharedPreferences("dpl", 0).getBoolean("tut", false)) tutorial(0)
        }
    }

    fun showBanner(text: String) {
        val r = gameRoot ?: return
        val t = TextView(this); t.text = text; t.textSize = 30f; t.typeface = android.graphics.Typeface.DEFAULT_BOLD; t.gravity = Gravity.CENTER
        t.setShadowLayer(8f, 0f, 0f, Color.BLACK)
        t.paint.shader = android.graphics.LinearGradient(0f, 0f, t.paint.measureText(text), 0f,
            intArrayOf(0xFFFF1744.toInt(), 0xFFFF9100.toInt(), 0xFFFFEA00.toInt(), 0xFF00E676.toInt(), 0xFF00B0FF.toInt(), 0xFFD500F9.toInt()),
            null, android.graphics.Shader.TileMode.CLAMP)
        r.addView(t, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        t.scaleX = 0.2f; t.scaleY = 0.2f; t.alpha = 0f
        t.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(450).withEndAction {
            t.postDelayed({ t.animate().alpha(0f).scaleX(1.4f).scaleY(1.4f).setDuration(600).withEndAction { r.removeView(t) }.start() }, 2600)
        }.start()
        Snd.play("win")
    }
    fun confirmLeave() {
        var d: Dialog? = null
        d = popup("LEAVE SERVER?", two(btn("YES", R.drawable.ic_exit) { d?.dismiss(); leaveGame() }, btn("NO") { d?.dismiss() }))
    }

    fun refresh() {
        if (!gameShown) return
        val g = game ?: return; val me = g.me ?: return
        tG.text = fmt(me.gold); tR.text = fmt(me.res); tF.text = fmt(me.food); tN.text = me.nukes.toString()
        tL.text = "${me.landTotal() - me.landUsed()}/${me.landTotal()} km"
        map?.invalidate()
        live?.invoke()
        if (g.evtT > evtSeen) { evtSeen = g.evtT; if (now() - g.evtT < 20000L) showBanner(g.evtMsg) }
        stopBtn.visibility = if (me.elast > 0) View.VISIBLE else View.GONE
        for ((id, r) in g.reqs.toList()) if (seenReq.add(id)) reqPopup(g, id, r)
        val last = g.news.lastOrNull()
        if (newsSeen < 0) newsSeen = last?.t ?: 0L
        else if (last != null && last.t > newsSeen) { newsSeen = last.t; toast(last.msg) }
        if (chatCount >= 0 && g.chat.size > chatCount && chatDlg == null) Snd.play("notif")
        chatCount = g.chat.size
    }
}
