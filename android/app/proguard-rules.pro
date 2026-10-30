# Optimisation sans renommage : moins de risques avec JNI / réflexion.
-dontobfuscate

# LibVLC : classes appelées depuis le code natif (JNI)
-keep class org.videolan.** { *; }
-dontwarn org.videolan.**

# kotlinx.serialization (profil, historique)
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers class com.bibitv.tv.** {
    *** Companion;
}
-keepclasseswithmembers class com.bibitv.tv.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.bibitv.tv.**$$serializer { *; }

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
