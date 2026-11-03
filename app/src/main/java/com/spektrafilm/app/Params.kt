package com.spektrafilm.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Bottom-bar tabs. */
enum class Tab(val title: String) {
    FILM("胶片"), EXPOSURE("曝光"), COLOR("色彩"), GRAIN("颗粒"), GLOW("光晕"), SHARP("锐度"), FRAME("边框"),
}

/**
 * One user-adjustable parameter. [key] is either a dotted path into
 * spektrafilm's RuntimeParams JSON (optionally with `#i` for an array
 * element) or a `ui.` key that never reaches the engine.
 */
sealed class ParamDef(val key: String, val label: String, val tab: Tab, val hint: String) {
    abstract val default: Any
    val engineParam: Boolean get() = !key.startsWith("ui.")
}

class SliderDef(
    key: String, label: String, tab: Tab,
    val min: Double, val max: Double, val step: Double, override val default: Double,
    val decimals: Int = 2, val unit: String = "", hint: String = "",
) : ParamDef(key, label, tab, hint)

class ToggleDef(key: String, label: String, tab: Tab, override val default: Boolean, hint: String = "") :
    ParamDef(key, label, tab, hint)

class ChoiceDef(
    key: String, label: String, tab: Tab,
    val options: List<Pair<String, Any>>, override val default: Any, hint: String = "",
) : ParamDef(key, label, tab, hint)

/**
 * The parameter catalogue. Defaults are spektrafilm-rs' RuntimeParams
 * defaults (which mirror the Python reference); only values that differ
 * from these are sent to the engine.
 */
object Params {
    val all: List<ParamDef> = listOf(
        // ------------------------------------------------------------ exposure
        ToggleDef("camera.auto_exposure", "自动曝光", Tab.EXPOSURE, true, "虚拟相机按测光结果给底片曝光"),
        ChoiceDef(
            "camera.auto_exposure_method", "测光", Tab.EXPOSURE,
            listOf("中央重点" to "center_weighted", "平均" to "average", "中位数" to "median", "矩阵" to "matrix"),
            "center_weighted",
        ),
        SliderDef("camera.exposure_compensation_ev", "曝光补偿", Tab.EXPOSURE, -3.0, 3.0, 0.1, 0.0, 1, " EV",
            "底片过曝/欠曝，影响颗粒与色彩"),
        SliderDef("enlarger.print_exposure", "印放曝光", Tab.EXPOSURE, 0.25, 2.5, 0.01, 1.0, 2, "",
            "放大机曝光：越大照片越暗"),
        SliderDef("enlarger.preflash_exposure", "预闪光", Tab.EXPOSURE, 0.0, 0.1, 0.002, 0.0, 3, "",
            "相纸预曝光，压低高光反差、保留亮部细节"),

        // ------------------------------------------------------------ colour
        SliderDef("enlarger.y_filter_shift", "黄滤镜 Y", Tab.COLOR, -40.0, 40.0, 1.0, 0.0, 0, "",
            "减小偏黄，增大偏蓝（放大机 CC 值）"),
        SliderDef("enlarger.m_filter_shift", "品滤镜 M", Tab.COLOR, -40.0, 40.0, 1.0, 0.0, 0, "",
            "减小偏品红，增大偏绿（放大机 CC 值）"),
        SliderDef("film_render.density_curve_gamma", "底片反差", Tab.COLOR, 0.5, 1.5, 0.02, 1.0, 2),
        SliderDef("print_render.density_curves_morph.gamma_factor", "相纸反差", Tab.COLOR, 0.5, 2.0, 0.05, 1.0, 2),
        SliderDef("print_render.density_curves_morph.developer_exhaustion", "显影液疲劳", Tab.COLOR, 0.0, 1.0, 0.02, 0.0, 2,
            "", "相纸肩部变软，暗部发灰"),
        SliderDef("film_render.dir_couplers.amount", "DIR 耦合剂", Tab.COLOR, 0.0, 2.0, 0.05, 1.0, 2, "",
            "饱和度与局部对比的来源"),
        SliderDef("film_render.dir_couplers.inhibition_interlayer", "层间抑制", Tab.COLOR, 0.0, 2.0, 0.05, 1.0, 2, "",
            "层与层之间的抑制，主要影响饱和度"),
        SliderDef("film_render.dir_couplers.diffusion_size_um", "耦合剂扩散", Tab.COLOR, 0.0, 60.0, 1.0, 20.0, 0, " µm",
            "扩散范围，影响边缘锐感"),

        // ------------------------------------------------------------ grain
        ToggleDef("film_render.grain.active", "颗粒", Tab.GRAIN, true),
        SliderDef("film_render.grain.agx_particle_area_um2", "颗粒尺寸", Tab.GRAIN, 0.05, 1.0, 0.01, 0.2, 2, " µm²"),
        SliderDef("film_render.grain.blur", "颗粒柔化", Tab.GRAIN, 0.0, 2.0, 0.05, 0.65, 2),
        ChoiceDef(
            "camera.film_format_mm", "画幅", Tab.GRAIN,
            listOf("35mm" to 35.0, "6×4.5" to 56.0, "6×7" to 70.0, "6×9" to 84.0, "4×5" to 127.0), 35.0,
            "画幅越大，同一张照片上的颗粒越细",
        ),

        // ------------------------------------------------------------ glow
        ToggleDef("film_render.halation.active", "光晕/散射", Tab.GLOW, true),
        SliderDef("film_render.halation.halation_amount", "光晕强度", Tab.GLOW, 0.0, 3.0, 0.05, 1.0, 2, "",
            "片基反射造成的高光红晕"),
        SliderDef("film_render.halation.halation_spatial_scale", "光晕范围", Tab.GLOW, 0.25, 4.0, 0.05, 1.0, 2),
        SliderDef("film_render.halation.scatter_amount", "乳剂散射", Tab.GLOW, 0.0, 2.0, 0.05, 1.0, 2),
        SliderDef("film_render.halation.boost_ev", "高光重建", Tab.GLOW, 0.0, 4.0, 0.1, 0.0, 1, " EV",
            "把手机已剪切的高光提亮，光晕更明显"),
        ToggleDef("camera.diffusion_filter.active", "柔光镜", Tab.GLOW, false),
        ChoiceDef(
            "camera.diffusion_filter.filter_family", "柔光镜类型", Tab.GLOW,
            listOf("黑柔" to "black_pro_mist", "白柔" to "pro_mist", "微光" to "glimmerglass", "泛光" to "cinebloom"),
            "black_pro_mist",
        ),
        SliderDef("camera.diffusion_filter.strength", "柔光强度", Tab.GLOW, 0.0, 2.0, 0.125, 0.5, 3, "",
            "滤镜档位：1/8=0.125，1/4=0.25，1/2=0.5"),
        SliderDef("camera.diffusion_filter.halo_warmth", "柔光冷暖", Tab.GLOW, -1.5, 1.5, 0.05, 0.0, 2),
        ToggleDef("print_render.glare.active", "相纸眩光", Tab.GLOW, true),
        SliderDef("print_render.glare.percent", "眩光量", Tab.GLOW, 0.0, 0.25, 0.005, 0.03, 3),

        // ------------------------------------------------------------ sharpness
        SliderDef("camera.lens_blur_um", "镜头柔化", Tab.SHARP, 0.0, 10.0, 0.1, 0.0, 1, " µm", "约 5µm 为普通镜头"),
        SliderDef("scanner.unsharp_mask#1", "扫描锐化", Tab.SHARP, 0.0, 2.0, 0.05, 0.7, 2),
        SliderDef("scanner.unsharp_mask#0", "锐化半径", Tab.SHARP, 0.0, 3.0, 0.05, 0.7, 2, " px"),
        SliderDef("scanner.lens_blur", "扫描柔化", Tab.SHARP, 0.0, 3.0, 0.05, 0.0, 2, " px"),

        // ------------------------------------------------------------ frame (UI only)
        ChoiceDef(
            "ui.frame_style", "样式", Tab.FRAME,
            listOf("无" to "none", "相纸白边" to "white", "35mm 片边" to "film35", "120 片边" to "film120", "拍立得" to "instant"),
            "none",
        ),
        SliderDef("ui.frame_size", "边框宽度", Tab.FRAME, 0.0, 1.0, 0.05, 0.5, 2),
        ToggleDef("ui.frame_text", "片边文字与片号", Tab.FRAME, true),
    )

    val byKey: Map<String, ParamDef> = all.associateBy { it.key }
    fun forTab(tab: Tab) = all.filter { it.tab == tab }
}

/** Mutable editing state: film/paper selection plus parameter values. */
class EditState(
    var film: String = "kodak_portra_400",
    /** "" = automatic paper for the film; [SCAN_FILM] = scan the film directly. */
    var paper: String = "",
    val values: MutableMap<String, Any> = mutableMapOf(),
) {
    fun get(def: ParamDef): Any = values[def.key] ?: def.default
    fun double(key: String): Double = (get(Params.byKey.getValue(key)) as Number).toDouble()
    fun bool(key: String): Boolean = get(Params.byKey.getValue(key)) as Boolean
    fun string(key: String): String = get(Params.byKey.getValue(key)).toString()

    fun set(def: ParamDef, v: Any) {
        if (sameValue(v, def.default)) values.remove(def.key) else values[def.key] = v
    }

    fun isDefault(def: ParamDef) = !values.containsKey(def.key)
    fun reset(tab: Tab) = Params.forTab(tab).forEach { values.remove(it.key) }
    val scanFilm: Boolean get() = paper == SCAN_FILM

    fun copy() = EditState(film, paper, values.toMutableMap())

    /** Sparse RuntimeParams JSON: only non-default engine parameters. */
    fun toParamsJson(scanFilm: Boolean = this.scanFilm): String {
        val root = JSONObject()
        val arrays = mutableMapOf<String, MutableList<Double>>()
        for ((key, v) in values) {
            val def = Params.byKey[key] ?: continue
            if (!def.engineParam) continue
            val hash = key.indexOf('#')
            if (hash >= 0) {
                val base = key.substring(0, hash)
                val list = arrays.getOrPut(base) { ARRAY_DEFAULTS.getValue(base).toMutableList() }
                list[key.substring(hash + 1).toInt()] = (v as Number).toDouble()
            } else {
                putPath(root, key, v)
            }
        }
        for ((base, list) in arrays) putPath(root, base, JSONArray(list))
        // Print-curve morphing only applies when its group is switched on.
        if (values.keys.any { it.startsWith("print_render.density_curves_morph.") }) {
            putPath(root, "print_render.density_curves_morph.active", true)
        }
        if (scanFilm) putPath(root, "io.scan_film", true)
        return root.toString()
    }

    fun toJson(): JSONObject = JSONObject()
        .put("film", film)
        .put("paper", paper)
        .put("values", JSONObject(values as Map<*, *>))

    companion object {
        const val SCAN_FILM = "__scan_film__"
        private val ARRAY_DEFAULTS = mapOf("scanner.unsharp_mask" to listOf(0.7, 0.7))

        private fun sameValue(a: Any, b: Any): Boolean =
            if (a is Number && b is Number) kotlin.math.abs(a.toDouble() - b.toDouble()) < 1e-9 else a == b

        private fun putPath(root: JSONObject, path: String, v: Any) {
            val parts = path.split('.')
            var node = root
            for (p in parts.dropLast(1)) {
                node = node.optJSONObject(p) ?: JSONObject().also { node.put(p, it) }
            }
            node.put(parts.last(), v)
        }

        fun fromJson(o: JSONObject): EditState {
            val s = EditState(o.optString("film", "kodak_portra_400"), o.optString("paper", ""))
            val vals = o.optJSONObject("values") ?: return s
            for (k in vals.keys()) {
                val def = Params.byKey[k] ?: continue
                val raw = vals.get(k)
                val v: Any = when (def) {
                    is SliderDef -> (raw as? Number)?.toDouble() ?: continue
                    is ToggleDef -> raw as? Boolean ?: continue
                    is ChoiceDef -> def.options.firstOrNull { it.second.toString() == raw.toString() }?.second ?: continue
                }
                s.set(def, v)
            }
            return s
        }

        private const val PREFS = "spektrafilm"

        fun load(ctx: Context): EditState = try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("state", null)
                ?.let { fromJson(JSONObject(it)) } ?: EditState()
        } catch (_: Exception) {
            EditState()
        }

        fun save(ctx: Context, s: EditState) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("state", s.toJson().toString()).apply()
        }
    }
}

data class ProfileInfo(
    val id: String,
    val name: String,
    val stage: String,
    val filmType: String,
    val channelModel: String,
    val targetPrint: String?,
) {
    val isBw get() = channelModel == "bw"
    val isPositive get() = filmType == "positive"
    val brand: String get() = name.substringBefore(' ')
    /** Name without brand/marketing words, for compact cards. */
    val shortName: String
        get() = name.removePrefix(brand).trim()
            .removePrefix("Professional ").removePrefix("Vision ")
            .replace(" Negative Film", "").replace(" Reversal Film", "").ifEmpty { name }
    val kindLabel: String
        get() = when {
            isBw && isPositive -> "黑白反转"
            isBw -> "黑白负片"
            isPositive -> "反转片"
            else -> "彩色负片"
        }
}

fun parseProfiles(json: String): List<ProfileInfo> {
    val arr = JSONArray(json)
    return (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        ProfileInfo(
            id = o.getString("id"),
            name = o.optString("name", o.getString("id")),
            stage = o.optString("stage"),
            filmType = o.optString("film_type"),
            channelModel = o.optString("channel_model", "color"),
            targetPrint = if (o.isNull("target_print")) null else o.optString("target_print"),
        )
    }
}
