/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */


package org.ytp.patch;

import static org.ytp.share.Constants.ANDROID_MANIFEST_XML;

import org.ytp.patch.util.Logger;
import org.ytp.patch.util.ManifestParser;
import org.ytp.patch.util.StreamUtil;
import org.ytp.share.Apk;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import bin.zip.ZipEntry;
import bin.zip.ZipFile;
import bin.zip.ZipMaker;

/**
 * 超大容器 APK 的专用修补回退策略。
 *
 * <p>这类 APK 通常在外层 ZIP 中包含一份完整的原始 APK，同时又把原始 APK
 * 的大量条目放在外层。直接用 {@link YTPPatch} 的 {@code ZFile} 将外层条目
 * 全部写回内嵌 APK，会让被替换的大条目仍占据旧位置，并在文件末尾再追加
 * 新内容；累计偏移可能超过普通 ZIP 32 位字段的上限。</p>
 *
 * <p>本类先只提取确实不同的条目供原有修补流程使用，再使用项目内支持
 * ZIP64 的 {@link ZipMaker} 生成最终容器。与内嵌 APK 内容完全相同的外层条目
 * 会作为指向内嵌 APK 数据的虚拟条目写入目录，避免重复保存数 GB 的内容。</p>
 */
public class YTPBigFile extends YTPPatch {

    private static final long ZIP32_MAX_VALUE = 0xffffffffL;
    private static final long LARGE_CONTAINER_BYTES = 512L * 1024 * 1024;
    private static final long LARGE_HOST_BYTES = 256L * 1024 * 1024;

    /** 本策略的临时目录，保存提取出的内嵌 APK 和发生变化的条目。 */
    private PatchWorkspace workspace;

    /**
     * 创建大文件修补器。
     *
     * @param logger 日志接收器
     * @param apk 当前修补任务的 APK 状态，包括包名和待合并条目
     * @param args 与主修补器相同的命令行参数，用于读取签名等配置
     */
    public YTPBigFile(Logger logger, Apk apk, String... args) {
        super(logger, args);
        this.apk = apk;
    }

    /**
     * 判断上一个修补策略是否因为 ZIP 32 位字段溢出而失败。
     *
     * <p>异常通常被多层 {@code PatchError} 包装，因此需要沿着 cause 链检查。
     * 这里只匹配 ZIP 大小或偏移越界的错误，避免普通修补失败也被误判为大文件问题。</p>
     *
     * @param error 前一个策略抛出的异常
     * @return 匹配到 ZIP32 上限错误时返回 {@code true}
     */
    static boolean canHandleFallback(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message == null) {
                continue;
            }
            String normalized = message.toLowerCase(Locale.ROOT);
            if (normalized.contains("0x00000000ffffffffl")
                    || normalized.contains("value out of unsigned int")
                    || normalized.contains("zip entry size needs zip64")) {
                return true;
            }
            if (normalized.contains("value (") && normalized.contains(") > 0x")
                    && normalized.contains("ffffffff")) {
                return true;
            }
        }
        return false;
    }

    /** 只检查 ZIP 目录项与偏移，不读取大型资源的实际字节。 */
    static boolean containsPhysicalAliases(ZipFile container, ZipEntry host, ZipFile inner) {
        for (ZipEntry outerEntry : container.getEntries()) {
            if (outerEntry != host && isPhysicalAlias(outerEntry, host,
                    inner.getEntry(outerEntry.getName()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 执行大文件修补流程：提取原包、筛选变化条目、调用常规修补逻辑，
     * 最后用 ZIP64 写出容器并签名。
     *
     * @param srcApkFile 原始容器 APK
     * @param destApkFile 修补后 APK 的输出位置
     */
    public void patchBigFile(File srcApkFile, File destApkFile) throws Exception {
        logger.i("-----------------------------------");
        logger.i("Big file patch parsing starting");

        workspace = PatchWorkspace.create(destApkFile, "ytp-bigfile-");

        // 前一条回退路径可能留下指向已删除临时文件的路径，重新收集前必须清空。
        apk.getPatchFile().clear();
        try {
            long stageStarted = System.nanoTime();
            // 先按包名定位外层容器中真正的原始 APK，再只提取需要覆盖或新增的条目。
            String hostEntryName = extractOriginalApk(srcApkFile);
            logDuration("Extract inline APK", stageStarted);
            File hostApk = workspace.resolveEntry(hostEntryName);
            stageStarted = System.nanoTime();
            collectChangedEntries(srcApkFile, hostEntryName);
            logDuration("Filter outer items", stageStarted);

            // 提取出的 APK 只属于本次工作区，直接修改可省下一次数 GB 的整包复制。
            // 若标准修补失败，先从外层原包恢复，再交给兼容性回退链。
            stageStarted = System.nanoTime();
            boolean standardPatched = true;
            try {
                patchWorkspaceApk(hostApk);
            } catch (PatchError error) {
                if (!YTPExtend.canHandleFallback(error)) {
                    throw error;
                }
                standardPatched = false;
                logger.i("Patch failed. Restored embedded APK and used fallback....");
                restoreOriginalApk(srcApkFile, hostEntryName, hostApk);
                patchIntermediateApk(hostApk);
            }
            logDuration("Patch inner APK", stageStarted);

            // 合并时既保留外层独有条目，也把补丁后的同名条目及内嵌 APK 新增条目带入输出。
            stageStarted = System.nanoTime();
            buildOptimizedContainer(srcApkFile, hostApk, destApkFile, hostEntryName, standardPatched);
            logDuration("Rebuild ZIP64 container", stageStarted);
            if (!deferOutputFinalization) {
                signApk(destApkFile);
            }
        } finally {
            deleteRecursively(workspace.directory());
        }
    }

    /** 先只读取候选 APK 的目录和 Manifest，确认包名后才提取完整数据。 */
    private String extractOriginalApk(File srcApkFile) throws IOException {
        logger.i("Analyse big APK container...");
        try (ZipFile container = new ZipFile(srcApkFile)) {
            ZipEntry host = findOriginalEntry(container, apk.getPackageName());
            if (host == null) {
                throw new PatchError("Original APK patch not found for package " + apk.getPackageName());
            }
            File destination = workspace.resolveEntry(host.getName());
            byte[] buffer = new byte[128 * 1024];
            copy(container.getInputStream(host), destination, buffer);
            apk.setOriginalApkPatch(host.getName());
            logger.d("Original APK patch: " + host.getName());
            return host.getName();
        }
    }

    /** 标准修补可能已写入部分 ZIP 数据；回退前从未修改的外层容器重新提取。 */
    private void restoreOriginalApk(File source, String hostEntryName, File destination) throws IOException {
        try (ZipFile container = new ZipFile(source)) {
            ZipEntry host = container.getEntry(hostEntryName);
            if (host == null) {
                throw new IOException("Embedded APK not found while restoring: " + hostEntryName);
            }
            copy(container.getInputStream(host), destination, new byte[128 * 1024]);
        }
    }

    /**
     * 未压缩的内嵌 APK 可以直接映射为外层 ZIP 的数据片段，无需落盘。
     * 大文件虚拟条目也要求宿主为 STORED，因此跳过压缩候选项。
     */
    private static ZipEntry findOriginalEntry(ZipFile container, String packageName) {
        for (ZipEntry entry : container.getEntries()) {
            if (entry.isDirectory() || !entry.getName().endsWith(".apk")
                    || entry.getMethod() != ZipMaker.METHOD_STORED) {
                continue;
            }
            try (ZipFile inner = container.openEntryAsZipFile(entry)) {
                ZipEntry manifest = inner.getEntry(ANDROID_MANIFEST_XML);
                if (manifest == null) {
                    continue;
                }
                try (InputStream input = inner.getInputStream(manifest)) {
                    ManifestParser.Pair pair = ManifestParser.parseManifestFile(input);
                    if (pair != null && packageName.equals(pair.packageName)) {
                        return entry;
                    }
                }
            } catch (Exception ignored) {
                // 容器里可能混有无效 APK；检查后续候选项即可。
            }
        }
        return null;
    }

    /**
     * 只提取需要覆盖或新增到内嵌 APK 的外层条目。
     *
     * <p>对内外层都存在且内容相同的条目不创建临时副本，也不交给 ZFile 重写，
     * 以免数 GB 的相同数据被追加到工作 APK 尾部。不存在或内容不同的条目会被提取，
     * 并登记到父类使用的 patchFile 映射中。</p>
     */
    private void collectChangedEntries(File srcApkFile, String hostEntryName) throws IOException {
        try (ZipFile container = new ZipFile(srcApkFile)) {
            ZipEntry host = container.getEntry(hostEntryName);
            if (host == null || host.getMethod() != ZipMaker.METHOD_STORED) {
                throw new IOException("Stored embedded APK not found: " + hostEntryName);
            }
            try (ZipFile inner = container.openEntryAsZipFile(host)) {
                byte[] buffer = new byte[64 * 1024];
                byte[] compareBuffer = new byte[buffer.length];
                int shared = 0;
                int changed = 0;
                long skippedBytes = 0;
                for (ZipEntry entry : container.getEntries()) {
                    if (entry.isDirectory() || entry.getName().equals(hostEntryName)) {
                        continue;
                    }
                    ZipEntry innerEntry = inner.getEntry(entry.getName());
                    if (innerEntry != null && isPhysicalAlias(entry, host, innerEntry)) {
                        // 外层和内层指向源文件中的同一段压缩字节，无需再读取数 GB 数据。
                        shared++;
                        skippedBytes += entry.getCompressedSize();
                        continue;
                    }
                    if (innerEntry != null && sameContent(container, entry, inner, innerEntry,
                            buffer, compareBuffer)) {
                        continue;
                    }
                    File changedFile = workspace.resolveEntry(entry.getName());
                    copy(container.getInputStream(entry), changedFile, buffer);
                    apk.addPatchFile(entry.getName(), changedFile.getAbsolutePath());
                    changed++;
                }
                logger.i("Shared ZIP entries: " + shared + ", changed entries: " + changed
                        + ", skipped comparison bytes: " + skippedBytes);
            }
        }
    }

    /**
     * 外层虚拟条目和内嵌条目若引用同一物理数据区，原始压缩字节必然完全相同。
     * 同时核对方法、长度和 CRC，避免损坏的目录记录误入快速路径。
     */
    static boolean isPhysicalAlias(ZipEntry outerEntry, ZipEntry host, ZipEntry innerEntry) {
        if (innerEntry == null) {
            return false;
        }
        long innerOffset = innerEntry.getDataOffset();
        long compressedSize = innerEntry.getCompressedSize();
        return !outerEntry.isDirectory()
                && host.getMethod() == ZipMaker.METHOD_STORED
                && host.getDataOffset() >= 0
                && innerOffset >= 0 && compressedSize >= 0
                && compressedSize <= host.getCompressedSize()
                && innerOffset <= host.getCompressedSize() - compressedSize
                && innerOffset <= Long.MAX_VALUE - host.getDataOffset()
                && outerEntry.getDataOffset() == host.getDataOffset() + innerOffset
                && outerEntry.getCompressedSize() == compressedSize
                && outerEntry.getMethod() == innerEntry.getMethod()
                && outerEntry.getGeneralPurposeFlag() == innerEntry.getGeneralPurposeFlag()
                && outerEntry.getSize() == innerEntry.getSize()
                && outerEntry.getCrc() == innerEntry.getCrc();
    }

    /**
     * 通过 ZIP64 方式重建最终容器。
     *
     * <p>内嵌 APK 作为实际数据宿主写入一次。外层条目若与补丁后的内嵌 APK 条目一致，
     * 则写入虚拟目录记录；若同名但内容已被修补，则从内嵌 APK 复制新版本；
     * 若内嵌 APK 中没有该名称，则保留外层容器原条目。最后再补入只存在于修补后 APK 的新条目。</p>
     */
    void buildOptimizedContainer(File source, File patchedHost, File output, String hostEntryName,
                                 boolean standardPatched)
            throws IOException {
        logger.i("Rebuilding APK with ZIP64 support...");
        try (ZipFile container = new ZipFile(source);
             ZipFile inner = new ZipFile(patchedHost);
             ZipMaker maker = new ZipMaker(output)) {
            ZipEntry host = container.getEntry(hostEntryName);
            if (host == null) {
                throw new IOException("Embedded APK not found: " + hostEntryName);
            }
            if (host.getMethod() != ZipMaker.METHOD_STORED) {
                throw new IOException("Embedded APK must be stored in the container: " + hostEntryName);
            }

            try (ZipFile originalInner = container.openEntryAsZipFile(host)) {
                ZipMaker.HostEntryHolder hostHolder = maker.putNextHostEntry(hostEntryName, inner);
                // 记录已经由外层循环处理的名称，避免后续加入内层新增项时产生重名条目。
                Set<String> copiedNames = new HashSet<>();
                // 同一轮循环复用缓冲区，避免大量小条目反复分配 128 KiB 数组。
                byte[] firstBuffer = new byte[64 * 1024];
                byte[] secondBuffer = new byte[firstBuffer.length];
                int fastVirtualized = 0;
                long skippedBytes = 0;
                for (ZipEntry entry : container.getEntries()) {
                    if (entry.getName().equals(hostEntryName)) {
                        continue;
                    }
                    ZipEntry innerEntry = inner.getEntry(entry.getName());
                    copiedNames.add(entry.getName());
                    boolean unchangedShared = isUnchangedSharedEntry(entry, host,
                            originalInner.getEntry(entry.getName()), innerEntry, standardPatched);
                    if (canVirtualize(container, entry, inner, innerEntry, unchangedShared,
                            firstBuffer, secondBuffer)) {
                        hostHolder.putNextVirtualEntry(entry.getName());
                        if (unchangedShared) {
                            fastVirtualized++;
                            skippedBytes += entry.getCompressedSize();
                        }
                    } else if (innerEntry != null) {
                        // 内外层同名但不能共享数据时，内嵌 APK 中的是已修补版本，应优先使用它。
                        maker.copyZipEntry(innerEntry, inner);
                    } else {
                        // 仅在外层存在的文件（例如容器自己的辅助文件）仍从原容器复制。
                        maker.copyZipEntry(entry, container);
                    }
                }

                // 将外层原本没有的 loader、配置文件和新增 DEX 等条目放入最终 APK 顶层。
                for (ZipEntry entry : inner.getEntries()) {
                    if (!entry.getName().equals(hostEntryName) && copiedNames.add(entry.getName())) {
                        maker.copyZipEntry(entry, inner);
                    }
                }
                logger.i("Fast virtual entries: " + fastVirtualized
                        + ", skipped comparison bytes: " + skippedBytes);
            }
        }
    }

    /**
     * 原始外层条目已共享宿主数据。标准修补只改写显式列出的名称；其余条目即使
     * 被 ZFile.realign 移到新偏移，逻辑内容仍未变化。兼容性回退的修改范围不明确，
     * 因此要求偏移也保持不变，否则走完整字节比对。
     */
    boolean isUnchangedSharedEntry(ZipEntry outerEntry, ZipEntry host,
                                   ZipEntry originalInnerEntry, ZipEntry patchedInnerEntry,
                                   boolean standardPatched) {
        if (originalInnerEntry == null || patchedInnerEntry == null
                || !isPhysicalAlias(outerEntry, host, originalInnerEntry)) {
            return false;
        }
        String name = outerEntry.getName();
        if (apk.getPatchFile().containsKey(name) || name.equals(ANDROID_MANIFEST_XML)
                || name.startsWith("assets/ytp/")
                || (name.startsWith("classes") && name.endsWith(".dex"))) {
            return false;
        }
        boolean sameLocation = originalInnerEntry.getHeaderOffset() == patchedInnerEntry.getHeaderOffset()
                && originalInnerEntry.getDataOffset() == patchedInnerEntry.getDataOffset();
        return (standardPatched || sameLocation)
                && originalInnerEntry.getCompressedSize() == patchedInnerEntry.getCompressedSize()
                && originalInnerEntry.getMethod() == patchedInnerEntry.getMethod()
                && originalInnerEntry.getGeneralPurposeFlag() == patchedInnerEntry.getGeneralPurposeFlag()
                && originalInnerEntry.getSize() == patchedInnerEntry.getSize()
                && originalInnerEntry.getCrc() == patchedInnerEntry.getCrc()
                && Arrays.equals(originalInnerEntry.getCommentData(), patchedInnerEntry.getCommentData());
    }

    /**
     * 判断一个外层条目是否可以通过虚拟条目引用内嵌 APK 中的数据。
     *
     * <p>除了内容一致，ZIP 方法、校验和、长度和注释也必须匹配。对需要满足 APK
     * 对齐要求的 resources.arsc 和 .so 文件，还要检查内层数据偏移。</p>
     */
    private boolean canVirtualize(ZipFile outer, ZipEntry outerEntry, ZipFile inner, ZipEntry innerEntry,
                                  boolean unchangedShared, byte[] firstBuffer, byte[] secondBuffer)
            throws IOException {
        if (innerEntry == null
                || outerEntry.getMethod() != innerEntry.getMethod()
                || outerEntry.getCrc() != innerEntry.getCrc()
                || outerEntry.getSize() != innerEntry.getSize()
                || !Arrays.equals(outerEntry.getCommentData(), innerEntry.getCommentData())) {
            return false;
        }
        if (innerEntry.getMethod() == ZipMaker.METHOD_STORED) {
            String name = innerEntry.getName();
            if ((name.equals("resources.arsc") && innerEntry.getDataOffset() % 4 != 0)
                    || (name.endsWith(".so") && innerEntry.getDataOffset() % 4096 != 0)) {
                return false;
            }
        }
        return unchangedShared || sameContent(outer, outerEntry, inner, innerEntry,
                firstBuffer, secondBuffer);
    }

    /**
     * 比较两个 ZIP 条目的解压后内容。
     *
     * <p>先用未压缩长度和 CRC 快速排除不同内容；若压缩方法、压缩长度一致，
     * 再优先比较压缩数据，命中时无需解压。压缩表示不同的情况下则逐块比较解压流，
     * 从而允许相同内容采用不同压缩结果。</p>
     */
    private boolean sameContent(ZipFile firstZip, ZipEntry first, ZipFile secondZip, ZipEntry second,
                                byte[] firstBuffer, byte[] secondBuffer) throws IOException {
        if (first.getSize() != second.getSize() || first.getCrc() != second.getCrc()) {
            return false;
        }
        if (first.getMethod() == second.getMethod()
                && first.getCompressedSize() == second.getCompressedSize()
                && StreamUtil.contentEquals(firstZip.getRawInputStream(first), secondZip.getRawInputStream(second),
                        firstBuffer, secondBuffer)) {
            return true;
        }
        return StreamUtil.contentEquals(firstZip.getInputStream(first), secondZip.getInputStream(second),
                firstBuffer, secondBuffer);
    }

    /** 将条目流以固定大小的缓冲区复制到临时文件，不按条目大小分配内存。 */
    private void copy(InputStream input, File output, byte[] buffer) throws IOException {
        try (InputStream source = input; FileOutputStream destination = new FileOutputStream(output)) {
            int length;
            while ((length = source.read(buffer)) != -1) {
                destination.write(buffer, 0, length);
            }
        }
    }

}
