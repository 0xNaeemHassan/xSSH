# xSSH — R8 / ProGuard rules

# sshj + BouncyCastle
-keep class org.bouncycastle.** { *; }
-keep class net.schmizz.sshj.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn net.schmizz.sshj.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Compile-time annotations
-dontwarn org.jetbrains.annotations.**

# Compose keeps itself; Hilt too.
