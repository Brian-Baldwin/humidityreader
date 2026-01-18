package com.example.humidityreader

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.humidityreader.databinding.ActivityMainBinding
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var imageAnalyzer: ImageAnalysis? = null

    private val humidityMap = listOf(
        Color.rgb(255, 0, 0) to 0.0,
        Color.rgb(255, 255, 0) to 50.0,
        Color.rgb(0, 0, 255) to 100.0
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS
            )
        }

        viewBinding.imageCaptureButton.setOnClickListener { takePhoto() }
        viewBinding.ibClose.setOnClickListener {
            viewBinding.ivCapture.visibility = View.GONE
            viewBinding.humidityResultText.visibility = View.GONE
            viewBinding.ibClose.visibility = View.GONE
            viewBinding.viewFinder.visibility = View.VISIBLE
            viewBinding.imageCaptureButton.visibility = View.VISIBLE
            viewBinding.previewOverlay.visibility = View.VISIBLE
            viewBinding.redDotIndicator.visibility = View.GONE
        }

        viewBinding.rgbToggle.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                viewBinding.rgbText.visibility = View.VISIBLE
                viewBinding.redDotIndicator.visibility = View.VISIBLE
                startCamera()
            } else {
                viewBinding.rgbText.visibility = View.GONE
                viewBinding.redDotIndicator.visibility = View.GONE
                startCamera()
            }
        }
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(
                contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )
            .build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = output.savedUri ?: return

                    viewBinding.ivCapture.setImageURI(savedUri)
                    viewBinding.ivCapture.visibility = View.VISIBLE
                    viewBinding.ibClose.visibility = View.VISIBLE
                    viewBinding.viewFinder.visibility = View.GONE
                    viewBinding.imageCaptureButton.visibility = View.GONE
                    viewBinding.previewOverlay.visibility = View.GONE

                    val inputStream = contentResolver.openInputStream(savedUri)
                    val bitmap = BitmapFactory.decodeStream(inputStream)

                    val borderView = viewBinding.borderView
                    val borderLeft = (borderView.left / viewBinding.root.width.toFloat() * bitmap.width).toInt()
                    val borderTop = (borderView.top / viewBinding.root.height.toFloat() * bitmap.height).toInt()
                    val borderRight = (borderView.right / viewBinding.root.width.toFloat() * bitmap.width).toInt()
                    val borderBottom = (borderView.bottom / viewBinding.root.height.toFloat() * bitmap.height).toInt()

                    val calibrationAreaWidth = (borderRight - borderLeft) * 0.1f
                    val searchLeft = (borderLeft + calibrationAreaWidth).toInt()

                    var maxRedness = -1.0
                    var mostRedColor = Color.BLACK

                    for (x in searchLeft until borderRight) {
                        for (y in borderTop until borderBottom) {
                            val pixel = bitmap.getPixel(x, y)
                            val red = Color.red(pixel)
                            val green = Color.green(pixel)
                            val blue = Color.blue(pixel)

                            val redness = red.toDouble() / (green + blue + 1)

                            if (redness > maxRedness) {
                                maxRedness = redness
                                mostRedColor = pixel
                            }
                        }
                    }

                    val closestHumidity = getClosestHumidity(mostRedColor)
                    viewBinding.humidityResultText.text = "Humidity: ${closestHumidity.roundToInt()}%"
                    viewBinding.humidityResultText.visibility = View.VISIBLE
                }
            }
        )
    }

    private fun getClosestHumidity(color: Int): Double {
        var minDistance = Double.MAX_VALUE
        var closestHumidity = 0.0

        for ((mapColor, humidity) in humidityMap) {
            val distance = colorDistance(color, mapColor)
            if (distance < minDistance) {
                minDistance = distance
                closestHumidity = humidity
            }
        }
        return closestHumidity
    }

    private fun colorDistance(c1: Int, c2: Int): Double {
        val r1 = Color.red(c1)
        val g1 = Color.green(c1)
        val b1 = Color.blue(c1)
        val r2 = Color.red(c2)
        val g2 = Color.green(c2)
        val b2 = Color.blue(c2)
        return sqrt((r1 - r2).toDouble().pow(2) + (g1 - g2).toDouble().pow(2) + (b1 - b2).toDouble().pow(2))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val metrics = DisplayMetrics().also { viewBinding.viewFinder.display.getRealMetrics(it) }
            val screenAspectRatio = AspectRatio.RATIO_16_9

            val preview = Preview.Builder()
                .setTargetAspectRatio(screenAspectRatio)
                .build()
                .also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder()
                .setTargetAspectRatio(screenAspectRatio)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            if (viewBinding.rgbToggle.isChecked) {
                imageAnalyzer?.setAnalyzer(cameraExecutor, RgbAnalyzer { rgb, point ->
                    runOnUiThread {
                        viewBinding.rgbText.text = "RGB: $rgb"
                        viewBinding.redDotIndicator.x = point.x.toFloat() - viewBinding.redDotIndicator.width / 2
                        viewBinding.redDotIndicator.y = point.y.toFloat() - viewBinding.redDotIndicator.height / 2
                    }
                })
            }

            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, imageAnalyzer
                )

                val borderTopLeftX = viewBinding.borderView.left.toFloat()
                val borderTopLeftY = viewBinding.borderView.top.toFloat()

                val factory = SurfaceOrientedMeteringPointFactory(
                    viewBinding.viewFinder.width.toFloat(),
                    viewBinding.viewFinder.height.toFloat()
                )
                val point = factory.createPoint(borderTopLeftX * 1.1f, borderTopLeftY * 1.1f)

                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AWB)
                    .build()

                camera.cameraControl.startFocusAndMetering(action)

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private inner class RgbAnalyzer(private val listener: (String, android.graphics.Point) -> Unit) : ImageAnalysis.Analyzer {

        private fun ImageProxy.toBitmap(): Bitmap? {
            val yBuffer = planes[0].buffer
            val uBuffer = planes[1].buffer
            val vBuffer = planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)

            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, this.width, this.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 50, out)
            val imageBytes = out.toByteArray()
            return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        }

        override fun analyze(image: ImageProxy) {
            val bitmap = image.toBitmap() ?: run {
                image.close()
                return
            }

            val borderView = viewBinding.borderView
            val rootView = viewBinding.root

            if (rootView.width == 0 || rootView.height == 0) {
                image.close()
                return
            }

            val borderLeft = (borderView.left / rootView.width.toFloat() * bitmap.width).toInt()
            val borderTop = (borderView.top / rootView.height.toFloat() * bitmap.height).toInt()
            val borderRight = (borderView.right / rootView.width.toFloat() * bitmap.width).toInt()
            val borderBottom = (borderView.bottom / rootView.height.toFloat() * bitmap.height).toInt()

            val calibrationAreaWidth = (borderRight - borderLeft) * 0.1f
            val searchLeft = (borderLeft + calibrationAreaWidth).toInt()

            val clippedSearchLeft = searchLeft.coerceIn(0, bitmap.width)
            val clippedBorderRight = borderRight.coerceIn(0, bitmap.width)
            val clippedBorderTop = borderTop.coerceIn(0, bitmap.height)
            val clippedBorderBottom = borderBottom.coerceIn(0, bitmap.height)

            var maxRedness = -1.0
            var mostRedColor = Color.BLACK
            var mostRedPoint = android.graphics.Point(0, 0)

            for (x in clippedSearchLeft until clippedBorderRight) {
                for (y in clippedBorderTop until clippedBorderBottom) {
                    val pixel = bitmap.getPixel(x, y)
                    val red = Color.red(pixel)
                    val green = Color.green(pixel)
                    val blue = Color.blue(pixel)

                    val redness = red.toDouble() / (green + blue + 1)

                    if (redness > maxRedness) {
                        maxRedness = redness
                        mostRedColor = pixel
                        mostRedPoint = android.graphics.Point(x, y)
                    }
                }
            }

            val viewX = (mostRedPoint.x / bitmap.width.toFloat() * rootView.width)
            val viewY = (mostRedPoint.y / bitmap.height.toFloat() * rootView.height)

            listener("(${Color.red(mostRedColor)}, ${Color.green(mostRedColor)}, ${Color.blue(mostRedColor)})", android.graphics.Point(viewX.toInt(), viewY.toInt()))
            image.close()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS =
            mutableListOf(
                Manifest.permission.CAMERA
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }
}