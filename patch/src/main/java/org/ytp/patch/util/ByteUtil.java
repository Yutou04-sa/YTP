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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

public class ByteUtil {
    public static byte[] is2ByteArray(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[1024];
        while ((nRead = inputStream.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
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
        if (knownSize <= 0 || knownSize > Integer.MAX_VALUE) {
            return is2ByteArray(inputStream);
        }
        byte[] data = new byte[(int) knownSize];
        int offset = 0;
        int remaining = data.length;
        int nRead;
        while (remaining > 0 && (nRead = inputStream.read(data, offset, remaining)) != -1) {
            offset += nRead;
            remaining -= nRead;
        }
        return data;
    }

    /**
     * Open a build-time resource (loader dex / core.so / lib*.so / config templates).
     *
     * The assets are packed under the "assets/" entry prefix, because on Android the Apk's entries
     * are resolved straight through the class loader. A source checkout keeps the same files in a
     * directory instead (out/assets/release/&lt;name&gt;), optionally relocated with
     * -Dytp.assets.dir=&lt;dir&gt; or the YTP_ASSETS_DIR environment variable.
     *
     * @param path Resource path, e.g. "assets/ytp/core.so"
     * @return InputStream of the resource
     * @throws FileNotFoundException if the resource is neither on disk nor on the classpath
     */
    public static InputStream getResourceAsStream(String path) throws FileNotFoundException {
        String dir = System.getProperty("ytp.assets.dir");
        if (dir == null || dir.isEmpty()) {
            dir = System.getenv("YTP_ASSETS_DIR");
        }
        if (dir == null || dir.isEmpty()) {
            dir = new File("out", "assets/release").getAbsolutePath();
        }
        String relative = path.startsWith(ASSET_ENTRY_PREFIX) ? path.substring(ASSET_ENTRY_PREFIX.length()) : path;
        File local = new File(dir, relative);
        if (!local.isFile()) {
            File alt = new File(dir, path);
            if (alt.isFile()) {
                local = alt;
            }
        }
        if (local.isFile()) {
            return new FileInputStream(local);
        }
        InputStream fromClasspath = ByteUtil.class.getClassLoader().getResourceAsStream(path);
        if (fromClasspath != null) {
            return fromClasspath;
        }
        throw new FileNotFoundException(path + " (not in " + dir + ", not on the classpath)");
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

    private static final String ASSET_ENTRY_PREFIX = "assets/";
}
