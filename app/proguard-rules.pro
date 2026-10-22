# PdfBox-Android: optional JPX / crypto providers are not bundled
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
# JLatexMath (Markdown math) looks up its macro classes, their methods and the constants named in its XML
# assets by name, and ships no rules of its own, so a minified build would lose every `\frac` and `\sum`.
-keep class org.scilab.forge.jlatexmath.** { *; }
-keep class ru.noties.jlatexmath.awt.Color { *; }
