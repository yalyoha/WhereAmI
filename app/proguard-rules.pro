# ProGuard / R8 rules for WhereAmI release build.

# ─── Полностью оставляем все классы нашего пакета (маленький, не сжимаем) ───
# Это избавляет от ошибок reflection / WebView / Fragment / WorkManager в нашем коде.
-keep class com.example.whereami.** { *; }
-keep interface com.example.whereami.** { *; }
-keepclassmembers class com.example.whereami.** { *; }

# ─── WebView JavaScript Interface (на всякий случай, кроме нашего пакета) ──
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ─── WorkManager — воркеры создаются рефлексией ─────────────────────────────
-keep class androidx.work.** { *; }
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }
-keep class * extends androidx.work.ListenableWorker { *; }

# ─── AndroidX Security Crypto — EncryptedSharedPreferences через Tink ──────
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
-keepclassmembers class com.google.crypto.tink.** { *; }
-keep class com.google.crypto.tink.proto.** { *; }
-dontwarn com.google.crypto.tink.**
# Protobuf-классы используются Tink через рефлексию
-keep class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite { *; }
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite {
    <fields>;
}

# ─── OkHttp / Okio ─────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# ─── Kotlin reflection / coroutines ────────────────────────────────────────
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.debug.AgentPremain

# ─── Сохраняем имена файлов/строк для крэш-репортов ────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ─── Сохраняем signature атрибуты (нужно для reflection generics) ──────────
-keepattributes Signature,Exceptions,InnerClasses,EnclosingMethod
