/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.patch;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

public class PatchWorkspaceTest {
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void workspacesAreIsolatedAndEntriesStayInsideThem() throws IOException {
        File output = new File(temporaryFolder.getRoot(), "result.apk");
        PatchWorkspace first = PatchWorkspace.create(output, "ytp-test-");
        PatchWorkspace second = PatchWorkspace.create(output, "ytp-test-");

        assertNotEquals(first.directory(), second.directory());
        assertTrue(first.resolveEntry("assets/base.apk").toPath()
                .startsWith(first.directory().toPath()));
        assertThrows(IOException.class, () -> first.resolveEntry("../outside.apk"));
        assertThrows(IOException.class, () -> first.resolveEntry("/outside.apk"));
        assertThrows(IOException.class, () -> first.resolveEntry("..\\outside.apk"));
        assertThrows(IOException.class, () -> first.resolveEntry("C:/outside.apk"));
    }
}
