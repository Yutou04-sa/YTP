/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.patch;

import static org.ytp.share.Constants.ANDROID_MANIFEST_XML;

import org.ytp.patch.util.Logger;
import org.ytp.patch.util.ManifestParser;
import org.ytp.share.Apk;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipInputStream;

import bin.zip.DataMultiplexing;
import bin.zip.ZipEntry;
import bin.zip.ZipFile;

/** 标准 ZIP 解压回退：提取外层条目，修补匹配包名的内嵌 APK，再复用外层数据。 */
public class YTPExtend extends YTPPatch {

    private PatchWorkspace workspace;

    /** 创建与主流程共享 APK 状态和命令行配置的回退修补器。 */
    public YTPExtend(Logger logger, Apk apk, String... args) {
        super(logger, args);
        this.apk = apk;
    }

    /** 普通 ZFile 报告重叠条目时，尝试按标准 ZIP 流提取并重建。 */
    static boolean canHandleFallback(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains("overlaps with")) {
                return true;
            }
        }
        return false;
    }

    /** 提取容器并重建结果；工作目录始终由本次调用独占和清理。 */
    public void patchExtend(File srcApkFile, File destApkFile) throws Exception {
        logger.i("-----------------------------------");
        logger.i("Extend Parsing Starting");
        workspace = PatchWorkspace.create(destApkFile, "ytp-extend-");
        logger.d("Temporary directory: " + workspace.directory());
        // 前一次尝试可能留下指向已清理文件的映射，重新提取前先清空。
        apk.getPatchFile().clear();
        try {
            unZipApk(srcApkFile);
            rebuildApkUsingStandardTools(destApkFile, apk.getOriginalApkPatch());
        } finally {
            deleteRecursively(workspace.directory());
        }
    }

    /**
     * 逐条目解压外层 ZIP，并根据内嵌 Manifest 的包名定位真正的原包。
     * 使用单个固定缓冲区，条目名经工作空间校验，避免路径逃逸。
     */
    private void unZipApk(File srcApkFile) throws IOException {
        logger.i("Analyse apk...");
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(srcApkFile))) {
            java.util.zip.ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            Map<String, String> packageNameAndOriginalApkPatch = new HashMap<>();

            while ((entry = zis.getNextEntry()) != null) {
                File outputFile = workspace.resolveEntry(entry.getName());

                if (!entry.isDirectory()) {
                    try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                        int len;
                        while ((len = zis.read(buffer)) != -1) {
                            fos.write(buffer, 0, len);
                        }
                    }
                    if (entry.getName().endsWith(".apk")) {
                        logger.d("Processing APK: " + entry.getName());
                        processEmbeddedApk(outputFile, packageNameAndOriginalApkPatch, entry.getName());
                    }
                    // 嵌套 APK 修补时，外层条目将作为待合并补丁。
                    apk.addPatchFile(entry.getName(), outputFile.getAbsolutePath());
                }
                zis.closeEntry();
            }
            
            apk.setOriginalApkPatch(packageNameAndOriginalApkPatch.get(apk.getPackageName()));
            if(apk.getOriginalApkPatch()==null){
                throw new PatchError("Warning: Original APK patch not found for package " + apk.getPackageName());
            }
            logger.d("Original APK patch: " + apk.getOriginalApkPatch());
        }
    }

    /** 容器可包含多个 APK 候选项，按 Manifest 包名登记其外层条目名。 */
    private void processEmbeddedApk(File apkFile, Map<String, String> packageNameMap, String entryName) {
        try (ZipFile originalApk = new ZipFile(apkFile)) {
            ZipEntry manifestEntry = originalApk.getEntry(ANDROID_MANIFEST_XML);
            if (manifestEntry != null) {
                try (var is = originalApk.getInputStream(manifestEntry)) {
                    ManifestParser.Pair originalPair = ManifestParser.parseManifestFile(is);
                    if (originalPair != null) {
                        packageNameMap.put(originalPair.packageName,entryName);
                    }
                }
            }
        } catch (Exception e) {
            // 容器中可能包含并非 Android APK 的 .apk 文件，继续检查其他候选项。
            logger.d("Could not parse embedded APK: " + apkFile.getName());
        }
    }

    /** 修补内嵌 APK，再把未重复的数据写入最终容器并签名。 */
    private void rebuildApkUsingStandardTools(File destApkFile, String originApkPatch) throws Exception {
        logger.i("Rebuilding Apk ...");
        
        if (originApkPatch == null) {
            throw new PatchError("EmbeddedApkPath cannot be null");
        }
        
        File originApk = workspace.resolveEntry(originApkPatch);
        if (!originApk.exists()) {
            throw new PatchError("Embedded APK not found: " + originApkPatch);
        }
        // patchIntermediateApk 本身会复制工作副本；这里直接使用提取文件，避免再次复制整个内嵌 APK。
        File baseApk = originApk;
        try {
            patchIntermediateApk(baseApk);
            logger.i("Optimize...");
            new DataMultiplexing(logger ,super.monitorExecutor).optimize(baseApk, destApkFile, apk.getOriginalApkPatch(), false);
            if (!deferOutputFinalization) {
                signApk(destApkFile);
            }
        } catch (YTPPatch.PatchError e) {
            throw new PatchError("Failed to patch embedded APK", e);
        }
    }
}
