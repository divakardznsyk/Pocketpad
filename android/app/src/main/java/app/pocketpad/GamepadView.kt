package app.pocketpad

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The on-screen controller. Draws whatever the [Preset] says, handles multi-touch play, a touchpad that
 * moves the laptop's mouse, a live TUNE panel (stick and pad speed while the game runs) and an edit mode.
 */
class GamepadView(
    context: Context,
    private val state: PadState,
    private val preset: Preset,
) : View(context) {

    /** Round-trip time to the laptop in ms; -2 = connecting, -1 = no answer. */
    @Volatile
    var pingMs = -2

    var onClose: (() -> Unit)? = null
    var onSave: (() -> Unit)? = null
    var haptics = true

    /** When true, "Done" in edit mode leaves the screen instead of returning to play. */
    var exitOnDone = false

    var editMode = false
        private set

    private class Item(val ctl: Ctl, val cfg: CC) {
        var cx = 0f
        var cy = 0f
        var a = 0f
        var b = 0f
        val shown get() = cfg.visible

        /** 0 at the centre, 1 at the edge; used to pick the nearest control. */
        fun distance(x: Float, y: Float): Float =
            if (ctl.kind == Ctl.RECT || ctl.kind == Ctl.PAD) maxOf(abs(x - cx) / a, abs(y - cy) / b)
            else hypot(x - cx, y - cy) / a
    }

    private class StickRt {
        var pid = -1
        var dx = 0f
        var dy = 0f
    }

    private class Chip(val id: String, val label: String, val rect: RectF, val accent: Boolean = false, val on: Boolean = false)

    private val items = ArrayList<Item>()
    private val sticks = HashMap<String, StickRt>()
    private var pressedMask = 0
    private var ltOn = false
    private var rtOn = false

    // chips (HUD, tune panel, edit toolbar). Rebuilt only when something changes, never per frame.
    private var chipCache: List<Chip>? = null
    private var chipPid = -1
    private var chipDownId: String? = null
    private var deadPid = -1
    private var tuneOpen = false
    private val panel = RectF()
    private val pingRect = RectF()

    // touchpad
    private var padPid = -1
    private var pad2Pid = -1
    private var padX = 0f
    private var padY = 0f
    private var padAccX = 0f
    private var padAccY = 0f
    private var padDownT = 0L
    private var pad2DownT = 0L
    private var padTravel = 0f

    // edit mode
    private var selected: String? = null
    private var dragging = false
    private var dragOffX = 0f
    private var dragOffY = 0f

    private val dens = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Theme.cond()
    }
    private val rect = RectF()
    private val path = Path()
    private val ui = Handler(Looper.getMainLooper())
    private val vibrator: Vibrator? = try {
        context.getSystemService(Vibrator::class.java)
    } catch (_: Exception) {
        null
    }

    // Haptics on their own thread: a vibrate() call on the UI thread can stall a frame.
    private val hapticExec = Executors.newSingleThreadExecutor()

    init {
        for (c in Layout.all) if (c.kind == Ctl.STICK) sticks[c.id] = StickRt()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        hapticExec.shutdownNow()
    }

    fun startEditing() {
        releaseAll()
        tuneOpen = false
        editMode = true
        selected = null
        chipCache = null
        invalidate()
    }

    private fun stopEditing() {
        editMode = false
        selected = null
        dragging = false
        chipCache = null
        onSave?.invoke()
        if (exitOnDone) onClose?.invoke()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
        chipCache = null
        if (android.os.Build.VERSION.SDK_INT >= 29) systemGestureExclusionRects = listOf(Rect(0, 0, w, h))
    }

    /** Recompute pixel positions from the preset. Cheap enough to run on every drag step. */
    private fun rebuild() {
        items.clear()
        val w = width.toFloat()
        val h = height.toFloat()
        for (ctl in Layout.all) {
            val cfg = preset.cc[ctl.id] ?: continue
            val it = Item(ctl, cfg)
            it.cx = cfg.x * w
            it.cy = cfg.y * h
            it.a = ctl.a * h * cfg.scale
            it.b = ctl.b * h * cfg.scale
            items.add(it)
        }
        // draw order: touchpad under sticks under buttons
        items.sortBy { if (it.ctl.kind == Ctl.PAD) 0 else if (it.ctl.kind == Ctl.STICK) 1 else 2 }
    }

    private fun releaseAll() {
        chipPid = -1
        chipDownId = null
        deadPid = -1
        padPid = -1
        pad2Pid = -1
        state.mouseButtons = 0
        for (s in sticks.values) {
            s.pid = -1
            s.dx = 0f
            s.dy = 0f
        }
        pushSticks()
        publish(0, 0, 0)
    }

    // ---------- sticks ----------

    private fun item(id: String) = items.firstOrNull { it.ctl.id == id }

    private fun moveStick(id: String, x: Float, y: Float) {
        val it = item(id) ?: return
        val s = sticks[id] ?: return
        var vx = (x - it.cx) / it.a
        var vy = (y - it.cy) / it.a
        val m = hypot(vx, vy)
        if (m > 1f) {
            vx /= m
            vy /= m
        }
        s.dx = vx
        s.dy = vy
        pushSticks()
    }

    private fun releaseStick(id: String) {
        val s = sticks[id] ?: return
        s.pid = -1
        s.dx = 0f
        s.dy = 0f
        pushSticks()
    }

    /** Apply the preset's stick speed (gain) and a small dead zone, then hand the values to the sender. */
    private fun pushSticks() {
        fun axis(v: Float, m: Float, gain: Float) = (v * (minOf(1f, m * gain) / m) * 32767f).toInt()
        val l = sticks["LS"]
        val r = sticks["RS"]
        if (l != null) {
            val m = hypot(l.dx, l.dy)
            state.lx = if (m < 0.05f) 0 else axis(l.dx, m, preset.leftGain)
            state.ly = if (m < 0.05f) 0 else -axis(l.dy, m, preset.leftGain) // Xbox: up is positive
        }
        if (r != null) {
            val m = hypot(r.dx, r.dy)
            state.rx = if (m < 0.05f) 0 else axis(r.dx, m, preset.rightGain)
            state.ry = if (m < 0.05f) 0 else -axis(r.dy, m, preset.rightGain)
        }
        state.changed()
        invalidate()
    }

    // ---------- touch ----------

    private fun hit(x: Float, y: Float): Item? {
        var best: Item? = null
        var bestD = 1.15f
        for (it in items) {
            if (it.ctl.kind == Ctl.STICK || it.ctl.kind == Ctl.PAD || !it.shown) continue
            val d = it.distance(x, y)
            if (d <= bestD) {
                bestD = d
                best = it
            }
        }
        return best
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) requestUnbufferedDispatch(e) // no touch resampling: less latency
        return if (editMode) editTouch(e) else playTouch(e)
    }

    private fun playTouch(e: MotionEvent): Boolean {
        val action = e.actionMasked
        if (action == MotionEvent.ACTION_CANCEL) {
            releaseAll()
            return true
        }
        val actId = e.getPointerId(e.actionIndex)
        val lifting = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP
        val downing = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN

        var mask = 0
        var lt = 0
        var rt = 0
        for (i in 0 until e.pointerCount) {
            val id = e.getPointerId(i)
            val x = e.getX(i)
            val y = e.getY(i)
            val thisOne = id == actId

            if (id == chipPid) {
                if (lifting && thisOne) {
                    val c = chipDownId
                    chipPid = -1
                    chipDownId = null
                    if (c != null && chipAt(x, y)?.id == c) runChip(c)
                }
                continue
            }
            if (id == deadPid) {
                if (lifting && thisOne) deadPid = -1
                continue
            }
            if (downing && thisOne) {
                val c = chipAt(x, y)
                if (c != null) {
                    chipPid = id
                    chipDownId = c.id
                    continue
                }
                if (tuneOpen && panel.contains(x, y)) {
                    deadPid = id
                    continue
                }
            }
            if (lifting && thisOne) {
                for ((sid, s) in sticks) if (s.pid == id) releaseStick(sid)
                if (id == padPid) padUp()
                if (id == pad2Pid) pad2Up()
                continue
            }
            val stickId = sticks.entries.firstOrNull { it.value.pid == id }?.key
            if (stickId != null) {
                moveStick(stickId, x, y)
                continue
            }
            if (id == padPid) {
                padMove(x, y)
                continue
            }
            if (id == pad2Pid) continue

            val b = hit(x, y)
            if (b != null) {
                val a = b.cfg.action
                if (a in 0..13) mask = mask or (1 shl a)
                if (a == Act.LT) lt = 255
                if (a == Act.RT) rt = 255
                continue
            }
            if (downing && thisOne) {
                var taken = false
                for (it in items) {
                    if (it.ctl.kind != Ctl.STICK || !it.shown) continue
                    val s = sticks[it.ctl.id] ?: continue
                    if (s.pid == -1 && hypot(x - it.cx, y - it.cy) <= it.a * 1.2f) {
                        s.pid = id
                        moveStick(it.ctl.id, x, y)
                        taken = true
                        break
                    }
                }
                if (!taken) {
                    val p = items.firstOrNull { it.ctl.kind == Ctl.PAD && it.shown && it.distance(x, y) <= 1f }
                    if (p != null) {
                        if (padPid == -1) {
                            padPid = id
                            padX = x
                            padY = y
                            padTravel = 0f
                            padDownT = SystemClock.uptimeMillis()
                            invalidate()
                        } else if (pad2Pid == -1) {
                            pad2Pid = id
                            pad2DownT = SystemClock.uptimeMillis()
                        }
                    }
                }
            }
        }
        publish(mask, lt, rt)
        return true
    }

    // ---------- touchpad ----------

    private fun padMove(x: Float, y: Float) {
        val dx = x - padX
        val dy = y - padY
        padX = x
        padY = y
        padTravel += hypot(dx, dy)
        padAccX += dx * preset.padSpeed
        padAccY += dy * preset.padSpeed
        val ix = padAccX.toInt()
        val iy = padAccY.toInt()
        if (ix != 0 || iy != 0) {
            padAccX -= ix
            padAccY -= iy
            state.addMouse(ix, iy)
        }
    }

    private fun padUp() {
        if (SystemClock.uptimeMillis() - padDownT < 220 && padTravel < dens * 10f) click(1)
        padPid = -1
        pad2Pid = -1
        padAccX = 0f
        padAccY = 0f
        invalidate()
    }

    private fun pad2Up() {
        if (SystemClock.uptimeMillis() - pad2DownT < 250) click(2)
        pad2Pid = -1
    }

    private fun click(bit: Int) {
        state.mouseButtons = state.mouseButtons or bit
        state.changed()
        tick()
        ui.postDelayed({
            state.mouseButtons = state.mouseButtons and bit.inv()
            state.changed()
        }, 45)
    }

    // ---------- publish ----------

    private fun publish(mask: Int, lt: Int, rt: Int) {
        val newlyPressed = (mask and pressedMask.inv()) != 0 || (lt == 255 && !ltOn) || (rt == 255 && !rtOn)
        val changed = mask != pressedMask || (lt == 255) != ltOn || (rt == 255) != rtOn
        pressedMask = mask
        ltOn = lt == 255
        rtOn = rt == 255
        state.buttons = mask
        state.lt = lt
        state.rt = rt
        state.changed()
        if (newlyPressed) tick()
        if (changed) invalidate()
    }

    private fun tick() {
        if (!haptics) return
        val v = vibrator ?: return
        try {
            hapticExec.execute {
                try {
                    v.vibrate(
                        if (android.os.Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                        else VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE),
                    )
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {
        }
    }

    // ---------- chips ----------

    private fun chips(): List<Chip> = chipCache ?: buildChips().also { chipCache = it }

    private fun chipAt(x: Float, y: Float): Chip? = chips().firstOrNull { it.rect.contains(x, y) }

    private fun fmt(v: Float) = "%.1f".format(v)

    private fun buildChips(): List<Chip> {
        val w = width.toFloat()
        val h = height.toFloat()
        val out = ArrayList<Chip>()
        if (editMode) return buildEditChips(out, w, h)

        // HUD: ping readout + TUNE / EDIT / close
        val hh = 0.09f * h
        val gap = hh * 0.14f
        val ws = floatArrayOf(3.0f, 2.2f, 2.0f, 1.2f)
        val total = ws.sum() * hh + gap * 3
        var x = w / 2 - total / 2
        val y = 0.025f * h
        pingRect.set(x, y, x + ws[0] * hh, y + hh)
        x += ws[0] * hh + gap
        fun add(id: String, label: String, k: Float, on: Boolean = false) {
            out.add(Chip(id, label, RectF(x, y, x + k * hh, y + hh), on = on))
            x += k * hh + gap
        }
        add("tune", "TUNE", ws[1], tuneOpen)
        add("edit", "EDIT", ws[2])
        add("close", "X", ws[3])

        if (tuneOpen) {
            val rh = 0.085f * h
            val rg = 0.012f * h
            val pw = 0.46f * w
            val top = y + hh + 0.02f * h
            panel.set(w / 2 - pw / 2, top, w / 2 + pw / 2, top + 4 * rh + 5 * rg)
            val bw = rh * 1.1f
            fun row(i: Int, minus: String, plus: String) {
                val ry = top + rg + i * (rh + rg)
                out.add(Chip(minus, "-", RectF(panel.right - rg - bw * 2 - rg * 3 - bw * 1.6f, ry, panel.right - rg - bw - rg * 3 - bw * 1.6f, ry + rh)))
                out.add(Chip(plus, "+", RectF(panel.right - rg - bw, ry, panel.right - rg, ry + rh)))
            }
            row(0, "l-", "l+")
            row(1, "r-", "r+")
            row(2, "p-", "p+")
            val ty = top + rg + 3 * (rh + rg)
            val vis = preset.cc["TP"]?.visible == true
            out.add(Chip("padv", if (vis) "ON" else "OFF", RectF(panel.right - rg - bw * 2, ty, panel.right - rg, ty + rh), on = vis))
        }
        return out
    }

    private fun buildEditChips(out: ArrayList<Chip>, w: Float, h: Float): List<Chip> {
        val ch = 0.08f * h
        val gap = 0.012f * w
        var x = w * 0.5f - (0.09f + 0.09f + 0.15f) * w / 2 - gap
        val y1 = 0.02f * h
        fun add1(id: String, label: String, wFrac: Float, accent: Boolean = false) {
            out.add(Chip(id, label, RectF(x, y1, x + wFrac * w, y1 + ch), accent))
            x += wFrac * w + gap
        }
        add1("done", "DONE", 0.09f, true)
        add1("reset", "RESET", 0.09f)
        add1("skin", if (preset.playstation) "LOOK  PS5" else "LOOK  XBOX", 0.15f)
        pingRect.set(0.02f * w, y1, 0.02f * w + 3.0f * ch, y1 + ch)

        val sel = selected?.let { id -> items.firstOrNull { it.ctl.id == id } }
        if (sel != null) {
            val y2 = y1 + ch + 0.016f * h
            val kind = sel.ctl.kind
            // id, label, width as a fraction of the screen; an empty id is a gap
            val spec = ArrayList<Triple<String, String, Float>>()
            if (sel.ctl.natural >= 0) {
                spec.add(Triple("act-", "<", 0.04f))
                spec.add(Triple("actname", Act.name(sel.cfg.action, preset.playstation), 0.12f))
                spec.add(Triple("act+", ">", 0.04f))
                spec.add(Triple("", "", 0.012f))
            }
            spec.add(Triple("size-", "-", 0.04f))
            spec.add(Triple("size", "${(sel.cfg.scale * 100).toInt()}%", 0.07f))
            spec.add(Triple("size+", "+", 0.04f))
            spec.add(Triple("", "", 0.012f))
            spec.add(Triple("op-", "-", 0.04f))
            spec.add(Triple("op", "OP ${Math.round(sel.cfg.alpha * 100)}%", 0.095f))
            spec.add(Triple("op+", "+", 0.04f))
            spec.add(Triple("", "", 0.012f))
            if (kind == Ctl.STICK || kind == Ctl.PAD) {
                val g = when (sel.ctl.id) { "LS" -> preset.leftGain; "RS" -> preset.rightGain; else -> preset.padSpeed }
                spec.add(Triple("sens-", "-", 0.04f))
                spec.add(Triple("sens", "SENS ${fmt(g)}X", 0.11f))
                spec.add(Triple("sens+", "+", 0.04f))
                spec.add(Triple("", "", 0.012f))
            }
            spec.add(Triple("vis", if (sel.cfg.visible) "SHOWN" else "HIDDEN", 0.1f))
            var x2 = w * 0.5f - spec.sumOf { it.third.toDouble() }.toFloat() * w / 2
            for ((id, label, f) in spec) {
                if (id.isNotEmpty()) out.add(Chip(id, label, RectF(x2, y2, x2 + f * w, y2 + ch), on = id == "vis" && sel.cfg.visible))
                x2 += f * w
            }
        }
        return out
    }

    private fun runChip(id: String) {
        val sel = selected?.let { s -> items.firstOrNull { it.ctl.id == s } }
        when (id) {
            "done" -> stopEditing()
            "reset" -> {
                preset.resetLayout()
                selected = null
                rebuild()
            }
            "skin" -> {
                preset.applySkin(!preset.playstation)
                rebuild()
            }
            "act+", "act-" -> if (sel != null) {
                val i = Act.cycle.indexOf(sel.cfg.action).coerceAtLeast(0)
                val n = Act.cycle.size
                sel.cfg.action = Act.cycle[(i + if (id == "act+") 1 else n - 1) % n]
            }
            "size+", "size-" -> if (sel != null) {
                sel.cfg.scale = (sel.cfg.scale + if (id == "size+") 0.1f else -0.1f).coerceIn(0.5f, 2.0f)
                rebuild()
            }
            "padv" -> preset.cc["TP"]?.let { it.visible = !it.visible }
            "vis" -> if (sel != null) sel.cfg.visible = !sel.cfg.visible
            "op+", "op-" -> if (sel != null) sel.cfg.alpha = step(sel.cfg.alpha, id == "op+", 0.2f, 1f)
            "sens+", "sens-" -> if (sel != null) {
                val up = id == "sens+"
                when (sel.ctl.id) {
                    "LS" -> preset.leftGain = step(preset.leftGain, up, 0.5f, 3f)
                    "RS" -> preset.rightGain = step(preset.rightGain, up, 0.5f, 3f)
                    else -> preset.padSpeed = step(preset.padSpeed, up, 0.5f, 4f)
                }
            }
            "tune" -> {
                tuneOpen = !tuneOpen
                if (!tuneOpen) onSave?.invoke()
            }
            "edit" -> {
                startEditing()
                return
            }
            "close" -> {
                onClose?.invoke()
                return
            }
            "l-", "l+" -> preset.leftGain = step(preset.leftGain, id == "l+", 0.5f, 3.0f)
            "r-", "r+" -> preset.rightGain = step(preset.rightGain, id == "r+", 0.5f, 3.0f)
            "p-", "p+" -> preset.padSpeed = step(preset.padSpeed, id == "p+", 0.5f, 4.0f)
        }
        if (id[0] == 'l' || id[0] == 'r') pushSticks()
        chipCache = null
        invalidate()
    }

    private fun step(v: Float, up: Boolean, lo: Float, hi: Float) =
        (Math.round((v + if (up) 0.1f else -0.1f) * 10f) / 10f).coerceIn(lo, hi)

    // ---------- edit touch ----------

    private fun editTouch(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val c = chipAt(x, y)
                if (c != null) {
                    chipDownId = c.id
                    return true
                }
                chipDownId = null
                var best: Item? = null
                var bestD = 1.15f
                for (it in items) {
                    val d = it.distance(x, y)
                    if (d <= bestD) {
                        bestD = d
                        best = it
                    }
                }
                selected = best?.ctl?.id
                chipCache = null
                if (best != null) {
                    dragging = true
                    dragOffX = best.cx - x
                    dragOffY = best.cy - y
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val id = selected
                if (dragging && id != null) {
                    val cfg = preset.cc[id] ?: return true
                    cfg.x = ((x + dragOffX) / width).coerceIn(0.03f, 0.97f)
                    cfg.y = ((y + dragOffY) / height).coerceIn(0.03f, 0.97f)
                    rebuild()
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                dragging = false
                val d = chipDownId
                chipDownId = null
                if (d != null && chipAt(x, y)?.id == d) runChip(d)
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                chipDownId = null
            }
        }
        return true
    }

    // ---------- drawing ----------

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Theme.bg)
        val sw = dens * 1.2f
        val ps = preset.playstation

        for (it in items) drawItem(canvas, it, sw, ps)

        if (editMode) {
            drawSelection(canvas, sw)
            drawChips(canvas, sw)
            drawPing(canvas, sw)
            textPaint.textSize = dens * 10f
            textPaint.color = Theme.sub
            canvas.drawText("DRAG TO MOVE  //  TAP A CONTROL TO CHANGE IT", width / 2f, height - dens * 14f, textPaint)
        } else {
            if (tuneOpen) drawTune(canvas, sw)
            drawChips(canvas, sw)
            drawPing(canvas, sw)
        }
    }

    /** One control, faded to its own opacity (hidden ones only show, faintly, while editing). */
    private fun drawItem(canvas: Canvas, it: Item, sw: Float, ps: Boolean) {
        if (!it.shown && !editMode) return
        val al = if (!it.shown) 0.3f else it.cfg.alpha
        val save = if (al < 0.99f) {
            val hh = if (it.ctl.kind == Ctl.RECT || it.ctl.kind == Ctl.PAD) it.b else it.a
            val m = dens * 4f
            canvas.saveLayerAlpha(it.cx - it.a - m, it.cy - hh - m, it.cx + it.a + m, it.cy + hh + m, (al * 255).toInt())
        } else -1
        when (it.ctl.kind) {
            Ctl.PAD -> drawPad(canvas, it, sw)
            Ctl.STICK -> drawStick(canvas, it, sw)
            else -> drawButton(canvas, it, sw, ps)
        }
        if (save >= 0) canvas.restoreToCount(save)
    }

    private fun cut(r: RectF) = minOf(r.width(), r.height()) * 0.28f

    private fun drawPad(canvas: Canvas, it: Item, sw: Float) {
        rect.set(it.cx - it.a, it.cy - it.b, it.cx + it.a, it.cy + it.b)
        Theme.chamfer(rect, dens * 14f, path)
        val live = padPid != -1
        val on = it.shown
        paint.style = Paint.Style.FILL
        paint.color = if (live) Theme.alpha(Theme.cyan, 34) else Theme.surface
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = sw
        paint.color = if (on) Theme.cyan else Theme.line
        if (!on) paint.pathEffect = DashPathEffect(floatArrayOf(dens * 6f, dens * 5f), 0f)
        canvas.drawPath(path, paint)
        paint.pathEffect = null
        // faint grid so it reads as a surface
        paint.color = Theme.alpha(Theme.cyan, if (on) 40 else 20)
        paint.strokeWidth = sw * 0.7f
        for (k in 1..2) {
            val gx = rect.left + rect.width() * k / 3f
            canvas.drawLine(gx, rect.top + dens * 6f, gx, rect.bottom - dens * 6f, paint)
        }
        val gy = rect.centerY()
        canvas.drawLine(rect.left + dens * 6f, gy, rect.right - dens * 6f, gy, paint)
        textPaint.textSize = dens * 9f
        textPaint.color = if (on) Theme.cyan else Theme.sub
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(if (on) "TOUCHPAD" else "TOUCHPAD  (OFF)", rect.left + dens * 18f, rect.top + dens * 16f, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
    }

    private fun drawStick(canvas: Canvas, it: Item, sw: Float) {
        val st = sticks[it.ctl.id] ?: return
        val active = st.pid != -1
        paint.style = Paint.Style.FILL
        paint.color = Theme.surface
        canvas.drawCircle(it.cx, it.cy, it.a, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = sw
        paint.color = if (active) Theme.outline else Theme.line
        canvas.drawCircle(it.cx, it.cy, it.a, paint)
        canvas.drawCircle(it.cx, it.cy, it.a * 0.55f, paint)
        // reticle ticks
        paint.color = if (active) Theme.accent else Theme.outline
        paint.strokeWidth = sw * 1.6f
        val t0 = it.a * 0.78f
        canvas.drawLine(it.cx, it.cy - it.a, it.cx, it.cy - t0, paint)
        canvas.drawLine(it.cx, it.cy + it.a, it.cx, it.cy + t0, paint)
        canvas.drawLine(it.cx - it.a, it.cy, it.cx - t0, it.cy, paint)
        canvas.drawLine(it.cx + it.a, it.cy, it.cx + t0, it.cy, paint)

        val kx = it.cx + st.dx * it.a * 0.55f
        val ky = it.cy + st.dy * it.a * 0.55f
        val kr = it.a * 0.36f
        paint.style = Paint.Style.FILL
        paint.color = if (active) Theme.alpha(Theme.accent, 60) else Theme.surface2
        canvas.drawCircle(kx, ky, kr, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = sw * 1.4f
        paint.color = if (active) Theme.accent else Theme.outline
        canvas.drawCircle(kx, ky, kr, paint)
        paint.strokeWidth = sw
        canvas.drawLine(kx - kr * 0.45f, ky, kx + kr * 0.45f, ky, paint)
        canvas.drawLine(kx, ky - kr * 0.45f, kx, ky + kr * 0.45f, paint)
    }

    private fun drawButton(canvas: Canvas, it: Item, sw: Float, ps: Boolean) {
        val act = it.cfg.action
        val pressed = (act in 0..13 && (pressedMask and (1 shl act)) != 0) ||
            (act == Act.LT && ltOn) || (act == Act.RT && rtOn)
        val tint = Act.color(act, ps)
        val shown = if (tint != 0) tint else Theme.sub
        val fill = if (pressed) (if (tint != 0) tint else Theme.accent) else Theme.surface
        val line = if (pressed) fill else if (tint != 0) Theme.alpha(tint, 170) else Theme.line
        val round = it.ctl.kind == Ctl.CIRCLE

        paint.style = Paint.Style.FILL
        paint.color = fill
        if (round) {
            canvas.drawCircle(it.cx, it.cy, it.a, paint)
        } else {
            rect.set(it.cx - it.a, it.cy - it.b, it.cx + it.a, it.cy + it.b)
            Theme.chamfer(rect, cut(rect), path)
            canvas.drawPath(path, paint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = sw
        paint.color = line
        if (round) canvas.drawCircle(it.cx, it.cy, it.a, paint) else canvas.drawPath(path, paint)

        val ink = if (pressed) Theme.bg else shown
        val label = Act.label(act, ps)
        if (label.isEmpty()) {
            drawPsGlyph(canvas, act, it.cx, it.cy, it.a * 0.42f, ink, sw * 1.6f)
        } else {
            textPaint.textSize = (if (round) it.a else it.b) * (if (label.length > 2) 0.66f else 0.9f)
            textPaint.color = ink
            canvas.drawText(label, it.cx, it.cy + textPaint.textSize * 0.35f, textPaint)
        }
    }

    /** PlayStation face symbols, drawn as shapes so they never depend on fonts. */
    private fun drawPsGlyph(canvas: Canvas, act: Int, cx: Float, cy: Float, r: Float, color: Int, width: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = color
        when (act) {
            Act.A -> {
                canvas.drawLine(cx - r, cy - r, cx + r, cy + r, paint)
                canvas.drawLine(cx - r, cy + r, cx + r, cy - r, paint)
            }
            Act.B -> canvas.drawCircle(cx, cy, r, paint)
            Act.X -> canvas.drawRect(cx - r * 0.9f, cy - r * 0.9f, cx + r * 0.9f, cy + r * 0.9f, paint)
            else -> {
                path.reset()
                path.moveTo(cx, cy - r)
                path.lineTo(cx + r * 1.05f, cy + r * 0.8f)
                path.lineTo(cx - r * 1.05f, cy + r * 0.8f)
                path.close()
                canvas.drawPath(path, paint)
            }
        }
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeJoin = Paint.Join.MITER
    }

    private fun drawSelection(canvas: Canvas, sw: Float) {
        val sel = selected?.let { id -> items.firstOrNull { it.ctl.id == id } } ?: return
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = sw * 1.6f
        paint.color = Theme.accent
        paint.pathEffect = DashPathEffect(floatArrayOf(dens * 6f, dens * 5f), 0f)
        val g = dens * 6f
        if (sel.ctl.kind == Ctl.RECT || sel.ctl.kind == Ctl.PAD) {
            rect.set(sel.cx - sel.a - g, sel.cy - sel.b - g, sel.cx + sel.a + g, sel.cy + sel.b + g)
            canvas.drawRect(rect, paint)
        } else {
            canvas.drawCircle(sel.cx, sel.cy, sel.a + g, paint)
        }
        paint.pathEffect = null
    }

    private fun drawChips(canvas: Canvas, sw: Float) {
        for (c in chips()) {
            val lit = c.accent
            Theme.chamfer(c.rect, cut(c.rect), path)
            paint.style = Paint.Style.FILL
            paint.color = if (lit) Theme.accent else Theme.surface
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = sw
            paint.color = if (lit) Theme.accent else if (c.on) Theme.cyan else Theme.line
            canvas.drawPath(path, paint)
            textPaint.textSize = c.rect.height() * 0.40f
            textPaint.color = if (lit) Theme.bg else if (c.on) Theme.cyan else Theme.ink
            canvas.drawText(c.label, c.rect.centerX(), c.rect.centerY() + textPaint.textSize * 0.35f, textPaint)
        }
    }

    private fun drawPing(canvas: Canvas, sw: Float) {
        val ms = pingMs
        val col = when {
            ms < 0 -> if (ms == -1) Theme.red else Theme.sub
            ms < 30 -> Theme.cyan
            ms < 70 -> Theme.accent
            else -> Theme.red
        }
        Theme.chamfer(pingRect, cut(pingRect), path)
        paint.style = Paint.Style.FILL
        paint.color = Theme.surface
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = sw
        paint.color = Theme.alpha(col, 140)
        canvas.drawPath(path, paint)
        val h = pingRect.height()
        paint.style = Paint.Style.FILL
        paint.color = col
        canvas.drawCircle(pingRect.left + h * 0.5f, pingRect.centerY(), h * 0.11f, paint)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = h * 0.42f
        textPaint.color = col
        val label = when {
            ms >= 0 -> "$ms MS"
            ms == -1 -> "NO LINK"
            else -> "LINKING"
        }
        canvas.drawText(label, pingRect.left + h * 0.8f, pingRect.centerY() + textPaint.textSize * 0.35f, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
    }

    private fun drawTune(canvas: Canvas, sw: Float) {
        Theme.chamfer(panel, dens * 14f, path)
        paint.style = Paint.Style.FILL
        paint.color = Theme.alpha(Theme.surface, 245)
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = sw
        paint.color = Theme.outline
        canvas.drawPath(path, paint)
        val rh = 0.085f * height
        val rg = 0.012f * height
        val names = arrayOf("LEFT STICK", "RIGHT STICK", "PAD SPEED", "TOUCHPAD")
        val vals = arrayOf(
            fmt(preset.leftGain) + "X", fmt(preset.rightGain) + "X", fmt(preset.padSpeed) + "X", "",
        )
        val bw = rh * 1.1f
        textPaint.textAlign = Paint.Align.LEFT
        for (i in 0..3) {
            val ry = panel.top + rg + i * (rh + rg)
            textPaint.textSize = rh * 0.42f
            textPaint.color = Theme.sub
            canvas.drawText(names[i], panel.left + rg * 3, ry + rh / 2 + textPaint.textSize * 0.35f, textPaint)
            if (vals[i].isNotEmpty()) {
                textPaint.color = Theme.ink
                textPaint.textAlign = Paint.Align.CENTER
                val cx = panel.right - rg - bw - rg * 3 - bw * 0.8f
                canvas.drawText(vals[i], cx, ry + rh / 2 + textPaint.textSize * 0.35f, textPaint)
                textPaint.textAlign = Paint.Align.LEFT
            }
        }
        textPaint.textAlign = Paint.Align.CENTER
    }
}
