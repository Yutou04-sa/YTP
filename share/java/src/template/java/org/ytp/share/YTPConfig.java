package org.ytp.share;

public class YTPConfig {

    public static final YTPConfig instance;

    public int API_CODE;
    public int VERSION_CODE;
    public String VERSION_NAME;
    public int CORE_VERSION_CODE;
    public String CORE_VERSION_NAME;

    private YTPConfig() {
    }

    static {
        instance = new YTPConfig();
        instance.API_CODE = ${apiCode};
        instance.VERSION_CODE = ${verCode};
        instance.VERSION_NAME = "${verName}";
        instance.CORE_VERSION_CODE = ${coreVerCode};
        instance.CORE_VERSION_NAME = "${coreVerName}";
    }
}
