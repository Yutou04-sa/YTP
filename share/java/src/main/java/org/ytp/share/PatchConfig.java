/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.share;

public class PatchConfig {
    public final String appComponentFactory;
    public final YTPConfig ytpConfig;

    public final String packageName;

    /** 修补后写进 manifest 的新包名（applicationId）；空字符串表示保持原包名。 */
    public final String newPackageName;

    /** 修补后写进 manifest 的应用名（android:label）；空字符串表示保持原应用名。 */
    public final String appLabel;

    public PatchConfig(
            String appComponentFactory,
            String packageName
    ) {
        this(appComponentFactory, packageName, "", "");
    }

    public PatchConfig(
            String appComponentFactory,
            String packageName,
            String newPackageName,
            String appLabel
    ) {
        this.appComponentFactory = appComponentFactory;
        this.ytpConfig = YTPConfig.instance;
        this.packageName = packageName;
        this.newPackageName = newPackageName == null ? "" : newPackageName;
        this.appLabel = appLabel == null ? "" : appLabel;
    }
}
