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
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
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
 * LSPatch class for patching APK files
 * Supports command-line argument processing, APK modification, signing and more
 */
public class YTPPatch {

    /**
     * Custom exception class representing errors in the patching process
     */
    static class PatchError extends Error {
        public PatchError(String message, Throwable cause) {
            super(message+"\n"+cause.getMessage(), cause);
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

    @Parameter(names = {"-n", "--newPackageName"}, description = "Rename the manifest package (application id) of the patched apk")
    private String newPackageName = "";

    @Parameter(names = {"-l", "--label"}, description = "Override the application label of the patched apk")
    private String appLabel = "";

    @Parameter(names = {"-k", "--keystore"}, arity = 5, description = "Set custom signature keystore. Followed by 5 arguments: keystore path, keystore password, keystore alias, keystore alias password,keystore name")
    public List<String> keystoreArgs = Arrays.asList(null, Constants.KEY_STORE_PASSWORD, Constants.KEY_STORE_ALIAS, Constants.KEY_STORE_ALIAS_PASSWORD, Constants.KEY_STORE_ALIAS);

    @Parameter(names = {"-v", "--verbose"}, description = "Verbose output")
    private boolean verbose = false;

    @Parameter(names = {"-m", "--embed"}, description = "Embed provided modules to apk")
    private List<String> modules = new ArrayList<>();

    public Apk apk = null;

    private static final HashSet<String> ARCHES = new HashSet<>(Arrays.asList(
            "armeabi-v7a",
            "arm64-v8a",
            "x86",
            "x86_64"
    ));

    /**
     * Type descriptor of a class that only the metaloader dex defines. Update patches use it to
     * recognise the dex a previous patch embedded when the dex itself has changed since.
     */
    private static final byte[] LOADER_DEX_MARKER =
            "Landroidx/app/Init;".getBytes(StandardCharsets.US_ASCII);

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
     * Constructor to initialize LSPatch instance
     *
     * @param logger Logger instance
     * @param args   Command-line arguments
     */
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
    }

    /**
     * Main entry point
     *
     * @param args Command-line arguments
     * @throws IOException IO exception
     */
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

    /**
     * Process command-line arguments and execute APK patching
     *
     * @throws PatchError  Patch error
     * @throws IOException IO exception
     */
    public void doCommandLine() throws Exception {
        doPatchProcess();
        logger.i("Writing apk...");
        //关闭线程池
        if(monitorExecutor != null && !monitorExecutor.isShutdown() && !monitorExecutor.isTerminated()){
            monitorExecutor.shutdown();
        }
    }
    
    /**
     * 实际的补丁处理过程
     */
    private void doPatchProcess() throws Exception {
        apk = new Apk();apk.setPackageName(packageName);
        if(packageName.isEmpty()){
            throw new PatchError("PackageName is Not null");
        }
        for (var apkPath : apkPaths) {
            //不支持多个
            if(apkPaths.size() > 1 && apkPath.contains("/split_")){
                continue;
            }
            File srcApkFile = new File(apkPath).getAbsoluteFile();

            String apkFileName = srcApkFile.getName();

            var outputDir = new File(outputPath);
            outputDir.mkdirs();

            outputFile = new File(outputDir, String.format(
                    Locale.getDefault(), "%s-%d" + PATCH_FILE_SUFFIX,
                    FilenameUtils.getBaseName(apkFileName),
                    YTPConfig.instance.VERSION_CODE)
            ).getAbsolutePath();

            logger.i("Processing \n" + srcApkFile + "\n -> \n" + outputFile);
            if(!srcApkFile.getAbsolutePath().contains("/cache/")){
                // Never reuse a fixed temporary name: the next source apk of the same run would
                // hit FileAlreadyExistsException and fail the whole patch. Keep the name derived
                // from the source so a split still produces its own output file.
                File file = new File(outputDir, "work-" + apkFileName);
                Files.copy(srcApkFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                patch(file);
            }else {
                patch(srcApkFile);
            }
        }
    }
    
    public void patch(File srcApkFile, Apk apk) throws Exception {
        this.apk = apk;
        patch(srcApkFile);
    }
    
    /**
     * Main method for patching APK files
     *
     * @param srcApkFile Source APK file
     * @throws PatchError  Patch error
     * @throws IOException IO exception
     */
    public void patch(File srcApkFile) throws Exception {
        if (!srcApkFile.exists())
            throw new PatchError("The source apk file does not exit. Please provide a correct path.");

        logger.i("Parsing original apk...");
        try (var srcZFile = ZFile.openReadWrite(srcApkFile,Z_FILE_OPTIONS)) {
            //检测是否有更新补丁
            checkUpdatePatch(srcZFile);

            embedPatchFile(srcZFile);
            var manifestEntry = srcZFile.get(ANDROID_MANIFEST_XML);
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
            } catch (Throwable e) {
                throw new PatchError("Error when modifying manifest", e);
            }

            logger.i("Adding config...");
            try (var is = new ByteArrayInputStream(configBytes)) {
                srcZFile.add(CONFIG_ASSET_PATH, is);
            } catch (Throwable e) {
                throw new PatchError("Error when saving config", e);
            }

            logger.i("Adding core.so..");
            try (var is = ByteUtil.getResourceAsStream(LOADER_CORE_SO_PATH)) {
                srcZFile.add(LOADER_CORE_SO_PATH, is);
            } catch (Throwable e) {
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
                } catch (Throwable e) {
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
            } catch (Throwable e) {
                throw new PatchError("Error when adding dex", e);
            }
            logger.i("Process DexFile...");
            processDexFile(srcZFile);
            srcZFile.realign();
        } catch (Exception e) {
            if (isOverlapFailure(e)) {
                patchWithFallbacks(srcApkFile, Paths.get(outputFile).toFile());
            } else {
                throw new PatchError("Error when patching", e);
            }
        }
        //只有数据复用优化的才会有PatchFile，不需要重命名为输出文件，在数据复用优化模式下，会保存在outputFile中
        if(this.apk.getPatchFile().isEmpty()) {
            var output = Paths.get(outputFile);
            if (Files.exists(output) && !forceOverwrite) {
                logger.i("Overwriting existing output " + outputFile);
            }
            // renameTo() reports nothing: on Windows it silently failed when the output already
            // existed, so the previous - stale - apk was signed again and handed out as the
            // fresh patch.
            if (!srcApkFile.getAbsolutePath().equals(outputFile)) {
                try {
                    Files.move(srcApkFile.toPath(), output, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new PatchError("Failed to move the patched apk to " + outputFile, e);
                }
            }
            V2V3SchemeSigner.sign(Paths.get(outputFile).toFile(), new BksSignatureKey(keystoreArgs.get(0), keystoreArgs.get(1), keystoreArgs.get(2), keystoreArgs.get(3)), true, true);
        }
    }

    private void patchWithFallbacks(File srcApkFile, File destApkFile) throws Exception {
        new PatchFallbackPipeline(logger)
                .add(
                        "Standard patch failed, trying extend patch method",
                        () -> new YTPExtend(logger, apk, args).patchExtend(srcApkFile, destApkFile),
                        YTPPhantom::canHandleFallback)
                .add(
                        "Extend patch failed, trying Extend2 patch method",
                        () -> new YTPPhantom(logger, apk, args).patchPhantom(srcApkFile, destApkFile))
                .execute();
    }

    private boolean isOverlapFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains("overlaps with")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Generate a unique dex file name that does not conflict with existing entries
     *
     * @param zFile The ZFile to check for existing entries
     * @return A unique file name
     */
    protected String generateUniqueDexFileName(ZFile zFile) {
        int dexNum = 1;
        String targetName;
        do {
            targetName = "classes" + (dexNum == 1 ? "" : dexNum) + ".dex";
            dexNum++;
        } while (zFile.get(targetName) != null);
        return targetName;
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

    /**
     * Embed modules into APK
     *
     * @param zFile Target ZFile object
     */
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

    private void embedPatchFile(ZFile dstZFile){
        apk.getPatchFile().forEach((name, path) -> {
            if (name.startsWith("META-INF") && (name.endsWith(".SF") || name.endsWith(".MF") || name.endsWith(".RSA")))
                return;
            if(name.startsWith("assets/"+LOWER_CASE_NAME+"/")) return;
            try (var patchIs = new FileInputStream(path)) {
                if (name.endsWith(".so") || name.endsWith(".apk") || name.endsWith(".arsc")) {
                    dstZFile.add(name, patchIs, false);
                } else if (name.startsWith("classes") && name.endsWith(".dex")) {
                    dstZFile.add(name, patchIs);
                }
                else {
                    dstZFile.add(name, patchIs);
                }
            } catch (Throwable e) {
               throw new PatchError("Error when adding patch file", e);
            }
        });
    }

    /**
     * Modify manifest file
     *
     * @param is               Manifest file input stream
     * @param config           Patch configuration
     * @param permissions      Permissions list
     * @param uses_permissions Used permissions list
     * @param activity_names      activity_names list
     * @return Modified manifest file as byte array
     * @throws IOException IO exception
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
        is.close();
        os.flush();
        os.close();
        return os.toByteArray();
    }

    /**
     * Recursively delete directory and files
     * Deletes the specified directory and all files and subdirectories it contains
     *
     * @param file File or directory to delete
     */
    protected void deleteRecursively(File file) {
        if (file.isDirectory()) {
            // If it's a directory, recursively delete all subfiles and subdirectories first
            File[] files = file.listFiles();
            if (files != null) {
                for (File child : files) {
                    deleteRecursively(child);
                }
            }
        }
        if (!file.delete()) {
            logger.e("Warning: Could not delete file/directory: " + file.getAbsolutePath());
        }
    }

    public void processDexFile(ZFile destZFile) throws IOException {
        if(updatePatch) return;
        if(apk.getAppComponentFactory() == null || ANDROID_PROXY_FACTORIES.contains(apk.getAppComponentFactory())) {
            return;
        }

        int dexCount = 1;
        String targetName;
        StoredEntry se;
        DexModifier dexModifier = new DexModifier(logger);
        boolean modified = false;
        String newSuperSmali = "L" + PROXY_APP_PROXY_FACTORY.replace('.', '/') + ";";

        while (true) {
            targetName = "classes" + (dexCount == 1 ? "" : dexCount) + ".dex";
            se = destZFile.get(targetName);
            if (se == null) break;

            long knownSize = se.getCentralDirectoryHeader().getUncompressedSize();

            try {
                byte[] bytes;
                try (InputStream is = se.open()) {
                    bytes = ByteUtil.is2ByteArray(is, knownSize);
                }

                Map<String, ClassDefInfo> classNames = dexModifier.getAllClass(bytes, targetName);
                logger.d(" -Find " + targetName + " class: " + classNames.size());
                ClassDefInfo cls = DexModifier.getFinalClass(classNames, apk.getAppComponentFactory());
                classNames = null;
                System.gc();

                if (cls != null) {
                    logger.d(" -Modifying " + cls.getDexName());
                    logger.d("  -Class: " + cls.getClassName());
                    logger.d("  -SuperClass: " + cls.getSuperClassName());
                    R<InputStream> inputStreamR = dexModifier.modifySuperclass(
                            bytes, cls.getClassName(), newSuperSmali);
                    bytes = null;
                    System.gc();
                    logger.d("  -Merge modified... ");
                    destZFile.add(cls.getDexName(), inputStreamR.getData(), true);
                    modified = true;
                    break;
                }
            } catch (Exception e) {
                System.gc();
               throw new PatchError("Error when modify dex file", e);
            }
            dexCount++;
        }

        System.gc();
        if(!modified){
            //logger.i(" -未在 dex 中找到 ComponentFactory: " + apk.getAppComponentFactory());
            setManifestComponentFactory(destZFile);
        }
    }

    private void checkUpdatePatch(ZFile srcZFile) {
        updatePatch = (srcZFile.get(LOADER_CORE_SO_PATH) != null || apk.getPatchFile().containsKey(LOADER_CORE_SO_PATH)
        || srcZFile.get("assets/ytp/loader.dex") != null || apk.getPatchFile().containsKey("assets/ytp/loader.dex")//TODO 以后会删除这行，兼容旧版本
        );
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
                is.close();
                os.flush();
                os.close();
                var manifest = os.toByteArray();
                destZFile.add(ANDROID_MANIFEST_XML, new ByteArrayInputStream(manifest));
            } catch (IOException e) {
                throw new PatchError("Failed to modify manifest", e);
            }
        }
    }
}
