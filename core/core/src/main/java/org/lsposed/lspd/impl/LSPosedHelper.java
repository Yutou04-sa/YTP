package org.lsposed.lspd.impl;

import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.error.HookFailedError;

public class LSPosedHelper {

    @SuppressWarnings("UnusedReturnValue")
    public static <T> XposedInterface.HookHandle
    hookMethod(Class<? extends XposedInterface.Hooker> hookerClass, Class<T> clazz, String methodName, Class<?>... parameterTypes) {
        try {
            var method = clazz.getDeclaredMethod(methodName, parameterTypes);
            var hooker = hookerClass.getDeclaredConstructor().newInstance();
            return LSPosedBridge.doHook(method, XposedInterface.PRIORITY_DEFAULT, hooker);
        } catch (NoSuchMethodException e) {
            throw new HookFailedError(e);
        } catch (Exception e) {
            throw new HookFailedError("Failed to instantiate hooker: " + hookerClass.getName(), e);
        }
    }

    @SuppressWarnings("UnusedReturnValue")
    public static <T> Set<XposedInterface.HookHandle>
    hookAllMethods(Class<? extends XposedInterface.Hooker> hookerClass, Class<T> clazz, String methodName) {
        var unhooks = new HashSet<XposedInterface.HookHandle>();
        try {
            var hooker = hookerClass.getDeclaredConstructor().newInstance();
            for (var method : clazz.getDeclaredMethods()) {
                if (method.getName().equals(methodName)) {
                    unhooks.add(LSPosedBridge.doHook(method, XposedInterface.PRIORITY_DEFAULT, hooker));
                }
            }
        } catch (Exception e) {
            throw new HookFailedError("Failed to instantiate hooker: " + hookerClass.getName(), e);
        }
        return unhooks;
    }

    @SuppressWarnings("UnusedReturnValue")
    public static <T> XposedInterface.HookHandle
    hookConstructor(Class<? extends XposedInterface.Hooker> hookerClass, Class<T> clazz, Class<?>... parameterTypes) {
        try {
            var constructor = clazz.getDeclaredConstructor(parameterTypes);
            var hooker = hookerClass.getDeclaredConstructor().newInstance();
            return LSPosedBridge.doHook(constructor, XposedInterface.PRIORITY_DEFAULT, hooker);
        } catch (NoSuchMethodException e) {
            throw new HookFailedError(e);
        } catch (Exception e) {
            throw new HookFailedError("Failed to instantiate hooker: " + hookerClass.getName(), e);
        }
    }
}