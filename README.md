<div align="center">

# 🚶‍♂️ PathSense

**Ultra-Low-Latency Footpath Obstacle & Pothole Detection for Android**

*Real-time geometric computer vision & inertial sensor fusion to protect pedestrians walking while looking at their phones ("doomscrolling").*

[![Platform](https://img.shields.io/badge/Platform-Android%2010%2B%20%28API%2029%20--%2035%29-3DDC84.svg?logo=android&logoColor=white)](https://android.com)
[![OpenCV](https://img.shields.io/badge/OpenCV-4.9.0%20Native-blue.svg?logo=opencv&logoColor=white)](https://opencv.org/)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-7F52FF.svg?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Build](https://img.shields.io/badge/Build-Gradle%208.9-02303A.svg?logo=gradle&logoColor=white)](https://gradle.org)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

</div>

---

## 💡 Overview

Pedestrians frequently trip over broken pavements, curbs, construction debris, or drop into potholes and open drains while looking at their phones. Standard deep-learning object detectors (YOLO, SSD) consume excessive battery, heat up the phone, require heavy neural network weights, and often fail to detect textureless concrete steps or dark potholes in direct sunlight.

**PathSense** solves this with a **Zero-Deep-Learning, Pure Geometric Computer Vision + IMU Sensor Fusion Pipeline** that:
- Runs at a locked **30 FPS** in **under 8ms per frame** on modest mobile CPUs.
- Requires **zero cloud connectivity** and **zero neural network weights** (fully offline).
- Works even while using other apps (WhatsApp, Instagram, browsers) via an **Android 16 invisible keep-alive overlay** with directional haptics and screen-edge glow alerts.

---

## ⚡ Core Engineering Highlights

- **Zero-Copy Grayscale Ingestion**: Extracts only `planes[0]` (Y-luminance) from CameraX `YUV_420_888` buffers, unpacks row-stride padding with pre-allocated native memory, and rotates $90^\circ$ to portrait ($240 \times 320$).
- **CLAHE Contrast Normalization**: Contrast-Limited Adaptive Histogram Equalization boosts local gradient edges on dull, sun-bleached concrete.
- **Inertial Step-Phase Guard**: Rolling sample variance of `TYPE_LINEAR_ACCELERATION` over a 300ms window detects when the user is stationary, pausing optical flow to eliminate battery drain at traffic stops.
- **Gyroscope Jitter Derotation**: Integrates `TYPE_GYROSCOPE` angular rates $(\omega_x, \omega_y)$ to calculate and subtract pitch/roll camera shifts ($\Delta x_{\text{rot}} = \omega_y \cdot dt \cdot f$, $\Delta y_{\text{rot}} = \omega_x \cdot dt \cdot f$), leaving pure translational ground flow.
- **Safe-Anchor vs. Look-Ahead Homography**: Fits flat-ground homography matrix $\mathbf{H}_{\text{ground}}$ via RANSAC on the immediate ground in front of feet (bottom 30% of frame), then perspective-projects the path ahead (middle 40%).
- **Vertical Parallax Classification**:
  - Downward flow faster than expected ($\mathbf{r}_y > 0$) $\rightarrow$ **Elevated Obstacle** (brick, curb, step).
  - Downward flow slower than expected ($\mathbf{r}_y < 0$) $\rightarrow$ **Depression / Pothole / Drop** (pothole, missing slab).
- **3-Bucket Temporal Debounce**: Partitions look-ahead hazards into `LEFT`, `CENTER`, and `RIGHT` sectors, requiring $\ge 35\%$ anomaly points across 3 consecutive frames to eliminate transient camera motion noise.

---

## 📱 Operational Modes

### 1. Visual Debug HUD (Default Mode)
- Real-time transparent `Canvas` overlay drawn over the CameraX preview.
- Displays Safe Anchor Zone (green boundary), Look-Ahead Zone (cyan dashed grid), and tracked vector lines:
  - **Green lines**: Flat-ground inliers.
  - **Amber circles**: Elevated obstacle anomalies.
  - **Crimson circles**: Potholes and drops.
- Telemetry banner: `FPS | Latency (ms) | Valid Tracks | Inlier % | State`.
- Live tuning sliders for **Anomaly Threshold** ($0.20 - 1.00$) and **Debounce Frames** ($1 - 6$).

### 2. Background "Doomscroll Shield" (Android 16 Compatible)
- Persistent `ForegroundService` with `foregroundServiceType="camera"`.
- Binds an invisible $1\times 1$ pixel `TYPE_APPLICATION_OVERLAY` (`alpha = 0.01`) anchor to satisfy Android 16 background camera visibility rules while the user browses other apps.
- **Peripheral Screen-Edge Glow**:
  - `LEFT` hazard: Flashes amber/crimson on the left border.
  - `RIGHT` hazard: Flashes amber/crimson on the right border.
  - `CENTER` hazard: Flashes along the bottom border.
- **Directional Haptic Waveforms**:
  - `LEFT`: Double short sharp tick (`[0, 35, 40, 35]` ms).
  - `RIGHT`: Single long firm pulse (`[0, 110]` ms).
  - `CENTER`: Urgent triple pulse (`[0, 50, 30, 50, 30, 80]` ms).

---

## 📂 Repository Structure

```
Path-Sense/
├── AGENTS.md                                # AI & developer engineering guidelines
├── architecture.md                          # Mathematical proofs, equations & pipeline
├── LICENSE                                  # MIT License
├── README.md                                # Project documentation
├── build.gradle.kts                         # Root Gradle build script
├── settings.gradle.kts                      # Project settings & repositories
├── gradle.properties                       # 1.5GB RAM cap & compiler flags
├── gradlew / gradlew.bat                    # Gradle 8.9 wrapper scripts
└── app/
    ├── build.gradle.kts                     # App dependencies (OpenCV 4.9.0, CameraX 1.3.4)
    ├── proguard-rules.pro                   # R8/ProGuard rules for OpenCV & CameraX
    └── src/main/
        ├── AndroidManifest.xml              # Permissions & camera foreground service
        ├── java/com/pathsense/
        │   ├── MainActivity.kt              # Permissions, HUD preview & sliders
        │   ├── ImuTracker.kt                # Accel variance & Gyro derotation
        │   ├── HomographyObstacleEngine.kt  # Y-plane unpack, PyrLK, RANSAC, Debouncer
        │   ├── DebugHudView.kt              # 60 FPS Canvas rendering overlay
        │   └── EdgeAlertService.kt          # Background service + 1x1 keep-alive + haptics
        └── res/                             # Layouts, drawables & styles
```

---

## 🛠️ Build & Installation Guide

### Prerequisites
- **JDK 17** (Microsoft OpenJDK 17 recommended)
- **Android SDK** (API Level 35, Build-Tools 34.0.0 & 35.0.0, Platform-Tools)
- Connected Android device (Android 10+ / API 29 - 35) with USB Debugging enabled

### 1. Clone the Repository
```bash
git clone https://github.com/Prashantispacific/Path-Sense.git
cd Path-Sense
```

### 2. Configure Local Properties
Create `local.properties` in the project root:
```properties
sdk.dir=C\:\\Users\\<YourUsername>\\AppData\\Local\\Android\\Sdk
```

### 3. Build Debug APK
```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.21.21-hotspot"
.\gradlew.bat assembleDebug
```

### 4. Install & Launch on Device
```powershell
# Install APK
adb install -r app\build\outputs\apk\debug\app-debug.apk

# Grant Permissions
adb shell pm grant com.pathsense android.permission.CAMERA
adb shell pm grant com.pathsense android.permission.POST_NOTIFICATIONS
adb shell appops set com.pathsense SYSTEM_ALERT_WINDOW allow

# Launch App
adb shell am start -n com.pathsense/.MainActivity
```

---

## 📊 Performance Benchmarks (Tested on Vivo T3 5G, Android 16)

| Metric | Target Budget | Measured Performance |
| :--- | :--- | :--- |
| **Pipeline Latency** | $< 8.0\text{ ms}$ | **$3.5 - 5.8\text{ ms}$** |
| **Throughput** | $30\text{ FPS}$ sustained | **$30.0\text{ FPS}$** |
| **Allocations / Frame** | $0\text{ bytes}$ (reusable Mats) | **Zero GC Pauses** |
| **APK Footprint** | $< 60\text{ MB}$ | **$50.5\text{ MB}$** |
| **Incremental Build** | $< 10\text{ s}$ | **$4.2\text{ s}$** |

---

## 📄 Documentation

For deep technical details, mathematical derivations, and agent instructions, refer to:
- [`architecture.md`](architecture.md): Mathematical formulations, optical flow geometry, and Android 16 keep-alive mechanics.
- [`AGENTS.md`](AGENTS.md): Hard constraints, memory cap guidelines, and developer instructions.

---

## 📜 License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.
