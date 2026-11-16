package com.lumalink.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Choreographer
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.Future

class MainActivity : ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var work: Future<*>? = null
    private var provider: ProcessCameraProvider? = null
    private var analyzer: ImageAnalysis? = null
    private var frameCallback: Choreographer.FrameCallback? = null
    @Volatile private var session = 0L
    private lateinit var root: LinearLayout
    private var pendingSave: ByteArray? = null
    private var requestedFps = 6

    private val saveDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val data = pendingSave
        pendingSave = null
        if (uri == null || data == null) { home(); return@registerForActivityResult }
        layout(); title("Saving verified file...")
        val token = session
        work = worker.submit {
            val result = runCatching { contentResolver.openOutputStream(uri, "w")?.use { it.write(data) } ?: error("Cannot open document") }
            main.post {
                if (token != session) return@post
                layout()
                title(if (result.isSuccess) "File saved and SHA-256 verified" else "Save failed")
                root.addView(TextView(this).apply { text = result.exceptionOrNull()?.message ?: "${data.size} bytes saved" })
                button("HOME") { home() }
            }
        }
    }
    private val pick = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            layout(); title("Reading file..."); button("CANCEL") { home() }
            val token = session
            work = worker.submit {
                val result = runCatching {
                    val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else "file.bin"
                    } ?: "file.bin"
                    val data = contentResolver.openInputStream(uri)?.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            if (Thread.currentThread().isInterrupted) throw InterruptedException()
                            val n = input.read(buffer)
                            if (n < 0) break
                            require(output.size() + n <= Codec.MAX_BYTES) { "Maximum file size is 10 MB" }
                            output.write(buffer, 0, n)
                        }
                        output.toByteArray()
                    } ?: error("Cannot read file")
                    Sender(name, data)
                }
                main.post {
                    if (token != session) return@post
                    result.fold({ send(it) }, { toast(it.message ?: "Read failed"); home() })
                }
            }
        }
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) receive() else toast("Camera permission required")
    }
    private val ink = Color.rgb(20, 43, 48)
    private val muted = Color.rgb(87, 110, 112)
    private val paper = Color.rgb(246, 248, 244)
    private val mint = Color.rgb(207, 245, 222)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        home()
    }
    private fun stop() {
        session++
        work?.cancel(true); work = null
        frameCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }; frameCallback = null
        analyzer?.clearAnalyzer(); analyzer = null
        provider?.unbindAll()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun layout(scrollable: Boolean = false) {
        stop()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(paper)
        }
        val host = if (scrollable) ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(paper)
            addView(root, FrameLayout.LayoutParams(-1, -2))
        } else root
        ViewCompat.setOnApplyWindowInsetsListener(host) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(dp(24) + bars.left, dp(20) + bars.top, dp(24) + bars.right, dp(20) + bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(host)
        ViewCompat.requestApplyInsets(host)
    }
    private fun rounded(color: Int, radius: Int = 22) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun label(value: String, size: Float = 15f, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun gap(height: Int) { root.addView(android.view.View(this), LinearLayout.LayoutParams(1, dp(height))) }
    private fun title(value: String) {
        root.addView(label(value, 25f, bold = true).apply { setPadding(0, dp(8), 0, dp(20)) })
    }
    private fun button(value: String, action: () -> Unit) {
        root.addView(Button(this).apply {
            text = value; textSize = 14f; isAllCaps = false
            setTextColor(Color.WHITE); backgroundTintList = ColorStateList.valueOf(ink)
            minHeight = dp(56); setOnClickListener { action() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }
    private fun actionCard(name: String, description: String, dark: Boolean, action: () -> Unit) {
        val foreground = if (dark) Color.WHITE else ink
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(20))
            background = RippleDrawable(ColorStateList.valueOf(0x22349676), rounded(if (dark) ink else mint), null)
            isClickable = true; isFocusable = true; minimumHeight = dp(108)
            setOnClickListener { action() }
            contentDescription = "$name. $description"
        }
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(label(name, 22f, foreground, true))
        copy.addView(label(description, 14f, if (dark) Color.rgb(187, 210, 207) else muted).apply { setPadding(0, dp(5), dp(12), 0) })
        card.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(label(if (dark) "↓" else "↗", 30f, foreground).apply { importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO })
        root.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
    private fun home() {
        layout(scrollable = true)
        val brand = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        brand.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            background = rounded(ink, 14)
            contentDescription = null
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        brand.addView(label("LumaLink", 23f, bold = true), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
        brand.addView(label("OFFLINE", 10f, ink, true).apply { background = rounded(mint, 12); setPadding(dp(10), dp(7), dp(10), dp(7)); letterSpacing = .1f })
        root.addView(brand)
        gap(32)
        root.addView(label("Files move.\nThrough light.", 34f, bold = true).apply { setLineSpacing(0f, 1f) })
        gap(12)
        root.addView(label("A screen. A camera. A direct connection.", 15f, muted))
        gap(28)
        actionCard("Send a file", "Choose a file to show as animated QR codes", false) { pick.launch("*/*") }
        actionCard("Receive a file", "Point this camera at the sending phone", true) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) receive()
            else permission.launch(Manifest.permission.CAMERA)
        }
        gap(18)
        root.addView(label("DISPLAY RATE", 11f, muted, true).apply { letterSpacing = .14f })
        gap(12)
        val rates = listOf(6, 15, 30, 60)
        val choices = LinearLayout(this)
        val chips = mutableListOf<TextView>()
        fun updateRates() {
            chips.forEachIndexed { index, chip ->
                val selected = rates[index] == requestedFps
                chip.isSelected = selected
                chip.setTextColor(if (selected) Color.WHITE else ink)
                chip.background = rounded(if (selected) ink else Color.rgb(232, 237, 230), 14)
                chip.contentDescription = "${rates[index]} frames per second requested${if (selected) ", selected" else ""}"
            }
        }
        rates.forEachIndexed { index, rate ->
            val chip = label("$rate FPS", 14f, bold = true).apply {
                gravity = Gravity.CENTER; minHeight = dp(52); isClickable = true; isFocusable = true
                setOnClickListener { requestedFps = rate; updateRates() }
            }
            chips.add(chip)
            choices.addView(chip, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) leftMargin = dp(8) })
        }
        updateRates(); root.addView(choices)
        gap(10)
        root.addView(label("Start at 6 FPS. Higher rates depend on both phones.", 12f, muted))
        gap(28)
        root.addView(label("Offline by design", 15f, bold = true))
        gap(6)
        root.addView(label("No internet, Bluetooth or pairing. Install LumaLink on both phones to transfer.", 13f, muted))
        gap(14)
        root.addView(label("Experimental QR mode · 190 KB/s is not yet supported.", 12f, muted))
    }
    private fun send(sender: Sender) {
        layout(); window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val token = session
        title("Sending: ${sender.name}")
        val qr = ImageView(this).apply { setBackgroundColor(android.graphics.Color.WHITE); scaleType = ImageView.ScaleType.FIT_CENTER }
        root.addView(qr, LinearLayout.LayoutParams(-1,0,1f))
        val status = label("Preparing QR frames…", 13f, muted).apply { setPadding(0, dp(16), 0, dp(4)) }; root.addView(status)
        button("STOP") { home() }
        val queue = ArrayBlockingQueue<Pair<Int, Bitmap>>(2)
        work = worker.submit {
            try {
                val writer = QRCodeWriter()
                val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 4,
                    EncodeHintType.CHARACTER_SET to "UTF-8")
                var seq = 0
                while (!Thread.currentThread().isInterrupted && token == session) {
                    val matrix = writer.encode(sender.frame(seq), BarcodeFormat.QR_CODE, 0, 0, hints)
                    val pixels = IntArray(matrix.width * matrix.height) { i -> if (matrix[i % matrix.width, i / matrix.width]) 0xff000000.toInt() else 0xffffffff.toInt() }
                    queue.put(seq to Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888))
                    seq++
                }
            } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            catch (e: Exception) { main.post { if (token == session) { toast(e.message ?: "Encoding failed"); home() } } }
        }
        val interval = 1_000_000_000L / requestedFps
        var last = 0L
        var first = 0L
        var submitted = 0
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(time: Long) {
                if (token != session) return
                if (last == 0L || time - last >= interval - 500_000L) {
                    queue.poll()?.let { (seq, bitmap) ->
                        qr.setImageDrawable(android.graphics.drawable.BitmapDrawable(resources, bitmap).apply { isFilterBitmap = false })
                        last = time; if (first == 0L) first = time
                        submitted++
                        val fps = if (time == first) 0.0 else (submitted - 1) * 1e9 / (time - first)
                        status.text = "Symbol $seq | %.1f UI submissions/s\n%d bytes; %d blocks".format(fps, sender.bytes.size, sender.count)
                    }
                }
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        frameCallback = callback
        Choreographer.getInstance().postFrameCallback(callback)
    }
    private fun receive() {
        layout(); window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val token = session
        title("Point camera at sender")
        val view = PreviewView(this); root.addView(view, LinearLayout.LayoutParams(-1,0,1f))
        val status = label("Waiting for the first QR frame…", 13f, muted).apply { setPadding(0, dp(16), 0, dp(4)) }; root.addView(status)
        button("STOP") { home() }
        val receiver = Receiver()
        val decoder = FrameDecoder()
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (token != session) return@addListener
            try {
                val cameraProvider = future.get(); provider = cameraProvider
                val preview = androidx.camera.core.Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analyzer = analysis
                var frames = 0L
                var decoded = 0L
                var start = 0L
                var lastUi = 0L
                analysis.setAnalyzer(cameraExecutor) { proxy ->
                    try {
                        if (token != session) return@setAnalyzer
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (start == 0L) start = now
                        frames++
                        val text = decoder.decode(proxy)
                        if (text != null) { decoded++; receiver.accept(text) }
                        val result = receiver.complete
                        val failure = receiver.error
                        if (result != null || failure != null || now - lastUi >= 250) {
                            lastUi = now
                            val elapsed = ((now - start) / 1000.0).coerceAtLeast(0.001)
                            val message = "${receiver.progress}% | ${receiver.solved} blocks | ${receiver.received} accepted\n%.1f camera/s | %.1f QR/s | %.1f accepted/s".format(frames / elapsed, decoded / elapsed, receiver.received / elapsed)
                            main.post {
                                if (token != session) return@post
                                status.text = message
                                if (failure != null) { layout(); title(failure); button("RETRY") { receive() }; button("HOME") { home() } }
                                else if (result != null) {
                                    stop(); pendingSave = result
                                    saveDocument.launch(receiver.filename)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        main.post { if (token == session) { toast(e.message ?: "Camera analysis failed"); home() } }
                    } finally { proxy.close() }
                }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) { if (token == session) { toast(e.message ?: "Camera unavailable"); home() } }
        }, ContextCompat.getMainExecutor(this))
    }
    override fun onStop() { stop(); super.onStop() }
    override fun onDestroy() { stop(); worker.shutdownNow(); cameraExecutor.shutdownNow(); super.onDestroy() }
}
