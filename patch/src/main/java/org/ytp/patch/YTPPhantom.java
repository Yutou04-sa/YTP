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

import com.android.tools.build.apkzlib.zip.ZFile;

import org.ytp.patch.util.Logger;
import org.ytp.patch.util.ManifestParser;
import org.ytp.share.Apk;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipException;

import bin.zip.DataMultiplexing;
import bin.zip.ZipEntry;
import bin.zip.ZipFile;

/**
 * 处理外层 ZIP 的 CRC 已失效、但仍可通过项目内 ZIP 读取器提取内嵌 APK 的容器。
 */
public class YTPPhantom extends YTPPatch {

    private PatchWorkspace workspace;

    public YTPPhantom(Logger logger, Apk apk, String... args) {
        super(logger, args);
        this.apk = apk;
    }

    /** 只接管 CRC 校验失败或无法定位内嵌原包的回退错误。 */
    static boolean canHandleFallback(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message == null) {
                continue;
            }

            String normalized = message.toLowerCase(Locale.ROOT);
            if (cause instanceof ZipException
                    && normalized.contains("crc")
                    && (normalized.contains("invalid") || normalized.contains("mismatch"))) {
                return true;
            }
            if (cause instanceof YTPPatch.PatchError
                    && normalized.contains("original apk patch not found for package")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 为 CRC 异常容器创建独立工作区，重建并签名最终结果。
     * 提取失败也会清理本次工作区，后续任务不会读到旧条目。
     */
    public void patchPhantom(File srcApkFile, File destApkFile) throws Exception {
        logger.i("-----------------------------------");
        logger.i("Extend2 Parsing Starting");

        workspace = PatchWorkspace.create(destApkFile, "ytp-phantom-");
        logger.d("Temporary directory: " + workspace.directory());
        apk.getPatchFile().clear();
        try {
            extractContainer(srcApkFile);
            rebuildApk(destApkFile, apk.getOriginalApkPatch());
        } finally {
            deleteRecursively(workspace.directory());
        }
    }

    /** 使用随机访问 ZIP 读取器提取条目，绕过标准流式读取器的旧 CRC 校验。 */
    private void extractContainer(File srcApkFile) throws IOException {
        logger.i("Analyse apk...");
        try (ZipFile zipFile = new ZipFile(srcApkFile)) {
            byte[] buffer = new byte[64 * 1024];
            Map<String, String> packageNameAndOriginalApkPatch = new HashMap<>();

            for (ZipEntry entry : zipFile.getEntries()) {
                if (entry.isDirectory()) {
                    continue;
                }

                File outputFile = workspace.resolveEntry(entry.getName());

                try (InputStream entryInputStream = zipFile.getInputStream(entry);
                     FileOutputStream output = new FileOutputStream(outputFile)) {
                    int length;
                    while ((length = entryInputStream.read(buffer)) != -1) {
                        output.write(buffer, 0, length);
                    }
                }

                if (isEmbeddedApkCandidate(entry.getName())) {
                    logger.d("Processing embedded APK candidate: " + entry.getName());
                    processEmbeddedApk(outputFile, packageNameAndOriginalApkPatch, entry.getName());
                }

                apk.addPatchFile(entry.getName(), outputFile.getAbsolutePath());
            }

            apk.setOriginalApkPatch(packageNameAndOriginalApkPatch.get(apk.getPackageName()));
            if (apk.getOriginalApkPatch() == null) {
                throw new PatchError("Warning: Original APK patch not found for package " + apk.getPackageName());
            }
            logger.d("Original APK patch: " + apk.getOriginalApkPatch());
        }
    }

    /** 此类容器可能把原包伪装为 .dat，因此两种后缀都作为候选。 */
    private boolean isEmbeddedApkCandidate(String entryName) {
        return entryName.endsWith(".apk") || entryName.endsWith(".dat");
    }

    private void processEmbeddedApk(File apkFile, Map<String, String> packageNameMap, String entryName) {
        try (ZipFile originalApk = new ZipFile(apkFile)) {
            ZipEntry manifestEntry = originalApk.getEntry(ANDROID_MANIFEST_XML);
            if (manifestEntry != null) {
                try (InputStream input = originalApk.getInputStream(manifestEntry)) {
                    ManifestParser.Pair originalPair = ManifestParser.parseManifestFile(input);
                    if (originalPair != null) {
                        packageNameMap.put(originalPair.packageName, entryName);
                    }
                }
            }
        } catch (Exception e) {
            logger.d("Could not parse embedded APK: " + apkFile.getName());
        }
    }

    private void rebuildApk(File destApkFile, String originalApkPatch) throws Exception {
        logger.i("Rebuilding APK...");
        if (originalApkPatch == null) {
            throw new PatchError("EmbeddedApkPath cannot be null");
        }

        File originalApk = workspace.resolveEntry(originalApkPatch);
        if (!originalApk.exists()) {
            throw new PatchError("Embedded APK not found: " + originalApkPatch);
        }

        // 此处必须保留未修补的原包：patchFile 中的 .dat 宿主仍要读取原始字节，
        // 否则会把修补后的容器再次嵌入自身，导致数据重复甚至无限增长。
        File baseApk = Files.createTempFile(workspace.directory().toPath(), "embedded-", ".apk").toFile();
        Files.copy(originalApk.toPath(), baseApk.toPath(), StandardCopyOption.REPLACE_EXISTING);

        // 修补内嵌 APK 是中间步骤，签名只在最终容器构建完成后进行。
        patchIntermediateApk(baseApk);

        // .dat 条目需保持 STORED，供数据复用器引用内嵌 APK 的原始数据。
        restoreStoredDatEntries(baseApk);

        logger.i("Optimize...");
        new DataMultiplexing(logger, super.monitorExecutor)
                .optimize(baseApk, destApkFile, apk.getOriginalApkPatch(), false);
        if (!deferOutputFinalization) {
            signApk(destApkFile);
        }
    }

    /**
     * 常规合并可能压缩 .dat，而复用器要求宿主条目为 STORED。
     * 只重写这类条目，避免重新压缩其他数据。
     */
    private void restoreStoredDatEntries(File baseApk) throws IOException {
        try (ZFile zFile = ZFile.openReadWrite(baseApk, Z_FILE_OPTIONS)) {
            for (Map.Entry<String, String> patchFile : apk.getPatchFile().entrySet()) {
                if (!patchFile.getKey().endsWith(".dat")) {
                    continue;
                }
                try (InputStream input = new FileInputStream(patchFile.getValue())) {
                    zFile.add(patchFile.getKey(), input, false);
                }
            }
        }
    }
}
