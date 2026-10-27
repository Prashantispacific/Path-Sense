# AGENTS.md — PathSense Developer & AI Agent Guidelines

This document provides essential instructions, architectural boundaries, and operational workflows for AI coding assistants and developers collaborating on the **PathSense** codebase.

---

## 1. Project Mission & Identity

**PathSense** is an ultra-low-latency, offline Android application in Kotlin that detects upcoming footpath obstacles (curbs, bricks, steps) and potholes/depressions in real time as the user walks while looking at their phone ("doomscrolling").

- **Target Device**: Vivo T3 5G (`V2334`), ARM64 (`arm64-v8a`), running Android 16 (OriginOS 6).
- **Host Development Environment**: Windows 11 laptop, Intel Core i3-1215U (6 cores / 8 threads), 8 GB RAM.
- **Tooling**: Android CLI (`gradlew` + `adb`), VS Code / Terminal.

---

## 2. Hard Architectural Constraints (DO NOT VIOLATE)

1. **NO CMake / NDK Compilation**:
   - The host laptop (8 GB RAM, Core i3) will exhaust memory if compiling native C++ via CMake/NDK.
   - All computer vision native operations **MUST** use the official pre-compiled Maven artifact: `org.opencv:opencv:4.9.0` (or `4.10.0`). Native `.so` libraries (`libopencv_java4.so`) are pre-packaged in the AAR and extracted during build.
2. **RAM Cap Enforcement**:
   - [`gradle.properties`](file:///D:/DEV/Obstacle_Detect/gradle.properties) **MUST** strictly enforce:
     ```properties
     org.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8
     org.gradle.daemon=true
     ```
   - Never increase JVM heap beyond 1536 MB to prevent host thrashing and crashes.
3. **No Heavy UI Toolkits (No Jetpack Compose)**:
   - Use pure Kotlin + standard XML layouts + custom Canvas `View` (`DebugHudView.kt`). This keeps incremental builds under 10 seconds.
4. **Android 16 Compatibility**:
   - `minSdk = 29`, `compileSdk = 35`, `targetSdk = 35`.
   - Android 16 enforces strict background camera isolation. Background camera execution requires a foreground service with `foregroundServiceType="camera"` combined with a 1x1 pixel system overlay (`TYPE_APPLICATION_OVERLAY`) to maintain the "user-visible" state.
5. **Zero Deep Learning**:
   - Do NOT introduce TensorFlow Lite, ONNX, PyTorch, or neural network weights. PathSense relies entirely on classical Geometric Optical Flow + RANSAC Homography + IMU sensor fusion running in under 8ms per frame.

---

## 3. Environment & Tooling Paths on this Machine

| Component | Path / Location |
| :--- | :--- |
| **Project Root** | `D:\DEV\Obstacle_Detect` |
| **JDK 17 Home** | `C:\Program Files\Microsoft\jdk-17.0.21.21-hotspot` |
| **Android SDK Root** | `C:\Users\prash\AppData\Local\Android\Sdk` |
| **Build Tools** | `C:\Users\prash\AppData\Local\Android\Sdk\build-tools\34.0.0` & `35.0.0` |
| **Platform Tools (ADB)** | `C:\Users\prash\AppData\Local\Android\Sdk\platform-tools\adb.exe` |
| **Built APK Output** | `D:\DEV\Obstacle_Detect\app\build\outputs\apk\debug\app-debug.apk` |

---

## 4. Key Source Code Directory Map

```
D:\DEV\Obstacle_Detect
├── app/
│   ├── build.gradle.kts           # App-level dependencies (OpenCV 4.9.0, CameraX 1.3.4)
│   └── src/main/
│       ├── AndroidManifest.xml    # Permissions: Camera, ForegroundService, Overlay, Vibrate
│       ├── java/com/pathsense/
│       │   ├── MainActivity.kt           # Permission launcher, HUD preview, tuning sliders
│       │   ├── ImuTracker.kt             # Linear Accel ring buffer & Gyro derotation math
│       │   ├── HomographyObstacleEngine.kt # Y-plane unpack, CLAHE, PyrLK, RANSAC, Debounce
│       │   ├── DebugHudView.kt           # Canvas overlay: flow vectors, zones, HUD telemetry
│       │   └── EdgeAlertService.kt       # Background service + 1x1 keep-alive + edge glow + haptics
│       └── res/
│           ├── layout/
│           │   ├── activity_main.xml     # PreviewView + DebugHudView + Control Panel
│           │   └── view_edge_glow.xml    # Overlay layout with 1x1 anchor & edge glow bars
│           └── drawable/                 # Linear gradient alert glow drawables
├── gradle.properties             # 1.5GB RAM cap & suppressUnsupportedCompileSdk=35
├── local.properties              # sdk.dir configured to C:\Users\prash\AppData\Local\Android\Sdk
└── settings.gradle.kts           # Repositories & project include
```

---

## 5. Development Workflows (PowerShell)

### Build Debug APK
Always pass `JAVA_HOME` pointing to Microsoft OpenJDK 17:
```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.21.21-hotspot"
cd D:\DEV\Obstacle_Detect
.\gradlew.bat assembleDebug
```

### Install & Grant Permissions on Connected Device
```powershell
$adb = "C:\Users\prash\AppData\Local\Android\Sdk\platform-tools\adb.exe"

# 1. Install APK
& $adb install -r "D:\DEV\Obstacle_Detect\app\build\outputs\apk\debug\app-debug.apk"

# 2. Grant permissions
& $adb shell pm grant com.pathsense android.permission.CAMERA
& $adb shell pm grant com.pathsense android.permission.POST_NOTIFICATIONS
& $adb shell appops set com.pathsense SYSTEM_ALERT_WINDOW allow

# 3. Launch PathSense
& $adb shell am start -n com.pathsense/.MainActivity
```

### Inspect Live Telemetry & Process Logs
```powershell
$adb = "C:\Users\prash\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$pid = & $adb shell pidof com.pathsense
& $adb logcat --pid=$pid
```

---

## 6. Guidelines for Modifying Core Components

### `HomographyObstacleEngine.kt`
- **Memory Allocations**: Mats (`rawMat`, `rotatedMat`, `claheMat`, `prevMat`, `ptsPrevMat`, etc.) and byte arrays must be pre-allocated and reused across frames. Do not allocate new `Mat()` inside `processFrame()`.
- **Close Frames**: Always invoke `imageProxy.close()` on every code path (including early returns and guard clauses) to prevent CameraX frame pipeline exhaustion.
- **Resolution**: Camera input is $320 \times 240$ (landscape) rotated $90^\circ$ clockwise to $240 \times 320$ (portrait). Zone boundaries must align with portrait height ($H = 320$) and width ($W = 240$).

### `ImuTracker.kt`
- Operates on a ring buffer of timestamps. Ensure all accesses to `accSamples` and `gyroSamples` are thread-safe (`synchronized`).
- Gyro integration must calculate rotational pixel displacement using the current frame interval $dt$ and the camera's effective focal length $f \approx 180\text{ px}$.

### `EdgeAlertService.kt`
- Android 16 requires foreground camera services to be declared with `foregroundServiceType="camera"`.
- Do not remove the `1x1` pixel `keepAliveAnchor` with `alpha = 0.01` from `view_edge_glow.xml`. OriginOS 6 suspends camera access if no window of the owning application is attached to `WindowManager`.
