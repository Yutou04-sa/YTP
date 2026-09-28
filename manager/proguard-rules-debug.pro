-dontobfuscate
-keep class com.beust.jcommander.** { *; }
-keep class org.ytp.Patcher$Options { *; }
-keep class org.ytp.share.YTPConfig { *; }
-keep class org.ytp.share.PatchConfig { *; }
-keepclassmembers class org.ytp.patch.YTPPatch {
    private <fields>;
}
-dontwarn com.google.auto.value.AutoValue$Builder
-dontwarn com.google.auto.value.AutoValue
