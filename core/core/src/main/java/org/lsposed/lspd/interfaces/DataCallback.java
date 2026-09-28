package org.lsposed.lspd.interfaces;

public interface DataCallback {
    void log(int priority, String tag, String msg);
    void println(int priority, String tag, String msg);
    String getModuleConfigPath();
}
