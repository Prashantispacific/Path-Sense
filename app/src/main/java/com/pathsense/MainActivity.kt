package com.pathsense

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Size
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.pathsense.databinding.ActivityMainBinding
import org.opencv.android.OpenCVLoader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var imuTracker: ImuTracker
    private lateinit var obstacleEngine: HomographyObstacleEngine

    private var isBackgroundShieldActive = false
    private var cameraProvider: ProcessCameraProvider? = null

    // Live tuning state
    private var currentThreshold = 0.45f
    private var currentDebounce = 3

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        if (cameraGranted) {
            startCameraPreview()
        } else {
            Toast.makeText(this, "Camera permission is required for PathSense", Toast.LENGTH_LONG).show()
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            toggleBackgroundMode()
        } else {
            Toast.makeText(this, "Overlay permission is required for Background Shield", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize OpenCV Native C++ Library via Maven wrapper
        initOpenCV()

        cameraExecutor = Executors.newSingleThreadExecutor()
        imuTracker = ImuTracker(this)
        obstacleEngine = HomographyObstacleEngine(imuTracker)

        setupControls()
        checkAndRequestPermissions()
    }

    private fun initOpenCV() {
        // Modern OpenCV 4.9.0 loads native shared libraries locally
        val loaded = try {
            OpenCVLoader.initLocal()
        } catch (e: NoSuchMethodError) {
            @Suppress("DEPRECATION")
            OpenCVLoader.initDebug()
        }

        if (!loaded) {
            Toast.makeText(this, "Failed to load OpenCV native binaries", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupControls() {
        // Threshold SeekBar (0.20 to 1.00) -> progress 0 to 80 (offset 20)
        binding.seekBarThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentThreshold = (progress + 20) / 100f
                binding.tvThresholdLabel.text = getString(R.string.anomaly_threshold_label, currentThreshold)
                obstacleEngine.anomalyThreshold = currentThreshold
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Debounce SeekBar (1 to 6 frames) -> progress 0 to 5 (offset 1)
        binding.seekBarDebounce.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentDebounce = progress + 1
                binding.tvDebounceLabel.text = getString(R.string.debounce_frames_label, currentDebounce)
                obstacleEngine.debounceFrames = currentDebounce
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnToggleBackground.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                overlayPermissionLauncher.launch(intent)
            } else {
                toggleBackgroundMode()
            }
        }
    }

    private fun toggleBackgroundMode() {
        isBackgroundShieldActive = !isBackgroundShieldActive

        if (isBackgroundShieldActive) {
            // Unbind camera from MainActivity to transfer control to ForegroundService
            cameraProvider?.unbindAll()
            imuTracker.stop()

            val serviceIntent = Intent(this, EdgeAlertService::class.java).apply {
                action = EdgeAlertService.ACTION_START
                putExtra(EdgeAlertService.EXTRA_ANOMALY_THRESHOLD, currentThreshold)
                putExtra(EdgeAlertService.EXTRA_DEBOUNCE_FRAMES, currentDebounce)
            }
            ContextCompat.startForegroundService(this, serviceIntent)

            binding.btnToggleBackground.text = getString(R.string.btn_stop_service)
            binding.tvModeLabel.text = "Shield: Active in Background"

            Toast.makeText(this, "PathSense Shield Running! You can now use other apps.", Toast.LENGTH_SHORT).show()
            // Minimize to background
            moveTaskToBack(true)
        } else {
            // Stop background service and return camera to HUD
            val serviceIntent = Intent(this, EdgeAlertService::class.java).apply {
                action = EdgeAlertService.ACTION_STOP
            }
            startService(serviceIntent)

            imuTracker.start()
            startCameraPreview()

            binding.btnToggleBackground.text = getString(R.string.btn_start_service)
            binding.tvModeLabel.text = "HUD Mode: Active"
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            startCameraPreview()
        }
    }

    private fun startCameraPreview() {
        imuTracker.start()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(320, 240))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val result = obstacleEngine.processFrame(imageProxy)
                runOnUiThread {
                    binding.hudOverlayView.updateResult(result)
                }
            }

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onResume() {
        super.onResume()
        if (!isBackgroundShieldActive) {
            imuTracker.start()
        }
    }

    override fun onPause() {
        super.onPause()
        if (!isBackgroundShieldActive) {
            imuTracker.stop()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isBackgroundShieldActive) {
            imuTracker.stop()
            obstacleEngine.release()
        }
        cameraExecutor.shutdown()
    }
}
