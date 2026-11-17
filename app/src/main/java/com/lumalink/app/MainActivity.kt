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
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CameraCharacteristics
import android.util.Size
import android.view.MotionEvent
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Camera
import androidx.camera.core.AspectRatio
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.core.view.doOnLayout
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
    private var pendingSave: Pair<ByteArray, String>? = null
    private val saveExecutor = Executors.newSingleThreadExecutor()
    private var requestedFps = 3
    private var motionEnabled = true
    private var verifiedSpeed: String? = null
    private var wifiMode = false
    private var direct: WifiDirectTransfer? = null
    private var permissionAction: (() -> Unit)? = null
    private val directPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val action = permissionAction; permissionAction = null
        if (grants.isNotEmpty() && grants.values.all { it }) action?.invoke() else {
            layout(); title("Wi-Fi Direct permission needed")
            root.addView(label("Allow nearby Wi-Fi devices (or Location on Android 8–12) to connect directly. Light mode remains available.", 14f, muted))
            button("Back to home") { home() }
        }
    }
    private fun withDirectPermission(receiving: Boolean, action: () -> Unit) {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            toast("Wi-Fi Direct is unavailable on this device. Use Light / QR mode."); return
        }
        val required = mutableListOf(if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION)
        if (android.os.Build.VERSION.SDK_INT <= 32) required.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (receiving) required.add(Manifest.permission.CAMERA)
        val missing = required.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) action() else { permissionAction = action; directPermissions.launch(missing.toTypedArray()) }
    }

    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = pendingSave ?: return@registerForActivityResult
        if (granted) saveVerified(pending.first, pending.second)
        else {
            layout(); title("File verified; storage permission needed")
            root.addView(label("Allow storage access in app settings to save automatically on Android 8–9.", 15f, muted))
            button("Retry saving") { requestSave(pending.first, pending.second) }
            button("Discard and go home") { pendingSave = null; home() }
        }
    }
    private fun requestSave(data: ByteArray, name: String) {
        stop()
        pendingSave = data to name
        if (android.os.Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else saveVerified(data, name)
    }
    private fun saveVerified(data: ByteArray, name: String) {
        pendingSave = null
        layout(); title("Saving verified file...")
        val saveScreen = root
        val appContext = applicationContext
        // Finish an in-progress disk write even if the activity goes into the background.
        saveExecutor.submit {
            val result = runCatching { ReceivedFileWriter(appContext).save(data, name) }
            main.post {
                if (root !== saveScreen || isDestroyed) return@post
                layout()
                title(if (result.isSuccess) "File saved and SHA-256 verified" else "Save failed")
                root.addView(label(result.fold({ "Saved to $it\n${data.size} bytes" }, { it.message ?: "Storage unavailable" })))
                if (result.isSuccess) verifiedSpeed?.let { root.addView(label(it, 14f, muted)) }
                if (result.isFailure) button("Retry saving") { requestSave(data, name) }
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
                    Sender(name, data, if (wifiMode) Codec.BLOCK else 192)
                }
                main.post {
                    if (token != session) return@post
                    result.fold({ if (wifiMode) sendDirect(it) else send(it) }, { toast(it.message ?: "Read failed"); home() })
                }
            }
        }
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) receive() else toast("Camera permission required")
    }
    private val ink = Color.rgb(238, 242, 250)
    private val muted = Color.rgb(153, 166, 190)
    private val paper = Color.rgb(10, 14, 24)
    private val mint = Color.rgb(185, 255, 101)
    private val panel = Color.rgb(24, 32, 49)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        home()
    }
    private fun stop() {
        session++
        direct?.close(); direct = null
        work?.cancel(true); work = null
        frameCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }; frameCallback = null
        analyzer?.clearAnalyzer(); analyzer = null
        provider?.unbindAll()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
    }
    private fun layout(scrollable: Boolean = false) {
        stop()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (!scrollable) setBackgroundColor(paper)
        }
        val host = if (scrollable) AmbientLayout(this).apply {
            motionEnabled = this@MainActivity.motionEnabled
            addView(ScrollView(this@MainActivity).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            addView(root, FrameLayout.LayoutParams(-1, -2))
            }, FrameLayout.LayoutParams(-1, -1))
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
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun gap(height: Int) { root.addView(android.view.View(this), LinearLayout.LayoutParams(1, dp(height))) }
    private fun title(value: String) {
        root.addView(label(value, 25f, bold = true).apply { setPadding(0, dp(8), 0, dp(20)) })
    }
    private fun button(value: String, action: () -> Unit) {
        root.addView(Button(this).apply {
            text = value; textSize = 14f; isAllCaps = false
            setTextColor(paper); backgroundTintList = ColorStateList.valueOf(mint)
            minHeight = dp(56); setOnClickListener { action() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }
    private fun actionCard(name: String, description: String, dark: Boolean, action: () -> Unit) {
        val foreground = if (dark) ink else paper
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(20))
            val surface = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                if (dark) intArrayOf(0xFF292C46.toInt(), 0xFF192636.toInt()) else intArrayOf(0xFFC8ECA5.toInt(), 0xFF8CC9B0.toInt())).apply {
                cornerRadius = dp(22).toFloat()
                if (dark) setStroke(dp(1), 0xFF3A405B.toInt())
            }
            background = RippleDrawable(ColorStateList.valueOf(0x22349676), surface, null)
            isClickable = true; isFocusable = true; minimumHeight = dp(104)
            setOnClickListener { action() }
            contentDescription = "$name. $description"
        }
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(label(name, 22f, foreground, true))
        copy.addView(label(description, 14f, if (dark) muted else Color.rgb(45, 64, 31)).apply { setPadding(0, dp(5), dp(12), 0) })
        card.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(ImageView(this).apply {
            setImageResource(if (dark) R.drawable.ic_scan else R.drawable.ic_send_file)
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(32), dp(32)))
        root.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
    private fun home() {
        layout(scrollable = true)
        val brand = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        brand.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            background = rounded(panel, 14)
            contentDescription = null
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        brand.addView(label("LumaLink", 23f, bold = true), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
        root.addView(brand)
        gap(32)
        root.addView(label("THE OFFLINE TRANSFER LAB", 10f, muted, true).apply { letterSpacing = .12f })
        gap(12)
        root.addView(label(if (wifiMode) "Skip the cloud.\nGo direct." else "Skip the cloud.\nSend with light.", 34f, bold = true).apply { setLineSpacing(0f, 1f) })
        gap(12)
        root.addView(label(if (wifiMode) "Your files. Direct Wi-Fi. Zero internet." else "Your files. A beam of light. Zero network.", 15f, muted))
        val signal = OpticalSignalView(this).apply { motionEnabled = this@MainActivity.motionEnabled }
        root.addView(signal, LinearLayout.LayoutParams(-1, dp(116)))
        val motion = label("", 11f, muted).apply {
            gravity = Gravity.CENTER; minHeight = dp(48); isClickable = true; isFocusable = true
            setOnClickListener {
                motionEnabled = !motionEnabled
                signal.motionEnabled = motionEnabled
                (root.parent.parent as? AmbientLayout)?.motionEnabled = motionEnabled
                text = if (motionEnabled) "Tap the beam to pulse  ·  Motion on" else "Background motion paused  ·  Tap to enable"
            }
            text = if (motionEnabled) "Tap the beam to pulse  ·  Motion on" else "Background motion paused  ·  Tap to enable"
        }
        // The root is nested in a ScrollView inside the ambient background container.
        root.addView(motion)
        val modes = LinearLayout(this)
        for ((title, wifi) in listOf("Light / QR" to false, "Wi-Fi Direct" to true)) {
            modes.addView(label(title, 14f, if (wifiMode == wifi) paper else muted, true).apply {
                gravity = Gravity.CENTER; minHeight = dp(52)
                background = rounded(if (wifiMode == wifi) mint else panel, 14)
                isClickable = true; isFocusable = true; isSelected = wifiMode == wifi
                setOnClickListener { wifiMode = wifi; home() }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
        }
        root.addView(modes); gap(12)
        root.addView(label(if (wifiMode) "Direct Wi-Fi connection. No router or internet required. Keep both apps open." else "Camera-only file data. No network connection required.", 12f, muted))
        gap(16)
        actionCard("Send file", if (wifiMode) "01  /  Create a direct Wi-Fi offer" else "01  /  Turn your screen into a sender", false) {
            if (wifiMode) withDirectPermission(false) { pick.launch("*/*") } else pick.launch("*/*")
        }
        actionCard("Scan & receive", "02  /  Capture, verify, save automatically", true) {
            if (wifiMode) withDirectPermission(true) { receive() }
            else if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) receive()
            else permission.launch(Manifest.permission.CAMERA)
        }
        if (!wifiMode) {
        gap(18)
        root.addView(label("LIGHT MODE DISPLAY RATE", 11f, muted, true).apply { letterSpacing = .14f })
        gap(12)
        val rates = listOf(3, 6, 15, 30, 60)
        val choices = LinearLayout(this)
        val chips = mutableListOf<TextView>()
        fun updateRates() {
            chips.forEachIndexed { index, chip ->
                val selected = rates[index] == requestedFps
                chip.isSelected = selected
                chip.setTextColor(if (selected) paper else muted)
                chip.background = rounded(if (selected) mint else panel, 14)
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
        root.addView(label("Reliable mode: smaller QR frames. Start at 3 FPS; update both phones.", 12f, muted))
        }
        gap(28)
        root.addView(label("SCREEN / CAMERA / SAVED", 12f, bold = true))
        gap(6)
        root.addView(label("No internet or Bluetooth. Light mode needs no network; Wi-Fi Direct establishes a local connection. Install LumaLink on both phones.", 13f, muted))
        gap(14)
        root.addView(label("Experimental QR mode · 190 KB/s is not yet supported.", 12f, muted))
    }
    private fun send(sender: Sender) {
        layout(); window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val token = session
        title("Sending: ${sender.name}")
        window.attributes = window.attributes.apply { screenBrightness = .9f }
        val qr = QrDisplayView(this)
        root.addView(qr, LinearLayout.LayoutParams(-1,0,1f))
        val status = label("Preparing QR frames…", 13f, muted).apply { setPadding(0, dp(16), 0, dp(4)) }; root.addView(status)
        var paused = false
        root.addView(Button(this).apply {
            text = "Hold QR for focus"; isAllCaps = false
            setOnClickListener { paused = !paused; text = if (paused) "Resume transfer" else "Hold QR for focus" }
        }, LinearLayout.LayoutParams(-1, dp(48)))
        button("STOP") { home() }
        val queue = ArrayBlockingQueue<Pair<Int, Bitmap>>(2)
        work = worker.submit {
            try {
                val writer = QRCodeWriter()
                val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 4,
                    EncodeHintType.CHARACTER_SET to "UTF-8")
                var emission = 0L
                while (!Thread.currentThread().isInterrupted && token == session) {
                    val seq = LightSchedule.sequence(sender.count, emission++)
                    val matrix = writer.encode(sender.frame(seq), BarcodeFormat.QR_CODE, 0, 0, hints)
                    val pixels = IntArray(matrix.width * matrix.height) { i -> if (matrix[i % matrix.width, i / matrix.width]) 0xff000000.toInt() else 0xffffffff.toInt() }
                    queue.put(seq to Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888))
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
                if (!paused && (last == 0L || (time - first >= 1_000_000_000L && time - last >= interval - 500_000L))) {
                    queue.poll()?.let { (seq, bitmap) ->
                        qr.setSymbol(bitmap)
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
        verifiedSpeed = null
        layout(); window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val token = session
        title("Point camera at sender")
        val view = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
        root.addView(view, LinearLayout.LayoutParams(-1,0,1f))
        val status = label("Waiting for the first QR frame…", 13f, muted).apply { setPadding(0, dp(16), 0, dp(4)) }; root.addView(status)
        button("STOP") { home() }
        val receiver = Receiver()
        val speed = ReceiveSpeed()
        val decoder = FrameDecoder()
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (token != session) return@addListener
            try {
                val cameraProvider = future.get(); provider = cameraProvider
                val preview = androidx.camera.core.Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_16_9).build().also { it.surfaceProvider = view.surfaceProvider }
                val resolution = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build()
                val analysis = ImageAnalysis.Builder().setResolutionSelector(resolution)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analyzer = analysis
                var frames = 0L
                var decoded = 0L
                var start = 0L
                var lastUi = 0L
                var duplicates = 0L; var rejected = 0L; var foreign = 0L
                var decodeNanos = 0L; var fecNanos = 0L
                analysis.setAnalyzer(cameraExecutor) { proxy ->
                    try {
                        if (token != session) return@setAnalyzer
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (start == 0L) start = now
                        frames++
                        val decodeStart = System.nanoTime()
                        val text = decoder.decode(proxy)
                        decodeNanos += System.nanoTime() - decodeStart
                        if (wifiMode && text != null) {
                            val invite = runCatching { DirectInvitation.parse(text) }.getOrNull()
                            if (invite != null) main.post { if (token == session) confirmDirect(invite) }
                            return@setAnalyzer
                        }
                        if (text != null) {
                            decoded++
                            val fecStart = System.nanoTime()
                            receiver.accept(text)
                            fecNanos += System.nanoTime() - fecStart
                            when (receiver.lastPacketResult) {
                                Receiver.PacketResult.DUPLICATE -> duplicates++
                                Receiver.PacketResult.INVALID -> rejected++
                                Receiver.PacketResult.FOREIGN -> foreign++
                                else -> Unit
                            }
                        }
                        if (receiver.received > 0) speed.start(android.os.SystemClock.elapsedRealtime())
                        val result = receiver.complete
                        val failure = receiver.error
                        if (result != null || failure != null || now - lastUi >= 250) {
                            lastUi = now
                            val elapsed = ((now - start) / 1000.0).coerceAtLeast(0.001)
                            val measuredAt = android.os.SystemClock.elapsedRealtime()
                            val rate = speed.update(measuredAt, receiver.recoveredBytes, receiver.received)
                            val diagnostic = "\n%s · decode %.1f ms · FEC %.1f ms\nDuplicates %d · rejected %d · other session %d\nLast accepted #%d · Tap preview to focus".format(java.util.Locale.US,
                                decoder.resolution, decodeNanos / 1e6 / frames, if (decoded == 0L) 0.0 else fecNanos / 1e6 / decoded,
                                duplicates, rejected, foreign, receiver.lastSequence)
                            val message = rate + "\n" + TransferStatus.receiving(receiver.progress, receiver.solved, receiver.received, frames, decoded, elapsed) + diagnostic
                            val finishedRate = if (result != null) speed.verified(measuredAt, result.size) else null
                            main.post {
                                if (token != session) return@post
                                status.text = message
                                if (failure != null) { layout(); title(failure); button("RETRY") { receive() }; button("HOME") { home() } }
                                else if (result != null) {
                                    verifiedSpeed = finishedRate
                                    requestSave(result, receiver.filename)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("LumaLink", "Receive analysis failed", e)
                        main.post { if (token == session) receiveError("Camera analysis stopped", e) }
                    } finally { proxy.close() }
                }
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                tuneCamera(camera, view, token)
            } catch (e: Exception) { if (token == session) receiveError("Camera unavailable", e) }
        }, ContextCompat.getMainExecutor(this))
    }
    @androidx.annotation.OptIn(markerClass = [androidx.camera.camera2.interop.ExperimentalCamera2Interop::class])
    private fun tuneCamera(camera: Camera, view: PreviewView, token: Long) {
        runCatching {
            val info = Camera2CameraInfo.from(camera.cameraInfo)
            val ranges = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            val range = ranges?.filter { it.lower <= 30 && it.upper >= 30 }?.minByOrNull { it.upper - it.lower }
            val options = CaptureRequestOptions.Builder()
            if (range != null) options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
            Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(options.build())
            val exposure = camera.cameraInfo.exposureState
            if (exposure.isExposureCompensationSupported && exposure.exposureCompensationRange.contains(-1))
                camera.cameraControl.setExposureCompensationIndex(-1)
        }.onFailure { android.util.Log.w("LumaLink", "Optional camera tuning unavailable", it) }
        fun focus(x: Float, y: Float) {
            if (token != session) return
            runCatching {
                val point = view.meteringPointFactory.createPoint(x, y)
                camera.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point,
                    FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(5, java.util.concurrent.TimeUnit.SECONDS).build())
            }
        }
        view.doOnLayout { focus(view.width / 2f, view.height / 2f) }
        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) { view.performClick(); focus(event.x, event.y) }
            true
        }
    }
    private fun sendDirect(sender: Sender) {
        layout(); window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val token = session
        title("Wi-Fi Direct · Send")
        val qr = ImageView(this).apply { setBackgroundColor(Color.WHITE); scaleType = ImageView.ScaleType.FIT_CENTER }
        root.addView(qr, LinearLayout.LayoutParams(-1, 0, 1f))
        val status = label("Creating offer…", 14f, muted); root.addView(status)
        button("Cancel transfer") { home() }
        try {
            val transfer = WifiDirectTransfer(this, { if (token == session) status.text = it }, { if (token == session) receiveError("Wi-Fi Direct failed", it) })
            direct = transfer
            transfer.send(sender, { text, owner ->
                if (token != session) return@send
                status.text = "On the receiver, select Wi-Fi Direct and scan this QR.\nSender device: $owner\n${sender.name} · ${sender.bytes.size} bytes\nOnly share this QR with the intended receiver."
                work = worker.submit {
                    val encoded = runCatching {
                        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.CHARACTER_SET to "UTF-8"))
                        val pixels = IntArray(matrix.width * matrix.height) { i -> if (matrix[i % matrix.width, i / matrix.width]) Color.BLACK else Color.WHITE }
                        Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
                    }
                    main.post {
                        if (token != session) return@post
                        encoded.fold({ qr.setImageDrawable(android.graphics.drawable.BitmapDrawable(resources, it).apply { isFilterBitmap = false }) }, { receiveError("Cannot display Wi-Fi offer", it as? Exception ?: Exception(it)) })
                    }
                }
            }, {
                if (token == session) {
                    layout(); title("Receiver verified the file")
                    root.addView(label("${sender.name} transferred over Wi-Fi Direct. The receiver will save it automatically."))
                    button("Home") { home() }
                }
            })
        } catch (e: Exception) { receiveError("Wi-Fi Direct unavailable", e) }
    }
    private fun confirmDirect(invite: DirectInvitation) {
        layout(); title("Receive over Wi-Fi Direct?")
        root.addView(label("${invite.name}\n${invite.size} bytes\nSender: ${invite.owner}", 17f))
        gap(16)
        root.addView(label("Confirm the sender, then select their device from the nearby list. Android may ask to approve the connection.", 14f, muted))
        button("Connect and receive") { discoverDirect(invite) }
        button("Cancel") { home() }
    }
    private fun discoverDirect(invite: DirectInvitation) {
        layout(); window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val token = session
        title("Find the sender")
        val status = label("Looking for ${invite.owner}…\nEnable Wi-Fi and Location for peer discovery if required by your phone.", 14f, muted)
        root.addView(status)
        val peers = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            addView(peers)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        button("Cancel transfer") { home() }
        try {
            val transfer = WifiDirectTransfer(this, { if (token == session) status.text = it }, { if (token == session) receiveError("Wi-Fi Direct failed", it) })
            direct = transfer
            transfer.discover(invite, { devices ->
                if (token == session) {
                    peers.removeAllViews()
                    devices.forEach { device ->
                        peers.addView(Button(this).apply {
                            text = device.deviceName.ifBlank { "Nearby Wi-Fi device" }; isAllCaps = false
                            setOnClickListener { peers.removeAllViews(); transfer.connect(device) }
                        }, LinearLayout.LayoutParams(-1, dp(60)))
                    }
                    if (devices.isEmpty()) status.text = "No peers yet. Keep the sender's offer open and both phones nearby."
                }
            }, { bytes, name, speed ->
                if (token == session) { verifiedSpeed = speed; requestSave(bytes, name) }
            })
        } catch (e: Exception) { receiveError("Wi-Fi Direct unavailable", e) }
    }
    private fun receiveError(heading: String, error: Exception) {
        layout(); title(heading)
        root.addView(label(error.message ?: error.javaClass.simpleName, 15f, muted))
        gap(16)
        root.addView(label(if (wifiMode) "Enable Wi-Fi and nearby-device access. On Android 8–12, also enable Location. Keep both apps open and retry from home." else "Check camera access and try again. Start the sender at 6 FPS.", 14f, muted))
        if (!wifiMode) button("Try receiving again") { receive() }
        button("Back to home") { home() }
    }
    override fun onStop() { stop(); super.onStop() }
    override fun onDestroy() { stop(); worker.shutdownNow(); cameraExecutor.shutdownNow(); saveExecutor.shutdown(); super.onDestroy() }
}
