/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.patch.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

public class StreamUtilTest {
    @Test
    public void comparesStreamsWithDifferentReadChunkSizes() throws IOException {
        byte[] data = new byte[100_003];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        byte[] firstBuffer = new byte[4096];
        byte[] secondBuffer = new byte[4096];
        assertTrue(StreamUtil.contentEquals(new ChunkedInput(data, 3),
                new ChunkedInput(data, 127), firstBuffer, secondBuffer));

        byte[] changed = data.clone();
        changed[changed.length - 1]++;
        assertFalse(StreamUtil.contentEquals(new ChunkedInput(data, 5),
                new ChunkedInput(changed, 11), firstBuffer, secondBuffer));
    }

    private static class ChunkedInput extends ByteArrayInputStream {
        private final int chunkSize;

        ChunkedInput(byte[] bytes, int chunkSize) {
            super(bytes);
            this.chunkSize = chunkSize;
        }

        @Override
        public synchronized int read(byte[] target, int offset, int length) {
            return super.read(target, offset, Math.min(length, chunkSize));
        }
    }
}
