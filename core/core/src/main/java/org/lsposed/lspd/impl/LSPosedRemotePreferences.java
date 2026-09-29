package org.lsposed.lspd.impl;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.ArraySet;

import androidx.annotation.Nullable;

import org.lsposed.lspd.service.ILSPInjectedModuleService;
import org.lsposed.lspd.service.IRemotePreferenceCallback;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("unchecked")
public class LSPosedRemotePreferences implements SharedPreferences {

    private final Map<String, Object> mMap = new ConcurrentHashMap<>();

    final HashSet<OnSharedPreferenceChangeListener> mListeners = new HashSet<>();

    IRemotePreferenceCallback callback = new IRemotePreferenceCallback.Stub() {
        @Override
        synchronized public void onUpdate(Bundle bundle) {
            Set<String> changes = new ArraySet<>();
            boolean cleared = bundle.getBoolean("clear", false);
            if (cleared) {
                // clear 语义：清空本地缓存，通知监听器 null 键
                mMap.clear();
            }
            if (bundle.containsKey("delete")) {
                var deletes = (Set<String>) bundle.getSerializable("delete");
                changes.addAll(deletes);
                for (var key : deletes) {
                    mMap.remove(key);
                }
            }
            if (bundle.containsKey("put")) {
                var puts = (Map<String, Object>) bundle.getSerializable("put");
                mMap.putAll(puts);
                changes.addAll(puts.keySet());
            }
            // 快照监听器并在锁外回调，避免回调中 unregister 触发 ConcurrentModificationException
            List<OnSharedPreferenceChangeListener> listeners;
            synchronized (mListeners) {
                listeners = new ArrayList<>(mListeners);
            }
            if (cleared) {
                for (var listener : listeners) {
                    listener.onSharedPreferenceChanged(LSPosedRemotePreferences.this, null);
                }
            }
            for (var key : changes) {
                for (var listener : listeners) {
                    listener.onSharedPreferenceChanged(LSPosedRemotePreferences.this, key);
                }
            }
        }
    };

    public LSPosedRemotePreferences(ILSPInjectedModuleService service, String group) throws RemoteException {
        Bundle output = service.requestRemotePreferences(group, callback);
        if (output.containsKey("map")) {
            mMap.putAll((Map<String, Object>) output.getSerializable("map"));
        }
    }

    @Override
    public Map<String, ?> getAll() {
        return new TreeMap<>(mMap);
    }

    @Nullable
    @Override
    public String getString(String key, @Nullable String defValue) {
        var v = (String) mMap.getOrDefault(key, defValue);
        if (v != null) return v;
        return defValue;
    }

    @Nullable
    @Override
    public Set<String> getStringSet(String key, @Nullable Set<String> defValues) {
        var v = (Set<String>) mMap.get(key);
        if (v != null) {
            // 返回副本，避免调用方修改返回值污染内部缓存
            return new ArraySet<>(v);
        }
        return defValues;
    }

    @Override
    public int getInt(String key, int defValue) {
        var v = (Integer) mMap.getOrDefault(key, defValue);
        if (v != null) return v;
        return defValue;
    }

    @Override
    public long getLong(String key, long defValue) {
        var v = (Long) mMap.getOrDefault(key, defValue);
        if (v != null) return v;
        return defValue;
    }

    @Override
    public float getFloat(String key, float defValue) {
        var v = (Float) mMap.getOrDefault(key, defValue);
        if (v != null) return v;
        return defValue;
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        var v = (Boolean) mMap.getOrDefault(key, defValue);
        if (v != null) return v;
        return defValue;
    }

    @Override
    public boolean contains(String key) {
        return mMap.containsKey(key);
    }

    @Override
    public Editor edit() {
        throw new UnsupportedOperationException("Read only implementation");
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        synchronized (mListeners) {
            mListeners.add(listener);
        }
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        synchronized (mListeners) {
            mListeners.remove(listener);
        }
    }
}
