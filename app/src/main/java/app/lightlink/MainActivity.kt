package app.lightlink

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    private lateinit var cameraManager: CameraManager
    private var flashCameraId: String? = null
    private var transmitJob: Job? = null
    private var cameraExecutor: ExecutorService? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var receiving = false
    private var minLuma = 255.0
    private var maxLuma = 0.0
    private var currentSignal = false
    private var previousSignal = false
    private var frameStartAt = 0L
    private var nextBitIndex = 0
    private var lastDecodedFrameAt = 0L
    private val frameBits = ArrayList<Boolean>()
    private val scope = CoroutineScope(Dispatchers.Main)
    private lateinit var status: TextView
    private lateinit var received: TextView
    private lateinit var messageInput: EditText
    private lateinit var preview: PreviewView

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCameraReceiver()
        else setStatus("يلزم إذن الكاميرا لاستقبال الضوء.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        flashCameraId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        buildUi()
        setStatus(if (flashCameraId == null) "لم يُعثر على فلاش متاح للإرسال."
        else "جاهز. استخدم هاتفًا ثانيًا للاختبار.")
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
        }
        scroll.addView(root)
        setContentView(scroll)
        root.addView(TextView(this).apply {
            text = "LightLink"
            textSize = 30f
            setTextColor(0xFF35D6C5.toInt())
            gravity = Gravity.CENTER
        }, matchWrap())
        root.addView(label("اتصال ضوئي بين هاتفين — إصدار تجريبي"))
        messageInput = EditText(this).apply {
            hint = "اكتب رسالة قصيرة (حد أقصى 48 بايت UTF-8)"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF9CAAB5.toInt())
            minLines = 2
            gravity = Gravity.TOP
        }
        root.addView(messageInput, matchWrap())
        root.addView(button("إرسال عبر الفلاش") { transmitMessage() }, matchWrap())
        root.addView(label("للاستقبال: وجّه الكاميرا الخلفية نحو فلاش الهاتف المُرسل، ثم اضغط بدء الاستقبال."))
        preview = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        root.addView(preview, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(220)
        ))
        root.addView(button("بدء الاستقبال") {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startCameraReceiver()
            } else cameraPermission.launch(Manifest.permission.CAMERA)
        }, matchWrap())
        root.addView(button("إيقاف الاستقبال") { stopCameraReceiver() }, matchWrap())
        status = label("الحالة: تهيئة…")
        root.addView(status)
        received = label("الرسالة المستلمة: —")
        root.addView(received)
        root.addView(label("تنبيه: نموذج أولي غير مختبر على هاتفين. قد يفشل الاستقبال بسبب توقيت الكاميرا والتعريض والإضاءة. لا تستخدمه لنقل معلومات حساسة."))
    }

    private fun transmitMessage() {
        val id = flashCameraId
        if (id == null) {
            setStatus("هذا الهاتف لا يوفّر فلاشًا يمكن التحكم به.")
            return
        }
        val bits = try { LightProtocol.encode(messageInput.text.toString()) }
        catch (e: IllegalArgumentException) {
            setStatus(e.message ?: "الرسالة غير صالحة.")
            return
        }
        if (transmitJob?.isActive == true) return
        transmitJob = scope.launch {
            try {
                setStatus("سيبدأ الإرسال بعد 3 ثوانٍ. وجّه الكاميرا نحوي.")
                delay(3000)
                cameraManager.setTorchMode(id, false)
                delay(700)
                for (bit in bits) {
                    cameraManager.setTorchMode(id, bit)
                    delay(250)
                }
                cameraManager.setTorchMode(id, false)
                setStatus("انتهى إرسال الإطار. لم يتم تأكيد الاستلام بعد.")
            } catch (e: Exception) {
                setStatus("تعذّر الإرسال: ${e.localizedMessage ?: "خطأ في الفلاش"}")
            } finally {
                runCatching { cameraManager.setTorchMode(id, false) }
            }
        }
    }

    private fun startCameraReceiver() {
        if (receiving) return
        receiving = true
        frameBits.clear()
        frameStartAt = 0L
        nextBitIndex = 0
        previousSignal = false
        minLuma = 255.0
        maxLuma = 0.0
        lastDecodedFrameAt = 0L
        cameraExecutor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
                val previewUseCase = Preview.Builder().build().also {
                    it.setSurfaceProvider(preview.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(cameraExecutor!!) { image -> analyzeFrame(image) }
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    this, androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA,
                    previewUseCase, analysis
                )
                setStatus("الاستقبال يعمل. وجّه الكاميرا نحو الفلاش من مسافة قصيرة.")
            } catch (e: Exception) {
                receiving = false
                setStatus("تعذّر تشغيل الكاميرا: ${e.localizedMessage}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(image: ImageProxy) {
        try {
            val plane = image.planes.firstOrNull() ?: return
            val buffer = plane.buffer
            val width = image.width
            val height = image.height
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            var sum = 0L
            var count = 0
            var y = height / 3
            while (y < height * 2 / 3) {
                var x = width / 3
                while (x < width * 2 / 3) {
                    val index = y * rowStride + x * pixelStride
                    if (index < buffer.limit()) {
                        sum += buffer.get(index).toInt() and 0xFF
                        count++
                    }
                    x += 4
                }
                y += 4
            }
            if (count == 0) return
            val luma = sum.toDouble() / count
            minLuma = min(minLuma, luma)
            maxLuma = max(maxLuma, luma)
            val range = maxLuma - minLuma
            if (range > 12.0) currentSignal = luma > minLuma + range * 0.55
            val now = SystemClock.elapsedRealtime()

            if (receiving && range > 12.0) {
                if (frameStartAt == 0L && currentSignal && !previousSignal &&
                    now - lastDecodedFrameAt > 1200L) {
                    frameStartAt = now
                    nextBitIndex = 0
                    frameBits.clear()
                }
                if (frameStartAt != 0L) {
                    val dueIndex = ((now - frameStartAt - 125L) / 250L).toInt()
                    if (dueIndex >= nextBitIndex && dueIndex >= 0) {
                        if (dueIndex - nextBitIndex > 1) {
                            frameStartAt = 0L
                            frameBits.clear()
                            nextBitIndex = 0
                        } else {
                            frameBits.add(currentSignal)
                            nextBitIndex++
                            val expectedBits = expectedFrameBitCount(frameBits)
                            if (expectedBits > 0 && frameBits.size >= expectedBits) {
                                val decoded = LightProtocol.decode(frameBits.toBooleanArray())
                                runOnUiThread {
                                    if (decoded.isSuccess) {
                                        received.text = "الرسالة المستلمة: ${decoded.getOrThrow()}"
                                        setStatus("تم فك إطار صالح والتحقق من CRC. يلزم تكرار الاختبار على أجهزة فعلية.")
                                    } else {
                                        setStatus("تم رصد إطار ضوئي لكن فشل التحقق: ${decoded.exceptionOrNull()?.message}")
                                    }
                                }
                                lastDecodedFrameAt = now
                                frameStartAt = 0L
                                frameBits.clear()
                                nextBitIndex = 0
                            } else if (frameBits.size >= 24) {
                                val header = bitsToBytesLocal(frameBits.take(24).toBooleanArray())
                                if (header.size < 3 || header[0] != 0xD3 || header[1] != 0x91 ||
                                    header[2] !in 1..LightProtocol.MAX_PAYLOAD_BYTES) {
                                    frameStartAt = 0L
                                    frameBits.clear()
                                    nextBitIndex = 0
                                }
                            }
                        }
                    }
                }
            }
            previousSignal = currentSignal
        } catch (_: Exception) {
            // Camera frames may be dropped during reconfiguration.
        } finally {
            image.close()
        }
    }

    private fun expectedFrameBitCount(bits: List<Boolean>): Int {
        if (bits.size < 24) return -1
        val header = bitsToBytesLocal(bits.take(24).toBooleanArray())
        if (header.size < 3 || header[0] != 0xD3 || header[1] != 0x91) return -1
        val length = header[2]
        if (length !in 1..LightProtocol.MAX_PAYLOAD_BYTES) return -1
        return (3 + length + 1) * 8
    }

    private fun bitsToBytesLocal(bits: BooleanArray): IntArray {
        if (bits.size % 8 != 0) return intArrayOf()
        return IntArray(bits.size / 8) { index ->
            var value = 0
            for (i in 0 until 8) value = (value shl 1) or if (bits[index * 8 + i]) 1 else 0
            value
        }
    }

    private fun stopCameraReceiver() {
        receiving = false
        runCatching { cameraProvider?.unbindAll() }
        cameraExecutor?.shutdown()
        cameraExecutor = null
        frameStartAt = 0L
        frameBits.clear()
        setStatus("تم إيقاف الاستقبال.")
    }

    private fun setStatus(text: String) {
        if (::status.isInitialized) status.text = "الحالة: $text"
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(0xFFE5EDF2.toInt())
        setPadding(0, dp(10), 0, dp(10))
    }

    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { action() }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(8); bottomMargin = dp(4) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        transmitJob?.cancel()
        runCatching { flashCameraId?.let { cameraManager.setTorchMode(it, false) } }
        stopCameraReceiver()
        scope.cancel()
        super.onDestroy()
    }
}
