# JPXFilter references the optional, unbundled com.gemalto.jp2.JP2Decoder; keep it so R8 does not strip it
# and a PDF with a JPX image later throws NoClassDefFoundError.
-keep class com.tom_roush.pdfbox.filter.JPXFilter { *; }
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
-dontwarn org.spongycastle.**
-dontwarn javax.xml.**
-dontwarn java.awt.**
-keep class com.papyrus.app.crash.** { *; }
# SPIs loaded via ServiceLoader: class names and no-arg constructors must survive.
-keep class * implements org.acra.collector.Collector { <init>(); }
-keep class * implements org.acra.startup.StartupProcessor { <init>(); }
-keep class * implements org.acra.sender.ReportSenderFactory { <init>(); }
-keepattributes *Annotation*
-dontwarn org.acra.**
# Markwon's image module probes for optional SVG, GIF and OkHttp support at run time; none is bundled.
-dontwarn com.caverock.androidsvg.**
-dontwarn pl.droidsonroids.gif.**
-dontwarn okhttp3.**
-dontwarn okio.**
# Markdown math is drawn by RaTeX over the source text; its fonts load from assets, so no keep rule.
