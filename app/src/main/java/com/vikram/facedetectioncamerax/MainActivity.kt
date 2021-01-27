package com.vikram.facedetectioncamerax

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.*
import android.media.Image
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.vikram.facedetectioncamerax.databinding.ActivityMainBinding
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), ImageCapturedListener {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var imageAnalyzer = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor, FaceDetectAnalyzer(this))
            }

    private var preview: Preview? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture = ImageCapture.Builder()
            .setTargetRotation(Surface.ROTATION_0)
            .build()

    private var rotation = Surface.ROTATION_270

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }

    private val orientationEventListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) {
                    return
                }

                rotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }

                imageCapture.targetRotation = rotation
            }
        }
    }

    private fun startCamera() {
        orientationEventListener.enable()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(baseContext)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            preview = Preview.Builder().build()
                    .also { it.setSurfaceProvider(binding.preview.surfaceProvider) }

            val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                    .build()

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                        this,
                        cameraSelector,
                        preview,
                        imageAnalyzer,
                        imageCapture)
            } catch (e: Exception) {
                Log.e(TAG, "Use case binding failed: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(baseContext))
    }

    override fun onResume() {
        super.onResume()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions()
        }
    }

    override fun onPause() {
        super.onPause()
        cameraProvider?.unbindAll()
        orientationEventListener.disable()
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        requestPermissions(REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Permissions not granted", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onFaceDetected() {
        takePhoto()
    }

    private fun takePhoto() {
        imageCapture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            @SuppressLint("UnsafeExperimentalUsageError")
            override fun onCaptureSuccess(imageProxy: ImageProxy) {
                super.onCaptureSuccess(imageProxy)
                Log.d(TAG, "takePhoto success: image rotation: ${imageProxy.imageInfo.rotationDegrees}")
                imageProxy.image?.let {
                    processAndDisplayImage(imageProxy.imageInfo.rotationDegrees.toFloat(), it)
                    imageAnalyzer.clearAnalyzer()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                super.onError(exception)
                Log.d(TAG, "takePhoto error: ${exception.message}", exception)
            }
        })
    }

    private fun processAndDisplayImage(rotation: Float, image: Image) {
        Log.d(TAG, "rotation: $rotation")
        val bitmap = image
                .imageToBitmap()
                .rotateImage(rotation)

        runOnUiThread {
            binding.image.setImageBitmap(bitmap)
            binding.progressCircular.visibility = View.GONE
            cameraProvider?.unbindAll()
        }
    }
}

class FaceDetectAnalyzer(private val listener: ImageCapturedListener): ImageAnalysis.Analyzer {
    private val options = FaceDetectorOptions.Builder()
            .enableTracking()
            .build()

    private val detector = FaceDetection.getClient(options)

    @SuppressLint("UnsafeExperimentalUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        mediaImage?.let {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            detector.process(image)
                    .addOnSuccessListener { results ->
                        if (results.size > 0) {
                            Log.d(TAG, "onSuccess: number of faces: ${results.size}")
                            listener.onFaceDetected()
                        }
                    }.addOnFailureListener { exception ->
                        Log.e(TAG, "onFailure: ${exception.message}", exception)
                    }.addOnCompleteListener {
                        imageProxy.close()
                    }
        }
    }
    companion object {
        private const val TAG = "FaceDetectAnalyzer"
    }
}

interface ImageCapturedListener {
    fun onFaceDetected()
}

fun Image.imageToBitmap(): Bitmap {
    val buffer = this.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)

    val encoded = Base64.encodeToString(bytes, Base64.DEFAULT)
    Log.d("MainActivity", "encoded string: $encoded")

    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, null)
}

fun Bitmap.rotateImage(degree: Float): Bitmap? {
    val matrix = Matrix().apply {
        postRotate(degree)
    }

    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, false)
}