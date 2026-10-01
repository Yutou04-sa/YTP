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
import static org.ytp.share.Constants.ANDROID_PROXY_FACTORIES;
import static org.ytp.share.Constants.CONFIG_ASSET_PATH;
import static org.ytp.share.Constants.EMBEDDED_MODULES_ASSET_PATH;
import static org.ytp.share.Constants.LIB_ASSET_PATH;
import static org.ytp.share.Constants.LOADER_CORE_SO_PATH;
import static org.ytp.share.Constants.LOWER_CASE_NAME;
import static org.ytp.share.Constants.PATCH_FILE_SUFFIX;
import static org.ytp.share.Constants.PROXY_APP_PROXY_FACTORY;

import com.android.tools.build.apkzlib.zip.AlignmentRules;
import com.android.tools.build.apkzlib.zip.StoredEntry;
import com.android.tools.build.apkzlib.zip.ZFile;
import com.android.tools.build.apkzlib.zip.ZFileOptions;
import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.google.common.io.ByteSource;
import com.google.gson.Gson;
import com.wind.meditor.core.ManifestEditor;
import com.wind.meditor.property.AttributeItem;
import com.wind.meditor.property.ModificationProperty;
import com.wind.meditor.utils.NodeValue;

import org.apache.commons.io.FilenameUtils;
import org.ytp.patch.util.ByteUtil;
import org.ytp.patch.util.DexModifier;
import org.ytp.patch.util.JavaLogger;
import org.ytp.patch.util.Logger;
import org.ytp.patch.util.ManifestParser;
import org.ytp.share.Apk;
import org.ytp.share.ClassDefInfo;
import org.ytp.share.Constants;
import org.ytp.share.YTPConfig;
import org.ytp.share.PatchConfig;
import org.ytp.share.R;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import bin.mt.apksign.V2V3SchemeSigner;
import bin.mt.apksign.key.BksSignatureKey;

/**
 * APK 修补入口：解析参数、执行修补回退，并在普通修补成功后发布和签名结果。
 */
public class YTPPatch {

    /**
     * 修补流程错误。保留 Error 基类以兼容已有调用方。
     */
    static class PatchError extends Error {
        public PatchError(String message, Throwable cause) {
            super(message + ": " + cause.getMessage(), cause);
        }

        PatchError(String message) {
            super(message);
        }
    }
    ScheduledExecutorService monitorExecutor = Executors.newSingleThreadScheduledExecutor();
    @Parameter(description = "apks")
    private List<String> apkPaths = new ArrayList<>();

    @Parameter(names = {"-h", "--help"}, help = true, order = 0, description = "Print this message")
    private boolean help = false;

    @Parameter(names = {"-o", "--output"}, description = "Output directory")
    private String outputPath = ".";

    @Parameter(names = {"-f", "--force"}, description = "Force overwrite exists output file")
    private boolean forceOverwrite = false;

    @Parameter(names = {"-p", "--packageName"}, description = "Patch with packageName")
    private String packageName = "";

    @Parameter(names = {"-k", "--keystore"}, arity = 5, description = "Set custom signature keystore. Followed by 5 arguments: keystore path, keystore password, keystore alias, keystore alias password,keystore name")
    public List<String> keystoreArgs = Arrays.asList(null, Constants.KEY_STORE_PASSWORD, Constants.KEY_STORE_ALIAS, Constants.KEY_STORE_ALIAS_PASSWORD, Constants.KEY_STORE_ALIAS);

    @Parameter(names = {"-v", "--verbose"}, description = "Verbose output")
    private boolean verbose = false;

    @Parameter(names = {"-m", "--embed"}, description = "Embed provided modules to apk")
    private List<String> modules = new ArrayList<>();

    @Parameter(names = {"-n", "--newPackageName"}, description = "Rename the manifest package (application id) of the patched apk")
    private String newPackageName = "";

    @Parameter(names = {"-l", "--label"}, description = "Override the application label of the patched apk")
    private String appLabel = "";

    public Apk apk = null;

    private static final List<String> ARCHES = List.of(
            "armeabi-v7a",
            "arm64-v8a",
            "x86",
            "x86_64"
    );

    /**
     * Type descriptor of a class that only the metaloader dex defines. Update patches use it to
     * recognise the dex a previous patch embedded when the dex itself has changed since.
     */
    private static final byte[] LOADER_DEX_MARKER =
            "Landroidx/app/Init;".getBytes(StandardCharsets.US_ASCII);

    /**
     * Asset paths of the branding this fork used before it was renamed (HkPatch / HKP). That
     * framework is the same one - equal proxy component factory, equal metaloader, only the asset
     * directory and the native lib name differ - so an apk patched back then is patched in place
     * instead of getting a second loader stacked on top of it.
     */
    private static final String LEGACY_ASSETS_PATCH = "assets/hkp/";

    private static final String[] LEGACY_PATCH_MARKERS = {
            LEGACY_ASSETS_PATCH + "config.json",
            LEGACY_ASSETS_PATCH + "core.so",
            LEGACY_ASSETS_PATCH + "loader.dex",
    };

    public static final ZFileOptions Z_FILE_OPTIONS = new ZFileOptions().setAlignmentRule(AlignmentRules.compose(
            AlignmentRules.constantForSuffix(".so", 4096),
            AlignmentRules.constant(4)
    )).setSkipValidation(true).setCoverEmptySpaceUsingExtraField(false);

    private final JCommander jCommander;

    public final Logger logger;

    private String[] args;

    private String outputFile;

    private boolean updatePatch = false;

    /**
     * Whether the apk being patched carries a patch of the branding used before the rename, whose
     * assets have to be dropped while the patch is upgraded (see [LEGACY_PATCH_MARKERS]).
     */
    private boolean legacyPatch = false;
    /** 修补内嵌 APK 时暂缓最终签名，由外层容器策略完成发布。 */
    protected boolean deferOutputFinalization = false;
    /** 解析修补参数，并保留日志器供各回退策略复用。 */
    public YTPPatch(Logger logger, String... args) {
        this.args = args;
        jCommander = JCommander.newBuilder().addObject(this).build();
        try {
            jCommander.parse(args);
        } catch (ParameterException e) {
            logger.e(e.getMessage() + "\n");
            help = true;
        }
        if (apkPaths == null || apkPaths.isEmpty()) {
            logger.e("No apk specified\n");
            help = true;
        }

        this.logger = logger;
        logger.verbose = verbose;
        this.apk = new Apk().setPackageName(packageName);
    }

    /** 命令行入口。 */
    public static void main(String... args) throws Exception{
        YTPPatch ytp = new YTPPatch(new JavaLogger(), args);
        if (ytp.help) {
            ytp.jCommander.usage();
            return;
        }
        try {
            ytp.doCommandLine();
        } catch (PatchError e) {
            e.printStackTrace(System.err);
        }
    }

    /** 每个大阶段只输出一次耗时，便于区分磁盘复制、比对、重建和签名。 */
    public void logDuration(String stage, long startedAt) {
        logger.i(stage + "Duration：" + ((System.nanoTime() - startedAt) / 1_000_000) + " ms");
    }

    /** 执行命令行修补，并在退出时关闭进度监控线程。 */
    public void doCommandLine() throws Exception {
        try {
            doPatchProcess();
            logger.i("Writing apk...");
        } finally {
            // 即使修补或签名失败，也不能留下进度监控线程。
            monitorExecutor.shutdown();
        }
    }
    
    /**
     * 实际的补丁处理过程
     */
    private void doPatchProcess() throws Exception {
        if (packageName == null || packageName.isEmpty()) {
            throw new PatchError("Package name must not be empty");
        }
        for (String apkPath : apkPaths) {
            // 当前只处理主 APK，跳过多文件输入中的 split APK。
            File srcApkFile = new File(apkPath).getAbsoluteFile();
            if (apkPaths.size() > 1 && srcApkFile.getName().startsWith("split_")) {
                continue;
            }
            apk = new Apk().setPackageName(packageName);
            String apkFileName = srcApkFile.getName();
            File outputDir = new File(outputPath).getAbsoluteFile();
            Files.createDirectories(outputDir.toPath());

            outputFile = new File(outputDir, String.format(
                    Locale.ROOT, "%s-%d" + PATCH_FILE_SUFFIX,
                    FilenameUtils.getBaseName(apkFileName),
                    YTPConfig.instance.VERSION_CODE)
            ).getAbsolutePath();

            File destination = new File(outputFile);
            if (destination.exists() && !forceOverwrite) {
                throw new PatchError("Output APK already exists: " + destination);
            }
            logger.i("Processing \n" + srcApkFile + "\n -> \n" + outputFile);
            patch(srcApkFile);
        }
    }
    
    public void patch(File srcApkFile, Apk apk) throws Exception {
        this.apk = apk;
        patch(srcApkFile);
    }
    
    /**
     * 修补单个 APK。普通策略和容器回退策略都先写到目标目录中的临时文件，
     * 签名成功后再移动到目标路径，避免失败时破坏输入或现有输出。
     */
    public void patch(File srcApkFile) throws Exception {
        if (!srcApkFile.isFile()) {
            throw new PatchError("Source APK does not exist: " + srcApkFile);
        }
        if (apk == null || apk.getPackageName() == null || apk.getPackageName().isEmpty()) {
            throw new PatchError("Package name must not be empty");
        }

        boolean directPatch = outputFile == null;
        File target = directPatch ? srcApkFile : new File(outputFile);
        // 在目标目录创建文件，使最终移动只更新目录项，不增加一次整包复制。
        File fallbackOutput = Files.createTempFile(target.getAbsoluteFile().getParentFile().toPath(),
                "ytp-output-", ".apk").toFile();
        try {
            File standardResult = patchWithFallbacks(srcApkFile, fallbackOutput);
            if (standardResult == null) {
                // 回退策略已构建并签名容器；只有完整结果才对外可见。
                moveResult(fallbackOutput, target, directPatch);
                return;
            }
            try {
                if (apk.getPatchFile().isEmpty() && !deferOutputFinalization) {
                    // 在发布前完成签名，避免签名失败时留下不完整的目标文件。
                    signApk(standardResult);
                    moveResult(standardResult, target, directPatch);
                } else {
                    // 内嵌 APK 是中间文件；由外层策略继续组装和签名。
                    Files.move(standardResult.toPath(), srcApkFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                deleteTemporaryFile(standardResult);
            }
        } finally {
            deleteTemporaryFile(fallbackOutput);
        }
    }

    /** 输出和输入可能位于同一路径；仅在直接修补或指定覆盖时替换现有文件。 */
    private void moveResult(File source, File target, boolean directPatch) throws IOException {
        if (forceOverwrite || directPatch) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.move(source.toPath(), target.toPath());
        }
    }

    /** 修补内嵌 APK 时暂缓发布；外层容器完成后由相应策略统一签名。 */
    protected final void patchIntermediateApk(File source) throws Exception {
        boolean previous = deferOutputFinalization;
        deferOutputFinalization = true;
        try {
            patch(source, apk);
        } finally {
            deferOutputFinalization = previous;
        }
    }

    protected final void signApk(File file) throws Exception {
        V2V3SchemeSigner.sign(file,
                new BksSignatureKey(keystoreArgs.get(0), keystoreArgs.get(1),
                        keystoreArgs.get(2), keystoreArgs.get(3)), true, true);
    }

    /** 清理不能覆盖修补失败的原始异常；Windows 上 ZIP 打开失败时文件可能暂时被占用。 */
    private void deleteTemporaryFile(File file) {
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException cleanupError) {
            file.deleteOnExit();
            logger.e("Could not delete temporary file: " + file + ": " + cleanupError.getMessage());
        }
    }

    /**
     * 在独立候选副本上用 ZFile 修改 APK。先合并容器条目和 Manifest，
     * 再写入运行所需的配置、核心库、ABI 对应的原生库及 loader DEX。
     * 回退条件由 PatchFallbackPipeline 统一判断。
     */
    private void patchStandard(File srcApkFile) throws Exception {
        try (var srcZFile = ZFile.openReadWrite(srcApkFile,Z_FILE_OPTIONS)) {
            //检测是否有更新补丁
            checkUpdatePatch(srcZFile);

            embedPatchFile(srcZFile);
            StoredEntry manifestEntry = srcZFile.get(ANDROID_MANIFEST_XML);
            if (manifestEntry == null)
                throw new PatchError("Provided file is not a valid apk");

            ManifestParser.Pair pair;

            try (var is = manifestEntry.open()) {
                pair = ManifestParser.parseManifestFile(is);
                if (pair == null)
                    throw new PatchError("Failed to parse AndroidManifest.xml");
                if (apk.getAppComponentFactory() == null) {
                    apk.setAppComponentFactory(pair.appComponentFactory).setMinSdkVersion(pair.minSdkVersion);
                }

                logger.d("Original appComponentFactory class: " + apk.getAppComponentFactory());
            }

            logger.i("Patching apk...");
            final var config = new PatchConfig(apk.getAppComponentFactory(), apk.getPackageName(), newPackageName, appLabel);
            final var configBytes = new Gson().toJson(config).getBytes(StandardCharsets.UTF_8);

            try (var manifestEntryIs = manifestEntry.open();
                    var is = new ByteArrayInputStream(modifyManifestFile(manifestEntryIs, config, pair.permissions, pair.use_permissions, pair.activity_names))) {
                srcZFile.add(ANDROID_MANIFEST_XML, is);
            } catch (Exception e) {
                throw new PatchError("Error when modifying manifest", e);
            }

            logger.i("Adding config...");
            try (var is = new ByteArrayInputStream(configBytes)) {
                srcZFile.add(CONFIG_ASSET_PATH, is);
            } catch (Exception e) {
                throw new PatchError("Error when saving config", e);
            }

            logger.i("Adding core.so..");
            try (var is = ByteUtil.getResourceAsStream(LOADER_CORE_SO_PATH)) {
                srcZFile.add(LOADER_CORE_SO_PATH, is);
            } catch (Exception e) {
                throw new PatchError("Error when adding assets", e);
            }

            logger.i("Adding native lib...");
            HashSet<String> existingArchitectures = new HashSet<>();
            srcZFile.entries().forEach(e -> {
                var name = e.getCentralDirectoryHeader().getName();
                //删除之前已嵌入的模块文件
                if (name.startsWith(EMBEDDED_MODULES_ASSET_PATH)) {
                    try {
                        e.delete();
                    } catch (IOException ex) {
                        logger.e("Failed to delete embedded module entry " + name + ": " + ex);
                    }
                }
                //升级旧品牌（HkPatch/HKP）的补丁时把它那套资源清掉：新补丁写的是 assets/ytp/**，
                //旧的那份留着既占体积，其中的 metaloader dex 也已经由 removePreviousMetaloaderDex 删掉。
                else if (legacyPatch && name.startsWith(LEGACY_ASSETS_PATCH)) {
                    try {
                        e.delete();
                        logger.d(" -Removed legacy patch entry " + name);
                    } catch (IOException ex) {
                        logger.e("Failed to delete legacy patch entry " + name + ": " + ex);
                    }
                }
                //收集已有的so架构
                else if (name.startsWith("lib/") && name.endsWith(".so")) {
                    // "lib/foo.so" carries no architecture directory at all, and the old
                    // substring() call aborted the whole patch with a StringIndexOutOfBounds.
                    int archEnd = name.indexOf('/', 5);
                    if (archEnd > 5) {
                        existingArchitectures.add(name.substring(4, archEnd));
                    }
                }
                //删除v1签名文件
                else if(name.startsWith("META-INF/") && (name.endsWith(".RSA") || name.endsWith(".SF") || name.endsWith(".MF"))){
                    try {
                        e.delete();
                    } catch (IOException ex) {
                        logger.e("Failed to delete v1 signature entry " + name + ": " + ex);
                    }
                }
            });

            existingArchitectures.removeIf(arch -> !ARCHES.contains(arch));
            if(existingArchitectures.isEmpty()){
                existingArchitectures.addAll(ARCHES);
            }
            // Only embed the native libs that are actually built. The patch assets carry a single
            // architecture on purpose, and requiring all of them made every apk without a lib/
            // directory - or with any other abi - fail with "Error when adding native lib".
            var missingArchitectures = new ArrayList<String>();
            int addedLibs = 0;
            for (String arch : new TreeSet<>(existingArchitectures)) {
                String entryName = String.format(LIB_ASSET_PATH,  arch);
                if (!ByteUtil.hasResource(entryName)) {
                    missingArchitectures.add(arch);
                    continue;
                }
                try (var is = ByteUtil.getResourceAsStream(entryName)) {
                    srcZFile.add(entryName, is, false);
                } catch (Exception e) {
                    throw new PatchError("Error when adding native lib", e);
                }
                logger.d(" -Added " + entryName);
                addedLibs++;
            }
            if (addedLibs == 0) {
                throw new PatchError("No native lib is available for " + missingArchitectures
                        + ", the apk cannot be patched");
            }
            if (!missingArchitectures.isEmpty()) {
                logger.i(" -Skipped native libs that are not built: " + missingArchitectures);
            }

            logger.i("Embedding modules...");
            embedModules(srcZFile);

            logger.i("Adding metaloader dex...");
            final byte[] metaloaderDex;
            try (var is = ByteUtil.getResourceAsStream(Constants.META_LOADER_DEX_ASSET_PATH)) {
                metaloaderDex = ByteUtil.is2ByteArray(is);
            } catch (Throwable e) {
                throw new PatchError("Error when reading dex", e);
            }
            if (updatePatch) {
                removePreviousMetaloaderDex(srcZFile, metaloaderDex);
            }
            try (var is = new ByteArrayInputStream(metaloaderDex)) {
                String targetName = generateUniqueDexFileName(srcZFile);
                srcZFile.add(targetName, is);
                logger.d(" -Added " + targetName);
            } catch (Exception e) {
                throw new PatchError("Error when adding dex", e);
            }
            logger.i("Process DexFile...");
            processDexFile(srcZFile);
            srcZFile.realign();
        } catch (Exception e) {
            throw new PatchError("Error when patching", e);
        }
    }

    /**
     * 对由调用方独占的临时 APK 直接执行标准修补，省去第二份整包工作副本。
     * 失败时文件可能已被部分修改，调用方必须从原始容器恢复后再尝试回退。
     */
    protected final void patchWorkspaceApk(File workFile) throws Exception {
        patchStandard(workFile);
    }

    /**
     * 选择普通修补或容器回退。所有策略都读取原始输入，普通策略成功时返回
     * 未发布的候选文件；容器策略自行写入目标临时文件时返回 null。
     */
    private File patchWithFallbacks(File srcApkFile, File destApkFile) throws Exception {
        // ZFile 会原地修改文件。先复制候选文件，让所有回退策略始终读取完整的原包。
        File candidate = Files.createTempFile(destApkFile.getAbsoluteFile().getParentFile().toPath(),
                "ytp-standard-", ".apk").toFile();
        boolean keepCandidate = false;
        try {
            Files.copy(srcApkFile.toPath(), candidate.toPath(), StandardCopyOption.REPLACE_EXISTING);
            int successfulStrategy = new PatchFallbackPipeline(logger)
                .add(
                        "Parsing original apk...",
                        () -> patchStandard(candidate),
                        YTPExtend::canHandleFallback)
                .add(
                        "Standard patch failed, trying extend patch method",
                        () -> runFallback(new YTPExtend(logger, apk, args),
                                fallback -> fallback.patchExtend(srcApkFile, destApkFile)),
                        error -> YTPBigFile.canHandleFallback(error) || YTPPhantom.canHandleFallback(error))
                .addWhen(
                        "Extend patch failed, trying big file patch method",
                        () -> runFallback(new YTPBigFile(logger, apk, args),
                                fallback -> fallback.patchBigFile(srcApkFile, destApkFile)),
                        YTPBigFile::canHandleFallback,
                        YTPPhantom::canHandleFallback)
                .addWhen(
                        "Extend patch failed, trying Extend2 patch method",
                        () -> runFallback(new YTPPhantom(logger, apk, args),
                                fallback -> fallback.patchPhantom(srcApkFile, destApkFile)),
                        YTPPhantom::canHandleFallback)
                .execute();
            keepCandidate = successfulStrategy == 0;
            return keepCandidate ? candidate : null;
        } finally {
            if (!keepCandidate) {
                deleteTemporaryFile(candidate);
            }
        }
    }

    @FunctionalInterface
    private interface FallbackAction<T extends YTPPatch> {
        void run(T fallback) throws Exception;
    }

    /** 子策略继承发布状态，并在成功或失败后关闭自己的进度监控线程。 */
    private <T extends YTPPatch> void runFallback(T fallback, FallbackAction<T> action) throws Exception {
        fallback.deferOutputFinalization = deferOutputFinalization;
        try {
            action.run(fallback);
        } finally {
            fallback.monitorExecutor.shutdown();
        }
    }

    /** 为新补丁寻找可用 DEX 名；更新补丁时复用序号最大的现有 DEX。 */
    protected String generateUniqueDexFileName(ZFile zFile) {
        return chooseDexFileName(zFile, updatePatch);
    }

    /** 拆出无状态的命名规则，供不同修补策略复用和验证。 */
    static String chooseDexFileName(ZFile zFile, boolean updatePatch) {
        int dexNum = 1;
        while (zFile.get(dexFileName(dexNum)) != null) {
            dexNum++;
        }
        if (!updatePatch || dexNum == 1) {
            // 新补丁补齐首个空缺，兼容部分类加载器按连续序号查找多 DEX。
            return dexFileName(dexNum);
        }
        int lastDex = 1;
        for (StoredEntry entry : zFile.entries()) {
            lastDex = Math.max(lastDex, dexOrdinal(entry.getCentralDirectoryHeader().getName()));
        }
        // 有序号空缺时不能简单使用 dexNum - 1，否则可能误覆盖主 DEX。
        return dexFileName(lastDex);
    }

    /**
     * Update patches re-patch an apk that already carries the metaloader dex, and the previous
     * code picked the new name with a string replace on the first free slot. That pointed at a
     * host dex whenever the host numbering has a hole, and overwriting it corrupts the app.
     * Identify our own dex instead - by content when it is unchanged, by its loader classes
     * otherwise - and remove it so the slot it used is free again.
     */
    private void removePreviousMetaloaderDex(ZFile zFile, byte[] metaloaderDex) {
        var candidates = new ArrayList<String>();
        zFile.entries().forEach(entry -> {
            var name = entry.getCentralDirectoryHeader().getName();
            if (name.startsWith("classes") && name.endsWith(".dex") && !name.contains("/")) {
                candidates.add(name);
            }
        });
        for (String name : candidates) {
            if (!isMetaloaderDex(zFile.get(name), metaloaderDex)) {
                continue;
            }
            try {
                zFile.get(name).delete();
                logger.d(" -Removed previous " + name);
            } catch (IOException e) {
                logger.e("Failed to delete the previous metaloader dex " + name + ": " + e);
            }
            return;
        }
        logger.i(" -No previous metaloader dex found");
    }

    /**
     * @param entry         dex entry to inspect, may be null
     * @param metaloaderDex the dex that is about to be embedded
     * @return true when the entry is a metaloader dex of a previous patch
     */
    private boolean isMetaloaderDex(StoredEntry entry, byte[] metaloaderDex) {
        if (entry == null) {
            return false;
        }
        if (entry.getCentralDirectoryHeader().getUncompressedSize() == metaloaderDex.length) {
            try (var is = entry.open()) {
                if (Arrays.equals(ByteUtil.is2ByteArray(is), metaloaderDex)) {
                    return true;
                }
            } catch (IOException e) {
                logger.e("Failed to read " + entry.getCentralDirectoryHeader().getName() + ": " + e);
                return false;
            }
        }
        // A patch made by another version carries a different dex, so fall back to the loader
        // package it defines.
        try (var is = entry.open()) {
            return contains(is, LOADER_DEX_MARKER);
        } catch (IOException e) {
            logger.e("Failed to read " + entry.getCentralDirectoryHeader().getName() + ": " + e);
            return false;
        }
    }

    /**
     * Streams the input looking for a byte pattern.
     */
    private static boolean contains(InputStream input, byte[] pattern) {
        byte[] window = new byte[pattern.length + 8192];
        int kept = 0;
        int read;
        while (true) {
            try {
                read = input.read(window, kept, window.length - kept);
            } catch (IOException e) {
                return false;
            }
            if (read <= 0) {
                return false;
            }
            int length = kept + read;
            for (int i = 0; i + pattern.length <= length; i++) {
                boolean match = true;
                for (int j = 0; j < pattern.length; j++) {
                    if (window[i + j] != pattern[j]) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    return true;
                }
            }
            int keep = Math.min(pattern.length - 1, length);
            System.arraycopy(window, length - keep, window, 0, keep);
            kept = keep;
        }
    }

    /** DEX 文件名；序号 1 即 classes.dex。 */
    private static String dexFileName(int number) {
        return "classes" + (number == 1 ? "" : number) + ".dex";
    }

    /** 校验并嵌入模块 APK；空列表写入空目录标记。 */
    private void embedModules(ZFile zFile) {
        if(modules.isEmpty()) {
            try {
                zFile.add(EMBEDDED_MODULES_ASSET_PATH, ByteSource.wrap(new byte[0]), false);
            } catch (IOException ex) {
                logger.e("Failed to add embedded modules placeholder: " + ex);
            }
        }
        for (var module : modules) {
            File file = new File(module);
            try (var apk = ZFile.openReadOnly(new File(module));
                 var fileIs = new FileInputStream(file);
                 var xmlIs = Objects.requireNonNull(apk.get(ANDROID_MANIFEST_XML)).open()) {
                var manifest = Objects.requireNonNull(ManifestParser.parseManifestFile(xmlIs));
                var packageName = manifest.packageName;
                // 包名来自外部 APK，直接拼进 entry 名可能造出越出目录的 zip entry
                if (packageName == null || packageName.isEmpty()
                        || packageName.contains("/") || packageName.contains("\\")
                        || packageName.contains("..")) {
                    logger.e(module + ": invalid package name '" + packageName + "', skip embedding");
                    continue;
                }
                logger.d(" - " + packageName+".apk");
                zFile.add(EMBEDDED_MODULES_ASSET_PATH + packageName + ".apk", fileIs);
            } catch (NullPointerException | IOException e) {
                logger.e(module + " does not exist or is not a valid apk file. error:" + e);
            }
        }
    }

    /**
     * 合并外层容器提取出的条目。跳过旧签名和本项目的旧资源，
     * 避免它们覆盖本次生成的文件；原生库、APK 和资源表保持未压缩。
     */
    private void embedPatchFile(ZFile dstZFile){
        apk.getPatchFile().forEach((name, path) -> {
            if (name.startsWith("META-INF") && (name.endsWith(".SF") || name.endsWith(".MF") || name.endsWith(".RSA")))
                return;
            if(name.startsWith("assets/"+LOWER_CASE_NAME+"/")) return;
            try (var patchIs = new FileInputStream(path)) {
                boolean store = name.endsWith(".so") || name.endsWith(".apk") || name.endsWith(".arsc");
                dstZFile.add(name, patchIs, !store);
            } catch (Exception e) {
               throw new PatchError("Error when adding patch file", e);
            }
        });
    }

    /**
     * 增补组件工厂、权限和模块入口。保留历史参数以兼容现有调用方。
     * 输入流的关闭由调用方负责。
     */
    public byte[] modifyManifestFile(InputStream is, PatchConfig config, List<String> permissions, List<String> uses_permissions, List<String> activity_names) throws IOException {
        ModificationProperty property = new ModificationProperty();

        // 改包名：只改 manifest 的 package 属性，dex 里的类名保持原样。
        // 注意不能把新包名传给 -p，那里用的原包名还要用来找内嵌原包和备份目录。
        if (config.newPackageName != null && !config.newPackageName.isEmpty()) {
            property.addManifestAttribute(
                    new AttributeItem(NodeValue.Manifest.PACKAGE, config.newPackageName).setNamespace(null));

            // manifest 里以 "." 开头的相对名是相对旧包名解析的；package 改掉之后
            // 要把它们补成旧包名的全限定名，否则系统按新包名找不到这些类。
            final String originalPackageName = config.packageName;
            property.setComponentNameMapper(name ->
                    name != null && name.startsWith(".")
                            ? originalPackageName + name
                            : name);
        }

        // 改应用名：直接覆盖 android:label（不动 resources.arsc）。
        if (config.appLabel != null && !config.appLabel.isEmpty()) {
            property.addApplicationAttribute(new AttributeItem(NodeValue.Application.LABEL, config.appLabel));
        }

        if (config.appComponentFactory == null || ANDROID_PROXY_FACTORIES.contains(config.appComponentFactory)) {
            property.addApplicationAttribute(new AttributeItem("appComponentFactory", PROXY_APP_PROXY_FACTORY));
        }

        if(!uses_permissions.contains("android.permission.QUERY_ALL_PACKAGES")){
            property.addUsesPermission("android.permission.QUERY_ALL_PACKAGES");
        }

        if(!uses_permissions.contains("com.android.permission.GET_INSTALLED_APPS")){
            property.addUsesPermission("com.android.permission.GET_INSTALLED_APPS");
        }


        if(!activity_names.contains(Constants.MODULE_ACTIVITY)){
            ModificationProperty.Activity activity = new ModificationProperty.Activity(Constants.MODULE_ACTIVITY);
            activity.setExported(true);
            property.addActivity(activity);
        }

        var os = new ByteArrayOutputStream();
        (new ManifestEditor(is, os, property)).processManifest();
        return os.toByteArray();
    }

    /** 递归清理本次任务的工作目录；被占用的文件安排在进程退出时重试。 */
    protected void deleteRecursively(File file) {
        if (file.isDirectory()) {
            // 先清理子项再删除目录；只处理本次创建的工作空间。
            File[] files = file.listFiles();
            if (files != null) {
                for (File child : files) {
                    deleteRecursively(child);
                }
            }
        }
        if (!file.delete()) {
            file.deleteOnExit();
            logger.e("Warning: Could not delete file/directory: " + file.getAbsolutePath());
        }
    }

    /**
     * 根据 Manifest 的组件工厂定位应替换父类的 DEX。
     * 继承链可以跨多个 DEX，所以先逐个读取轻量类型关系；命中后仅重写目标 DEX。
     * 若始终找不到工厂，则让 Manifest 直接使用代理工厂。
     */
    public void processDexFile(ZFile destZFile) throws IOException {
        if (updatePatch || apk.getAppComponentFactory() == null
                || ANDROID_PROXY_FACTORIES.contains(apk.getAppComponentFactory())) {
            return;
        }

        DexModifier dexModifier = new DexModifier(logger);
        // 继承链可能横跨多个 DEX，逐个收集轻量的类型关系；完整 DEX 字节只保留当前项。
        Map<String, ClassDefInfo> classHierarchy = new HashMap<>();
        String newSuperSmali = "L" + PROXY_APP_PROXY_FACTORY.replace('.', '/') + ";";
        List<String> dexNames = new ArrayList<>();
        for (StoredEntry entry : destZFile.entries()) {
            String name = entry.getCentralDirectoryHeader().getName();
            if (dexOrdinal(name) > 0) {
                dexNames.add(name);
            }
        }
        // 直接枚举条目，兼容 classes2.dex 缺失而 classes3.dex 存在的 APK。
        dexNames.sort(Comparator.comparingInt(YTPPatch::dexOrdinal));
        for (String dexName : dexNames) {
            try {
                byte[] bytes = readDexBytes(destZFile.get(dexName));
                Map<String, ClassDefInfo> classNames = dexModifier.getAllClass(bytes, dexName);
                // 同名类出现于多个 DEX 时保留先扫描的定义，与类加载器顺序一致。
                classNames.forEach(classHierarchy::putIfAbsent);
                logger.d(" -Find " + dexName + " class: " + classNames.size());
                ClassDefInfo cls = DexModifier.getFinalClass(classHierarchy, apk.getAppComponentFactory());
                if (cls != null) {
                    logger.d(" -Modifying " + cls.getDexName());
                    logger.d("  -Class: " + cls.getClassName());
                    logger.d("  -SuperClass: " + cls.getSuperClassName());
                    // 目标父类可能位于较早读取的 DEX，此时只重读那一个条目。
                    if (!cls.getDexName().equals(dexName)) {
                        bytes = readDexBytes(destZFile.get(cls.getDexName()));
                    }
                    R<InputStream> inputStreamR = dexModifier.modifySuperclass(
                            bytes, cls.getClassName(), newSuperSmali);
                    try (InputStream modifiedDex = inputStreamR.getData()) {
                        if (!inputStreamR.getCode()) {
                            throw new IOException("DEX class was not modified: " + cls.getClassName());
                        }
                        logger.d("  -Merge modified... ");
                        destZFile.add(cls.getDexName(), modifiedDex, true);
                        return;
                    }
                }
            } catch (Exception e) {
                throw new PatchError("Error when modifying DEX file", e);
            }
        }
        // 自定义工厂不在可访问的 DEX 中，改由 Manifest 指向默认代理，避免启动失败。
        setManifestComponentFactory(destZFile);
    }

    private static byte[] readDexBytes(StoredEntry entry) throws IOException {
        try (InputStream input = entry.open()) {
            return ByteUtil.is2ByteArray(input, entry.getCentralDirectoryHeader().getUncompressedSize());
        }
    }

    /** 只接受 Android 多 DEX 命名约定；返回序号用于保持扫描顺序。 */
    private static int dexOrdinal(String name) {
        if ("classes.dex".equals(name)) {
            return 1;
        }
        if (!name.startsWith("classes") || !name.endsWith(".dex")) {
            return -1;
        }
        String digits = name.substring("classes".length(), name.length() - ".dex".length());
        try {
            int ordinal = Integer.parseInt(digits);
            return ordinal > 1 ? ordinal : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private void checkUpdatePatch(ZFile srcZFile) {
        updatePatch = (srcZFile.get(LOADER_CORE_SO_PATH) != null || apk.getPatchFile().containsKey(LOADER_CORE_SO_PATH)
        || srcZFile.get("assets/ytp/loader.dex") != null || apk.getPatchFile().containsKey("assets/ytp/loader.dex")//TODO 以后会删除这行，兼容旧版本
        );
        //这个 fork 改名成 YTP 之前打的补丁（HkPatch/HKP）用的是同一套框架，只是资源目录与 so 名
        //不同，所以也按「更新已有补丁」处理：跳过 dex 改写，只把品牌资源换成新的。
        for (String marker : LEGACY_PATCH_MARKERS) {
            if (srcZFile.get(marker) != null || apk.getPatchFile().containsKey(marker)) {
                updatePatch = true;
                legacyPatch = true;
                break;
            }
        }
        if(updatePatch){
            logger.i("Update patch: " + true);
        }
    }

    private void setManifestComponentFactory(ZFile destZFile){
        StoredEntry manifestEntry = destZFile.get(ANDROID_MANIFEST_XML);
        if(manifestEntry != null){
            logger.i(" -Set default ComponentFactory: " + PROXY_APP_PROXY_FACTORY);
            try(InputStream is = manifestEntry.open()){
                ModificationProperty property = new ModificationProperty();
                property.addApplicationAttribute(new AttributeItem("appComponentFactory", PROXY_APP_PROXY_FACTORY));
                var os = new ByteArrayOutputStream();
                (new ManifestEditor(is, os, property)).processManifest();
                var manifest = os.toByteArray();
                destZFile.add(ANDROID_MANIFEST_XML, new ByteArrayInputStream(manifest));
            } catch (IOException e) {
                throw new PatchError("Failed to modify manifest", e);
            }
        }
    }
}
