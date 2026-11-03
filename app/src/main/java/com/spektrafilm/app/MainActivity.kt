package com.spektrafilm.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {

    private val tag = "spektrafilm"
    private val main = Handler(Looper.getMainLooper())
    private lateinit var worker: RenderWorker

    // ---- engine / content state (main thread unless noted)
    @Volatile private var engineReady = false
    @Volatile private var imageToken = 0
    @Volatile private var previewGen = 0
    private var backendName = "?"
    private var state = EditState()
    private var profiles: List<ProfileInfo> = emptyList()
    private var films: List<ProfileInfo> = emptyList()
    private var papers: List<ProfileInfo> = emptyList()
    private var sourceUri: Uri? = null
    private var sourceIsRaw = false
    private var sourceEdge = 0
    private var hasImage = false
    private var lastRenderKey = ""
    private var lastThumbKey = ""
    private var rawPreview: Bitmap? = null
    private var rawOriginal: Bitmap? = null
    private var shownPreview: Bitmap? = null
    private var shownOriginal: Bitmap? = null
    private var comparing = false
    private var exporting = false
    private val thumbs = HashMap<String, Bitmap>()
    private var frameNumber = 1

    // ---- prefs
    private val prefs by lazy { getSharedPreferences("spektrafilm_ui", MODE_PRIVATE) }
    private var useGpu: Boolean
        get() = prefs.getBoolean("gpu", true)
        set(v) = prefs.edit().putBoolean("gpu", v).apply()
    private var exportEdge: Int
        get() = prefs.getInt("export_edge", 4096)
        set(v) = prefs.edit().putInt("export_edge", v).apply()
    private val previewEdge: Int by lazy {
        val m = resources.displayMetrics
        min(2048, max(m.widthPixels, m.heightPixels))
    }

    // ---- views
    private lateinit var imageView: ZoomImageView
    private lateinit var emptyView: View
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var statusView: TextView
    private lateinit var statusSpinner: ProgressBar
    private lateinit var exportButton: TextView
    private lateinit var compareButton: TextView
    private lateinit var contentArea: FrameLayout
    private lateinit var tabBar: LinearLayout
    private var currentTab = Tab.FILM
    private val selectedTool = HashMap<Tab, String>()
    private val filmCards = HashMap<String, View>()

    // ================================================================ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        worker = RenderWorker()
        state = EditState.load(this)
        setContentView(buildUi())
        setStatus("正在初始化光谱引擎…", busy = true)
        worker.urgent {
            try {
                val dataDir = DataInstaller.ensureInstalled(this)
                val backend = NativeBridge.init(dataDir.absolutePath, true)
                val list = parseProfiles(NativeBridge.listProfiles())
                main.post { onEngineReady(backend, list) }
            } catch (t: Throwable) {
                Log.e(tag, "init failed", t)
                main.post { setStatus("初始化失败：${t.message}", busy = false) }
            }
        }
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onPause() {
        EditState.save(this, state)
        super.onPause()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun onEngineReady(backend: String, list: List<ProfileInfo>) {
        backendName = backend
        profiles = list
        val order = compareBy<ProfileInfo>({ if (it.isBw) 2 else if (it.isPositive) 1 else 0 }, { it.name })
        films = list.filter { it.stage == "filming" }.sortedWith(order)
        papers = list.filter { it.stage == "printing" }.sortedBy { it.name }
        if (films.none { it.id == state.film }) state.film = films.firstOrNull()?.id ?: state.film
        engineReady = true
        updateTitle()
        showTab(currentTab)
        setStatus(if (hasImage) "" else "引擎就绪 · $backendName", busy = false)
        if (sourceUri != null && !hasImage) loadImage(sourceUri!!)
    }

    // ================================================================ film / paper

    private fun profile(id: String) = profiles.firstOrNull { it.id == id }

    /** The paper the engine should use and whether to scan the film directly. */
    private fun resolvePaper(s: EditState): Pair<String, Boolean> {
        val film = profile(s.film)
        if (s.paper == EditState.SCAN_FILM) return "" to true
        if (s.paper.isNotEmpty()) return s.paper to false
        return when {
            film == null -> "" to false
            film.targetPrint != null -> film.targetPrint to false
            film.isBw && !film.isPositive -> "kodak_2302" to false
            else -> "" to true
        }
    }

    private fun papersFor(film: ProfileInfo?): List<ProfileInfo> = when {
        film == null || film.isPositive -> emptyList()
        else -> papers.filter { it.isBw == film.isBw }
    }

    private fun renderKey(s: EditState): String {
        val (paper, scan) = resolvePaper(s)
        return "${s.film}|$paper|${s.toParamsJson(scan)}"
    }

    // ================================================================ image loading

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        uri?.let { if (engineReady) loadImage(it) else sourceUri = it }
    }

    private fun pickPhoto() {
        val intent = if (Build.VERSION.SDK_INT >= 33) {
            Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        }
        @Suppress("DEPRECATION") startActivityForResult(intent, REQ_PHOTO)
    }

    private fun pickRaw() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/x-adobe-dng", "image/dng", "image/*", "application/octet-stream"))
        @Suppress("DEPRECATION") startActivityForResult(intent, REQ_RAW)
    }

    @Deprecated("platform Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && (requestCode == REQ_PHOTO || requestCode == REQ_RAW)) {
            data?.data?.let { loadImage(it) }
        }
    }

    private fun loadImage(uri: Uri, edge: Int = exportEdge, then: (() -> Unit)? = null) {
        sourceUri = uri
        if (!engineReady) return
        val token = ++imageToken
        val isRaw = ImageIo.isRaw(this, uri)
        worker.clearBackground()
        setStatus(if (isRaw) "正在解码 RAW…" else "正在读取照片…", busy = true)
        worker.urgent {
            try {
                if (isRaw) {
                    NativeBridge.setSourceRaw(ImageIo.openFd(this, uri), edge)
                } else {
                    val bmp = ImageIo.decode(this, uri, edge)
                    NativeBridge.setSourceBitmap(bmp)
                    bmp.recycle()
                }
                val dims = NativeBridge.outputDims(previewEdge)
                val orig = Bitmap.createBitmap(dims[0], dims[1], Bitmap.Config.ARGB_8888)
                NativeBridge.renderOriginal(previewEdge, orig)
                main.post {
                    if (token != imageToken) return@post
                    sourceIsRaw = isRaw
                    sourceEdge = edge
                    if (then != null) { then(); return@post }
                    hasImage = true
                    frameNumber = 1 + (abs(uri.hashCode()) % 36)
                    rawOriginal = orig
                    rawPreview = null
                    lastRenderKey = ""
                    lastThumbKey = ""
                    thumbs.clear()
                    refreshFilmCards()
                    emptyView.visibility = View.GONE
                    exportButton.isEnabled = true
                    exportButton.alpha = 1f
                    compareButton.visibility = View.VISIBLE
                    applyFrames()
                    requestPreview(immediate = true)
                    scheduleThumbnails()
                }
            } catch (t: Throwable) {
                Log.e(tag, "load failed", t)
                main.post {
                    if (token == imageToken) setStatus("无法打开：${t.message}", busy = false)
                }
            }
        }
    }

    // ================================================================ rendering

    private val previewRunnable = Runnable { runPreview() }

    private fun onStateChanged(engineAffecting: Boolean) {
        updateTitle()
        if (!hasImage) return
        if (engineAffecting) requestPreview() else applyFrames()
    }

    private fun requestPreview(immediate: Boolean = false) {
        main.removeCallbacks(previewRunnable)
        if (immediate) runPreview() else main.postDelayed(previewRunnable, 120)
    }

    private fun runPreview() {
        if (!hasImage || !engineReady) return
        val key = renderKey(state)
        if (key == lastRenderKey && rawPreview != null) { applyFrames(); return }
        val gen = ++previewGen
        val token = imageToken
        val s = state.copy()
        val (paper, scan) = resolvePaper(s)
        val json = s.toParamsJson(scan)
        val gpu = useGpu
        setStatus("渲染中…", busy = true)
        worker.preview {
            if (gen != previewGen || token != imageToken) return@preview
            try {
                val dims = NativeBridge.outputDims(previewEdge)
                val bmp = Bitmap.createBitmap(dims[0], dims[1], Bitmap.Config.ARGB_8888)
                val report = NativeBridge.render(s.film, paper, json, previewEdge, gpu, true, bmp)
                main.post {
                    if (token != imageToken) return@post
                    rawPreview = bmp
                    lastRenderKey = key
                    applyFrames()
                    if (gen == previewGen) setStatus(describe(report), busy = false)
                }
            } catch (t: Throwable) {
                Log.e(tag, "preview failed", t)
                main.post { if (gen == previewGen) setStatus("渲染失败：${t.message}", busy = false) }
            }
        }
    }

    private fun frameSpec(s: EditState): FrameRenderer.Spec {
        val film = profile(s.film)
        val (_, scan) = resolvePaper(s)
        val look = when {
            !scan -> FrameRenderer.Look.PRINT
            film?.isPositive == true -> FrameRenderer.Look.SCAN_POSITIVE
            else -> FrameRenderer.Look.SCAN_NEGATIVE
        }
        return FrameRenderer.Spec(
            style = s.string("ui.frame_style"),
            size = s.double("ui.frame_size"),
            text = s.bool("ui.frame_text"),
            look = look,
            frameNumber = frameNumber,
            isoLabel = FrameRenderer.isoFrom(film?.name.orEmpty()),
        )
    }

    /** Re-apply the (cheap, UI-only) frame to the cached renders and show them. */
    private fun applyFrames() {
        val spec = frameSpec(state)
        rawOriginal?.let { shownOriginal = FrameRenderer.apply(it, spec) }
        rawPreview?.let { shownPreview = FrameRenderer.apply(it, spec) }
        showImage()
    }

    private fun showImage() {
        val bmp = if (comparing || shownPreview == null) shownOriginal else shownPreview
        imageView.setBitmapKeepZoom(bmp)
    }

    private fun describe(reportJson: String): String = try {
        val r = JSONObject(reportJson)
        if (r.optBoolean("gpu_fallback")) useGpu = false
        val fb = if (r.optBoolean("gpu_fallback")) " · GPU 出错，已改用 CPU" else ""
        val src = if (sourceIsRaw) "RAW · " else ""
        "$src${r.optString("backend")} · ${r.optInt("width")}×${r.optInt("height")} · ${r.optLong("millis")} ms$fb"
    } catch (_: Exception) {
        ""
    }

    // ---------------------------------------------------------------- thumbnails

    private fun thumbState(filmId: String): EditState = state.copy().apply { film = filmId; paper = "" }

    private fun scheduleThumbnails() {
        if (!hasImage) return
        val key = thumbKey()
        if (key == lastThumbKey) return
        lastThumbKey = key
        worker.clearBackground()
        val token = imageToken
        val ordered = films.sortedBy { if (it.id == state.film) 0 else 1 }
        for (f in ordered) {
            val s = thumbState(f.id)
            val (paper, scan) = resolvePaper(s)
            val json = s.toParamsJson(scan)
            worker.background {
                if (token != imageToken) return@background
                try {
                    val dims = NativeBridge.outputDims(THUMB_EDGE)
                    val bmp = Bitmap.createBitmap(dims[0], dims[1], Bitmap.Config.ARGB_8888)
                    NativeBridge.render(f.id, paper, json, THUMB_EDGE, false, false, bmp)
                    main.post {
                        if (token != imageToken) return@post
                        thumbs[f.id] = bmp
                        (filmCards[f.id]?.tag as? ImageView)?.let { iv ->
                            iv.setImageBitmap(bmp)
                            (iv.tag as? View)?.visibility = View.GONE
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(tag, "thumbnail ${f.id} failed", t)
                }
            }
        }
    }

    /** Thumbnails depend on every engine parameter except the film/paper choice. */
    private fun thumbKey(): String = "$imageToken|" + state.copy().apply { film = ""; paper = "" }.toParamsJson(false)

    // ---------------------------------------------------------------- export

    private fun showExportDialog() {
        if (!hasImage || exporting) return
        val sizes = intArrayOf(4096, 6144, 8192, 0)
        val labels = arrayOf("长边 4096（约 1200 万像素）", "长边 6144（约 2800 万像素）", "长边 8192（约 5000 万像素）", "原图尺寸")
        var choice = sizes.indexOf(exportEdge).coerceAtLeast(0)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("导出到相册")
            .setSingleChoiceItems(labels, choice) { _, which -> choice = which }
            .setPositiveButton("导出") { _, _ ->
                exportEdge = sizes[choice]
                export(sizes[choice])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun export(edge: Int) {
        val uri = sourceUri ?: return
        exporting = true
        exportButton.isEnabled = false
        exportButton.alpha = 0.5f
        val run = {
            val s = state.copy()
            val (paper, scan) = resolvePaper(s)
            val json = s.toParamsJson(scan)
            val spec = frameSpec(s)
            val gpu = useGpu
            setStatus("导出中：全分辨率模拟…", busy = true)
            worker.urgent {
                var msg: String
                try {
                    val dims = NativeBridge.outputDims(0)
                    val bmp = Bitmap.createBitmap(dims[0], dims[1], Bitmap.Config.ARGB_8888)
                    val report = NativeBridge.render(s.film, paper, json, 0, gpu, true, bmp)
                    val framed = FrameRenderer.apply(bmp, spec)
                    if (framed !== bmp) bmp.recycle()
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    ImageIo.saveJpeg(this, framed, "spektrafilm_${s.film}_$stamp.jpg")
                    msg = "已导出 ${framed.width}×${framed.height} → 相册 / Spektrafilm · " +
                        "${JSONObject(report).optLong("millis")} ms"
                    framed.recycle()
                    main.post { Toast.makeText(this, "已保存到相册", Toast.LENGTH_SHORT).show() }
                } catch (oom: OutOfMemoryError) {
                    Log.e(tag, "export OOM", oom)
                    msg = "内存不足：请选择更小的导出尺寸"
                } catch (t: Throwable) {
                    Log.e(tag, "export failed", t)
                    msg = "导出失败：${t.message}"
                }
                main.post {
                    exporting = false
                    exportButton.isEnabled = true
                    exportButton.alpha = 1f
                    setStatus(msg, busy = false)
                }
            }
        }
        // Make sure the source is held at the requested resolution first.
        if (edge != sourceEdge) loadImage(uri, edge, then = run) else run()
    }

    // ================================================================ UI construction

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsets.CONSUMED
            }
        }

        // ---- image area with overlays
        val stage = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        imageView = ZoomImageView(this).apply {
            onHold = { holding -> comparing = holding; showImage() }
        }
        stage.addView(imageView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        emptyView = buildEmptyState()
        stage.addView(emptyView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        stage.addView(buildTopBar(), FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        stage.addView(buildStatusPill(), FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(12), 0, dp(12), dp(10))
        })
        root.addView(stage, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // ---- bottom panel
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.PANEL)
        }
        contentArea = FrameLayout(this)
        panel.addView(contentArea, LinearLayout.LayoutParams(MATCH_PARENT, dp(176)))
        val tabScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(6), 0, dp(6), 0) }
        for (t in Tab.entries) {
            tabBar.addView(TextView(this).apply {
                text = t.title
                this.tag = t
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setOnClickListener { showTab(t) }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
        }
        tabScroll.addView(tabBar)
        panel.addView(tabScroll, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)))
        root.addView(panel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        styleTabs()
        return root
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#B0000000"), Color.TRANSPARENT))
        }
        val open = Ui.barButton(this, "打开").apply { setOnClickListener { v -> showOpenMenu(v) } }
        bar.addView(open)
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        titleView = TextView(this).apply {
            setTextColor(Ui.TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; maxLines = 1
        }
        subtitleView = TextView(this).apply {
            setTextColor(Ui.TEXT_DIM); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); gravity = Gravity.CENTER; maxLines = 1
        }
        titles.addView(titleView)
        titles.addView(subtitleView)
        bar.addView(titles, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        compareButton = Ui.barButton(this, "原图").apply {
            visibility = View.GONE
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { comparing = true; showImage(); v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { comparing = false; showImage(); v.performClick() }
                }
                true
            }
        }
        bar.addView(compareButton, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(8) })
        exportButton = Ui.barButton(this, "导出", accent = true).apply {
            isEnabled = false; alpha = 0.5f
            setOnClickListener { showExportDialog() }
        }
        bar.addView(exportButton)
        val more = Ui.barButton(this, "⋯").apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { v -> showMoreMenu(v) }
        }
        bar.addView(more, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
        return bar
    }

    private fun showOpenMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "相册照片（JPEG / HEIF）")
            menu.add(0, 2, 1, "RAW 文件（DNG 等）")
            setOnMenuItemClickListener { if (it.itemId == 1) pickPhoto() else pickRaw(); true }
        }.show()
    }

    private fun showMoreMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "使用 GPU 加速").apply { isCheckable = true; isChecked = useGpu }
            menu.add(0, 2, 1, "重置全部参数")
            menu.add(0, 3, 2, "关于")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> { useGpu = !useGpu; lastRenderKey = ""; requestPreview(immediate = true) }
                    2 -> {
                        Tab.entries.forEach { t -> state.reset(t) }
                        state.paper = ""
                        showTab(currentTab); onStateChanged(engineAffecting = true)
                    }
                    3 -> showAbout()
                }
                true
            }
        }.show()
    }

    private fun showAbout() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Spektrafilm")
            .setMessage(
                "光谱胶片模拟：底片曝光 → 显影（DIR 耦合剂、颗粒）→ 放大机印放 → 扫描。\n\n" +
                    "计算引擎：spektrafilm（Andrea Volpato，GPLv3）的 Rust 移植 spektrafilm-rs（GPLv3）。" +
                    "胶片与相纸数据：CC BY-SA 4.0。\n\n当前后端：$backendName\n\n" +
                    "提示：用手机专业模式拍的 RAW (DNG) 是线性数据，高光滚降和色彩最接近真实胶片；" +
                    "普通直出照片已经过手机的色调处理，效果会偏硬。双指缩放可查看颗粒，长按图片对比原图。",
            )
            .setPositiveButton("好", null)
            .show()
    }

    private fun buildEmptyState(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Ui.BG)
        addView(TextView(context).apply {
            text = "Spektrafilm"
            setTextColor(Ui.TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f); typeface = Typeface.create("serif", Typeface.BOLD)
        })
        addView(TextView(context).apply {
            text = "基于厂商光谱数据的胶片模拟"
            setTextColor(Ui.TEXT_DIM); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(6), 0, dp(28))
        })
        addView(Ui.barButton(context, "打开相册照片", accent = true).apply {
            setOnClickListener { pickPhoto() }
        }, LinearLayout.LayoutParams(dp(220), WRAP_CONTENT))
        addView(Ui.barButton(context, "打开 RAW (DNG)").apply {
            background = Ui.rounded(Ui.CHIP, 20f, context)
            setOnClickListener { pickRaw() }
        }, LinearLayout.LayoutParams(dp(220), WRAP_CONTENT).apply { topMargin = dp(12) })
        addView(TextView(context).apply {
            text = "RAW 是线性数据，效果最接近真实胶片"
            setTextColor(Ui.TEXT_DIM); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(12), 0, 0)
        })
    }

    private fun buildStatusPill(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Ui.rounded(Color.parseColor("#99000000"), 14f, context)
        setPadding(dp(10), dp(4), dp(10), dp(4))
        statusSpinner = ProgressBar(context).apply { isIndeterminate = true; visibility = View.GONE }
        addView(statusSpinner, LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(6) })
        statusView = TextView(context).apply {
            setTextColor(Color.parseColor("#D0D0D0")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        addView(statusView)
    }

    // ---------------------------------------------------------------- tabs

    private fun showTab(tab: Tab) {
        currentTab = tab
        styleTabs()
        contentArea.removeAllViews()
        filmCards.clear()
        contentArea.addView(if (tab == Tab.FILM) buildFilmTab() else buildToolTab(tab))
        if (tab == Tab.FILM) scheduleThumbnails()
    }

    private fun styleTabs() {
        for (i in 0 until tabBar.childCount) {
            val v = tabBar.getChildAt(i) as TextView
            val sel = v.tag == currentTab
            val modified = (v.tag as Tab).let { t -> Params.forTab(t).any { !state.isDefault(it) } }
            v.setTextColor(if (sel) Ui.ACCENT else if (modified) Ui.TEXT else Ui.TEXT_DIM)
            v.typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            v.text = (v.tag as Tab).title + if (modified && !sel) "·" else ""
        }
    }

    private fun buildFilmTab(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0) }
        if (!engineReady) {
            col.addView(TextView(this).apply { text = "加载胶片库…"; setTextColor(Ui.TEXT_DIM); setPadding(dp(16), dp(16), 0, 0) })
            return col
        }
        val rollScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val roll = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), 0, dp(10), 0) }
        var lastGroup = ""
        var selectedView: View? = null
        for (f in films) {
            if (f.kindLabel != lastGroup) {
                lastGroup = f.kindLabel
                roll.addView(TextView(this).apply {
                    text = f.kindLabel.toCharArray().joinToString("\n")
                    setTextColor(Ui.TEXT_DIM); setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    gravity = Gravity.CENTER; setPadding(dp(6), 0, dp(6), dp(18))
                }, LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
            }
            val card = filmCard(f)
            filmCards[f.id] = card
            if (f.id == state.film) selectedView = card
            roll.addView(card)
        }
        rollScroll.addView(roll)
        col.addView(rollScroll, LinearLayout.LayoutParams(MATCH_PARENT, dp(116)))
        selectedView?.let { v -> rollScroll.post { rollScroll.smoothScrollTo(max(0, v.left - dp(80)), 0) } }

        // paper row
        val film = profile(state.film)
        val paperScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), dp(4), dp(10), 0) }
        val (autoPaper, autoScan) = resolvePaper(EditState(state.film, ""))
        val autoLabel = if (autoScan) "自动：直接扫描" else "自动：" + (profile(autoPaper)?.shortName ?: autoPaper)
        val options = mutableListOf("" to autoLabel)
        papersFor(film).forEach { options += it.id to it.shortName }
        options += EditState.SCAN_FILM to if (film?.isPositive == true) "直接扫描" else "扫描底片（负像）"
        for ((id, label) in options) {
            val chip = Ui.chip(this, label)
            Ui.styleChip(chip, selected = state.paper == id)
            chip.setOnClickListener {
                if (state.paper != id) {
                    state.paper = id
                    showTab(Tab.FILM)
                    onStateChanged(engineAffecting = true)
                }
            }
            row.addView(chip, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
        paperScroll.addView(row)
        col.addView(paperScroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return col
    }

    private fun filmCard(f: ProfileInfo): View {
        val selected = f.id == state.film
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        val thumbFrame = FrameLayout(this).apply {
            background = Ui.rounded(Ui.CHIP, 10f, context)
            foreground = if (selected) Ui.rounded(Color.TRANSPARENT, 10f, context, Ui.ACCENT) else null
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, dp(10).toFloat())
            }
            clipToOutline = true
        }
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        thumbs[f.id]?.let { img.setImageBitmap(it) }
        thumbFrame.addView(img, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        if (thumbs[f.id] == null) {
            val placeholder = TextView(this).apply {
                text = FrameRenderer.isoFrom(f.name).ifEmpty { f.shortName.take(3) }
                setTextColor(Ui.TEXT_DIM); gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.DEFAULT_BOLD
            }
            img.tag = placeholder
            thumbFrame.addView(placeholder, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        card.addView(thumbFrame, LinearLayout.LayoutParams(dp(72), dp(72)))
        card.addView(TextView(this).apply {
            text = f.shortName
            setTextColor(if (selected) Ui.ACCENT else Ui.TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER; maxLines = 2
            typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }, LinearLayout.LayoutParams(dp(80), WRAP_CONTENT).apply { topMargin = dp(4) })
        card.tag = img
        card.setOnClickListener {
            if (state.film != f.id) {
                state.film = f.id
                state.paper = ""
                it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                showTab(Tab.FILM)
                onStateChanged(engineAffecting = true)
            }
        }
        return card
    }

    private fun refreshFilmCards() {
        if (currentTab == Tab.FILM) showTab(Tab.FILM)
    }

    private fun buildToolTab(tab: Tab): View {
        val defs = Params.forTab(tab)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, 0) }
        val toolScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), 0, dp(10), 0) }
        val control = FrameLayout(this)

        val firstAdjustable = defs.firstOrNull { it !is ToggleDef }?.key
        val sel = selectedTool[tab]?.takeIf { k -> defs.any { it.key == k } } ?: firstAdjustable
        fun rebuild() { contentArea.removeAllViews(); contentArea.addView(buildToolTab(tab)) }

        for (d in defs) {
            val chip = Ui.chip(this, chipText(d))
            val isSel = d.key == sel && d !is ToggleDef
            val on = d is ToggleDef && state.get(d) as Boolean
            Ui.styleChip(chip, selected = isSel || on, modified = !state.isDefault(d))
            if (d is ToggleDef && !on) chip.setTextColor(Ui.TEXT_DIM)
            chip.setOnClickListener {
                if (d is ToggleDef) {
                    state.set(d, !(state.get(d) as Boolean))
                    it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onStateChanged(d.engineParam)
                } else {
                    selectedTool[tab] = d.key
                }
                rebuild(); styleTabs()
            }
            tools.addView(chip, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
        val reset = Ui.chip(this, "↺ 重置").apply {
            setTextColor(Ui.TEXT_DIM)
            setOnClickListener {
                state.reset(tab)
                onStateChanged(engineAffecting = tab != Tab.FRAME)
                rebuild(); styleTabs()
            }
        }
        tools.addView(reset)
        toolScroll.addView(tools)
        col.addView(toolScroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        when (val d = defs.firstOrNull { it.key == sel }) {
            is SliderDef -> control.addView(FilmSlider(this, d).apply {
                setValue(state.get(d) as Double)
                onChange = { v ->
                    state.set(d, v)
                    onStateChanged(d.engineParam)
                    styleTabs()
                }
            }, FrameLayout.LayoutParams(MATCH_PARENT, dp(84)))
            is ChoiceDef -> {
                val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(12), 0) }
                box.addView(TextView(this).apply {
                    text = d.label + if (d.hint.isNotEmpty()) "  ·  ${d.hint}" else ""
                    setTextColor(Ui.TEXT_DIM); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                })
                val sc = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
                for ((label, value) in d.options) {
                    val c = Ui.chip(this, label)
                    Ui.styleChip(c, selected = state.get(d).toString() == value.toString())
                    c.setOnClickListener {
                        state.set(d, value)
                        onStateChanged(d.engineParam)
                        rebuild(); styleTabs()
                    }
                    row.addView(c, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(8) })
                }
                sc.addView(row)
                box.addView(sc)
                control.addView(box)
            }
            else -> control.addView(TextView(this).apply {
                text = "点上方按钮开关效果"
                setTextColor(Ui.TEXT_DIM); setPadding(dp(20), dp(20), 0, 0)
            })
        }
        col.addView(control, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return col
    }

    private fun chipText(d: ParamDef): String = when (d) {
        is ToggleDef -> d.label + if (state.get(d) as Boolean) " 开" else " 关"
        else -> d.label + if (!state.isDefault(d)) " •" else ""
    }

    // ---------------------------------------------------------------- misc

    private fun updateTitle() {
        val film = profile(state.film)
        titleView.text = film?.name ?: "Spektrafilm"
        val (paper, scan) = resolvePaper(state)
        subtitleView.text = when {
            film == null -> ""
            scan -> "${film.kindLabel} · 直接扫描"
            else -> "${film.kindLabel} · 印放于 ${profile(paper)?.shortName ?: paper}"
        }
    }

    private fun setStatus(text: String, busy: Boolean) {
        statusView.text = text
        statusSpinner.visibility = if (busy) View.VISIBLE else View.GONE
        (statusView.parent as View).visibility = if (text.isEmpty() && !busy) View.GONE else View.VISIBLE
    }

    private fun dp(v: Int) = Ui.dp(this, v.toFloat())

    companion object {
        private const val REQ_PHOTO = 1001
        private const val REQ_RAW = 1002
        private const val THUMB_EDGE = 192
    }
}
