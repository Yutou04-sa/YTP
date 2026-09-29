/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.share;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * APK information and patch data container
 */
public class Apk {

    public Apk() {
    }

    /**
     * Constructor for Apk class
     *
     * @param packageName          Package name of the APK
     * @param minSdkVersion        Minimum SDK version
     */
    public Apk(String packageName, String appComponentFactory, int minSdkVersion) {
        this.appComponentFactory = appComponentFactory;
        this.minSdkVersion = minSdkVersion;
        this.packageName = packageName;
    }

    private String appComponentFactory;
    private int minSdkVersion;

    private String packageName;

    private String originalApkPatch;

    private Map<String, String> patchFile = new HashMap<>();

    private List<ClassDefInfo> classDefInfoList = new ArrayList<>();

    /**
     * Get the application component factory class name
     *
     * @return Application component factory class name
     */
    public String getAppComponentFactory() {
        return appComponentFactory;
    }

    /**
     * Set the application component factory class name
     *
     * @param appComponentFactory Application component factory class name
     */
    public Apk setAppComponentFactory(String appComponentFactory) {
        this.appComponentFactory = appComponentFactory;
        return this;
    }

    /**
     * Get the package name
     *
     * @return Package name
     */
    public String getPackageName() {
        return packageName;
    }

    /**
     * Set the package name
     *
     * @param packageName Package name
     */
    public Apk setPackageName(String packageName) {
        this.packageName = packageName;
        return this;
    }

    /**
     * Get the minimum SDK version
     *
     * @return Minimum SDK version
     */
    public int getMinSdkVersion() {
        return minSdkVersion;
    }

    /**
     * Set the minimum SDK version
     *
     * @param minSdkVersion Minimum SDK version
     */
    public Apk setMinSdkVersion(int minSdkVersion) {
        this.minSdkVersion = minSdkVersion;
        return this;
    }

    /**
     * Get the original APK patch path
     *
     * @return Original APK patch path
     */
    public String getOriginalApkPatch() {
        return originalApkPatch;
    }

    /**
     * Set the original APK patch path
     *
     * @param originalApkPatch Original APK patch path
     */
    public Apk setOriginalApkPatch(String originalApkPatch) {
        this.originalApkPatch = originalApkPatch;
        return this;
    }

    /**
     * Get the patch file map
     *
     * @return Patch file map
     */
    public Map<String, String> getPatchFile() {
        return patchFile;
    }

    /**
     * Set the patch file map
     *
     * @param patchFile Patch file map
     */
    public Map<String, String> setPatchFile(Map<String, String> patchFile) {
        this.patchFile = patchFile;
        return this.patchFile;
    }

    /**
     * Add a patch file to the map
     *
     * @param name File name
     * @param path File path
     */
    public void addPatchFile(String name, String path) {
        this.patchFile.put(name, path);
    }

    public List<ClassDefInfo> getClassDefInfoList() {
        return classDefInfoList;
    }

    public void setClassDefInfoList(List<ClassDefInfo> classDefInfoList) {
        this.classDefInfoList = classDefInfoList;
    }

    public void addClassDefInfo(ClassDefInfo classDefInfo) {
        this.classDefInfoList.add(classDefInfo);
    }
}