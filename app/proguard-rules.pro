# v1: minifyEnabled = false nên file này chỉ là khung dự phòng.
# Nếu sau này bật R8, PHẢI giữ các class JNI của sherpa-onnx / youtubedl / ffmpeg-kit:
# -keep class com.k2fsa.sherpa.onnx.** { *; }
# -keep class com.yausername.youtubedl_android.** { *; }
# -keep class com.arthenica.ffmpegkit.** { *; }
# -keep class ai.onnxruntime.** { *; }
