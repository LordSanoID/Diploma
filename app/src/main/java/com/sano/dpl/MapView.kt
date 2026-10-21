package com.sano.dpl

import android.content.Context
import android.graphics.*
import android.util.Base64
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

class MapView(c: Context) : View(c) {
    var game: Game? = null
    var onLong: ((String) -> Unit)? = null
    var onTap: ((Int, Int) -> Unit)? = null
    private var fogBm: Bitmap? = null
    private var fogVis = BooleanArray(0)
    private var fogSig = 0
    private val fogPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val cp = 12f
    private var scale = 1f
    private var minScale = 0.2f
    private var tx = 0f
    private var ty = 0f
    private var bm: Bitmap? = null
    private var centered = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG)

    private val sg = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val ns = max(minScale, min(8f, scale * d.scaleFactor))
            val f = ns / scale
            tx = d.focusX - (d.focusX - tx) * f
            ty = d.focusY - (d.focusY - ty) * f
            scale = ns; clamp(); invalidate(); return true
        }
    })
    private val gd = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            tx -= dx; ty -= dy; clamp(); invalidate(); return true
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onTap?.invoke(((e.x - tx) / scale / cp).toInt(), ((e.y - ty) / scale / cp).toInt()); return true
        }
        override fun onLongPress(e: MotionEvent) {
            val wx = ((e.x - tx) / scale / cp).toInt()
            val wy = ((e.y - ty) / scale / cp).toInt()
            val g = game ?: return
            for ((k, p) in g.players) if (p.hit(wx, wy)) { onLong?.invoke(k); return }
        }
    })

    fun zoom(f: Float) {
        val ns = max(minScale, min(8f, scale * f)); val k = ns / scale
        tx = width / 2f - (width / 2f - tx) * k; ty = height / 2f - (height / 2f - ty) * k
        scale = ns; clamp(); invalidate()
    }

    private fun clamp() {
        val ww = World.W * cp * scale; val hh = World.H * cp * scale
        tx = if (ww <= width) (width - ww) / 2 else max(width - ww, min(0f, tx))
        ty = if (hh <= height) (height - hh) / 2 else max(height - hh, min(0f, ty))
    }

    fun visionR(m: Player): Int = minOf(40, 5 + 2 * m.b("watchtower").toInt() + 6 * m.b("radar").toInt())
    fun visible(cx: Float, cy: Float): Boolean {
        val x = cx.toInt(); val y = cy.toInt()
        return fogVis.size == World.W * World.H && x in 0 until World.W && y in 0 until World.H && fogVis[y * World.W + x]
    }
    private fun rebuildFog(m: Player) {
        val W = World.W; val H = World.H; val r = visionR(m)
        val vis = BooleanArray(W * H); val px = IntArray(W * H)
        for (sg in m.segs()) for (x in sg[1]..sg[2]) {
            for (dy in -r..r) {
                val yy = sg[0] + dy
                if (yy < 0 || yy >= H) continue
                val w = Math.sqrt((r * r - dy * dy).toDouble()).toInt()
                for (dx in -w..w) { val xx = x + dx; if (xx in 0 until W) vis[yy * W + xx] = true }
            }
        }
        for (i in px.indices) px[i] = if (vis[i]) 0 else 0xC8000000.toInt()
        val b = fogBm ?: Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        b.setPixels(px, 0, W, 0, 0, W, H); fogBm = b; fogVis = vis
    }

    fun centerOnMe() {
        val m = game?.me ?: return
        val b = m.box()
        scale = max(minScale, 3.5f)
        tx = width / 2f - b[4] * cp * scale
        ty = height / 2f - b[5] * cp * scale
        clamp(); invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        minScale = max(max(w / (World.W * cp), h / (World.H * cp)), 0.7f)
        if (scale < minScale) scale = minScale
        clamp()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        sg.onTouchEvent(e)
        if (!sg.isInProgress) gd.onTouchEvent(e)
        return true
    }

    override fun onDraw(cv: Canvas) {
        cv.drawColor(Color.BLACK)
        val g = game
        if (bm == null) bm = World.bitmap(4)
        if (!centered && g?.me != null && width > 0) { centered = true; centerOnMe() }
        cv.save(); cv.translate(tx, ty); cv.scale(scale, scale)
        paint.style = Paint.Style.FILL; paint.color = Color.WHITE
        cv.drawBitmap(bm!!, null, RectF(0f, 0f, World.W * cp, World.H * cp), paint)
        var anim = false
        val mine = g?.me
        if (g != null && mine != null && g.fogOn) {
            val sig = mine.cells.hashCode() * 31 + visionR(mine)
            if (sig != fogSig || fogBm == null) { fogSig = sig; rebuildFog(mine) }
            cv.drawBitmap(fogBm!!, null, RectF(0f, 0f, World.W * cp, World.H * cp), fogPaint)
        }
        if (g != null) {
            for ((k, p) in g.players) {
                if (k != g.uid && mine != null && g.fogOn) { val bb = p.box(); if (!visible(bb[4], bb[5])) continue }
                val fb = if (p.flag.isNotEmpty()) FlagCache.get(p.flag) else null
                if (fb != null) {
                    val path = Path()
                    for (s in p.segs()) path.addRect(s[1] * cp, s[0] * cp, (s[2] + 1) * cp, (s[0] + 1) * cp, Path.Direction.CW)
                    val bx = p.box()
                    cv.save(); cv.clipPath(path)
                    cv.drawBitmap(fb, null, RectF(bx[0] * cp, bx[1] * cp, bx[2] * cp, bx[3] * cp), fogPaint)
                    cv.restore()
                }
                paint.style = Paint.Style.FILL
                paint.color = (p.color and 0x00FFFFFF) or (if (fb != null) 0x30000000 else if (k == g.uid) 0xB0000000.toInt() else 0x80000000.toInt())
                for (s in p.segs()) cv.drawRect(s[1] * cp, s[0] * cp, (s[2] + 1) * cp, (s[0] + 1) * cp, paint)
                val b = p.box(); val bw = (b[2] - b[0]) * cp
                val cx = b[4] * cp; val cy = b[5] * cp
                val s = p.country + " | " + p.nick
                txt.color = Color.WHITE; txt.typeface = Typeface.DEFAULT_BOLD
                txt.textSize = max(9f, min(bw * 0.12f, 40f))
                txt.textAlign = Paint.Align.CENTER
                txt.setShadowLayer(4f, 0f, 0f, Color.BLACK)
                cv.drawText(s, cx, cy, txt)
                txt.clearShadowLayer()
                if (k != g.uid) {
                    val ic = when (g.relOf(k)) { "ally" -> R.drawable.ic_shield; "war" -> R.drawable.ic_swords; else -> 0 }
                    if (ic != 0) {
                        val d = context.getDrawable(ic)
                        if (d != null) {
                            val sz = (cp * 3.2f).toInt(); val l = (cx - sz / 2).toInt(); val t = (cy - txt.textSize - sz).toInt()
                            d.setBounds(l, t, l + sz, t + sz); d.draw(cv)
                        }
                    }
                    if (!p.online) {
                        txt.textSize = max(10f, txt.textSize * 0.9f); txt.color = 0xFF90CAF9.toInt()
                        cv.drawText("ZZZZ", cx, cy + txt.textSize * 1.3f, txt)
                    }
                }
            }
            if (mine != null && mine.elast > 0) {
                anim = true
                val side = maxOf(10f / scale, cp * Math.sqrt(maxOf(5L, mine.cprog) / 100.0).toFloat())
                val ccx = (mine.ex + 0.5f) * cp; val ccy = (mine.ey + 0.5f) * cp
                paint.style = Paint.Style.FILL; paint.color = 0xA0FFEA00.toInt()
                cv.drawRect(ccx - side / 2, ccy - side / 2, ccx + side / 2, ccy + side / 2, paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; paint.color = Color.YELLOW
                cv.drawCircle((mine.ex + 0.5f) * cp, (mine.ey + 0.5f) * cp, cp * (1.5f + (now() % 1500L) / 1500f * 2f), paint)
            }
            val t0 = now()
            for (f in g.fx) {
                val age = t0 - f.t
                if (age < 0 || age > 7000) continue
                anim = true
                val k = age / 7000f
                paint.style = Paint.Style.FILL; paint.color = Color.argb(((1 - k) * 150).toInt(), 255, 140, 0)
                cv.drawCircle(f.x * cp, f.y * cp, cp * (3 + k * 26), paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f; paint.color = Color.argb(((1 - k) * 255).toInt(), 255, 240, 160)
                cv.drawCircle(f.x * cp, f.y * cp, cp * (3 + k * 36), paint)
                if (age < 700) { paint.style = Paint.Style.FILL; paint.color = Color.argb(((1 - age / 700f) * 255).toInt(), 255, 255, 255); cv.drawCircle(f.x * cp, f.y * cp, cp * 7, paint) }
            }
        }
        cv.restore()
        if (anim) postInvalidateOnAnimation()
    }
}

object FlagCache {
    private val m = HashMap<Int, Bitmap?>()
    fun get(b64: String): Bitmap? {
        val k = b64.hashCode()
        if (m.containsKey(k)) return m[k]
        val bm = try { val by = Base64.decode(b64, Base64.DEFAULT); BitmapFactory.decodeByteArray(by, 0, by.size) } catch (ex: Exception) { null }
        m[k] = bm; return bm
    }
}
