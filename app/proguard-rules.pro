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
