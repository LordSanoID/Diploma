package com.sano.dpl

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.SoundPool
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.*

object C {
    val BG = 0xFF0A0C10.toInt()
    val PANEL = 0xFF161A22.toInt()
    val GOLD = 0xFFD4AF37.toInt()
    val RED = 0xFF8E1B1B.toInt()
    val TXT = 0xFFEDEDED.toInt()
    val GRAY = 0xFF8A8F98.toInt()
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.rect(fill: Int, stroke: Int = C.GOLD, r: Int = 10, sw: Int = 2): GradientDrawable {
    val g = GradientDrawable()
    g.setColor(fill); g.cornerRadius = dp(r).toFloat(); g.setStroke(dp(sw), stroke)
    return g
}

fun Context.tv(text: String, size: Float = 14f, color: Int = C.TXT, bold: Boolean = false): TextView {
    val t = TextView(this)
    t.text = L.tr(text); t.textSize = size; t.setTextColor(color)
    if (bold) t.typeface = Typeface.DEFAULT_BOLD
    return t
}

fun Context.col(vararg v: View): LinearLayout {
    val l = LinearLayout(this); l.orientation = LinearLayout.VERTICAL
    for (x in v) l.addView(x, LinearLayout.LayoutParams(-1, -2).also { it.setMargins(0, dp(3), 0, dp(3)) })
    return l
}

fun Context.row(vararg v: View): LinearLayout {
    val l = LinearLayout(this); l.orientation = LinearLayout.HORIZONTAL; l.gravity = Gravity.CENTER_VERTICAL
    for (x in v) l.addView(x, LinearLayout.LayoutParams(-2, -2).also { it.setMargins(dp(3), 0, dp(3), 0) })
    return l
}

fun LinearLayout.addW(v: View, w: Float = 1f) {
    val p = LinearLayout.LayoutParams(0, -2, w); p.setMargins(context.dp(3), context.dp(2), context.dp(3), context.dp(2)); addView(v, p)
}

fun Context.panel(vararg v: View): LinearLayout {
    val l = col(*v); l.background = grad(0xFF1F2530.toInt(), 0xFF12151B.toInt(), 0xFF3A4150.toInt(), 12, 1); l.setPadding(dp(12), dp(8), dp(12), dp(8)); return l
}

fun Context.btn(text: String, icon: Int = 0, f: () -> Unit): Button {
    val b = Button(this)
    b.text = L.tr(text); b.isAllCaps = false; b.setTextColor(C.GOLD); b.textSize = 14f
    b.typeface = Typeface.DEFAULT_BOLD; b.minHeight = dp(40); b.minimumHeight = dp(40)
    val sl = StateListDrawable()
    sl.addState(intArrayOf(android.R.attr.state_pressed), grad(0xFF6A5626.toInt(), 0xFF3A3220.toInt(), C.GOLD, 10, 2))
    sl.addState(intArrayOf(), grad(0xFF2C3341.toInt(), 0xFF171B24.toInt(), C.GOLD, 10, 2))
    b.background = sl
    if (icon != 0) {
        val d = getDrawable(icon)
        if (d != null) { d.setBounds(0, 0, dp(22), dp(22)); b.setCompoundDrawables(d, null, null, null); b.compoundDrawablePadding = dp(8) }
    }
    b.setOnClickListener { Snd.play("click"); f() }
    b.setOnTouchListener { v, e ->
        if (e.action == MotionEvent.ACTION_DOWN) v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80).start()
        else if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL) v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        false
    }
    return b
}

fun Context.edit(hint: String, num: Boolean = false, pass: Boolean = false): EditText {
    val e = EditText(this)
    e.hint = L.tr(hint); e.setHintTextColor(C.GRAY); e.setTextColor(C.TXT); e.textSize = 14f
    e.setSingleLine(true)
    e.background = rect(0xFF0F1218.toInt(), 0xFF3A4150.toInt(), 8, 1)
    e.setPadding(dp(10), dp(8), dp(10), dp(8))
    if (num) e.inputType = InputType.TYPE_CLASS_NUMBER
    if (pass) e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    return e
}

fun Context.img(res: Int, size: Int = 24): ImageView {
    val i = ImageView(this); i.setImageResource(res)
    i.layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)); return i
}

/** Tombol yang ganti nilai tiap diklik (publik/private, on/off, dll). */
class Cycle(ctx: Context, val label: String, val opts: List<String>, start: Int = 0, val onChange: (() -> Unit)? = null) {
    var i = start
    val view: Button = ctx.btn("") { i = (i + 1) % opts.size; upd(); onChange?.invoke() }
    init { upd() }
    private fun upd() { view.text = L.tr(label) + ": " + L.tr(opts[i]) }
    fun value(): String = opts[i]
}

fun Activity.popup(title: String, body: View, wide: Boolean = false, closable: Boolean = true, onDismiss: (() -> Unit)? = null): Dialog {
    val d = Dialog(this)
    d.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val box = LinearLayout(this); box.orientation = LinearLayout.VERTICAL
    box.background = grad(0xFF1D232E.toInt(), 0xFF0E1116.toInt(), C.GOLD, 16, 2); box.setPadding(dp(14), dp(10), dp(14), dp(10))
    val head = LinearLayout(this); head.gravity = Gravity.CENTER_VERTICAL
    val bk = ImageView(this); bk.setImageResource(R.drawable.ic_back); bk.setPadding(dp(4), dp(4), dp(4), dp(4))
    bk.setOnClickListener { Snd.play("click"); d.dismiss() }
    if (!closable) { bk.visibility = View.INVISIBLE; d.setCancelable(false) }
    head.addView(bk, LinearLayout.LayoutParams(dp(34), dp(34)))
    val t = tv(title, 18f, C.GOLD, true); t.gravity = Gravity.CENTER
    head.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
    head.addView(View(this), LinearLayout.LayoutParams(dp(34), dp(34)))
    box.addView(head, LinearLayout.LayoutParams(-1, -2))
    val sv = ScrollView(this); sv.addView(body)
    box.addView(sv, LinearLayout.LayoutParams(-1, -2))
    d.setContentView(box)
    d.setOnDismissListener { onDismiss?.invoke() }
    d.show()
    box.popIn()
    val w = resources.displayMetrics.widthPixels
    val h = resources.displayMetrics.heightPixels
    d.window?.setBackgroundDrawable(ColorDrawable(0))
    d.window?.setLayout(if (wide) (w * 0.92f).toInt() else minOf((w * 0.8f).toInt(), dp(560)), -2)
    return d
}

fun Activity.toast(s: String) {
    runOnUiThread {
        val root = findViewById<ViewGroup>(android.R.id.content) as? FrameLayout
        if (root != null) {
            root.findViewWithTag<View>("dpl_toast")?.let { root.removeView(it) }
            val bad = Regex("(?i)not |cannot|invalid|fail|error|only |wait|already|need|no |banned|muted|disabled|too far|locked|gone|still").containsMatchIn(s)
            val accent = if (bad) 0xFFEF5350.toInt() else 0xFF66BB6A.toInt()
            val ic = ImageView(this); ic.setImageResource(if (bad) R.drawable.ic_bell else R.drawable.ic_verified)
            val t = tv(s, 13f, C.TXT, true); t.maxWidth = dp(420)
            val card = LinearLayout(this); card.gravity = Gravity.CENTER_VERTICAL; card.tag = "dpl_toast"
            card.background = grad(0xF01D232E.toInt(), 0xF00E1116.toInt(), accent, 14, 2); card.setPadding(dp(14), dp(10), dp(16), dp(10))
            card.addView(ic, LinearLayout.LayoutParams(dp(22), dp(22)))
            card.addView(t, LinearLayout.LayoutParams(-2, -2).also { it.leftMargin = dp(10) })
            root.addView(card, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).also { it.topMargin = dp(48) })
            card.translationY = -dp(60).toFloat(); card.alpha = 0f
            card.animate().translationY(0f).alpha(1f).setDuration(220).start()
            card.postDelayed({ card.animate().translationY(-dp(60).toFloat()).alpha(0f).setDuration(220).withEndAction { root.removeView(card) }.start() }, 2400)
        }
    }
}
fun fmt(n: Long): String = String.format("%,d", n)
fun hm(t: Long): String = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(t))

object Snd {
    var sfx = true
    var music = true
    var track = "menu"
    private var sp: SoundPool? = null
    private val ids = HashMap<String, Int>()
    private var mp: MediaPlayer? = null
    private var cur = ""
    fun init(c: Context) {
        if (sp != null) return
        val p = c.getSharedPreferences("dpl", 0)
        sfx = p.getBoolean("sfx", true); music = p.getBoolean("mus", true)
        @Suppress("DEPRECATION")
        val s = SoundPool(8, AudioManager.STREAM_MUSIC, 0)
        ids["click"] = s.load(c, R.raw.click, 1); ids["build"] = s.load(c, R.raw.build, 1)
        ids["notif"] = s.load(c, R.raw.notif, 1); ids["war"] = s.load(c, R.raw.war, 1)
        ids["nuke"] = s.load(c, R.raw.nuke, 1); ids["win"] = s.load(c, R.raw.win, 1)
        ids["gacha"] = s.load(c, R.raw.gacha, 1); ids["coin"] = s.load(c, R.raw.coin, 1)
        ids["error"] = s.load(c, R.raw.error, 1)
        sp = s
    }
    fun save(c: Context) { c.getSharedPreferences("dpl", 0).edit().putBoolean("sfx", sfx).putBoolean("mus", music).apply() }
    fun play(n: String) { if (sfx) ids[n]?.let { sp?.play(it, 1f, 1f, 1, 0, 1f) } }
    fun startMusic(c: Context, t: String = track) {
        track = t
        if (!music) return
        try {
            if (mp == null || cur != t) {
                mp?.release()
                mp = MediaPlayer.create(c, if (t == "game") R.raw.bgm_game else R.raw.bgm_menu)
                mp?.isLooping = true; mp?.setVolume(0.5f, 0.5f); cur = t
            }
            mp?.start()
        } catch (e: Exception) { }
    }
    fun stopMusic() { try { mp?.pause() } catch (e: Exception) { } }
}

object L {
    var lang = "English"
    val names = listOf("English", "Indonesia", "Japan", "Korea", "Spanish")
    private val m: Map<String, Map<String, String>> = mapOf(
        "Indonesia" to mapOf("PLAY" to "MAIN", "PROFILE" to "PROFIL", "EXIT" to "KELUAR", "Action" to "Aksi", "Global Chat" to "Chat Global",
            "News" to "Berita", "BUILD" to "BANGUN", "CREATE SOLDIER" to "BUAT PASUKAN", "SHOP" to "TOKO", "TRADE" to "DAGANG",
            "ATTACK" to "SERANG", "WAR" to "PERANG", "Gold" to "Emas", "Resource" to "Sumber Daya", "Food" to "Makanan", "Land" to "Lahan", "APPLY" to "TERAPKAN"),
        "Japan" to mapOf("PLAY" to "プレイ", "PROFILE" to "プロフィール", "EXIT" to "終了", "Action" to "行動", "Global Chat" to "全体チャット",
            "News" to "ニュース", "BUILD" to "建設", "CREATE SOLDIER" to "兵を作る", "SHOP" to "ショップ", "TRADE" to "交易",
            "ATTACK" to "攻撃", "WAR" to "戦争", "Gold" to "金", "Resource" to "資源", "Food" to "食料", "Land" to "土地", "APPLY" to "適用"),
        "Korea" to mapOf("PLAY" to "플레이", "PROFILE" to "프로필", "EXIT" to "종료", "Action" to "행동", "Global Chat" to "전체 채팅",
            "News" to "뉴스", "BUILD" to "건설", "CREATE SOLDIER" to "병사 생성", "SHOP" to "상점", "TRADE" to "무역",
            "ATTACK" to "공격", "WAR" to "전쟁", "Gold" to "골드", "Resource" to "자원", "Food" to "식량", "Land" to "영토", "APPLY" to "적용"),
        "Spanish" to mapOf("PLAY" to "JUGAR", "PROFILE" to "PERFIL", "EXIT" to "SALIR", "Action" to "Acción", "Global Chat" to "Chat Global",
            "News" to "Noticias", "BUILD" to "CONSTRUIR", "CREATE SOLDIER" to "CREAR TROPAS", "SHOP" to "TIENDA", "TRADE" to "COMERCIO",
            "ATTACK" to "ATACAR", "WAR" to "GUERRA", "Gold" to "Oro", "Resource" to "Recurso", "Food" to "Comida", "Land" to "Tierra", "APPLY" to "APLICAR")
    )
    fun t(k: String): String = m[lang]?.get(k) ?: k
    fun tr(s: String): String {
        if (lang == "English" || s.isEmpty()) return s
        val ex = (if (lang == "Indonesia") ID_EXACT[s] else null) ?: m[lang]?.get(s)
        if (ex != null) return ex
        if (lang != "Indonesia") return s
        var out = s
        for ((k, v) in ID_FRAG) if (out.contains(k)) out = out.replace(k, v)
        return out
    }
}

/** Latar peta dunia redup untuk menu. */
class Backdrop(c: Context) : View(c) {
    private var bm: android.graphics.Bitmap? = null
    private val p = Paint()
    override fun onDraw(cv: Canvas) {
        if (bm == null) bm = World.bitmap(2)
        cv.drawColor(C.BG)
        p.alpha = 70
        val b = bm!!
        cv.drawBitmap(b, null, android.graphics.Rect(0, 0, width, height), p)
    }
}

fun Context.grad(top: Int, bottom: Int, stroke: Int = C.GOLD, r: Int = 10, sw: Int = 2): GradientDrawable {
    val g = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom))
    g.cornerRadius = dp(r).toFloat(); g.setStroke(dp(sw), stroke)
    return g
}

fun View.fadeIn(d: Long = 350) { alpha = 0f; animate().alpha(1f).setDuration(d).start() }
fun View.popIn() { scaleX = 0.85f; scaleY = 0.85f; alpha = 0f; animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).start() }
fun View.bob() {
    val a = ObjectAnimator.ofFloat(this, "translationY", 0f, -14f, 0f)
    a.duration = 1500; a.repeatCount = ValueAnimator.INFINITE; a.interpolator = AccelerateDecelerateInterpolator(); a.start()
}
fun View.pulse() {
    val a = ObjectAnimator.ofPropertyValuesHolder(this, PropertyValuesHolder.ofFloat("scaleX", 1f, 1.07f, 1f), PropertyValuesHolder.ofFloat("scaleY", 1f, 1.07f, 1f))
    a.duration = 1900; a.repeatCount = ValueAnimator.INFINITE; a.start()
}

/** Maskot + gelembung bicara */
fun Context.mascot(text: String, size: Int = 90): LinearLayout {
    val m = ImageView(this); m.setImageResource(R.drawable.mascot); m.bob()
    val t = tv(text, 14f, C.TXT, true)
    t.background = rect(0xFF222836.toInt(), C.GOLD, 14, 2); t.setPadding(dp(12), dp(8), dp(12), dp(8))
    val l = LinearLayout(this); l.gravity = Gravity.CENTER_VERTICAL
    l.addView(m, LinearLayout.LayoutParams(dp(size), dp(size)))
    l.addView(t, LinearLayout.LayoutParams(0, -2, 1f).also { it.leftMargin = dp(8) })
    return l
}
