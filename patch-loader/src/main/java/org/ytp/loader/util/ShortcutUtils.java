/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.loader.util;

import android.app.Activity;
import android.app.ActivityThread;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Icon;
import android.os.PersistableBundle;
import android.util.Log;

import org.ytp.share.Constants;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class ShortcutUtils {

    private  static final String TAG = "YTP";
    private static final String SHORTCUT_VERSION_KEY = "org.ytp.shortcut.VERSION";
    /** 加 1 会让已经打过补丁的应用在下次启动时重写快捷方式（换了图标也要重写）。 */
    private static final int SHORTCUT_VERSION = 2;
    /** 快捷方式图标（一扇门）用的颜色，和初音青主题保持一致。 */
    private static final int DOOR_FRAME_COLOR = 0xFF39C5BB;
    private static final int DOOR_EDGE_COLOR = 0xFF14776F;
    private static final int DOOR_PANEL_COLOR = 0xFFF1FCFB;
    private static final ThreadLocal<Boolean> internalShortcutCall =
            ThreadLocal.withInitial(() -> false);
    private static boolean shortcutHooksInstalled = false;

    public static void registerShortcut(Context context){
        // Hook 必须先安装，避免宿主在异步发布完成前覆盖或删除 YTP 快捷方式。
        installShortcutHooks(context);
        // ShortcutManager 的 Binder 调用放到后台线程，避免阻塞宿主启动。
        pushShortcut(context);
    }

    /**
     * 将模块管理快捷方式写入ShortcutManager（binder调用，较慢）
     */
    private static void pushShortcut(Context context) {
        try {
            ShortcutManager shortcutManager = context.getSystemService(ShortcutManager.class);
            if (shortcutManager == null) {
                Log.w(TAG, "ShortcutManager is unavailable");
                return;
            }
            if (hasOnlyExpectedShortcut(shortcutManager.getDynamicShortcuts(), context)) {
                Log.d(TAG, "YTP shortcut is already up to date");
                return;
            }
            boolean success = setOnlyYtpShortcut(shortcutManager, context);
            if (success) {
                Log.d(TAG, "Registered YTP shortcut");
            } else {
                Log.w(TAG, "Shortcut registration was rate-limited");
            }
        } catch (Throwable e) {
            Log.w(TAG, "Failed to register YTP shortcut", e);
        }
    }

    /**
     * 同步安装所有Hook，需在进程启动早期完成，保证拦截逻辑尽快生效
     */
    private static synchronized void installShortcutHooks(Context context) {
        if (shortcutHooksInstalled) {
            return;
        }
        shortcutHooksInstalled = true;
        hookModuleActivityLoading();

        XC_MethodHook replaceDynamicShortcuts = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (isInternalShortcutCall()) {
                    return;
                }
                try {
                    boolean success = setOnlyYtpShortcut(
                            (ShortcutManager) param.thisObject, context);
                    param.setResult(success);
                } catch (Throwable e) {
                    Log.w(TAG, "Failed to replace dynamic shortcuts", e);
                    param.setResult(false);
                }
            }
        };
        hookShortcutMethod("setDynamicShortcuts", replaceDynamicShortcuts, List.class);
        hookShortcutMethod("addDynamicShortcuts", replaceDynamicShortcuts, List.class);
        hookShortcutMethod("updateShortcuts", replaceDynamicShortcuts, List.class);

        hookShortcutMethod("removeDynamicShortcuts", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isInternalShortcutCall()) {
                    param.args[0] = withoutYtpId(param.args[0]);
                }
            }
        }, List.class);

        hookShortcutMethod("removeAllDynamicShortcuts", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isInternalShortcutCall()) {
                    param.setResult(null);
                }
            }
        });

        hookShortcutMethod("removeLongLivedShortcuts", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isInternalShortcutCall()) {
                    param.args[0] = withoutYtpId(param.args[0]);
                }
            }
        }, List.class);

        // 宿主通过 push 发布的新快捷方式一律丢弃，保持动态列表只有 YTP。
        hookShortcutMethod("pushDynamicShortcut", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isInternalShortcutCall()) {
                    param.setResult(null);
                }
            }
        }, ShortcutInfo.class);

        // disableShortcuts 有多个重载，必须 Hook ShortcutManager 而不是 ShortcutUtils。
        hookAllShortcutMethods("disableShortcuts", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isInternalShortcutCall() && param.args.length > 0) {
                    param.args[0] = withoutYtpId(param.args[0]);
                }
            }
        });

        // 不允许宿主重新启用旧快捷方式，只允许 YTP ID 通过。
        hookAllShortcutMethods("enableShortcuts", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isInternalShortcutCall() && param.args.length > 0) {
                    param.args[0] = onlyYtpId(param.args[0]);
                }
            }
        });
    }

    private static void hookShortcutMethod(String methodName, XC_MethodHook callback,
                                           Class<?>... parameterTypes) {
        Method method = XposedHelpers.findMethodExactIfExists(
                ShortcutManager.class, methodName, (Object[]) parameterTypes);
        if (method == null) {
            return;
        }
        try {
            XposedBridge.hookMethod(method, callback);
        } catch (Throwable e) {
            Log.w(TAG, "Failed to hook ShortcutManager." + methodName, e);
        }
    }

    private static void hookAllShortcutMethods(String methodName, XC_MethodHook callback) {
        try {
            XposedBridge.hookAllMethods(ShortcutManager.class, methodName, callback);
        } catch (Throwable e) {
            Log.w(TAG, "Failed to hook ShortcutManager." + methodName, e);
        }
    }

    private static boolean setOnlyYtpShortcut(ShortcutManager shortcutManager, Context context) {
        if (context == null) {
            return false;
        }
        internalShortcutCall.set(true);
        try {
            // 原子替换整个动态列表：成功时宿主 Shortcut 会被全部移除，只剩 YTP。
            // 不先 push，避免 push 失败后清理逻辑无法执行。
            return shortcutManager.setDynamicShortcuts(
                    Collections.singletonList(buildShortcut(context)));
        } finally {
            internalShortcutCall.remove();
        }
    }

    private static boolean isInternalShortcutCall() {
        return Boolean.TRUE.equals(internalShortcutCall.get());
    }

    @SuppressWarnings("unchecked")
    private static List<String> withoutYtpId(Object shortcutIds) {
        List<String> result = new java.util.ArrayList<>((List<String>) shortcutIds);
        result.removeIf(Constants.UPPER_CASE_NAME::equals);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<String> onlyYtpId(Object shortcutIds) {
        List<String> result = new java.util.ArrayList<>((List<String>) shortcutIds);
        result.removeIf(id -> !Constants.UPPER_CASE_NAME.equals(id));
        return result;
    }

    private static boolean classLoaderHookInstalled = false;
    private static final Set<Class<?>> hookedInstrumentationClasses =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static final XC_MethodHook moduleActivityClassLoaderHook = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            String className = (String) param.args[1];
            if (!Constants.MODULE_ACTIVITY.equals(className)) {
                return;
            }
            ClassLoader classLoader = (ClassLoader) param.args[0];
            try {
                classLoader.loadClass(className);
            } catch (ClassNotFoundException e) {
                // 部分自定义 Instrumentation（例如 Mira）会忽略传入的 ClassLoader，
                // 强制从自己的插件系统查找 Activity。直接完成系统默认的实例化操作，
                // 并跳过宿主的 newActivity 实现。
                Class<?> activityClass = ShortcutUtils.class.getClassLoader().loadClass(className);
                Activity activity = (Activity) activityClass.getDeclaredConstructor().newInstance();
                Log.d(TAG, "Instantiated ModuleActivity with YTP ClassLoader");
                param.setResult(activity);
            }
        }
    };

    /**
     * 兜底加载模块管理Activity
     * 过签/壳应用会替换进程的类加载器，导致系统在实例化
     * androidx.app.ModuleActivity 时 ClassNotFoundException。
     * 这里在实例化前若当前类加载器加载不到该类，则由 YTP 自身的类加载器直接实例化。
     * 部分宿主（如使用 Mira 的应用）会重写 Instrumentation.newActivity，调用不会经过
     * Instrumentation 基类。因此还需要在每次启动 Activity 前检查进程当前实际安装的
     * Instrumentation，并 Hook 它声明的重写方法。
     */
    public static synchronized void hookModuleActivityLoading() {
        if (classLoaderHookInstalled) {
            return;
        }
        classLoaderHookInstalled = true;
        try {
            hookInstrumentationNewActivity(Instrumentation.class);
            XposedBridge.hookAllMethods(ActivityThread.class, "performLaunchActivity", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Instrumentation instrumentation = (Instrumentation)
                                XposedHelpers.getObjectField(param.thisObject, "mInstrumentation");
                        if (instrumentation != null) {
                            hookInstrumentationNewActivity(instrumentation.getClass());
                        }
                    } catch (Throwable e) {
                        Log.w(TAG, "Failed to hook current Instrumentation", e);
                    }
                }
            });
        }catch (Throwable e){
            Log.w(TAG, "Failed to hook ModuleActivity ClassLoader", e);
        }
    }

    private static void hookInstrumentationNewActivity(Class<?> instrumentationClass) {
        for (Class<?> clazz = instrumentationClass;
             clazz != null && Instrumentation.class.isAssignableFrom(clazz);
             clazz = clazz.getSuperclass()) {
            Method newActivity = XposedHelpers.findMethodExactIfExists(
                    clazz, "newActivity", ClassLoader.class, String.class, Intent.class);
            if (newActivity == null || newActivity.getDeclaringClass() != clazz
                    || !hookedInstrumentationClasses.add(clazz)) {
                continue;
            }
            try {
                XposedBridge.hookMethod(newActivity, moduleActivityClassLoaderHook);
                Log.d(TAG, "Hooked Instrumentation: " + clazz.getName());
            } catch (Throwable e) {
                hookedInstrumentationClasses.remove(clazz);
                Log.w(TAG, "Failed to hook Instrumentation: " + clazz.getName(), e);
            }
        }
    }

    private static ShortcutInfo buildShortcut(Context context) {
        Intent intent = new Intent();
        intent.setClassName(context, Constants.MODULE_ACTIVITY);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setAction(Intent.ACTION_VIEW);

        PersistableBundle extras = new PersistableBundle();
        extras.putInt(SHORTCUT_VERSION_KEY, SHORTCUT_VERSION);

        String label= shortcutLabel();
        ShortcutInfo.Builder builder = new ShortcutInfo.Builder(
                context, Constants.UPPER_CASE_NAME)
                .setShortLabel(label)
                .setLongLabel(label)
                .setRank(0)
                .setIntent(intent)
                .setExtras(extras)
                .setIcon(buildDoorIcon(context));
        ComponentName launcherActivity = resolveLauncherActivity(context);
        if (launcherActivity != null) {
            builder.setActivity(launcherActivity);
        }
        return builder.build();
    }

    private static boolean hasOnlyExpectedShortcut(List<ShortcutInfo> shortcuts,
                                                   Context context) {
        if (shortcuts.size() != 1) {
            return false;
        }
        ShortcutInfo shortcut = shortcuts.get(0);
        if (!Constants.UPPER_CASE_NAME.equals(shortcut.getId())
                || !shortcut.isEnabled()
                || shortcut.getRank() != 0
                || !shortcutLabel().contentEquals(shortcut.getShortLabel())) {
            return false;
        }
        PersistableBundle extras = shortcut.getExtras();
        if (extras == null
                || extras.getInt(SHORTCUT_VERSION_KEY, 0) != SHORTCUT_VERSION) {
            return false;
        }
        Intent intent = shortcut.getIntent();
        if (intent == null
                || intent.getComponent() == null
                || !Constants.MODULE_ACTIVITY.equals(intent.getComponent().getClassName())) {
            return false;
        }
        ComponentName launcherActivity = resolveLauncherActivity(context);
        return launcherActivity == null || launcherActivity.equals(shortcut.getActivity());
    }

    private static String shortcutLabel() {
        return "zh".equals(Locale.getDefault().getLanguage()) ? "世界之门" : "World Gate";
    }

    /**
     * 快捷方式图标：现画一扇门。
     *
     * 补丁侧不能依赖自己的资源（loader 的 res 不会合进宿主 APK），所以直接用
     * {@link Bitmap} 画一扇初音青的门：门框 + 浅色门板 + 深色门把手。
     */
    private static Icon buildDoorIcon(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        int size = Math.max(1, Math.round(48f * density));
        float s = size;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        RectF frame = new RectF(s * 0.20f, s * 0.07f, s * 0.80f, s * 0.93f);
        float frameRadius = s * 0.09f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(DOOR_FRAME_COLOR);
        canvas.drawRoundRect(frame, frameRadius, frameRadius, paint);

        RectF panel = new RectF(s * 0.31f, s * 0.18f, s * 0.69f, s * 0.83f);
        paint.setColor(DOOR_PANEL_COLOR);
        canvas.drawRoundRect(panel, s * 0.05f, s * 0.05f, paint);

        paint.setColor(DOOR_EDGE_COLOR);
        canvas.drawCircle(s * 0.59f, s * 0.52f, s * 0.045f, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(s * 0.045f);
        RectF outline = new RectF(
                frame.left + s * 0.0225f,
                frame.top + s * 0.0225f,
                frame.right - s * 0.0225f,
                frame.bottom - s * 0.0225f);
        canvas.drawRoundRect(outline, frameRadius, frameRadius, paint);

        return Icon.createWithBitmap(bitmap);
    }

    private static ComponentName resolveLauncherActivity(Context context) {
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(context.getPackageName());
        ResolveInfo resolveInfo = context.getPackageManager().resolveActivity(launcherIntent, 0);
        if (resolveInfo == null || resolveInfo.activityInfo == null) {
            return null;
        }
        return new ComponentName(
                resolveInfo.activityInfo.packageName, resolveInfo.activityInfo.name);
    }
}
