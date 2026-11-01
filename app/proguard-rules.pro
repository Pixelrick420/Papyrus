# PdfBox-Android: optional JPX / crypto providers are not bundled
# JPXFilter references the optional com.gemalto.jp2.JP2Decoder. Keep JPXFilter so R8 does not
# strip it and later throw NoClassDefFoundError when a PDF contains a JPX image.
-keep class com.tom_roush.pdfbox.filter.JPXFilter { *; }
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
-dontwarn org.spongycastle.**
-dontwarn javax.xml.**
-dontwarn java.awt.**
# ACRA discovers our local sender through ServiceLoader
-keep class com.papyrus.app.crash.** { *; }
# SPIs loaded via ServiceLoader: class names and no-arg constructors must survive.
-keep class * implements org.acra.collector.Collector { <init>(); }
-keep class * implements org.acra.startup.StartupProcessor { <init>(); }
-keep class * implements org.acra.sender.ReportSenderFactory { <init>(); }
-keepattributes *Annotation*
-dontwarn org.acra.**
# Markwon's image module probes for optional SVG, GIF and OkHttp support at run time; none is bundled,
# and only `data:` URI images are used.
-dontwarn com.caverock.androidsvg.**
-dontwarn pl.droidsonroids.gif.**
-dontwarn okhttp3.**
-dontwarn okio.**
# No Markdown math renderer is bundled any more. No proguard keep rules are needed for math rendering.
