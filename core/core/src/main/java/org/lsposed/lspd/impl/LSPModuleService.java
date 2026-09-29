/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSPosed is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSPosed.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2021 LSPosed Contributors
 */

package org.lsposed.lspd.impl;

import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;

import org.lsposed.lspd.core.BuildConfig;
import org.lsposed.lspd.models.Module;
import org.lsposed.lspd.util.ModuleConfigManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.libxposed.service.HookedProcess;
import io.github.libxposed.service.IHotReloadCallback;
import io.github.libxposed.service.IXposedScopeCallback;
import io.github.libxposed.service.IXposedService;

public class LSPModuleService extends IXposedService.Stub {

    private final static String TAG = "LSPModuleService";

    private final static int PER_USER_RANGE = 100000;

    private final static String FILES_DIR = "files";

    private final Module module;

    private final ModuleConfigManager configManager;

    private final Long mid;

    public LSPModuleService(String ctxPackageName, Module module){
        this.module = module;
        this.configManager = ModuleConfigManager.getInstance();
        this.mid = configManager.upsertModule(module.packageName, module.apkPath, true);
        configManager.insertScope(mid, ctxPackageName);
    }

    @Override
    public int getApiVersion() throws RemoteException {
        return IXposedService.LIB_API;
    }

    @Override
    public String getFrameworkName() throws RemoteException {
        return BuildConfig.FRAMEWORK_NAME;
    }

    @Override
    public String getFrameworkVersion() throws RemoteException {
        return BuildConfig.VERSION_NAME;
    }

    @Override
    public long getFrameworkVersionCode() throws RemoteException {
        return BuildConfig.VERSION_CODE;
    }

    @Override
    public long getFrameworkProperties() throws RemoteException {
        return IXposedService.PROP_CAP_SYSTEM | IXposedService.PROP_CAP_REMOTE;
    }

    @Override
    public List<String> getScope() throws RemoteException {
        // 只返回本模块自己的作用域，避免把其它模块的作用域泄漏给调用方
        return configManager.getAllScope(mid);
    }

    @Override
    public void requestScope(List<String> packages, IXposedScopeCallback callback) throws RemoteException {
        List<String> approved = new ArrayList<>();
        for (String packageName : packages) {
            if(configManager.insertScope(mid, packageName)){
                approved.add(packageName);
            }
        }
        if (!approved.isEmpty()) {
            callback.onScopeRequestApproved(approved);
        } else {
            callback.onScopeRequestFailed("scope insert failed");
        }
    }

    @Override
    public void removeScope(List<String> packages) throws RemoteException {
        for (String packageName : packages) {
            configManager.deleteScope(mid, packageName);
        }
    }

    @Override
    public Bundle requestRemotePreferences(String group) throws RemoteException {
        var userId = ensureModule();
        var bundle = new Bundle();
        bundle.putSerializable("map", configManager.getModulePrefs(module.packageName, userId, group));
        return bundle;
    }

    @Override
    public void updateRemotePreferences(String group, Bundle diff) throws RemoteException {
        var userId = ensureModule();
        Map<String, Object> values = new ArrayMap<>();
        if (diff.getBoolean("clear", false)) {
            // clear 语义：把当前 group 的所有键置 null（updateModulePrefs 中 null 值表示删除）
            for (var key : configManager.getModulePrefs(module.packageName, userId, group).keySet()) {
                values.put(key, null);
            }
        }
        if (diff.containsKey("delete")) {
            var deletes = (Set<?>) diff.getSerializable("delete");
            for (var key : deletes) {
                values.put((String) key, null);
            }
        }
        if (diff.containsKey("put")) {
            try {
                var puts = (Map<?, ?>) diff.getSerializable("put");
                for (var entry : puts.entrySet()) {
                    values.put((String) entry.getKey(), entry.getValue());
                }
            } catch (Throwable e) {
                Log.e(TAG, "updateRemotePreferences: ", e);
            }
        }
        try {
            configManager.updateModulePrefs(module.packageName, userId, group, values);
            ((LSPInjectedModuleService) module.service).onUpdateRemotePreferences(group, diff);
        } catch (Throwable e) {
            throw new RemoteException(e.getMessage());
        }
    }

    @Override
    public void deleteRemotePreferences(String group) throws RemoteException {
        var userId = ensureModule();
        configManager.deleteModulePrefs(module.packageName, userId, group);
    }

    @Override
    public String[] listRemoteFiles() throws RemoteException {
        try {
            var dir = configManager.resolveModuleDir(module.packageName, FILES_DIR);
            var files = dir.toFile().list();
            return files == null ? new String[0] : files;
        } catch (IOException e) {
            throw new RemoteException(e.getMessage());
        }
    }

    @Override
    public ParcelFileDescriptor openRemoteFile(String path) throws RemoteException {
        configManager.ensureModuleFilePath(path);
        try {
            var dir = configManager.resolveModuleDir(module.packageName, FILES_DIR);
            return ParcelFileDescriptor.open(dir.resolve(path).toFile(), ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_READ_WRITE);
        } catch (IOException e) {
            throw new RemoteException(e.getMessage());
        }
    }

    @Override
    public boolean deleteRemoteFile(String path) throws RemoteException {
        configManager.ensureModuleFilePath(path);
        try {
            var dir = configManager.resolveModuleDir(module.packageName, FILES_DIR);
            return dir.resolve(path).toFile().delete();
        } catch (IOException e) {
            throw new RemoteException(e.getMessage());
        }
    }

    @Override
    public List<HookedProcess> getRunningTargets() throws RemoteException {
        return new ArrayList<>();
    }

    @Override
    public void hotReloadModule(long targetId, Bundle data, IHotReloadCallback callback) throws RemoteException {
        callback.onHotReloadResult(IXposedService.HOT_RELOAD_UNSUPPORTED, "hot reload not yet implemented");
    }

    private int ensureModule() {
        return Binder.getCallingUid() / PER_USER_RANGE;
    }
}