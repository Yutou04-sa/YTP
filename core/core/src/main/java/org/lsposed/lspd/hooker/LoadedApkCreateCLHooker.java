/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSPosed is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSPosed.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2020 EdXposed Contributors
 * Copyright (C) 2021 LSPosed Contributors
 */

package org.lsposed.lspd.hooker;

import static org.lsposed.lspd.core.ApplicationServiceClient.serviceClient;

import android.annotation.SuppressLint;
import android.app.ActivityThread;
import android.app.AppComponentFactory;
import android.app.LoadedApk;
import android.content.pm.ApplicationInfo;
import android.os.Build;

import androidx.annotation.NonNull;

import org.lsposed.lspd.impl.LSPosedContext;
import org.lsposed.lspd.util.Hookers;
import org.lsposed.lspd.util.MetaDataReader;
import org.lsposed.lspd.util.Utils;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XposedInit;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

@SuppressLint("BlockedPrivateApi")
public class LoadedApkCreateCLHooker implements XposedInterface.Hooker {
    private final static Field defaultClassLoaderField;

    // false: 等待默认 ClassLoader；true: onPackageLoaded 已发送
    private final static ConcurrentHashMap<LoadedApk, Boolean> loadedApks = new ConcurrentHashMap<>();

    static {
        Field defaultClassLoader = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                defaultClassLoader = LoadedApk.class.getDeclaredField("mDefaultClassLoader");
                defaultClassLoader.setAccessible(true);
            } catch (Throwable ignored) {
            }
        }
        defaultClassLoaderField = defaultClassLoader;
    }

    @SuppressLint("SoonBlockedPrivateApi")
    private static final class AppComponentFactoryFieldHolder {
        private static final Field field;

        static {
            Field appComponentFactory = null;
            try {
                appComponentFactory = LoadedApk.class.getDeclaredField("mAppComponentFactory");
                appComponentFactory.setAccessible(true);
            } catch (Throwable ignored) {
            }
            field = appComponentFactory;
        }
    }

    static void addLoadedApk(LoadedApk loadedApk) {
        loadedApks.put(loadedApk, Boolean.FALSE);
    }

    static void onDefaultClassLoaderReady(LoadedApk loadedApk, ClassLoader defaultClassLoader) {
        if (!LSPosedContext.hasModules()
                || !loadedApks.replace(loadedApk, Boolean.FALSE, Boolean.TRUE)) {
            return;
        }

        try {
            String packageName = loadedApk.getPackageName();
            boolean isFirstPackage = packageName.equals(ActivityThread.currentPackageName());
            if (!shouldHandlePackage(loadedApk, packageName, isFirstPackage)) {
                loadedApks.remove(loadedApk, Boolean.TRUE);
                return;
            }

            dispatchOnPackageLoaded(loadedApk, defaultClassLoader, isFirstPackage);
        } catch (Throwable t) {
            // 允许 createOrUpdateClassLoaderLocked 的结束阶段兜底补发
            loadedApks.replace(loadedApk, Boolean.TRUE, Boolean.FALSE);
            Hookers.logE("error when dispatching onPackageLoaded", t);
        }
    }

    private static void dispatchOnPackageLoaded(LoadedApk loadedApk,
                                                ClassLoader defaultClassLoader,
                                                boolean isFirstPackage) {
        String packageName = loadedApk.getPackageName();
        ApplicationInfo applicationInfo = loadedApk.getApplicationInfo();
        LSPosedContext.callOnPackageLoaded(new XposedModuleInterface.PackageLoadedParam() {
                @NonNull
                @Override
                public String getPackageName() {
                    return packageName;
                }

                @NonNull
                @Override
                public ApplicationInfo getApplicationInfo() {
                    return applicationInfo;
                }

                @NonNull
                @Override
                public ClassLoader getDefaultClassLoader() {
                    return defaultClassLoader;
                }

                @Override
                public boolean isFirstPackage() {
                    return isFirstPackage;
                }
        });
    }

    private static boolean shouldHandlePackage(LoadedApk loadedApk, String packageName,
                                               boolean isFirstPackage) {
        if (!isFirstPackage && !XposedHelpers.getBooleanField(loadedApk, "mIncludeCode")) {
            return false;
        }
        if (isFirstPackage) return true;
        var module = XposedInit.getLoadedModules().get(packageName);
        return module == null || module.isPresent();
    }

    private static ClassLoader getDefaultClassLoader(LoadedApk loadedApk,
                                                     ClassLoader classLoader) {
        if (defaultClassLoaderField != null) {
            try {
                ClassLoader defaultClassLoader =
                        (ClassLoader) defaultClassLoaderField.get(loadedApk);
                if (defaultClassLoader != null) {
                    return defaultClassLoader;
                }
            } catch (Throwable t) {
                Hookers.logD("LoadedApk#getDefaultClassLoader failed: " + t.getMessage());
            }
        }
        return classLoader;
    }

    private static AppComponentFactory getAppComponentFactory(LoadedApk loadedApk) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                && AppComponentFactoryFieldHolder.field != null) {
            try {
                return (AppComponentFactory) AppComponentFactoryFieldHolder.field.get(loadedApk);
            } catch (Throwable t) {
                Hookers.logD("LoadedApk#getAppComponentFactory failed: " + t.getMessage());
            }
        }
        return null;
    }

    @Override
    public Object intercept(XposedInterface.Chain chain) throws Throwable {
        var result = chain.proceed();
        afterIntercept(chain);
        return result;
    }

    private void afterIntercept(XposedInterface.Chain chain) {
        LoadedApk loadedApk = (LoadedApk) chain.getThisObject();

        if (chain.getArg(0) != null) {
            return;
        }

        Boolean packageLoaded = loadedApks.remove(loadedApk);
        if (packageLoaded == null) return;

        try {
            Hookers.logD("LoadedApk#createClassLoader starts");

            String packageName = loadedApk.getPackageName();
            String processName = ActivityThread.currentProcessName();
            boolean isFirstPackage = packageName.equals(ActivityThread.currentPackageName());
            if (isFirstPackage && packageName.equals("android")) {
                packageName = "system";
            }

            ApplicationInfo applicationInfo = loadedApk.getApplicationInfo();
            if (processName == null) processName = applicationInfo.processName;
            String appDir = applicationInfo.sourceDir;
            ClassLoader classLoader = loadedApk.getClassLoader();
            Hookers.logD("LoadedApk#createClassLoader ends: " + appDir + " -> " + classLoader);

            if (classLoader == null) {
                return;
            }

            if (!packageLoaded && !shouldHandlePackage(loadedApk, loadedApk.getPackageName(),
                    isFirstPackage)) {
                Hookers.logD("LoadedApk package does not include code or is excluded: " + appDir);
                return;
            }

            XC_LoadPackage.LoadPackageParam lpparam = new XC_LoadPackage.LoadPackageParam(
                    XposedBridge.sLoadedPackageCallbacks);
            lpparam.packageName = packageName;
            lpparam.processName = processName;
            lpparam.classLoader = classLoader;
            lpparam.appInfo = applicationInfo;
            lpparam.isFirstApplication = isFirstPackage;

            if (isFirstPackage && XposedInit.getLoadedModules().getOrDefault(packageName, Optional.empty()).isPresent()) {
                hookNewXSP(lpparam);
            }

            XC_LoadPackage.callAll(lpparam);

            if (!LSPosedContext.hasModules()) return;

            ClassLoader defaultClassLoader = getDefaultClassLoader(loadedApk, classLoader);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !packageLoaded) {
                dispatchOnPackageLoaded(loadedApk, defaultClassLoader, isFirstPackage);
            }
            AppComponentFactory appComponentFactory = getAppComponentFactory(loadedApk);
            LSPosedContext.callOnPackageReady(new XposedModuleInterface.PackageReadyParam() {
                @NonNull
                @Override
                public String getPackageName() { return applicationInfo.packageName; }

                @NonNull
                @Override
                public ApplicationInfo getApplicationInfo() { return applicationInfo; }

                @Override
                public boolean isFirstPackage() { return isFirstPackage; }

                @NonNull
                @Override
                public ClassLoader getDefaultClassLoader() { return defaultClassLoader; }

                @NonNull
                @Override
                public ClassLoader getClassLoader() { return classLoader; }

                @Override
                public AppComponentFactory getAppComponentFactory() { return appComponentFactory; }
            });
            Hookers.logD("Call handleLoadedPackage: packageName=" + lpparam.packageName + " processName=" + lpparam.processName + " isFirstPackage=" + isFirstPackage + " classLoader=" + lpparam.classLoader + " appInfo=" + lpparam.appInfo);
        } catch (Throwable t) {
            Hookers.logE("error when hooking LoadedApk#createClassLoader", t);
        }
    }

    private static void hookNewXSP(XC_LoadPackage.LoadPackageParam lpparam) {
        int xposedminversion = -1;
        boolean xposedsharedprefs = false;
        try {
            Map<String, Object> metaData = MetaDataReader.getMetaData(new File(lpparam.appInfo.sourceDir));
            Object minVersionRaw = metaData.get("xposedminversion");
            if (minVersionRaw instanceof Integer) {
                xposedminversion = (Integer) minVersionRaw;
            } else if (minVersionRaw instanceof String) {
                xposedminversion = MetaDataReader.extractIntPart((String) minVersionRaw);
            }
            xposedsharedprefs = metaData.containsKey("xposedsharedprefs");
        } catch (NumberFormatException | IOException e) {
            Hookers.logE("ApkParser fails", e);
        }

        if (xposedminversion > 92 || xposedsharedprefs) {
            Utils.logI("New modules detected, hook preferences");
            XposedHelpers.findAndHookMethod("android.app.ContextImpl", lpparam.classLoader, "checkMode", int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (((int) param.args[0] & 1/*Context.MODE_WORLD_READABLE*/) != 0) {
                        param.setThrowable(null);
                    }
                }
            });
            XposedHelpers.findAndHookMethod("android.app.ContextImpl", lpparam.classLoader, "getPreferencesDir", new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) {
                    return new File(serviceClient.getPrefsPath(lpparam.packageName));
                }
            });
        }
    }
}
