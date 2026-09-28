package org.ytp.loader.util;

import android.annotation.SuppressLint;
import android.app.AppComponentFactory;
import android.app.LoadedApk;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.system.Os;
import android.util.Log;

import org.lsposed.lspd.core.Startup;
import org.lsposed.lspd.impl.LSPosedContext;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import hidden.HiddenApiBridge;
import io.github.libxposed.api.XposedModuleInterface;

public class LoaderUtils {

    private static final String TAG = "YTP";

    public static void disableProfile(Context context, int PER_USER_RANGE) {
        final ArrayList<String> codePaths = new ArrayList<>();
        var appInfo = context.getApplicationInfo();
        var pkgName = context.getPackageName();
        if (appInfo == null) return;
        if ((appInfo.flags & ApplicationInfo.FLAG_HAS_CODE) != 0) {
            codePaths.add(appInfo.sourceDir);
        }
        if (appInfo.splitSourceDirs != null) {
            Collections.addAll(codePaths, appInfo.splitSourceDirs);
        }

        if (codePaths.isEmpty()) {
            // If there are no code paths there's no need to setup a profile file and register with
            // the runtime,
            return;
        }

        var profileDir = HiddenApiBridge.Environment_getDataProfilesDePackageDirectory(appInfo.uid / PER_USER_RANGE, pkgName);

        var attrs = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("r--------"));

        for (int i = codePaths.size() - 1; i >= 0; i--) {
            String splitName = i == 0 ? null : appInfo.splitNames[i - 1];
            File curProfileFile = new File(profileDir, splitName == null ? "primary.prof" : splitName + ".split.prof").getAbsoluteFile();
            Log.d(TAG, "Processing " + curProfileFile.getAbsolutePath());
            try {
                if (!curProfileFile.exists()) {
                    Files.createFile(curProfileFile.toPath(), attrs);
                    continue;
                }
                if (!curProfileFile.canWrite() && Files.size(curProfileFile.toPath()) == 0) {
                    Log.d(TAG, "Skip profile " + curProfileFile.getAbsolutePath());
                    continue;
                }
                if (curProfileFile.exists() && !curProfileFile.delete()) {
                    try (var writer = new FileOutputStream(curProfileFile)) {
                        Log.d(TAG, "Failed to delete, try to clear content " + curProfileFile.getAbsolutePath());
                    } catch (Throwable e) {
                        Log.e(TAG, "Failed to delete and clear profile file " + curProfileFile.getAbsolutePath(), e);
                    }
                    Os.chmod(curProfileFile.getAbsolutePath(), 00400);
                }
            } catch (Throwable e) {
                Log.e(TAG, "Failed to disable profile file " + curProfileFile.getAbsolutePath(), e);
            }
        }
    }

    public static void LoadModules(LoadedApk loadedApk) {
        try {
            Startup.bootstrapXposed();

            // 2. 获取模块所需的宿主包信息
            var pkgName = loadedApk.getPackageName();
            var appInfo = loadedApk.getApplicationInfo();
            boolean hasModernModules = LSPosedContext.hasModules();
            ClassLoader defaultClassLoader = null;
            boolean packageLoadedDispatched = false;

            // Android 10+ 的代理 AppComponentFactory 初始化发生在最终 ClassLoader 创建前，
            // 此时先发送 onPackageLoaded，避免把两个现代生命周期压缩到同一阶段。
            if (hasModernModules && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    @SuppressLint("BlockedPrivateApi")
                    var field = LoadedApk.class.getDeclaredField("mDefaultClassLoader");
                    field.setAccessible(true);
                    defaultClassLoader = (ClassLoader) field.get(loadedApk);
                } catch (Throwable e) {
                    Log.w(TAG, "mDefaultClassLoader field access failed: " + e.getMessage());
                }
                if (defaultClassLoader != null) {
                    callOnPackageLoaded(pkgName, appInfo, defaultClassLoader);
                    packageLoadedDispatched = true;
                }
            }

            var classLoader = loadedApk.getClassLoader();
            if (defaultClassLoader == null) defaultClassLoader = classLoader;
            if (hasModernModules && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    && !packageLoadedDispatched) {
                callOnPackageLoaded(pkgName, appInfo, defaultClassLoader);
            }

            XC_LoadPackage.LoadPackageParam lpparam = new XC_LoadPackage.LoadPackageParam(XposedBridge.sLoadedPackageCallbacks);
            lpparam.packageName = pkgName;
            lpparam.processName = LSPosedContext.processName != null
                    ? LSPosedContext.processName : appInfo.processName;
            lpparam.classLoader = classLoader;
            lpparam.appInfo = appInfo;
            lpparam.isFirstApplication = true;

            XC_LoadPackage.callAll(lpparam);

            if (!LSPosedContext.hasModules()) return;

            AppComponentFactory appComponentFactory = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    @SuppressLint("SoonBlockedPrivateApi")
                    var field = LoadedApk.class.getDeclaredField("mAppComponentFactory");
                    field.setAccessible(true);
                    appComponentFactory = (AppComponentFactory) field.get(loadedApk);
                } catch (Throwable e) {
                    Log.w(TAG, "mAppComponentFactory field access failed: " + e.getMessage());
                }
            }

            ClassLoader readyDefaultClassLoader = defaultClassLoader;
            AppComponentFactory readyAppComponentFactory = appComponentFactory;
            LSPosedContext.callOnPackageReady(new XposedModuleInterface.PackageReadyParam() {
                @Override
                public String getPackageName() { return pkgName; }

                @Override
                public ApplicationInfo getApplicationInfo() { return appInfo; }

                @Override
                public boolean isFirstPackage() { return true; }

                @Override
                public ClassLoader getDefaultClassLoader() { return readyDefaultClassLoader; }

                @Override
                public ClassLoader getClassLoader() { return classLoader; }

                @Override
                public AppComponentFactory getAppComponentFactory() { return readyAppComponentFactory; }
            });
        } catch (Throwable e) {
            Log.e(TAG, "Failed to LoadModules: " + e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    private static void callOnPackageLoaded(String packageName, ApplicationInfo applicationInfo,
                                            ClassLoader defaultClassLoader) {
        LSPosedContext.callOnPackageLoaded(new XposedModuleInterface.PackageLoadedParam() {
            @Override
            public String getPackageName() { return packageName; }

            @Override
            public ApplicationInfo getApplicationInfo() { return applicationInfo; }

            @Override
            public boolean isFirstPackage() { return true; }

            @Override
            public ClassLoader getDefaultClassLoader() { return defaultClassLoader; }
        });
    }
}
