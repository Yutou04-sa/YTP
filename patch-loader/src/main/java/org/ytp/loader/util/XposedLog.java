/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.loader.util;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;

public class XposedLog {

    private static FileOutputStream out;

    @SuppressLint("ConstantLocale")
    private static final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

    @SuppressLint("ConstantLocale")
    private static final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private static final HashMap<Integer,String> printlnTags = new HashMap<>();

    private final Context context;
    private File dir;
    private File currentLogFile;

    public XposedLog(Context context) {
        this.context = context;
        printlnTags.put(Log.VERBOSE,"V");
        printlnTags.put(Log.INFO,"I");
        printlnTags.put(Log.WARN,"W");
        printlnTags.put(Log.ERROR,"E");
        printlnTags.put(Log.DEBUG,"D");

        initLogDirectoryAndFile();
        cleanOldLogs();
    }

    private void initLogDirectoryAndFile() {
        dir = FileUtils.baseExternalPath(context).resolve("log").toFile();
        if (!dir.exists()) {
            boolean success = dir.mkdirs();
            if (!success && !dir.exists()) {
                return;
            }
        }
        currentLogFile = FileUtils.baseExternalPath(context).resolve("log").resolve(format.format(new Date()) + ".log").toFile();
    }

    private void cleanOldLogs() {
        try {
            if (dir == null || !dir.exists()) {
                return;
            }

            long threeDaysAgo = System.currentTimeMillis() - (3L * 24 * 60 * 60 * 1000);
            File[] logFiles = dir.listFiles((file, name) -> name.endsWith(".log"));

            if (logFiles != null) {
                for (File logFile : logFiles) {
                    if (logFile.lastModified() < threeDaysAgo) {
                        boolean deleted = logFile.delete();
                        if (deleted) {
                            Log.d("XposedLog", "Deleted old log file: " + logFile.getName());
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e("XposedLog", "Error cleaning old logs", e);
        }
    }

    public void println(int level, String tag, String msg) {
        log("["+printlnTags.getOrDefault(level,"I")+"]"+tag+msg);
    }

    public void log(int level, String tag, String msg) {
        println(level, " ", msg);
    }

    private synchronized void log(String text){
        try {
            if (out == null){
                if(!dir.exists()){
                    boolean success = dir.mkdirs();
                    if (!success && !dir.exists()) {
                        return;
                    }
                }
                out = new FileOutputStream(currentLogFile, true);
            }
            out.write((fmt.format(new Date())+" "+text).getBytes());
            out.write("\n".getBytes());
        }catch (Exception e){
            Log.e("XposedLog", "Error writing log file", e);
        }
    }
}
