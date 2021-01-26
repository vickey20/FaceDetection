package com.vikram.facedetectioncamerax

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.Image
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.vikram.facedetectioncamerax.databinding.ActivityMainBinding
import java.lang.Exception
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), ImageCapturedListener {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var imageAnalyzer: ImageAnalysis? = null
    private var preview: Preview? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(baseContext)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            preview = Preview.Builder().build()
                    .also { it.setSurfaceProvider(binding.preview.surfaceProvider) }

            imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, FaceDetectAnalyzer(this))
                    }

            val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                    .build()

            imageCapture = ImageCapture.Builder()
                    .setTargetRotation(binding.preview.display.rotation)
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
        imageAnalyzer?.clearAnalyzer()
    }

    private fun takePhoto() {
        imageCapture?.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            @SuppressLint("UnsafeExperimentalUsageError")
            override fun onCaptureSuccess(image: ImageProxy) {
                super.onCaptureSuccess(image)
                Log.d(TAG, "takePhoto success: image: $image")

                image.image?.let {
                    val bitmap = it.imageToBitmap()
                    runOnUiThread {
                        binding.image.setImageBitmap(bitmap)
                        binding.progressCircular.visibility = View.GONE
                        cameraProvider?.unbindAll()
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                super.onError(exception)
                Log.d(TAG, "takePhoto error: ${exception.message}", exception)
            }
        })
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

fun Image.imageToBitmap(): Bitmap? {
    val buffer = this.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)

    /*val encoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Base64.getEncoder().encodeToString(bytes)
    } else {
        TODO("VERSION.SDK_INT < O")
    }
    Log.d("MainActivity", "encoded string: $encoded")*/

    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, null)
}