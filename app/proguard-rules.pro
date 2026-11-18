# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# ---- Clockdown (Play build, R8 on) ----
# Shrink and optimise, but keep class and method names, so Sentry crash reports and Play's vitals read the same as
# before without uploading a mapping file. Line numbers stay too.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
# ML Kit (scanner / text recognition) creates its components by name at start-up: keep their empty constructors.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
