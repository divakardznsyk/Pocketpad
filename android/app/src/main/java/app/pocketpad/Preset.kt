package app.pocketpad

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Things an on-screen control can send. 0..13 are button bits (see Bit), 14/15 are the analog triggers. */
object Act {
    const val A = 0
    const val B = 1
    const val X = 2
    const val Y = 3
    const val LB = 4
    const val RB = 5
    const val BACK = 6
    const val START = 7
    const val L3 = 8
    const val R3 = 9
    const val UP = 10
    const val DOWN = 11
    const val LEFT = 12
    const val RIGHT = 13
    const val LT = 14
    const val RT = 15

    /** Order used when cycling through actions in the editor. */
    val cycle = intArrayOf(A, B, X, Y, LB, RB, LT, RT, BACK, START, L3, R3, UP, DOWN, LEFT, RIGHT)

    private val xbox = arrayOf("A", "B", "X", "Y", "LB", "RB", "BACK", "START", "L3", "R3", "↑", "↓", "←", "→", "LT", "RT")
    private val ps = arrayOf("", "", "", "", "L1", "R1", "SHARE", "OPT", "L3", "R3", "↑", "↓", "←", "→", "L2", "R2")
    private val xboxName = arrayOf("A", "B", "X", "Y", "LB", "RB", "BACK", "START", "L3", "R3", "UP", "DOWN", "LEFT", "RIGHT", "LT", "RT")
    private val psName = arrayOf("CROSS", "CIRCLE", "SQUARE", "TRIANGLE", "L1", "R1", "SHARE", "OPTIONS", "L3", "R3", "UP", "DOWN", "LEFT", "RIGHT", "L2", "R2")

    /** Short text drawn on the button. Empty for PlayStation face buttons, which are drawn as shapes. */
    fun label(a: Int, playstation: Boolean) = if (playstation) ps[a] else xbox[a]

    fun name(a: Int, playstation: Boolean) = if (playstation) psName[a] else xboxName[a]

    /** Face-button colour, 0 for neutral buttons. */
    fun color(a: Int, playstation: Boolean): Int = if (playstation) {
        when (a) {
            A -> 0xFF6EA8FF.toInt()
            B -> 0xFFFF6B6B.toInt()
            X -> 0xFFE58BD0.toInt()
            Y -> 0xFF5FD3A5.toInt()
            else -> 0
        }
    } else {
        when (a) {
            A -> 0xFF4CD08A.toInt()
            B -> 0xFFFF5F66.toInt()
            X -> 0xFF5B9BFF.toInt()
            Y -> 0xFFF5C542.toInt()
            else -> 0
        }
    }
}

/** Static description of one on-screen control. Sizes are in units of the screen height. */
class Ctl(val id: String, val kind: Int, val a: Float, val b: Float, val natural: Int) {
    companion object {
        const val CIRCLE = 0
        const val RECT = 1
        const val STICK = 2
    }
}

/** Where a control sits (centre as a fraction of screen width/height), how big, and what it sends. */
class CC(var x: Float, var y: Float, var scale: Float, var action: Int)

object Layout {
    val all = listOf(
        Ctl("LT", Ctl.RECT, 0.133f, 0.06f, Act.LT),
        Ctl("LB", Ctl.RECT, 0.133f, 0.06f, Act.LB),
        Ctl("RT", Ctl.RECT, 0.133f, 0.06f, Act.RT),
        Ctl("RB", Ctl.RECT, 0.133f, 0.06f, Act.RB),
        Ctl("BACK", Ctl.RECT, 0.089f, 0.055f, Act.BACK),
        Ctl("START", Ctl.RECT, 0.089f, 0.055f, Act.START),
        Ctl("L3", Ctl.CIRCLE, 0.05f, 0.05f, Act.L3),
        Ctl("R3", Ctl.CIRCLE, 0.05f, 0.05f, Act.R3),
        Ctl("UP", Ctl.CIRCLE, 0.075f, 0.075f, Act.UP),
        Ctl("DOWN", Ctl.CIRCLE, 0.075f, 0.075f, Act.DOWN),
        Ctl("LEFT", Ctl.CIRCLE, 0.075f, 0.075f, Act.LEFT),
        Ctl("RIGHT", Ctl.CIRCLE, 0.075f, 0.075f, Act.RIGHT),
        Ctl("Y", Ctl.CIRCLE, 0.085f, 0.085f, Act.Y),
        Ctl("A", Ctl.CIRCLE, 0.085f, 0.085f, Act.A),
        Ctl("X", Ctl.CIRCLE, 0.085f, 0.085f, Act.X),
        Ctl("B", Ctl.CIRCLE, 0.085f, 0.085f, Act.B),
        Ctl("LS", Ctl.STICK, 0.21f, 0.21f, -1),
        Ctl("RS", Ctl.STICK, 0.20f, 0.20f, -1),
    )

    private const val ASPECT = 2.22f // reference screen is 20:9

    /** Starting positions for the Xbox-style or PlayStation-style arrangement. */
    fun fresh(playstation: Boolean): MutableMap<String, CC> {
        val m = HashMap<String, CC>()
        fun put(id: String, x: Float, y: Float) {
            m[id] = CC(x, y, 1f, all.first { it.id == id }.natural)
        }
        put("LT", 0.08f, 0.08f); put("LB", 0.08f, 0.22f)
        put("RT", 0.92f, 0.08f); put("RB", 0.92f, 0.22f)
        put("BACK", 0.455f, 0.30f); put("START", 0.545f, 0.30f)
        // face buttons
        val fx = 0.88f
        val fy = if (playstation) 0.50f else 0.52f
        val fo = 0.145f / ASPECT
        put("Y", fx, fy - 0.145f); put("A", fx, fy + 0.145f)
        put("X", fx - fo, fy); put("B", fx + fo, fy)
        // d-pad, sticks, stick clicks
        val dox = 0.14f / ASPECT
        if (playstation) {
            put("UP", 0.14f, 0.38f); put("DOWN", 0.14f, 0.66f)
            put("LEFT", 0.14f - dox, 0.52f); put("RIGHT", 0.14f + dox, 0.52f)
            put("LS", 0.36f, 0.72f); put("RS", 0.66f, 0.72f)
            put("L3", 0.36f, 0.43f); put("R3", 0.66f, 0.43f)
        } else {
            put("UP", 0.35f, 0.60f); put("DOWN", 0.35f, 0.88f)
            put("LEFT", 0.35f - dox, 0.74f); put("RIGHT", 0.35f + dox, 0.74f)
            put("LS", 0.14f, 0.62f); put("RS", 0.64f, 0.76f)
            put("L3", 0.26f, 0.40f); put("R3", 0.60f, 0.46f)
        }
        return m
    }
}

/** One game profile: look, layout, remaps and stick speed. */
class Preset(
    var name: String,
    var playstation: Boolean,
    var leftGain: Float = 1.4f,
    var rightGain: Float = 1.4f,
    val cc: MutableMap<String, CC> = Layout.fresh(playstation),
) {
    fun resetLayout() {
        cc.clear()
        cc.putAll(Layout.fresh(playstation))
    }

    /** Switch look and move controls to that style's arrangement; remaps and sizes stay. */
    fun applySkin(ps: Boolean) {
        playstation = ps
        val f = Layout.fresh(ps)
        for ((id, c) in f) {
            val mine = cc[id]
            if (mine == null) cc[id] = c else {
                mine.x = c.x
                mine.y = c.y
            }
        }
    }

    fun copyAs(newName: String): Preset {
        val c = HashMap<String, CC>()
        for ((k, v) in cc) c[k] = CC(v.x, v.y, v.scale, v.action)
        return Preset(newName, playstation, leftGain, rightGain, c)
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("name", name)
        o.put("ps", playstation)
        o.put("lg", leftGain.toDouble())
        o.put("rg", rightGain.toDouble())
        val c = JSONObject()
        for ((k, v) in cc) {
            c.put(k, JSONArray().put(v.x.toDouble()).put(v.y.toDouble()).put(v.scale.toDouble()).put(v.action))
        }
        o.put("c", c)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): Preset {
            val ps = o.optBoolean("ps", false)
            val p = Preset(
                o.optString("name", "PRESET"), ps,
                o.optDouble("lg", 1.4).toFloat(), o.optDouble("rg", 1.4).toFloat(),
            )
            val c = o.optJSONObject("c")
            if (c != null) {
                for (ctl in Layout.all) {
                    val a = c.optJSONArray(ctl.id) ?: continue
                    val cfg = p.cc[ctl.id] ?: continue
                    cfg.x = a.optDouble(0, cfg.x.toDouble()).toFloat().coerceIn(0.02f, 0.98f)
                    cfg.y = a.optDouble(1, cfg.y.toDouble()).toFloat().coerceIn(0.02f, 0.98f)
                    cfg.scale = a.optDouble(2, 1.0).toFloat().coerceIn(0.5f, 2.0f)
                    val act = a.optInt(3, ctl.natural)
                    if (ctl.natural >= 0 && act in 0..15) cfg.action = act
                }
            }
            return p
        }
    }
}

/** Saves all presets (and which one is selected) in the app's private preferences. */
object PresetStore {
    private const val FILE = "pocketpad"

    fun load(ctx: Context): MutableList<Preset> {
        val list = ArrayList<Preset>()
        try {
            val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("presets", null)
            if (raw != null) {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) list.add(Preset.fromJson(arr.getJSONObject(i)))
            }
        } catch (_: Exception) {
        }
        if (list.isEmpty()) list.add(Preset("DEFAULT", false))
        return list
    }

    fun selected(ctx: Context, size: Int): Int =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt("sel", 0).coerceIn(0, size - 1)

    fun save(ctx: Context, list: List<Preset>, sel: Int) {
        val arr = JSONArray()
        for (p in list) arr.put(p.toJson())
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("presets", arr.toString())
            .putInt("sel", sel)
            .apply()
    }
}
