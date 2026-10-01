/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.patch;

import static org.junit.Assert.assertEquals;

import org.ytp.patch.util.Logger;
import org.junit.Test;

public class PatchFallbackPipelineTest {
    @Test
    public void reportsTheStrategyThatSucceeded() throws Exception {
        Logger logger = new Logger() {
            @Override public void d(String message) { }
            @Override public void i(String message) { }
            @Override public void e(String message) { }
        };
        int selected = new PatchFallbackPipeline(logger)
                .add("standard", () -> { throw new YTPPatch.PatchError("overlaps with"); },
                        YTPExtend::canHandleFallback)
                .add("extend", () -> { })
                .execute();
        assertEquals(1, selected);
    }
}
