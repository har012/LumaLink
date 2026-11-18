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
    private var selectedPayload = 192
    private var holdEvery = 10
    private var calibratedRate: Double? = null
    private var calibrationInfo: TextView? = null
    private fun invalidateCalibration() {
        calibratedRate = null
        calibrationInfo?.text = "Profile changed. Recalibrate for a matching estimate."
    }
    private var scanPurpose = 0 // 0=file, 1=calibration samples, 2=calibration result
    private val checkpoint by lazy { OpticalCheckpoint(java.io.File(filesDir, "optical-resume.journal")) }
    private var completedCheckpoint: String? = null
    private fun opticalProfile() = OpticalProfile(selectedPayload, requestedFps, holdEvery)
    private fun persistOpticalProfile() {
        getPreferences(MODE_PRIVATE).edit().putInt("payload", selectedPayload).putInt("fps", requestedFps)
            .putInt("holdEvery", holdEvery).putFloat("calibratedRate", calibratedRate?.toFloat() ?: -1f).apply()
    }
    private fun opticalChoices(items: List<String>) = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, items) {
        override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View =
            super.getView(position, convertView, parent).apply { (this as? TextView)?.setTextColor(ink) }
        override fun getDropDownView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View =
            super.getDropDownView(position, convertView, parent).apply { setBackgroundColor(Color.WHITE); (this as? TextView)?.setTextColor(Color.BLACK) }
    }
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
        val savedCheckpoint = completedCheckpoint
        // Finish an in-progress disk write even if the activity goes into the background.
        saveExecutor.submit {
            val result = runCatching { ReceivedFileWriter(appContext).save(data, name) }
            if (result.isSuccess) runCatching { checkpoint.clear(savedCheckpoint) }
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
                    if (wifiMode) Sender(name, data) else Sender(name, data, selectedPayload,
                        OpticalV4.sessionId(name, Codec.hash(data), selectedPayload))
                }
                main.post {
                    if (token != session) return@post
                    result.fold({ if (wifiMode) sendDirect(it) else reviewOptical(it) }, { toast(it.message ?: "Read failed"); home() })
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
        val saved = getPreferences(MODE_PRIVATE)
        runCatching { OpticalProfile(saved.getInt("payload", 192), saved.getInt("fps", 3), saved.getInt("holdEvery", 10)) }.getOrNull()?.let {
            selectedPayload = it.payload; requestedFps = it.fps; holdEvery = it.holdEvery
            calibratedRate = saved.getFloat("calibratedRate", -1f).toDouble().takeIf { rate -> rate > 0 && rate.isFinite() }
        }
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
        scanPurpose = 0
        calibrationInfo = null
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
        button("Calibrate optical link") { calibrationMenu() }
        root.addView(label("PAYLOAD PER SYMBOL", 11f, muted, true))
        val sizes = listOf(192, 384, 768, 1024)
        root.addView(Spinner(this).apply {
            adapter = opticalChoices(sizes.map { "$it bytes${if (it == 192) " · easier to scan" else ""}" })
            minimumHeight = dp(52); background = rounded(panel, 12)
            setSelection(sizes.indexOf(selectedPayload))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    if (selectedPayload != sizes[position]) invalidateCalibration()
                    selectedPayload = sizes[position]
                    persistOpticalProfile()
                }
            }
        })
        root.addView(label("LONGER DISPLAY HOLD", 11f, muted, true))
        val holdOptions = listOf(0, 1, 10)
        root.addView(Spinner(this).apply {
            adapter = opticalChoices(listOf("Off", "Every new symbol once · at least 500 ms", "Every 10th new symbol once · at least 500 ms"))
            minimumHeight = dp(52); background = rounded(panel, 12)
            setSelection(holdOptions.indexOf(holdEvery))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    if (holdEvery != holdOptions[position]) invalidateCalibration()
                    holdEvery = holdOptions[position]
                    persistOpticalProfile()
                }
            }
        })
        calibratedRate?.let {
            calibrationInfo = label("Calibration: %.1f accepted payload KB/s; file recovery may be slower.".format(java.util.Locale.US, it / 1000), 12f, muted)
            root.addView(calibrationInfo)
        }
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
                setOnClickListener { if (requestedFps != rate) invalidateCalibration(); requestedFps = rate; persistOpticalProfile(); updateRates() }
            }
            chips.add(chip)
            choices.addView(chip, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) leftMargin = dp(8) })
        }
        updateRates(); root.addView(choices)
        gap(10)
        root.addView(label("Calibrate both phones before increasing payload or FPS. Longer holds improve opportunity, not guaranteed capture.\nPartial Light reception is checkpointed privately for Resume.", 12f, muted))
        }
        gap(28)
        root.addView(label("SCREEN / CAMERA / SAVED", 12f, bold = true))
        gap(6)
        root.addView(label("No internet or Bluetooth. Light mode needs no network; Wi-Fi Direct establishes a local connection. Install LumaLink on both phones.", 13f, muted))
        gap(14)
        root.addView(label("Experimental QR mode · 190 KB/s is not yet supported.", 12f, muted))
    }
    private fun reviewOptical(sender: Sender) {
        layout(scrollable = true); title("Review optical transfer")
        root.addView(label("${sender.name}\n${sender.bytes.size} bytes · ${sender.count} source blocks\n${sender.blockSize} bytes/symbol · $requestedFps FPS requested", 17f))
        gap(16)
        root.addView(label(opticalProfile().estimate(sender.bytes.size), 15f, muted))
        calibratedRate?.let { root.addView(label("At calibration's accepted-payload rate: at least %.1f min; repeats/repairs may add time.".format(java.util.Locale.US, sender.bytes.size / it / 60), 14f, muted)) }
        root.addView(label("For large files, Wi-Fi Direct avoids holding the camera for long periods. Resume requires the same file name, contents and payload size on the sender.", 14f, muted))
        button("Start Light transfer") { send(sender) }
        button("Use Wi-Fi Direct instead") { wifiMode = true; withDirectPermission(false) { sendDirect(sender) } }
        button("Back") { home() }
    }
    private fun renderQr(text: String): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 4, EncodeHintType.CHARACTER_SET to "UTF-8"))
        return Bitmap.createBitmap(IntArray(matrix.width * matrix.height) { i -> if (matrix[i % matrix.width, i / matrix.width]) Color.BLACK else Color.WHITE }, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    }
    private fun calibrationMenu() {
        layout(scrollable = true); title("Calibrate the optical link")
        root.addView(label("On the receiver choose Scan test QRs first. On the sender choose Show test QRs. Keep the phones in the same position throughout the roughly 35-second test.\n\nThe receiver ranks unique accepted payload bytes per actual display second, including the current hold setting. This measures the optical channel, not fountain recovery speed.", 15f, muted))
        button("Show test QRs · sender") { showCalibration() }
        button("Scan test QRs · receiver") { startCalibrationScan(1) }
        button("Scan result QR · sender") { startCalibrationScan(2) }
        button("Back") { home() }
    }
    private fun startCalibrationScan(purpose: Int) {
        scanPurpose = purpose; wifiMode = false
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) receive(false)
        else permission.launch(Manifest.permission.CAMERA)
    }
    private fun applyCalibration(result: OpticalCalibration.Recommendation) {
        selectedPayload = result.profile.payload; requestedFps = result.profile.fps
        holdEvery = result.profile.holdEvery; calibratedRate = result.bytesPerSecond
        persistOpticalProfile()
    }
    private fun calibrationResult(result: OpticalCalibration.Recommendation) {
        layout(); title("Calibration recommendation")
        root.addView(label("${result.profile.payload} bytes · ${result.profile.fps} FPS · hold every ${result.profile.holdEvery}\n%.1f accepted payload KB/s".format(java.util.Locale.US, result.bytesPerSecond / 1000), 18f))
        val qr = QrDisplayView(this); root.addView(qr, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(label("Have the sender choose Calibrate → Scan result QR. Apply the profile there. Actual file recovery can be slower.", 14f, muted))
        val token = session
        work = worker.submit {
            val bitmap = runCatching { renderQr(result.encode()) }
            main.post { if (token == session) bitmap.fold(qr::setSymbol, { toast("Cannot render result QR") }) }
        }
        button("Apply on this phone") { applyCalibration(result); home() }
        button("Home") { home() }
    }
    private data class CalibrationFrame(val candidate: Int, val sequence: Int, val bitmap: Bitmap?, val dwell: Long)
    private fun showCalibration() {
        layout(); window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = .9f }
        val token = session; val run = java.util.UUID.randomUUID().toString()
        title("Optical calibration · sender")
        val qr = QrDisplayView(this); root.addView(qr, LinearLayout.LayoutParams(-1, 0, 1f))
        val status = label("Preparing test sequence…", 14f, muted); root.addView(status)
        button("Cancel") { home() }
        val queue = ArrayBlockingQueue<CalibrationFrame>(2)
        val holds = holdEvery
        work = worker.submit {
            try {
                OpticalCalibration.candidates.forEachIndexed { index, (payload, fps) ->
                    queue.put(CalibrationFrame(index, -1, renderQr(OpticalCalibration.packet(run, index, -1)), 1_000_000_000))
                    val profile = OpticalProfile(payload, fps, holds)
                    var duration = 0L; var sequence = 0
                    while (duration < 4_000_000_000L && !Thread.currentThread().isInterrupted) {
                        val dwell = profile.dwellNanos(sequence, true)
                        queue.put(CalibrationFrame(index, sequence, renderQr(OpticalCalibration.packet(run, index, sequence)), dwell))
                        duration += dwell; sequence++
                    }
                }
                queue.put(CalibrationFrame(-1, 0, null, 0))
            } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            catch (e: Exception) { main.post { if (token == session) { layout(); title("Calibration failed"); root.addView(label(e.message ?: "Encoding failed")); button("Home") { home() } } } }
        }
        val durations = LongArray(OpticalCalibration.candidates.size)
        var current = -1; var movingStart = 0L; var deadline = Long.MIN_VALUE
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(time: Long) {
                if (token != session) return
                if (time >= deadline) {
                    queue.poll()?.let { frame ->
                        if (frame.candidate != current && current >= 0 && movingStart > 0) {
                            durations[current] = ((time - movingStart) / 1_000_000).coerceAtLeast(1); movingStart = 0
                        }
                        if (frame.candidate == -1) {
                            status.text = "Test finished. Keep the receiver scanning this final QR to calculate its recommendation."
                            work = worker.submit {
                                val end = runCatching { renderQr(OpticalCalibration.finish(run, durations, holds)) }
                                main.post { if (token == session) end.fold(qr::setSymbol, { toast("Cannot render calibration summary") }) }
                            }
                            return
                        }
                        current = frame.candidate
                        if (frame.sequence == 0) movingStart = time
                        val (payload, fps) = OpticalCalibration.candidates[current]
                        qr.setSymbol(frame.bitmap!!); deadline = time + frame.dwell
                        status.text = "Profile ${current + 1}/${durations.size} · $payload bytes · $fps FPS\n${if (frame.sequence < 0) "Stationary acquisition check" else "Moving sample #${frame.sequence}"} · extra hold setting $holds"
                    }
                }
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        frameCallback = callback; Choreographer.getInstance().postFrameCallback(callback)
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
        val profile = opticalProfile()
        val pacer = OpticalPacer(profile)
        val queue = ArrayBlockingQueue<Triple<Int, Bitmap, Boolean>>(2)
        work = worker.submit {
            try {
                var emission = 0L
                var manifestDue = true; var sinceManifest = 0
                while (!Thread.currentThread().isInterrupted && token == session) {
                    val manifest = manifestDue
                    val seq = if (manifest) -1 else LightSchedule.sequence(sender.count, emission++)
                    val text = if (manifest) OpticalV4.manifest(sender) else OpticalV4.data(sender, seq)
                    if (manifest) { manifestDue = false; sinceManifest = 0 } else if (++sinceManifest >= 16) manifestDue = true
                    queue.put(Triple(seq, renderQr(text), manifest))
                }
            } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            catch (e: Exception) { main.post { if (token == session) { toast(e.message ?: "Encoding failed"); home() } } }
        }
        var first = 0L
        var submitted = 0
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(time: Long) {
                if (token != session) return
                if (!paused && pacer.ready(time)) {
                    queue.poll()?.let { (seq, bitmap, manifest) ->
                        qr.setSymbol(bitmap)
                        pacer.presented(time, seq, manifest, first == 0L)
                        if (first == 0L) first = time
                        submitted++
                        val fps = if (time == first) 0.0 else (submitted - 1) * 1e9 / (time - first)
                        status.text = "%s · %d FPS requested · %.1f UI slots/s\n%d B/symbol · hold %s · %d source blocks".format(java.util.Locale.US,
                            if (manifest) "File manifest" else "Symbol $seq", profile.fps, fps, sender.blockSize,
                            if (profile.holdEvery == 0) "off" else "every ${profile.holdEvery} once", sender.count)
                    }
                }
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        frameCallback = callback
        Choreographer.getInstance().postFrameCallback(callback)
    }
    private fun receive(resume: Boolean? = null) {
        if (!wifiMode && scanPurpose == 0 && resume == null && checkpoint.exists()) {
            android.app.AlertDialog.Builder(this).setTitle("Resume saved Light reception?")
                .setMessage("Resume restores recovered blocks. Start new replaces the checkpoint only when valid packets arrive. Use the same file and payload profile on the sender.")
                .setPositiveButton("Resume") { _, _ -> receive(true) }
                .setNegativeButton("Start new") { _, _ -> receive(false) }
                .setNeutralButton("Cancel", null).show()
            return
        }
        if (resume == true && !wifiMode && scanPurpose == 0) {
            layout(); title("Restoring received blocks…")
            button("Cancel restore") { home() }
            val token = session
            work = worker.submit {
                val restored = Receiver()
                val result = runCatching { checkpoint.restore(restored) }
                main.post {
                    if (token != session) return@post
                    result.fold({
                        if (restored.complete != null) {
                            completedCheckpoint = checkpoint.identity(); requestSave(restored.complete!!, restored.filename)
                        } else openReceiver(restored, true)
                    }, { error ->
                        layout(); title("Cannot restore checkpoint")
                        root.addView(label(error.message ?: "Invalid checkpoint", 14f, muted))
                        button("Discard checkpoint") { checkpoint.discard(); home() }
                        button("Home") { home() }
                    })
                }
            }
        } else openReceiver(Receiver(), false)
    }
    private fun openReceiver(receiver: Receiver, resumed: Boolean) {
        verifiedSpeed = null
        completedCheckpoint = null
        layout(); window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val token = session
        title(when (scanPurpose) { 1 -> "Scan calibration sequence"; 2 -> "Scan receiver's result QR"; else -> "Point camera at sender" })
        val view = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
        root.addView(view, LinearLayout.LayoutParams(-1,0,1f))
        val status = label("Waiting for the first QR frame…", 13f, muted).apply { setPadding(0, dp(16), 0, dp(4)) }; root.addView(status)
        button(if (!wifiMode && scanPurpose == 0) "Pause & keep progress" else "Stop") { home() }
        var journalStarted = resumed
        var journalManifest = if (resumed) receiver.manifestPacket else null
        val calibration = OpticalCalibration.Meter()
        val speed = ReceiveSpeed()
        val restoredBytes = receiver.recoveredBytes
        val restoredAccepted = receiver.received
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
                        if (!wifiMode && scanPurpose != 0) {
                            if (text != null) {
                                if (scanPurpose == 1 && (text.startsWith("LLCT:") || text.startsWith("LLCE:"))) {
                                    val score = runCatching { calibration.accept(text) }
                                    main.post {
                                        if (token != session) return@post
                                        score.fold({ recommendation ->
                                            if (recommendation != null) calibrationResult(recommendation)
                                            else status.text = "${calibration.count} unique calibration symbols read. Keep scanning until the result appears."
                                        }, { error -> if (text.startsWith("LLCE:")) { layout(); title("Calibration incomplete"); root.addView(label(error.message ?: "Retry calibration")); button("Home") { home() } } })
                                    }
                                } else if (scanPurpose == 2 && text.startsWith("LLCR:")) {
                                    runCatching { OpticalCalibration.recommendation(text) }.getOrNull()?.let { result ->
                                        main.post { if (token == session) {
                                            layout(); title("Apply calibration?")
                                            root.addView(label("${result.profile.payload} bytes · ${result.profile.fps} FPS\n%.1f accepted payload KB/s".format(java.util.Locale.US, result.bytesPerSecond / 1000)))
                                            button("Apply profile") { applyCalibration(result); home() }
                                            button("Cancel") { home() }
                                        } }
                                    }
                                }
                            }
                            return@setAnalyzer
                        }
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
                            if (!wifiMode && (receiver.lastPacketResult == Receiver.PacketResult.ACCEPTED || receiver.lastPacketResult == Receiver.PacketResult.MANIFEST)) {
                                if (!journalStarted) {
                                    checkpoint.reset(); journalStarted = true
                                    if (!text.startsWith("LL4M:")) receiver.manifestPacket?.let(checkpoint::append)
                                }
                                if (receiver.lastPacketResult == Receiver.PacketResult.ACCEPTED || text != journalManifest) {
                                    checkpoint.append(text)
                                    if (text.startsWith("LL4M:")) journalManifest = text
                                }
                            }
                            when (receiver.lastPacketResult) {
                                Receiver.PacketResult.DUPLICATE -> duplicates++
                                Receiver.PacketResult.INVALID -> rejected++
                                Receiver.PacketResult.FOREIGN -> foreign++
                                else -> Unit
                            }
                        }
                        if (receiver.received > restoredAccepted) speed.start(android.os.SystemClock.elapsedRealtime())
                        val result = receiver.complete
                        val failure = receiver.error
                        if (result != null || failure != null || now - lastUi >= 250) {
                            lastUi = now
                            val elapsed = ((now - start) / 1000.0).coerceAtLeast(0.001)
                            val measuredAt = android.os.SystemClock.elapsedRealtime()
                            val rate = speed.update(measuredAt, receiver.recoveredBytes - restoredBytes, receiver.received - restoredAccepted)
                            val diagnostic = "\n%s · decode %.1f ms · FEC %.1f ms\nDuplicates %d · rejected %d · other session %d\nLast accepted #%d · Tap preview to focus".format(java.util.Locale.US,
                                decoder.resolution, decodeNanos / 1e6 / frames, if (decoded == 0L) 0.0 else fecNanos / 1e6 / decoded,
                                duplicates, rejected, foreign, receiver.lastSequence)
                            val hint = when (receiver.lastPacketResult) {
                                Receiver.PacketResult.NEED_MANIFEST -> "\nWaiting for periodic file manifest…"
                                Receiver.PacketResult.FOREIGN -> "\nDifferent transfer: use the same file and payload profile to resume."
                                else -> if (resumed) "\nResumed · ${receiver.received} total accepted · ${receiver.filename}" else "\nProgress checkpointed privately"
                            }
                            val message = rate + "\n" + TransferStatus.receiving(receiver.progress, receiver.solved, receiver.received - restoredAccepted, frames, decoded, elapsed) + diagnostic + hint
                            val finishedRate = if (result != null) speed.verified(measuredAt, result.size - restoredBytes).let { if (resumed) it.replace("Verified transfer", "Verified remaining transfer") else it } else null
                            main.post {
                                if (token != session) return@post
                                status.text = message
                                if (failure != null) { layout(); title(failure); button("RETRY") { receive() }; button("HOME") { home() } }
                                else if (result != null) {
                                    verifiedSpeed = finishedRate
                                    completedCheckpoint = checkpoint.identity()
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
