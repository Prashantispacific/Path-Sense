# PathSense ProGuard / R8 Rules

# Keep OpenCV native symbols and JNI bindings
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# Keep CameraX classes and native utilities
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# Keep PathSense data models used across engine and UI
-keep class com.pathsense.SystemState { *; }
-keep class com.pathsense.BucketType { *; }
-keep class com.pathsense.HazardType { *; }
-keep class com.pathsense.FlowVector { *; }
-keep class com.pathsense.HazardAlert { *; }
-keep class com.pathsense.DetectionResult { *; }

# Keep Custom Canvas View for layout inflation
-keep class com.pathsense.DebugHudView {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
