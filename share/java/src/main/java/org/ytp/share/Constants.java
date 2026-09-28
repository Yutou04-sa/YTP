package org.ytp.share;

import java.util.List;

public class Constants {

    final static public String UPPER_CASE_NAME = "YTP";
    final static public String LOWER_CASE_NAME = "ytp";
    final static public String CONFIG_ASSET_PATH = "assets/ytp/config.json";

    final static public String CONFIG_EXTERNAL_MODULE = "external_module.cfg";

    final static public String ASSETS_PATCH = "assets/ytp/";
    final static public String LOADER_CORE_SO_PATH = "assets/ytp/core.so";
    final static public String META_LOADER_DEX_ASSET_PATH = "assets/ytp/metaloader.dex";
    final static public String LIB_ASSET_PATH = "assets/ytp/so/%s/libytp.so";
    final static public String EMBEDDED_MODULES_ASSET_PATH = "assets/ytp/modules/";

    final static public String MODULE_PROP_PATH = "META-INF/xposed/module.prop";

    final static public String KEY_STORE_PASSWORD = "123456";
    /** Built-in patch signing key name/alias. Must match the alias inside manager/assets/keystore. */
    final static public String KEY_STORE_ALIAS = "\u9c7c\u5b50";
    final static public String KEY_STORE_ALIAS_PASSWORD = "123456";

    final static public String LOG_PROCESS = "LOG_PROCESS";

    final static public String PATCH_FILE_SUFFIX = "-YTP.apk";

    final static public String MODULE_ACTIVITY = "androidx.app.ModuleActivity";

    final static public String ANDROID_MANIFEST_XML = "AndroidManifest.xml";

    final static public String PROXY_APP_PROXY_FACTORY = "androidx.app.AppComponentFactory";

    final static public List<String> ANDROID_PROXY_FACTORIES = List.of("android.app.AppComponentFactory","androidx.core.app.AppComponentFactory","androidx.core.app.CoreComponentFactory");
    final static public List<String> ANDROID_PROXY_FACTORIES_SMAIL = List.of("Landroid/app/AppComponentFactory;","Landroidx/core/app/AppComponentFactory;","Landroidx/core/app/CoreComponentFactory;");
    
}
