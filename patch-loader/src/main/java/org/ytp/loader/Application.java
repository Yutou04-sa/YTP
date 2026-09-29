/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.loader;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityThread;
import android.app.LoadedApk;
import android.content.Context;
import android.os.Process;
import android.util.Log;

import org.ytp.loader.service.YTPCallbackService;
import org.ytp.loader.service.LocalApplicationService;
import org.ytp.loader.util.FileUtils;
import org.ytp.loader.util.LoaderUtils;
import org.ytp.loader.util.ShortcutUtils;
import org.lsposed.lspd.core.Startup;
import org.lsposed.lspd.impl.LSPDataCallback;
import org.lsposed.lspd.service.ILSPApplicationService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import de.robv.android.xposed.XposedHelpers;

/**
 * Created by Windysha
 */
@SuppressWarnings("unused")
public class Application {

    private static final String TAG = "YTP";
    private static final int FIRST_APP_ZYGOTE_ISOLATED_UID = 90000;
    private static final int PER_USER_RANGE = 100000;

    private static ActivityThread activityThread;

    private static LoadedApk stubLoadedApk = null;

    @SuppressLint("StaticFieldLeak")
    public static Activity act = null;

    @SuppressLint("StaticFieldLeak")
    public static Context ctx = null;

    public static boolean isIsolated() {
        return (Process.myUid() % PER_USER_RANGE) >= FIRST_APP_ZYGOTE_ISOLATED_UID;
    }

    public static void onLoad() {
        if (isIsolated()) {
            Log.d(TAG, "Skip isolated process");
            return;
        }
        activityThread = ActivityThread.currentActivityThread();
        var context = createLoadedApkWithContext();
        if (context == null) {
            Log.e(TAG, "Error when creating context");
            return;
        }
        ctx = context;
        initPath(context);
        //初始化相关回调
        Log.d(TAG, "Register callback");
        LSPDataCallback.register(new YTPCallbackService(context));
        ShortcutUtils.registerShortcut(context);
        Log.d(TAG, "Initialize service client");
        ILSPApplicationService service = new LocalApplicationService(context);
        Startup.initXposed(false, ActivityThread.currentProcessName(), context.getApplicationInfo().dataDir, service);
        LoaderUtils.LoadModules(stubLoadedApk);
        LoaderUtils.disableProfile(context, PER_USER_RANGE);
        Log.i(TAG, "Modules initialized");

    }

    private static Context createLoadedApkWithContext() {
        try {
            var mBoundApplication = XposedHelpers.getObjectField(activityThread, "mBoundApplication");
            stubLoadedApk = (LoadedApk) XposedHelpers.getObjectField(mBoundApplication, "info");
            return (Context) XposedHelpers.callStaticMethod(Class.forName("android.app.ContextImpl"), "createAppContext", activityThread, stubLoadedApk);
        } catch (Throwable e) {
            Log.e(TAG, "createLoadedApk", e);
            return null;
        }
    }

    private static void initPath(Context context){
        try {
            Path basePath = FileUtils.basePath(context);
            if(!Files.exists(basePath)){
                Files.createDirectories(basePath);
            }
            Path baseExternalPath = FileUtils.baseExternalPath(context);
            if(!Files.exists(baseExternalPath)){
                Files.createDirectories(baseExternalPath);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}