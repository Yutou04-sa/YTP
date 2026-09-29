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

import bin.mt.apksign.V2V3SchemeSigner;
import bin.mt.apksign.key.BksSignatureKey;
import bin.zip.DataMultiplexing;
import bin.zip.ZipEntry;
import bin.zip.ZipFile;

/**
 * Patcher for APKs whose fallback extraction fails with an invalid ZIP-entry
 * CRC while containing an embedded original APK.
 *
 * <p>This path is intentionally separate from {@link YTPPatch} and
 * {@link YTPExtend}. Ordinary APKs keep their existing patch and extend logic.</p>
 */
public class YTPPhantom extends YTPPatch {

    private File tempDir;

    public YTPPhantom(Logger logger, Apk apk, String... args) {
        super(logger, args);
        this.apk = apk;
    }

    /**
     * Returns whether the generic extend path failed in a way this strategy
     * can recover from. Detection is based on the observed failure, not on a
     * package-specific container entry name.
     */
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

    public void patchPhantom(File srcApkFile, File destApkFile) throws Exception {
        logger.i("-----------------------------------");
        logger.i("Extend2 Parsing Starting");

        File parent = destApkFile.getParentFile();
        File[] tempFiles = parent.listFiles((dir, name) -> name.startsWith("temp_"));
        if (tempFiles != null) {
            for (File tempFile : tempFiles) {
                deleteRecursively(tempFile);
            }
        }

        tempDir = new File(parent, "temp_" + System.currentTimeMillis());
        if (!tempDir.mkdirs()) {
            throw new PatchError("Warning: Could not create temporary directory " + tempDir.getAbsolutePath());
        }

        logger.d("Temporary directories created:" + tempDir.getAbsolutePath());
        try {
            extractContainer(srcApkFile);
            rebuildApk(destApkFile, apk.getOriginalApkPatch());
        } finally {
            if (tempDir.exists()) {
                deleteRecursively(tempDir);
            }
        }
    }

    /**
     * Extract using the project's random-access ZIP reader. It intentionally
     * does not validate the stale CRC that caused the fallback failure.
     */
    private void extractContainer(File srcApkFile) throws IOException {
        logger.i("Analyse apk...");
        try (ZipFile zipFile = new ZipFile(srcApkFile)) {
            byte[] buffer = new byte[8192];
            Map<String, String> packageNameAndOriginalApkPatch = new HashMap<>();

            for (ZipEntry entry : zipFile.getEntries()) {
                if (entry.isDirectory()) {
                    continue;
                }

                File outputFile = resolveEntryFile(entry.getName());
                File parent = outputFile.getParentFile();
                if (!parent.exists() && !parent.mkdirs()) {
                    throw new PatchError("Warning: Could not create parent directory for " + outputFile.getAbsolutePath());
                }

                try (InputStream entryInputStream = zipFile.getInputStream(entry);
                     FileOutputStream output = new FileOutputStream(outputFile)) {
                    int length;
                    while ((length = entryInputStream.read(buffer)) > 0) {
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

    private File resolveEntryFile(String entryName) throws IOException {
        File root = tempDir.getCanonicalFile();
        File outputFile = new File(root, entryName).getCanonicalFile();
        String rootPath = root.getPath() + File.separator;
        if (!outputFile.getPath().startsWith(rootPath)) {
            throw new IOException("ZIP entry escapes extraction directory: " + entryName);
        }
        return outputFile;
    }

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

        File originalApk = new File(tempDir, originalApkPatch);
        if (!originalApk.exists()) {
            throw new PatchError("Embedded APK not found: " + originalApkPatch);
        }

        File baseApk = destApkFile.getParentFile().toPath().resolve("base.apk").toFile();
        Files.copy(originalApk.toPath(), baseApk.toPath(), StandardCopyOption.REPLACE_EXISTING);

        // The normal YTPPatch implementation remains unchanged. It receives
        // the extracted container entries through Apk.patchFile.
        patch(baseApk, apk);

        // Only this special path needs .dat entries to remain STORED so the
        // original APK can be used as DataMultiplexing's ZIP host.
        restoreStoredDatEntries(baseApk);

        logger.i("Optimize...");
        new DataMultiplexing(logger, super.monitorExecutor)
                .optimize(baseApk, destApkFile, apk.getOriginalApkPatch(), false);
        V2V3SchemeSigner.sign(destApkFile,
                new BksSignatureKey(keystoreArgs.get(0), keystoreArgs.get(1),
                        keystoreArgs.get(2), keystoreArgs.get(3)), true, true);
    }

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
