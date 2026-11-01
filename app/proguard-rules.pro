-keep class org.conscrypt.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**

# Room and WorkManager use reflection to instantiate generated classes.
# With AGP 9 / R8 full mode, a class-level keep rule alone does not keep
# its no-argument constructor. Without this the application crashes in
# androidx.startup.InitializationProvider before MainActivity can open.
# Identical constructor safeguards are used by VRec.
-keep class * extends androidx.room.RoomDatabase {
    <init>();
}

# Preserve WorkManager InputMerger constructors invoked by class name.
-keep class * extends androidx.work.InputMerger {
    <init>();
}

# Reboot-recovery worker is constructed reflectively by WorkManager.
-keep class dev.n2k02.mcplogger.core.AdbRecoveryWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Retain actionable stack traces from minified experimental builds.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
