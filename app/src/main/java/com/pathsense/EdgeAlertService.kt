package com.pathsense

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Size
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Foreground Service running the background camera analyzer and rendering
 * the persistent Android 16 keep-alive overlay with directional edge glow and haptics.
 */
class EdgeAlertService : Service(), LifecycleOwner {

    companion object {
        const val ACTION_START = "com.pathsense.action.START"
        const val ACTION_STOP = "com.pathsense.action.STOP"
        const val EXTRA_ANOMALY_THRESHOLD = "extra_anomaly_threshold"
        const val EXTRA_DEBOUNCE_FRAMES = "extra_debounce_frames"

        private const val CHANNEL_ID = "pathsense_shield_channel"
        private const val NOTIFICATION_ID = 4040
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private var windowManager: WindowManager? = null
    private var overlayRootView: View? = null
    private var glowLeft: View? = null
    private var glowRight: View? = null
    private var glowBottom: View? = null

    private lateinit var vibrator: Vibrator
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var imuTracker: ImuTracker
    private lateinit var obstacleEngine: HomographyObstacleEngine

    private var lastVibrateTimestamp = 0L
    private val vibrateCooldownMs = 600L

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        initVibrator()
        cameraExecutor = Executors.newSingleThreadExecutor()
        imuTracker = ImuTracker(this).apply { start() }
        obstacleEngine = HomographyObstacleEngine(imuTracker)

        createNotificationChannel()
        setupOverlayView()
    }

    private fun initVibrator() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val threshold = intent?.getFloatExtra(EXTRA_ANOMALY_THRESHOLD, 0.45f) ?: 0.45f
        val debounce = intent?.getIntExtra(EXTRA_DEBOUNCE_FRAMES, 3) ?: 3
        obstacleEngine.anomalyThreshold = threshold
        obstacleEngine.debounceFrames = debounce

        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        startBackgroundCamera()
        return START_STICKY
    }

    private fun setupOverlayView() {
        if (overlayRootView != null) return

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val inflater = LayoutInflater.from(this)
        overlayRootView = inflater.inflate(R.layout.view_edge_glow, null)

        glowLeft = overlayRootView?.findViewById(R.id.glowLeft)
        glowRight = overlayRootView?.findViewById(R.id.glowRight)
        glowBottom = overlayRootView?.findViewById(R.id.glowBottom)

        // Overlay Window Parameters optimized for Android 16 keep-alive
        val layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            windowManager?.addView(overlayRootView, layoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startBackgroundCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(320, 240))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val result = obstacleEngine.processFrame(imageProxy)
                handleDetectionResult(result)
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    imageAnalysis
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleDetectionResult(result: DetectionResult) {
        if (result.alerts.isEmpty()) return

        val primaryAlert = result.alerts.first()
        triggerAlertFeedback(primaryAlert.bucket, primaryAlert.hazardType)
    }

    private fun triggerAlertFeedback(bucket: BucketType, hazardType: HazardType) {
        val now = System.currentTimeMillis()
        if (now - lastVibrateTimestamp < vibrateCooldownMs) return
        lastVibrateTimestamp = now

        // 1. Directional Haptic Encoding
        executeHaptics(bucket)

        // 2. Visual Screen-Edge Glowing Bar
        overlayRootView?.post {
            animateEdgeGlow(bucket, hazardType)
        }
    }

    private fun executeHaptics(bucket: BucketType) {
        if (!vibrator.hasVibrator()) return

        when (bucket) {
            BucketType.LEFT -> {
                // Double short sharp tick: [0, 35, 40, 35], amplitudes [0, 255, 0, 255]
                val timings = longArrayOf(0, 35, 40, 35)
                val amplitudes = intArrayOf(0, 255, 0, 255)
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            }
            BucketType.RIGHT -> {
                // Single long firm pulse: [0, 110], amplitude [0, 255]
                val timings = longArrayOf(0, 110)
                val amplitudes = intArrayOf(0, 255)
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            }
            BucketType.CENTER -> {
                // Urgent triple pulse: [0, 50, 30, 50, 30, 80], amplitudes [0, 255, 0, 255, 0, 255]
                val timings = longArrayOf(0, 50, 30, 50, 30, 80)
                val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255)
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            }
        }
    }

    private fun animateEdgeGlow(bucket: BucketType, hazardType: HazardType) {
        val targetColor = if (hazardType == HazardType.ELEVATED_OBSTACLE) {
            Color.parseColor("#FFAB00") // Amber
        } else {
            Color.parseColor("#FF1744") // Crimson Red
        }

        val targetView = when (bucket) {
            BucketType.LEFT -> glowLeft
            BucketType.RIGHT -> glowRight
            BucketType.CENTER -> glowBottom
        } ?: return

        // Set gradient start color dynamically
        val angle = when (bucket) {
            BucketType.LEFT -> GradientDrawable.Orientation.LEFT_RIGHT
            BucketType.RIGHT -> GradientDrawable.Orientation.RIGHT_LEFT
            BucketType.CENTER -> GradientDrawable.Orientation.BOTTOM_TOP
        }
        val gradient = GradientDrawable(angle, intArrayOf(targetColor, Color.TRANSPARENT))
        targetView.background = gradient

        targetView.alpha = 1.0f
        targetView.animate()
            .alpha(0.0f)
            .setDuration(450)
            .start()
    }

    private fun buildForegroundNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PathSense Shield Active")
            .setContentText("Scanning footpath obstacles & potholes in background")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_desc)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)

        imuTracker.stop()
        obstacleEngine.release()
        cameraExecutor.shutdown()

        overlayRootView?.let {
            windowManager?.removeView(it)
            overlayRootView = null
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
