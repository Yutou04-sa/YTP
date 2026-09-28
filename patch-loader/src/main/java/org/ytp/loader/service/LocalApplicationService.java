package org.ytp.loader.service;

import static org.ytp.share.Constants.EMBEDDED_MODULES_ASSET_PATH;
import static org.ytp.share.Constants.LOWER_CASE_NAME;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import org.ytp.loader.util.FileUtils;
import org.ytp.util.ModuleLoader;
import org.lsposed.lspd.impl.LSPInjectedModuleService;
import org.lsposed.lspd.models.Module;
import org.lsposed.lspd.service.ILSPApplicationService;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class LocalApplicationService extends ILSPApplicationService.Stub {
    private static final String TAG = "YTP";
    private final List<Module> legacyModules;

    private final List<Module> modules;

    private static final List<String> loaded = new ArrayList<>();

    private final Context context;

    public LocalApplicationService(Context context) {
        this.context = context;
        legacyModules = Collections.synchronizedList(new ArrayList<>());
        modules = Collections.synchronizedList(new ArrayList<>());
        try {
            loadExternalModule();
            loadAssetsModule();
        } catch (IOException e) {
            Log.e(TAG, "Error when initializing LocalApplicationServiceClient", e);
        }
    }

    private void loadAssetsModule() throws IOException {
        String modulesAssetPath = LOWER_CASE_NAME + "/modules";
        String[] moduleNames = context.getAssets().list(modulesAssetPath);

        if (moduleNames == null || moduleNames.length == 0) {
            Log.w(TAG, "No modules found in assets/" + modulesAssetPath);
            return;
        }

        for (String fileName : moduleNames) {
            String packageName = fileName.contains(".")?fileName.substring(0, fileName.lastIndexOf(".")) : fileName;
            if(loaded.contains(packageName) && fileName.endsWith(".apk")){
                continue;
            }
            Path modulePath = FileUtils.getModulePath(context).resolve(packageName);
            String cacheApkPath;

            try (ZipFile sourceFile = new ZipFile(context.getPackageResourcePath())) {
                ZipEntry entry = sourceFile.getEntry(EMBEDDED_MODULES_ASSET_PATH + fileName);
                if (entry == null) {
                    Log.w(TAG, "Module entry not found in APK: " + EMBEDDED_MODULES_ASSET_PATH + fileName);
                    continue;
                }
                cacheApkPath = modulePath.resolve(entry.getCrc() + ".apk").toString();
            }

            Path cacheApkFilePath = Paths.get(cacheApkPath);
            if (!Files.exists(cacheApkFilePath)) {
                Log.i(TAG, "Extract module apk: " + packageName);
                FileUtils.deleteFolderIfExists(modulePath);
                Files.createDirectories(modulePath);
                try (InputStream is = context.getAssets().open(EMBEDDED_MODULES_ASSET_PATH.replace("assets/", "") + fileName)) {
                    Files.copy(is, cacheApkFilePath);
                }
            }

            Module module = new Module();
            module.apkPath = cacheApkPath;
            module.packageName = packageName;
            module.file = ModuleLoader.loadModule(cacheApkPath);
            addModule(module);
            Log.i(TAG, "Loaded assets module: " + packageName + " from " + cacheApkPath);
        }
    }

    private void loadExternalModule(){
        Path moduleConfigPath = FileUtils.getModuleConfigPath(context);
        if(Files.exists(moduleConfigPath)){
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(moduleConfigPath);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            if(bytes == null || bytes.length == 0) return;
            String config = new String(bytes);
            // 解析JSON
            String[] appList = config.split(",");
            PackageManager pm = context.getPackageManager();
            for (String packageName : appList) {
                if(packageName.isEmpty()) continue;
                try {
                    ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
                    Module module = loadExternalModule(appInfo.sourceDir,packageName);
                    if (module != null) {
                        addModule(module);
                        Log.d(TAG, "Loaded external module: " + packageName + " from " + appInfo.sourceDir);
                    }
                }catch (Exception ignored){}
            }
        }
    }

    /**
     * 从外部APK路径加载模块
     * @param apkPath 外部APK文件的完整路径
     * @return 加载的模块对象，如果加载失败则返回null
     */
    public Module loadExternalModule(String apkPath,String packageName) {
        try {
            if (apkPath == null || !apkPath.endsWith(".apk")) {
                Log.w(TAG, "Invalid external module APK path: " + apkPath);
                return null;
            }
            
            Path apkFile = Paths.get(apkPath);
            if (!Files.exists(apkFile)) {
                Log.w(TAG, "External module APK does not exist: " + apkPath);
                return null;
            }
            
            // 使用原始APK路径直接加载模块
            Module module = new Module();
            module.apkPath = apkPath;
            module.packageName = packageName;
            module.file = ModuleLoader.loadModule(apkPath);
            loaded.add(packageName);
            return module;
        } catch (Exception e) {
            Log.e(TAG, "Error loading external module from: " + apkPath, e);
            return null;
        }
    }

    @Override
    public List<Module> getLegacyModulesList() throws RemoteException {
        Log.d(TAG, "LocalApplicationService.getLegacyModulesList:"+legacyModules.size());
        return legacyModules;
    }

    @Override
    public List<Module> getModulesList() throws RemoteException {
        Log.d(TAG, "LocalApplicationService.getModulesList:"+modules.size());
        return modules;
    }

    @Override
    public String getPrefsPath(String packageName) throws RemoteException {
        if (packageName == null || packageName.isEmpty()) {
            return context.getFilesDir().getAbsolutePath();
        }
        try {
            ApplicationInfo appInfo = context.getPackageManager().getApplicationInfo(packageName, 0);
            return new File(appInfo.dataDir, "shared_prefs").getAbsolutePath();
        } catch (PackageManager.NameNotFoundException e) {
            Path currentDataPath = Paths.get(context.getApplicationInfo().dataDir)
                    .toAbsolutePath()
                    .normalize();
            Path userDataPath = currentDataPath.getParent();
            if (userDataPath == null) {
                throw new RemoteException("Cannot resolve data directory for current user");
            }
            Path prefsPath = userDataPath.resolve(packageName).resolve("shared_prefs").normalize();
            if (!prefsPath.startsWith(userDataPath)) {
                throw new RemoteException("Invalid package name: " + packageName);
            }
            return prefsPath.toString();
        }
    }

    @Override
    public ParcelFileDescriptor requestInjectedManagerBinder(List<IBinder> binder) throws RemoteException {
        return null;
    }

    @Override
    public IBinder asBinder() {
        return this;
    }

    @Override
    public boolean isLogMuted() throws RemoteException {
        return false;
    }

    private void addModule(Module module) {
        if(module!=null && module.file!=null){
            if(module.file.legacy){
                legacyModules.add(module);
            }else {
                try {
                    module.applicationInfo = context.getPackageManager().getApplicationInfo(module.packageName, 0);
                } catch (PackageManager.NameNotFoundException e) {
                    ApplicationInfo applicationInfo = new ApplicationInfo();
                    applicationInfo.packageName = module.packageName;
                    applicationInfo.sourceDir = module.apkPath;
                    applicationInfo.publicSourceDir = module.apkPath;
                    applicationInfo.processName = module.packageName;
                    module.applicationInfo = applicationInfo;
                }
                module.service = new LSPInjectedModuleService(module);
                modules.add(module);
            }
        }
    }
}
