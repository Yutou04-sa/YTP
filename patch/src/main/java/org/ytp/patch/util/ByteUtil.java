/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.patch.util;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** 修补资源及 DEX 字节流的读取工具。调用方负责关闭传入的流。 */
public class ByteUtil {
    /** 未知长度时使用固定块读取，避免逐字节读取带来的 I/O 开销。 */
    public static byte[] is2ByteArray(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(64 * 1024);
        int nRead;
        byte[] data = new byte[64 * 1024];
        while ((nRead = inputStream.read(data, 0, data.length)) != -1) {
            if (nRead == 0) {
                int next = inputStream.read();
                if (next == -1) {
                    break;
                }
                buffer.write(next);
            } else {
                buffer.write(data, 0, nRead);
            }
        }
        return buffer.toByteArray();
    }

    /**
     * 当已知未压缩大小时，将 InputStream 读入字节数组。
     * 预分配精确大小的数组，避免 ByteArrayOutputStream 的防御性拷贝，
     * 将大型 DEX 文件的峰值内存从 2x 降至 1x。
     *
     * @param inputStream 要读取的输入流
     * @param knownSize   已知的未压缩大小（字节）
     * @return 包含所有数据的字节数组
     * @throws IOException 如果发生 I/O 错误
     */
    public static byte[] is2ByteArray(InputStream inputStream, long knownSize) throws IOException {
        if (knownSize > Integer.MAX_VALUE) {
            throw new IOException("DEX data exceeds Java array limit: " + knownSize);
        }
        if (knownSize <= 0) {
            return is2ByteArray(inputStream);
        }
        byte[] data = new byte[(int) knownSize];
        int offset = 0;
        int remaining = data.length;
        int nRead;
        while (remaining > 0 && (nRead = inputStream.read(data, offset, remaining)) != -1) {
            if (nRead == 0) {
                int next = inputStream.read();
                if (next == -1) {
                    break;
                }
                data[offset++] = (byte) next;
                remaining--;
                continue;
            }
            offset += nRead;
            remaining -= nRead;
        }
        if (remaining != 0) {
            throw new EOFException("Input ended before the expected " + knownSize + " bytes were read");
        }
        if (inputStream.read() != -1) {
            throw new IOException("Input contains more than the expected " + knownSize + " bytes");
        }
        return data;
    }

    /**
     * 按顺序从 classpath、指定资源目录和项目构建输出目录加载资源。
     * 使用 ytp.assets.dir 系统属性可为命令行指定自定义资源根目录。
     *
     * @param path 资源路径
     * @return 资源输入流，调用方负责关闭
     * @throws FileNotFoundException 找不到或无法打开资源时抛出
     */
    public static InputStream getResourceAsStream(String path) throws FileNotFoundException {
        InputStream resource = ByteUtil.class.getClassLoader().getResourceAsStream(path);
        if (resource != null) {
            return resource;
        }
        String relativePath = path.startsWith("assets/") ? path.substring("assets/".length()) : path;
        String configuredRoot = System.getProperty("ytp.assets.dir");
        Path[] candidates = configuredRoot == null || configuredRoot.trim().isEmpty()
                ? new Path[] {Paths.get(path), Paths.get("out", "assets", "release", relativePath)}
                : new Path[] {Paths.get(configuredRoot, relativePath), Paths.get(path)};
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                try {
                    return Files.newInputStream(candidate);
                } catch (IOException e) {
                    FileNotFoundException failure = new FileNotFoundException("Cannot open patch resource: " + candidate);
                    failure.initCause(e);
                    throw failure;
                }
            }
        }
        throw new FileNotFoundException("Patch resource not found: " + path);
    }

    /**
     * Checks whether a resource can be read without opening it for the caller.
     *
     * <p>Only the architectures that were actually built are packaged, so the caller has to be
     * able to skip the ones that are missing instead of failing the whole patch.
     *
     * @param path Resource path, e.g. "assets/ytp/so/arm64-v8a/libytp.so"
     * @return true when {@link #getResourceAsStream(String)} can provide the resource
     */
    public static boolean hasResource(String path) {
        try (InputStream is = getResourceAsStream(path)) {
            return is != null;
        } catch (IOException e) {
            return false;
        }
    }
}
