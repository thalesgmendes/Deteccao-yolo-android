package com.example.myapplication4

import android.content.ContentValues
import android.content.ContentValues.TAG
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.icu.text.SimpleDateFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.myapplication.databinding.ActivityCameraPreviewBinding
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraPreviewActivity : AppCompatActivity(), ImageProcessor.DetectorListener {

    private lateinit var detector: ImageProcessor
    private val processingScope = CoroutineScope(Dispatchers.IO)

    private lateinit var binding: ActivityCameraPreviewBinding
    private lateinit var cameraProviderFeature: ListenableFuture<ProcessCameraProvider>
    private lateinit var cameraSelector: CameraSelector
    private lateinit var imgCaptureExecutor: ExecutorService
    private var imageAnalysis: ImageAnalysis? = null

    private val handler = Handler(Looper.getMainLooper())
    private var isProcessingFrame = false

    private var lastCapturedBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraProviderFeature = ProcessCameraProvider.getInstance(this)
        cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        imgCaptureExecutor = Executors.newSingleThreadExecutor()

        detector = ImageProcessor(baseContext, this)
        detector.setup()

        binding.btnPreview.setOnClickListener {
            startCamera()
            binding.btnPreview.isEnabled = false
        }

        binding.btnOut.setOnClickListener {
            sairCamera()
        }
    }

    private fun startCamera() {
        cameraProviderFeature.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFeature.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
                }

            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(imgCaptureExecutor, ImageAnalysis.Analyzer { imageProxy ->
                        captureFrame(imageProxy)
                    })
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
            } catch (exc: Exception) {
                Log.e(TAG, "Erro ao iniciar a câmera", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureFrame(imageProxy: ImageProxy) {
        if (isProcessingFrame) {
            imageProxy.close()
            return
        }
        isProcessingFrame = true
        val bitmap = when (imageProxy.format) {
            ImageFormat.YUV_420_888 -> imageProxyToBitmapYUV(imageProxy)
            ImageFormat.JPEG -> imageProxyToBitmapJPEG(imageProxy)
            else -> {
                Log.e("captureFrame", "Formato de imagem não suportado: ${imageProxy.format}")
                null
            }
        }
        imageProxy.close()
        bitmap?.let {
            lastCapturedBitmap = it
            processingScope.launch(Dispatchers.IO) {
                detector.detect(it)
            }
        } ?: Log.e("captureFrame", "Falha ao converter ImageProxy para Bitmap")

        handler.postDelayed({ isProcessingFrame = false }, 300)
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>) {
        val image = lastCapturedBitmap
        if (image == null) {
            Log.e("CameraPreview", "Nenhuma imagem capturada para processar!")
            return
        }
        processingScope.launch {
            val croppedBitmaps = cropBoundingBoxes(image, boundingBoxes)
            for ((index, croppedBitmap) in croppedBitmaps.withIndex()) {
                saveBitmapToFile(croppedBitmap, "detected_${System.currentTimeMillis()}_$index.png")
            }
        }
    }

    override fun onEmptyDetect() {
        Log.i("Detector", "Nenhum objeto detectado.")
    }

    private fun imageProxyToBitmapYUV(image: ImageProxy): Bitmap? {
        return try {
            val planes = image.planes
            if (planes.size < 3) {
                Log.e("imageProxyToBitmapYUV", "Formato inesperado: menos de 3 planos disponíveis")
                return null
            }

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

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, out)
            val imageBytes = out.toByteArray()

            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        } catch (e: Exception) {
            Log.e("imageProxyToBitmapYUV", "Erro ao converter ImageProxy para Bitmap: ${e.message}")
            null
        }
    }

    private fun imageProxyToBitmapJPEG(image: ImageProxy): Bitmap? {
        return try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.e("imageProxyToBitmapJPEG", "Erro ao converter ImageProxy para Bitmap: ${e.message}")
            null
        }
    }

    private fun cropBoundingBoxes(bitmap: Bitmap, boxes: List<BoundingBox>): List<Bitmap> {
        val croppedBitmaps = mutableListOf<Bitmap>()

        for (box in boxes) {

            val left = (box.x1 * bitmap.width).toInt()
            val top = (box.y1 * bitmap.height).toInt()
            val right = (box.x2 * bitmap.width).toInt()
            val bottom = (box.y2 * bitmap.height).toInt()
            val width = right - left
            val height = bottom - top
            if (width > 0 && height > 0 && left >= 0 && top >= 0 && right <= bitmap.width && bottom <= bitmap.height) {
                val croppedBitmap = Bitmap.createBitmap(bitmap, left, top, width, height)
                croppedBitmaps.add(croppedBitmap)
            } else {
                Log.e("CropBoundingBoxes", "Coordenadas inválidas para a caixa: $box")
            }
        }

        return croppedBitmaps
    }

    private fun saveBitmapToFile(bitmap: Bitmap, fileName: String) {
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(System.currentTimeMillis()) + ".jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        val contentResolver = contentResolver
        val imageUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        imageUri?.let {
            try {
                contentResolver.openOutputStream(it)?.use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                    Log.d("saveBitmapToFile", "Imagem salva com sucesso: $it")
                }
            } catch (e: IOException) {
                Log.e("saveBitmapToFile", "Erro ao salvar a imagem: ${e.message}")
            }
        } ?: run {
            Log.e("saveBitmapToFile", "Falha ao obter URI para salvar a imagem.")
        }
    }

    private fun sairCamera() {
        val intentSair = Intent(this, MainActivity::class.java)
        startActivity(intentSair)
    }

    override fun onPause() {
        super.onPause()
        cameraProviderFeature.get()?.unbindAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        imgCaptureExecutor.shutdown()
        cameraProviderFeature.get()?.unbindAll()
    }
}
