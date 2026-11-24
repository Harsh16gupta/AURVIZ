# Aurviz ProGuard / R8 rules
# 1. AndroidX Security Crypto (EncryptedSharedPreferences / Google Tink reflection)
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**

# 2. Room Database, DAOs, and Entities (Room v6)
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep class com.aurviz.agent.*_Impl { *; }

# 3. WorkManager Workers constructed via reflection
-keep class * extends androidx.work.Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# 4. OkHttp & Jsoup platform warnings
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.jsoup.**
-dontwarn org.codehaus.mojo.animal_sniffer.**

# 5. SQLCipher for Android JNI bridges
-keep class net.zetetic.database.** { *; }
-dontwarn net.zetetic.database.**
