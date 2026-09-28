#-ignorewarnings                     # 忽略警告，避免打包时某些警告出现
#-optimizationpasses 7               # 指定代码的压缩级别
#-dontusemixedcaseclassnames         # 是否使用大小写混合
#-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*        # 混淆时所采用的算法
# 保留行号信息便于调试
-keepattributes SourceFile,LineNumberTable
-keep class androidx.app.Init {
    public static byte[] core;
    public static void load(...);
}

-dontwarn androidx.annotation.NonNull
-dontwarn androidx.annotation.Nullable
-dontwarn androidx.annotation.VisibleForTesting
# 保留 Constants 类不被混淆
-keep class org.ytp.share.Constants
-keep class org.ytp.share.AppInfo{
    *;
}
-keep class androidx.app.AppComponentFactory
-keep class androidx.app.Application {*;}
# 保持必要的系统类不被移除
-keep class dalvik.system.VMRuntime
-keep class android.app.ActivityThread
-keep class android.os.ServiceManager
-keep class android.content.pm.IPackageManager

# 移除Android日志调用
-assumenosideeffects class android.util.Log{
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}

# 保留ModuleActivity类及其所有成员不被混淆
-keep class androidx.app.ModuleActivity {*;}

# 保留ModuleActivity使用的所有内部类和匿名类
-keepclassmembers class androidx.app.ModuleActivity$* {*;}

# 保留AppInfo类（ModuleActivity中使用）
-keep class org.ytp.share.AppInfo {*;}
# 启用代码收缩但禁用混淆
-dontobfuscate