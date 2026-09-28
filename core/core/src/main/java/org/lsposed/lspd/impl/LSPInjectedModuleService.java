package org.lsposed.lspd.impl;

import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import org.lsposed.lspd.models.Module;
import org.lsposed.lspd.service.ILSPInjectedModuleService;
import org.lsposed.lspd.service.IRemotePreferenceCallback;
import org.lsposed.lspd.util.ModuleConfigManager;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.service.IXposedService;

public class LSPInjectedModuleService extends ILSPInjectedModuleService.Stub {

    private static final String TAG = "LSPInjectedModuleService";

    private final static String FILES_DIR = "files";

    private final Module module;
    private static final int PER_USER_RANGE = 100000;

    Map<String, Set<IRemotePreferenceCallback>> callbacks = new ConcurrentHashMap<>();

    public LSPInjectedModuleService(Module module) {
        this.module = module;
    }

    @Override
    public long getFrameworkProperties() {
        return IXposedService.PROP_CAP_SYSTEM | IXposedService.PROP_CAP_REMOTE;
    }

    @Override
    public Bundle requestRemotePreferences(String group, IRemotePreferenceCallback callback) {
        var userId = ensureModule();
        // 注册回调，使 onUpdateRemotePreferences 能推送后续变更（修复监听失效）
        if (callback != null) {
            var groupCallbacks = callbacks.computeIfAbsent(group, k -> ConcurrentHashMap.newKeySet());
            groupCallbacks.add(callback);
            // 监听方进程死亡时清理，避免回调泄漏
            try {
                callback.asBinder().linkToDeath(() -> groupCallbacks.remove(callback), 0);
            } catch (RemoteException ignored) {
            }
        }
        var bundle = new Bundle();
        bundle.putSerializable("map", ModuleConfigManager.getInstance().getModulePrefs(module.packageName, userId, group));
        return bundle;
    }

    @Override
    public ParcelFileDescriptor openRemoteFile(String path) throws RemoteException {
        ModuleConfigManager.getInstance().ensureModuleFilePath(path);
        try {
            var dir = ModuleConfigManager.getInstance().resolveModuleDir(module.packageName, FILES_DIR);
            return ParcelFileDescriptor.open(dir.resolve(path).toFile(), ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_READ_WRITE);
        } catch (IOException e) {
            throw new RemoteException(e.getMessage());
        }
    }

    @Override
    public String[] getRemoteFileList() throws RemoteException {
        try {
            var dir = ModuleConfigManager.getInstance().resolveModuleDir(module.packageName, FILES_DIR);
            var files = dir.toFile().list();
            return files == null ? new String[0] : files;
        } catch (IOException e) {
            throw new RemoteException(e.getMessage());
        }
    }

    public void onUpdateRemotePreferences(String group, Bundle diff) {
        var groupCallbacks = callbacks.get(group);
        if (groupCallbacks != null) {
            var iterator = groupCallbacks.iterator();
            while (iterator.hasNext()) {
                var callback = iterator.next();
                try {
                    callback.onUpdate(diff);
                } catch (RemoteException e) {
                    // 回调已失效，通过迭代器移除，避免并发修改问题
                    iterator.remove();
                }
            }
        }
    }

    private int ensureModule() {
        return Binder.getCallingUid() / PER_USER_RANGE;
    }
}