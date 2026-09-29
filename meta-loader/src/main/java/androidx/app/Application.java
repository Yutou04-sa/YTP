/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package androidx.app;

import android.content.Context;

public class Application extends android.app.Application{

    @Override
    public void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Init.load(this);
    }
}
