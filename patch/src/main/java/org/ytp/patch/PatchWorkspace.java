/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */


package org.ytp.patch;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 回退策略使用的独立工作目录。每次调用只管理自己创建的目录，避免清理其他任务的文件。
 */
final class PatchWorkspace {
    private final Path root;

    private PatchWorkspace(Path root) {
        this.root = root;
    }

    static PatchWorkspace create(File output, String prefix) throws IOException {
        File parent = output.getAbsoluteFile().getParentFile();
        Files.createDirectories(parent.toPath());
        return new PatchWorkspace(Files.createTempDirectory(parent.toPath(), prefix));
    }

    File directory() {
        return root.toFile();
    }

    /**
     * ZIP 条目名必须留在工作目录内。ZIP 规范使用正斜杠，因此直接拒绝反斜杠、
     * 盘符、绝对路径与向上回退的路径，防止提取时写到任务目录之外。
     */
    File resolveEntry(String entryName) throws IOException {
        if (entryName == null || entryName.isEmpty() || entryName.indexOf('\\') >= 0
                || entryName.indexOf(':') >= 0) {
            throw new IOException("Invalid ZIP entry name: " + entryName);
        }
        Path relative;
        try {
            relative = Paths.get(entryName);
        } catch (InvalidPathException error) {
            throw new IOException("Invalid ZIP entry name: " + entryName, error);
        }
        Path file = root.resolve(relative).normalize();
        if (relative.isAbsolute() || !file.startsWith(root) || file.equals(root)) {
            throw new IOException("ZIP entry escapes extraction directory: " + entryName);
        }
        Files.createDirectories(file.getParent());
        return file.toFile();
    }
}
