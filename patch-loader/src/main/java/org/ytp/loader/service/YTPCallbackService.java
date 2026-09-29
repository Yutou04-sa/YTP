/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.loader.service;

import android.content.Context;

import org.ytp.loader.util.XposedLog;
import org.ytp.share.Constants;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lsposed.lspd.interfaces.DataCallback;

import java.nio.file.Paths;

public class YTPCallbackService implements DataCallback {
    private final XposedLog xposedLog;
    private final Context context;

    public YTPCallbackService(Context context) {
        this.context = context;
        xposedLog = new XposedLog(context);
    }


    /**
     * XposedBridge.log & org.lsposed.lspd.util.utils
     * @param level
     * @param tag
     * @param msg
     */
    @Override
    public void log(int level,@Nullable String tag, @NotNull String msg) {
        xposedLog.log(level,null, msg);
    }

    /**
     * org.lsposed.lspd.util.utils.println
     * @param level
     * @param tag
     * @param msg
     */
    @Override
    public void println(int level, String tag, String msg) {
        xposedLog.println(level, " ["+tag+"] ", msg);
    }

    @Override
    public String getModuleConfigPath() {
        return Paths.get(context.getFilesDir().getAbsolutePath(), Constants.LOWER_CASE_NAME).toString();
    }


}
