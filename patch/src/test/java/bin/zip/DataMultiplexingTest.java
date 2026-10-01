/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package bin.zip;

import static org.junit.Assert.assertTrue;

import org.ytp.patch.util.Logger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class DataMultiplexingTest {
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void rebuildsContainerWithReadableVirtualEntry() throws Exception {
        byte[] payload = new byte[128 * 1024 + 7];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }
        ByteArrayOutputStream embeddedBytes = new ByteArrayOutputStream();
        try (ZipOutputStream embedded = new ZipOutputStream(embeddedBytes)) {
            embedded.putNextEntry(new ZipEntry("payload.bin"));
            embedded.write(payload);
            embedded.closeEntry();
        }

        File input = temporaryFolder.newFile("input.apk");
        File output = temporaryFolder.newFile("output.apk");
        try (ZipOutputStream outer = new ZipOutputStream(new FileOutputStream(input))) {
            ZipEntry host = new ZipEntry("assets/base.apk");
            host.setMethod(ZipEntry.STORED);
            host.setSize(embeddedBytes.size());
            host.setCompressedSize(embeddedBytes.size());
            CRC32 crc = new CRC32();
            crc.update(embeddedBytes.toByteArray());
            host.setCrc(crc.getValue());
            outer.putNextEntry(host);
            outer.write(embeddedBytes.toByteArray());
            outer.closeEntry();
            outer.putNextEntry(new ZipEntry("payload.bin"));
            outer.write(payload);
            outer.closeEntry();
            outer.putNextEntry(new ZipEntry("only-outer-a.txt"));
            outer.write(new byte[] {1, 2, 3});
            outer.closeEntry();
            outer.putNextEntry(new ZipEntry("only-outer-b.txt"));
            outer.write(new byte[] {4, 5, 6});
            outer.closeEntry();
        }

        Logger logger = new Logger() {
            @Override public void d(String message) { }
            @Override public void i(String message) { }
            @Override public void e(String message) { }
        };
        ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();
        try {
            new DataMultiplexing(logger, monitor).optimize(input, output, "assets/base.apk", false);
        } finally {
            monitor.shutdown();
        }
        assertTrue(DataMultiplexing.isZipFileContentEquals(input, output));
    }
}
