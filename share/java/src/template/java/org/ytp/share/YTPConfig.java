/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

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
