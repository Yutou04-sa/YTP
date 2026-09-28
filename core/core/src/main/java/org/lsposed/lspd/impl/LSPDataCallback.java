package org.lsposed.lspd.impl;

import org.lsposed.lspd.interfaces.DataCallback;

public class LSPDataCallback {
    private static volatile DataCallback instance = null;

    private LSPDataCallback() {
        throw new IllegalStateException("DataCallback cannot be instantiated");
    }

    public static DataCallback getInstance() {
        DataCallback cb = instance;
        if (cb == null) {
            throw new IllegalStateException("DataCallback not initialized");
        }
        return cb;
    }

    public static void register(DataCallback callback) {
        if (callback == null) throw new IllegalArgumentException("callback cannot be null");
        instance = callback;
    }
}
