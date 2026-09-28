-keep class com.wind.xposed.entry.MMPEntry {
    public <init>();
    public void initAndLoadModules();
}

-keep class com.wind.xpatch.proxy.**{*;}

-keep class de.robv.android.xposed.**{*;}

-keep class android.app.**{*;}
-keep class android.content.**{*;}
-keep class android.os.**{*;}

-keep class android.view.**{*;}
-keep class com.lody.whale.**{*;}
-keep class com.android.internal.**{*;}
-keep class xposed.dummy.**{*;}
-keep class com.wind.xposed.entry.util.**{*;}

-keep class com.swift.sandhook.**{*;}
-keep class com.swift.sandhook.xposedcompat.**{*;}

-dontwarn android.content.res.Resources
-dontwarn android.content.res.Resources$Theme
-dontwarn android.content.res.AssetManager
-dontwarn android.content.res.TypedArray

# 移除Android日志调用
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}
