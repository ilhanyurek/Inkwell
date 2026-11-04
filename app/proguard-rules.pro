# PdfBox-Android loads fonts, CMaps and filters reflectively and from bundled resources.
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
-dontwarn java.awt.**
-dontwarn javax.**
-dontwarn org.bouncycastle.**
-dontwarn com.gemalto.jp2.**
-dontwarn org.slf4j.**

# Keep line numbers so crash reports stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
