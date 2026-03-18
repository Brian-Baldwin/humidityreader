package com.example.humidityreader

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import androidx.exifinterface.media.ExifInterface
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.humidityreader.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null

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
            viewBinding.resultText.visibility = View.GONE
            viewBinding.ibClose.visibility = View.GONE
            viewBinding.viewFinder.visibility = View.VISIBLE
            viewBinding.imageCaptureButton.visibility = View.VISIBLE
            viewBinding.previewOverlay.visibility = View.VISIBLE
            viewBinding.heightIndicatorLine.visibility = View.GONE
            viewBinding.main.background = null
        }
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
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
                    val sourceBitmap = BitmapFactory.decodeStream(inputStream) ?: return
                    
                    val exif = contentResolver.openInputStream(savedUri)?.use { ExifInterface(it) }
                    val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                    val matrix = Matrix()
                    when (orientation) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                    }
                    val rotatedBitmap = Bitmap.createBitmap(sourceBitmap, 0, 0, sourceBitmap.width, sourceBitmap.height, matrix, true)

                    // Scaling for analysis
                    val targetSize = 1000f
                    val analysisScale = targetSize / Math.max(rotatedBitmap.width, rotatedBitmap.height)
                    val bitmap = Bitmap.createScaledBitmap(
                        rotatedBitmap,
                        (rotatedBitmap.width * analysisScale).toInt(),
                        (rotatedBitmap.height * analysisScale).toInt(),
                        true
                    )

                    val bWidth = bitmap.width.toFloat()
                    val bHeight = bitmap.height.toFloat()
                    val rootView = viewBinding.root
                    val vWidth = rootView.width.toFloat()
                    val vHeight = rootView.height.toFloat()

                    // Coordinate mapping: Bitmap to View space (FIT_CENTER)
                    val displayScale = Math.min(vWidth / bWidth, vHeight / bHeight)
                    val offsetX = (vWidth - bWidth * displayScale) / 2f
                    val offsetY = (vHeight - bHeight * displayScale) / 2f

                    fun vToBX(vx: Float) = ((vx - offsetX) / displayScale).toInt().coerceIn(0, (bWidth - 1).toInt())
                    fun vToBY(vy: Float) = ((vy - offsetY) / displayScale).toInt().coerceIn(0, (bHeight - 1).toInt())

                    val borderView = viewBinding.borderView
                    val bw = borderView.width.toFloat()
                    val bh = borderView.height.toFloat()
                    
                    // Shorter guide SVG coordinates: 60 to 210 vertically, 20 to 150 horizontally
                    val guideTopV = borderView.top + bh * (60f / 270f)
                    val guideBottomV = borderView.top + bh * (210f / 270f)
                    val guideLeftV = borderView.left + bw * (20f / 170f)
                    val guideRightV = borderView.left + bw * (150f / 170f)

                    // Mapping guide boundaries to Bitmap space
                    val scanBottom = vToBY(guideBottomV)
                    val scanTop = vToBY(guideTopV)
                    val scanLeft = vToBX(guideLeftV)
                    val scanRight = vToBX(guideRightV)

                    // Search area strictly within the narrow center column of the guide
                    val searchLeft = vToBX(borderView.left + bw * (75f / 170f))
                    val searchRight = vToBX(borderView.left + bw * (95f / 170f))

                    var minY = -1
                    var maxY = -1
                    val threshold = 60.0 

                    for (y in scanTop..scanBottom) {
                        var darkPixels = 0
                        val rowW = searchRight - searchLeft + 1
                        if (rowW <= 0) continue
                        
                        for (x in searchLeft..searchRight) {
                            if (x < scanLeft || x > scanRight) continue
                            
                            val p = bitmap.getPixel(x, y)
                            val lum = 0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)
                            if (lum < threshold) darkPixels++
                        }
                        
                        if (darkPixels > rowW * 0.1) {
                            if (minY == -1) minY = y
                            maxY = y
                        }
                    }

                    // Calculate height of the black area relative to the guide box height (60 to 210)
                    val totalGuideH = (scanBottom - scanTop).toDouble()
                    val relativeHeight = if (minY != -1 && maxY != -1 && totalGuideH > 0) {
                        ((maxY - minY).toDouble() / totalGuideH).coerceIn(0.0, 1.0)
                    } else 0.0
                    
                    val isSafe = relativeHeight <= 0.4
                    val statusColor = if (isSafe) Color.GREEN else Color.RED
                    
                    // Create surround drawable with thickness = 10% of display width
                    val strokeWidth = (vWidth * 0.1f).toInt()
                    val surround = GradientDrawable().apply {
                        setStroke(strokeWidth, statusColor)
                        setColor(Color.TRANSPARENT)
                    }
                    viewBinding.main.background = surround
                    
                    viewBinding.resultText.apply {
                        text = if (isSafe) "SAFE" else "UNSAFE"
                        setTextColor(statusColor)
                        visibility = View.VISIBLE
                    }

                    if (minY != -1) {
                        val indicatorYV = minY * displayScale + offsetY
                        viewBinding.heightIndicatorLine.visibility = View.VISIBLE
                        viewBinding.heightIndicatorLine.y = indicatorYV
                    } else {
                        viewBinding.heightIndicatorLine.visibility = View.GONE
                    }
                    
                    val guideWidthV = guideRightV - guideLeftV
                    viewBinding.heightIndicatorLine.layoutParams = viewBinding.heightIndicatorLine.layoutParams.apply {
                        width = guideWidthV.toInt()
                    }
                    viewBinding.heightIndicatorLine.x = guideLeftV
                }
            }
        )
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

            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .build()
                .also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )

                viewBinding.borderView.post {
                    val borderWidth = viewBinding.borderView.width.toFloat()
                    val borderHeight = viewBinding.borderView.height.toFloat()
                    val guideCenterX = viewBinding.borderView.left + borderWidth * (85f / 170f)
                    val guideCenterY = viewBinding.borderView.top + borderHeight * (135f / 270f)

                    val factory = SurfaceOrientedMeteringPointFactory(
                        viewBinding.viewFinder.width.toFloat(),
                        viewBinding.viewFinder.height.toFloat()
                    )
                    val point = factory.createPoint(guideCenterX, guideCenterY)

                    val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AWB)
                        .build()

                    camera.cameraControl.startFocusAndMetering(action)
                }

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
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
                Toast.makeText(this, "Permissions not granted.", Toast.LENGTH_SHORT).show()
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
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
