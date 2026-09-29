/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.patch.util;

public class JavaLogger extends Logger {

    @Override
    public void d(String msg) {
        if (verbose) System.out.println(msg);
    }

    @Override
    public void i(String msg) {
        System.out.println(msg);
    }

    @Override
    public void e(String msg) {
        System.err.println(msg);
    }
}
