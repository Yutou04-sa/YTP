/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.patch.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.ytp.share.ClassDefInfo;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

public class DexModifierTest {
    @Test
    public void findsFactoryAndStopsOnCyclicInheritance() {
        ClassDefInfo factory = new ClassDefInfo("Lexample/Factory;",
                "Landroid/app/AppComponentFactory;", "classes.dex");
        Map<String, ClassDefInfo> classes = new HashMap<>();
        classes.put(factory.getClassName(), factory);
        assertSame(factory, DexModifier.getFinalClass(classes, "example.Factory"));

        classes.clear();
        classes.put("Lexample/A;", new ClassDefInfo("Lexample/A;", "Lexample/B;", "classes.dex"));
        classes.put("Lexample/B;", new ClassDefInfo("Lexample/B;", "Lexample/A;", "classes.dex"));
        assertNull(DexModifier.getFinalClass(classes, "example.A"));
    }

    @Test
    public void rewritesOnlyWholePackageNamesInStrings() throws Exception {
        Method rewrite = DexModifier.class.getDeclaredMethod("rewritePackageInString",
                String.class, String.class, String.class);
        rewrite.setAccessible(true);
        assertEquals("com.foobar and org.bar.Type; org.bar",
                rewrite.invoke(null, "com.foobar and com.foo.Type; com.foo", "com.foo", "org.bar"));
    }
}
