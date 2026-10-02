# OpenCV: JNI binds by class/method name
-keep class org.opencv.** { *; }
# PdfBox-Android: optional JPX / crypto providers are not bundled
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
-dontwarn org.spongycastle.**
-dontwarn javax.xml.**
-dontwarn java.awt.**
# ACRA discovers our local sender through ServiceLoader
-keep class com.papyrus.app.crash.** { *; }
-keep class org.acra.** { *; }
