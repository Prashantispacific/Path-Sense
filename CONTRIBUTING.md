# Contributing to PathSense

Thank you for your interest in contributing to **PathSense**!

PathSense is designed with strict performance constraints (sub-8ms per frame, zero heap allocations in the critical computer vision loop, and strict 1.5GB Gradle memory limits). Please review the following guidelines before submitting code.

---

## 🛑 Critical Architectural Rules

1. **Zero Deep Learning**:
   Do not introduce TensorFlow Lite, ONNX, PyTorch, or neural network weights. All anomaly detection must rely on classical geometric optical flow, RANSAC homography, and IMU sensor fusion.
2. **Zero NDK / CMake Ingestion**:
   Do not add custom C++ files or CMake build scripts. Use the official pre-compiled OpenCV Maven artifact (`org.opencv:opencv:4.9.0`).
3. **Zero Allocations in the Processing Loop**:
   Avoid allocating `Mat`, `Point`, `ByteArray`, or collection objects inside `HomographyObstacleEngine.processFrame()`. All buffers must be pre-allocated and reused.
4. **Android 16 Keep-Alive Safety**:
   Do not remove or alter the `1x1` keep-alive overlay in `view_edge_glow.xml`. It ensures that background CameraX processing is not suspended by Android 16.

---

## 🛠️ Development Workflow

1. **Fork and Clone**:
   ```bash
   git clone https://github.com/<your-username>/Path-Sense.git
   cd Path-Sense
   ```
2. **Create a Feature Branch**:
   ```bash
   git checkout -b feature/my-enhancement
   ```
3. **Build and Test**:
   Ensure the project builds cleanly without exceeding the 1.5GB RAM cap:
   ```powershell
   .\gradlew.bat assembleDebug
   ```
4. **Deploy and Verify on Device**:
   ```powershell
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
5. **Commit Message Format**:
   Follow conventional commits:
   - `feat: add new feature`
   - `fix: resolve bug`
   - `perf: optimize frame latency`
   - `docs: update documentation`
6. **Submit a Pull Request**:
   Push to your fork and open a Pull Request targeting the `main` branch.
