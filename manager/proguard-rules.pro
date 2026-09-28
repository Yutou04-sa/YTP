-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
 public static void check*(...);
 public static void throw*(...);
}
-assumenosideeffects class java.util.Objects {
    public static ** requireNonNull(...);
}
-assumenosideeffects public class kotlin.coroutines.jvm.internal.DebugMetadataKt {
   private static ** getDebugMetadataAnnotation(...) return null;
}

-keep class com.beust.jcommander.** { *; }
-keep class org.ytp.database.** { *; }
-keep class org.ytp.Patcher { *; }
-keep class org.ytp.share.YTPConfig { *; }
-keep class org.ytp.share.PatchConfig { *; }
-keep class org.ytp.config.** {*;}
-keepclassmembers class org.ytp.patch.YTPPatch {
    private <fields>;
    public <fields>;
}
-keepclassmembers class org.ytp.patch.YTPExtend {
    private <fields>;
    public <fields>;
}
-dontwarn com.google.auto.value.AutoValue$Builder
-dontwarn com.google.auto.value.AutoValue
