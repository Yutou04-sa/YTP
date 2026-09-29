/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package androidx.app;

import static org.ytp.share.Constants.LIB_ASSET_PATH;
import static org.ytp.share.Constants.LOWER_CASE_NAME;

import android.annotation.SuppressLint;
import android.app.ActivityThread;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Environment;
import android.util.Log;

import org.ytp.share.Constants;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;


@SuppressLint("UnsafeDynamicallyLoadedCode")
public class Init {

    private static final String TAG = "YTP";
    private static final Map<String, String> archToLib = new HashMap<>(4);
    private static final String ASSETS_PREFIX = "assets/";
    private static final int BUFFER_SIZE = 8192;

    public static byte[] core;

    private static String arch;

    static {
        initArch();
    }

    public static void load() {
        loadInternal(null);
    }

    public static void load(Context context) {
        loadInternal(context);
    }

    private static void loadInternal(Context context) {
        try {
            Log.i(TAG, "Initialize YTP");
            if (ActivityThread.currentActivityThread() == null) {
                Log.i(TAG, "Skip loading so for appZygote");
                return;
            }

            if (arch == null) {
                throw new IllegalStateException("Arch is null, architecture not supported");
            }

            ClassLoader cl = context != null ? context.getClassLoader() : Objects.requireNonNull(Init.class.getClassLoader());

            Log.i(TAG, "Loading core.so");
            try (InputStream is = getFileToStream(context, cl, Constants.LOADER_CORE_SO_PATH);
                 ByteArrayOutputStream os = new ByteArrayOutputStream()) {
                transfer(is, os);
                core = os.toByteArray();
            }

            String soAssetPath = String.format(LIB_ASSET_PATH, arch);
            // context == null 时 extractSoFile 只返回 apk 内路径（<apk>!/assets/ytp/so/<abi>/libytp.so），
            // nativeloader 能直接从这个路径加载（Android 10+ 走 clns 命名空间），不需要落到 cache。
            String soFile = soPath(extractSoFile(context, soAssetPath, cl));
            Log.i(TAG, "loader so:" + soFile);
            try {
                System.load(soFile);
            } catch (UnsatisfiedLinkError e) {
                // 备选路径：先试 APK 自己的 nativeLibraryDir（若补丁包带了 lib/<abi>/libytp.so），
                // 再退回把 so 解到 cache 目录（API 28 及以下可行，API 29+ 因 untrusted_app 禁止
                // 执行应用数据目录里的文件而可能失败——所以放在最后）。
                Log.w(TAG, "Failed to load so from: " + soFile + ", trying nativeLibraryDir / cache dir", e);
                fallbackLoad(context, cl, soFile, soAssetPath);
            }
            Log.i(TAG, context != null ? "Initialize Application Success" : "Initialize AppComponentFactory Success");
        } catch (Throwable e) {
            Log.e(TAG, "Failed to initialize", e);
            writeCrashLog(context, e);
            throw new ExceptionInInitializerError(e);
        }
    }

    private static InputStream getFileToStream(Context context, ClassLoader cl, String path) throws IOException {
        if (context != null) {
            return context.getAssets().open(removeAssetsPrefix(path));
        }
        InputStream is = cl.getResourceAsStream(path);
        if (is == null) {
            throw new IOException("Cannot find core.so in classloader");
        }
        return is;
    }

    private static String extractSoFile(Context context, String soAssetPath, ClassLoader cl) throws IOException {
        if (context == null) {
            return cl.getResource(String.format(LIB_ASSET_PATH, arch)).getPath();
        }

        String libPatch = removeAssetsPrefix(soAssetPath);
        File outFile = new File(context.getCacheDir(), libPatch);

        if (outFile.exists()) {
            outFile.delete();
        }

        File parentDir = outFile.getParentFile();
        if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
            throw new IOException("Failed to create directory: " + parentDir.getAbsolutePath());
        }

        try (InputStream is = context.getAssets().open(libPatch);
             FileOutputStream fos = new FileOutputStream(outFile)) {
            copyStream(is, fos);
        }

        if (!outFile.setExecutable(true, false)) {
            Log.w(TAG, "Failed to set executable permission for: " + outFile.getAbsolutePath());
        }
        return outFile.getAbsolutePath();
    }

    private static String removeAssetsPrefix(String path) {
        return path != null && path.startsWith(ASSETS_PREFIX) ? path.substring(ASSETS_PREFIX.length()) : path;
    }

    private static void copyStream(InputStream is, OutputStream os) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int n;
        while (-1 != (n = is.read(buffer))) {
            os.write(buffer, 0, n);
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    private static void initArch() {
        archToLib.put("arm", "armeabi-v7a");
        archToLib.put("arm64", "arm64-v8a");
        archToLib.put("x86", "x86");
        archToLib.put("x86_64", "x86_64");
        try {
            Class<?> VMRuntime = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = VMRuntime.getDeclaredMethod("getRuntime");
            getRuntime.setAccessible(true);
            Method vmInstructionSet = VMRuntime.getDeclaredMethod("vmInstructionSet");
            vmInstructionSet.setAccessible(true);
            String detectedArch = (String) vmInstructionSet.invoke(getRuntime.invoke(null));
            arch = archToLib.get(detectedArch);
            if (arch == null) {
                Log.e(TAG, "Unsupported architecture: " + detectedArch);
            }
        } catch (Throwable e) {
            Log.e(TAG, "Failed to detect architecture", e);
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * 从 file:/xxx 或 jar:file:/xxx 中截取真实文件路径
     */
    public static String soPath(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        int index = path.lastIndexOf(":");
        return index != -1 ? path.substring(index + 1) : path;
    }

    private static void transfer(InputStream is, OutputStream os) throws IOException {
        byte[] buffer = new byte[8192];
        int n;
        while (-1 != (n = is.read(buffer))) {
            os.write(buffer, 0, n);
        }
    }

    /**
     * 降级加载：先试 APK 的 nativeLibraryDir（补丁包带 lib/<abi>/libytp.so 时存在），
     * 再退回把 so 写到 cache 目录加载（API 28 及以下可行）。
     */
    private static void fallbackLoad(Context context, ClassLoader cl, String failedSoFile, String soAssetPath) throws Throwable {
        String libName = new File(failedSoFile).getName();

        try {
            String nativeLibraryDir = getAppInfo().nativeLibraryDir;
            if (nativeLibraryDir != null) {
                File nativeSoFile = new File(nativeLibraryDir, libName);
                if (nativeSoFile.isFile()) {
                    Log.i(TAG, "Loading so from nativeLibraryDir: " + nativeSoFile.getAbsolutePath());
                    System.load(nativeSoFile.getAbsolutePath());
                    return;
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "Failed to load so from nativeLibraryDir", e);
        }

        File cacheDir = context != null 
            ? Paths.get(context.getCacheDir().getAbsolutePath()).resolve(LOWER_CASE_NAME).toFile()
            : Paths.get(getDataDir()).resolve("cache").resolve(LOWER_CASE_NAME).toFile();
        
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw new IOException("Failed to create cache directory: " + cacheDir.getAbsolutePath());
        }
        
        File cacheSoFile = new File(cacheDir, libName);
        if (cacheSoFile.exists()) {
            cacheSoFile.delete();
        }
        
        try (InputStream is = getFileToStream(context, cl, soAssetPath);
             FileOutputStream fos = new FileOutputStream(cacheSoFile)) {
            copyStream(is, fos);
        }
        
        if (!cacheSoFile.setExecutable(true, false)) {
            Log.w(TAG, "Failed to set executable permission for: " + cacheSoFile.getAbsolutePath());
        }
        
        Log.i(TAG, "Loading so from cache: " + cacheSoFile.getAbsolutePath());
        System.load(cacheSoFile.getAbsolutePath());
    }

    @SuppressLint("PrivateApi")
    private static ApplicationInfo getAppInfo() {
        try {
            ActivityThread activityThread = ActivityThread.currentActivityThread();
            if (activityThread == null) {
                throw new IllegalStateException("ActivityThread is null");
            }
            Field mBoundApplication = activityThread.getClass().getDeclaredField("mBoundApplication");
            mBoundApplication.setAccessible(true);
            Object boundApp = mBoundApplication.get(activityThread);
            if (boundApp == null) {
                throw new IllegalStateException("mBoundApplication is null");
            }
            Field appInfo = boundApp.getClass().getDeclaredField("appInfo");
            appInfo.setAccessible(true);
            return (ApplicationInfo) appInfo.get(boundApp);
        } catch (Throwable e) {
            Log.e(TAG, "Failed to get app info", e);
            throw new RuntimeException("Cannot get application info", e);
        }
    }

    /**
     * 获取应用数据目录（当context为null时使用）
     */
    private static String getDataDir() {
        return getAppInfo().dataDir;
    }

    /**
     * 将崩溃日志写入 /data/<package>/files/ytp_crash_logs 目录
     * 仅在异常时调用，不影响主流程
     */
    private static void writeCrashLog(Context context, Throwable throwable) {
        try {
            File crashDir;
            String packageName = getAppInfo().packageName;
            File externalFilesDir = context != null ? context.getExternalFilesDir(null) : null;
            if (externalFilesDir != null) {
                // /storage/emulated/0/Android/data/<pkg>/files/ytp/log
                crashDir = Paths.get(externalFilesDir.getAbsolutePath())
                        .resolve(TAG.toLowerCase())
                        .resolve("log")
                        .toFile();
            } else {
                crashDir = Paths.get(Environment.getExternalStorageDirectory().getAbsolutePath())
                        .resolve("Android")
                        .resolve("data")
                        .resolve(packageName)
                        .resolve("files")
                        .resolve(TAG.toLowerCase())
                        .resolve("log")
                        .toFile();
            }

            if (!crashDir.exists()) {
                if (!crashDir.mkdirs()) {
                    Log.w(TAG, "Failed to create crash log directory: " + crashDir.getAbsolutePath());
                    return;
                }
            }

            String date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
            String fileName =  "error.log";
            File crashFile = new File(crashDir, fileName);

            try (PrintWriter writer = new PrintWriter(new FileWriter(crashFile))) {
                writer.println("=== Crash Report ===");
                writer.println("Date: " + date);
                writer.println("Architecture: " + arch);
                writer.println("Package: " + packageName);
                writer.println();
                writer.println("=== Exception Details ===");
                throwable.printStackTrace(writer);
                writer.println();
                writer.println("=== Stack Trace ===");
                for (StackTraceElement element : throwable.getStackTrace()) {
                    writer.println("\tat " + element.toString());
                }
                writer.println();
                writer.println("=== End of Report ===");
            }

            Log.i(TAG, "Crash log written to: " + crashFile.getAbsolutePath());
        } catch (Throwable e) {
            // 静默失败，避免影响主流程
            Log.e(TAG, "Failed to write crash log", e);
        }
    }
}
