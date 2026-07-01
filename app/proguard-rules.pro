# Keep Telegram reporter
-keep class com.companyname.appname.TelegramReporter { *; }

# Keep ErrorLog
-keep class com.companyname.appname.ErrorLog { *; }

# Keep MediaKind enum
-keep enum com.companyname.appname.MediaKind { *; }

# Keep AudioEditorActivity
-keep class com.companyname.appname.AudioEditorActivity { *; }

# Keep application class
-keep class com.companyname.appname.AudioCutterApp { *; }

# ===== FFmpeg (pao11) ProGuard rules =====
-keep class com.github.pao11.libffmpeg.** { *; }
-keep class com.github.pao11.ffmpeg.** { *; }
-keep class arm.alex.** { *; }
-keep class * extends com.github.pao11.libffmpeg.FFmpegLoadBinaryResponseHandler { *; }
-keep class * extends com.github.pao11.libffmpeg.FFmpegExecuteResponseHandler { *; }
-keep class * extends com.github.pao11.libffmpeg.LoadBinaryResponseHandler { *; }
-keep class * extends com.github.pao11.libffmpeg.ExecuteBinaryResponseHandler { *; }
-keep class com.github.pao11.libffmpeg.FFmpeg { *; }
-keep class com.github.pao11.libffmpeg.FFmpeg$* { *; }
-keep class com.github.pao11.libffmpeg.FFmpegApi { *; }
