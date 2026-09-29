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
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import bin.mt.apksign.V2V3SchemeSigner;
import bin.mt.apksign.key.BksSignatureKey;
import bin.zip.DataMultiplexing;

/**
 * Extended LSPatch class that handles APK patching using standard ZIP tools
 * as a fallback when the primary ZFile approach fails
 */
public class YTPExtend extends YTPPatch {

    private File tempDir;

    /**
     * Constructor to initialize LSPatch instance
     *
     * @param logger Logger instance
     * @param args   Command-line arguments
     */
    public YTPExtend(Logger logger, Apk apk, String... args) {
        super(logger, args);
        this.apk = apk;
    }

    /**
     * Uses standard ZIP tools to rebuild APK file
     * Called as fallback when ZFile approach fails
     * Mainly handles nested APK cases to ensure all files are packaged correctly
     *
     * @param srcApkFile  Source APK file
     * @param destApkFile Destination APK file
     * @throws IOException IO exception
     */
    public void patchExtend(File srcApkFile, File destApkFile) throws Exception {
        logger.i("-----------------------------------");
        logger.i("Extend Parsing Starting");
        
        // Clean up previous temp directories
        File[] tempFiles = destApkFile.getParentFile().listFiles((dir, name) -> name.startsWith("temp_"));
        if (tempFiles != null) {
            for (File tempFile : tempFiles) {
                deleteRecursively(tempFile);
            }
        }
        
        // Create temporary directories for file extraction
        tempDir = new File(destApkFile.getParentFile(), "temp_" + System.currentTimeMillis());
        if (!tempDir.mkdirs()) {
            throw new PatchError("Warning: Could not create temporary directory " + tempDir.getAbsolutePath());
        }

        logger.d("Temporary directories created:"+tempDir.getAbsolutePath());

        unZipApk(srcApkFile);
        rebuildApkUsingStandardTools(destApkFile, apk.getOriginalApkPatch());
    }

    /**
     * Extract all files to local extracted directory and read APK info
     *
     * @param srcApkFile Source APK file
     * @throws IOException IO exception
     */
    private void unZipApk(File srcApkFile) throws IOException {
        logger.i("Analyse apk...");
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(srcApkFile))) {
            ZipEntry entry;
            byte[] buffer = new byte[1024];
            Map<String, String> packageNameAndOriginalApkPatch = new HashMap<>();

            while ((entry = zis.getNextEntry()) != null) {
                File outputFile = resolveEntryFile(entry.getName());
                // Ensure parent directories exist
                if (!outputFile.getParentFile().exists() && !outputFile.getParentFile().mkdirs()) {
                    throw new PatchError("Warning: Could not create parent directory for " + outputFile.getAbsolutePath());
                }

                if (!entry.isDirectory()) {
                    try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                        
                        if (entry.getName().endsWith(".apk")) {
                            logger.d("Processing APK: " + entry.getName());
                            processEmbeddedApk(outputFile, packageNameAndOriginalApkPatch, entry.getName());
                        }
                    }
                    
                    // Add extracted file to patch file list
                    apk.addPatchFile(entry.getName(), outputFile.getAbsolutePath());
                }
                zis.closeEntry();
            }
            
            //logger.d("Total files extracted: " + fileCount);
            apk.setOriginalApkPatch(packageNameAndOriginalApkPatch.get(apk.getPackageName()));
            if(apk.getOriginalApkPatch()==null){
                throw new PatchError("Warning: Original APK patch not found for package " + apk.getPackageName());
            }
            logger.d("Original APK patch: " + apk.getOriginalApkPatch());
        }
    }

    /**
     * Resolves a zip entry to a file below the extraction directory.
     *
     * <p>A crafted entry name ("../../shared_prefs/x.xml") would otherwise be written outside of
     * the temp directory while the apk is being analysed.
     *
     * @param entryName Name of the zip entry
     * @return The file the entry has to be extracted to
     * @throws IOException when the entry escapes the extraction directory
     */
    private File resolveEntryFile(String entryName) throws IOException {
        File root = tempDir.getCanonicalFile();
        File outputFile = new File(root, entryName).getCanonicalFile();
        String rootPath = root.getPath() + File.separator;
        if (!outputFile.getPath().startsWith(rootPath)) {
            throw new IOException("ZIP entry escapes extraction directory: " + entryName);
        }
        return outputFile;
    }

    /**
     * Process embedded APK file
     *
     * @param apkFile Embedded APK file
     * @param packageNameMap Package name map to populate
     */
    private void processEmbeddedApk(File apkFile, Map<String, String> packageNameMap, String entryName) {
        try (var originalApk = ZFile.openReadOnly(apkFile)) {
            var manifestEntry = originalApk.get(ANDROID_MANIFEST_XML);
            if (manifestEntry != null) {
                try (var is = manifestEntry.open()) {
                    ManifestParser.Pair originalPair = ManifestParser.parseManifestFile(is);
                    if (originalPair != null) {
                        packageNameMap.put(originalPair.packageName,entryName);
                    }
                }
            }
        } catch (Exception e) {
            // Silently ignore errors when parsing embedded APKs
            logger.d("Could not parse embedded APK: " + apkFile.getName());
        }
    }

    /**
     * Rebuild APK using standard ZIP tools (with specified nested APK path)
     * Called as fallback when ZFile approach fails
     * Mainly handles nested APK cases to ensure all files are packaged correctly
     *
     * @param destApkFile     Destination APK file
     * @param originApkPatch Path to origin APK (relative to APK root)
     * @throws IOException IO exception
     */
    private void rebuildApkUsingStandardTools(File destApkFile, String originApkPatch) throws Exception {
        logger.i("Rebuilding Apk ...");
        
        if (originApkPatch == null) {
            throw new PatchError("EmbeddedApkPath cannot be null");
        }
        
        // Step 1: Process the specified embedded APK (if it exists)
        File originApk = new File(tempDir,originApkPatch);
        if (!originApk.exists()) {
            throw new PatchError("Embedded APK not found: " + originApkPatch);
        }
        File baseApk = destApkFile.getParentFile().toPath().resolve("base.apk").toFile();
        try {
            Files.copy(originApk.toPath(), baseApk.toPath(), StandardCopyOption.REPLACE_EXISTING);
            patch(baseApk, apk); // Patch the extracted APK
            logger.i("Optimize...");
            new DataMultiplexing(logger ,super.monitorExecutor).optimize(baseApk, destApkFile, apk.getOriginalApkPatch(), false);
            V2V3SchemeSigner.sign(destApkFile, new BksSignatureKey(keystoreArgs.get(0), keystoreArgs.get(1), keystoreArgs.get(2), keystoreArgs.get(3)), true, true);
        } catch (YTPPatch.PatchError e) {
            throw new PatchError("Failed to patch Embedded APK: " + e.getMessage());
        }finally {
            // Clean up temp directory
            deleteRecursively(tempDir);
        }
    }
}