/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */


package org.ytp.patch.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/** 大文件流的有界内存比较工具，供两种 ZIP 复用策略共用。 */
public final class StreamUtil {
    private StreamUtil() { }

    /**
     * 逐块比较两条流并关闭它们。缓冲区由调用方复用，避免每个 ZIP 条目都重新分配。
     * readBlock 会读满缓冲区或遇到 EOF，因此不依赖两条流每次 read 的返回长度相同。
     */
    public static boolean contentEquals(InputStream firstInput, InputStream secondInput,
                                        byte[] firstBuffer, byte[] secondBuffer) throws IOException {
        if (firstBuffer.length == 0 || firstBuffer.length != secondBuffer.length) {
            throw new IllegalArgumentException("Comparison buffers must have the same non-zero length");
        }
        try (InputStream first = firstInput; InputStream second = secondInput) {
            while (true) {
                int firstLength = readBlock(first, firstBuffer);
                int secondLength = readBlock(second, secondBuffer);
                if (firstLength != secondLength) {
                    return false;
                }
                if (firstLength < 0) {
                    return true;
                }
                if (firstLength == firstBuffer.length) {
                    if (!Arrays.equals(firstBuffer, secondBuffer)) {
                        return false;
                    }
                } else {
                    for (int i = 0; i < firstLength; i++) {
                        if (firstBuffer[i] != secondBuffer[i]) {
                            return false;
                        }
                    }
                }
            }
        }
    }

    private static int readBlock(InputStream input, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int length = input.read(buffer, offset, buffer.length - offset);
            if (length < 0) {
                return offset == 0 ? -1 : offset;
            }
            if (length == 0) {
                // 少数 InputStream 会对非空缓冲区返回 0，读取单字节保证循环有进展。
                int next = input.read();
                if (next < 0) {
                    return offset == 0 ? -1 : offset;
                }
                buffer[offset++] = (byte) next;
            } else {
                offset += length;
            }
        }
        return offset;
    }
}
