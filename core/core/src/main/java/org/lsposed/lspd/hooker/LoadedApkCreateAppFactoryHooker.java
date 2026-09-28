/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.lsposed.lspd.hooker;

import android.app.LoadedApk;

import io.github.libxposed.api.XposedInterface;

/** Dispatches the pre-AppComponentFactory package lifecycle phase. */
public class LoadedApkCreateAppFactoryHooker implements XposedInterface.Hooker {

    @Override
    public Object intercept(XposedInterface.Chain chain) throws Throwable {
        LoadedApkCreateCLHooker.onDefaultClassLoaderReady(
                (LoadedApk) chain.getThisObject(),
                (ClassLoader) chain.getArg(1));
        return chain.proceed();
    }
}
