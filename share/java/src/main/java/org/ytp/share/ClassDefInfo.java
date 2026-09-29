/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.share;

public class ClassDefInfo {


    public ClassDefInfo(String className, String superClassName, String dexName) {
        this.className = className;
        this.superClassName = superClassName;
        this.dexName = dexName;
    }

    private String className;
    private String superClassName;
    private String dexName;

    private String outFilePath;

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public String getSuperClassName() {
        return superClassName;
    }

    public void setSuperClassName(String superClassName) {
        this.superClassName = superClassName;
    }

    public String getDexName() {
        return dexName;
    }

    public void setDexName(String dexName) {
        this.dexName = dexName;
    }

    public String getOutFilePath() {
        return outFilePath;
    }

    public void setOutFilePath(String outFilePath) {
        this.outFilePath = outFilePath;
    }
}
