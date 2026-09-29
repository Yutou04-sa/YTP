/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.loader.util;

import static org.ytp.share.Constants.CONFIG_EXTERNAL_MODULE;
import static org.ytp.share.Constants.LOWER_CASE_NAME;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

public class FileUtils {

    public static void deleteFolderIfExists(Path target) throws IOException {
        if (Files.notExists(target)) return;
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e)
                    throws IOException {
                if (e == null) {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                } else {
                    throw e;
                }
            }
        });
    }

    public static Path basePath(Context context){
        return Paths.get(context.getFilesDir().getAbsolutePath(), LOWER_CASE_NAME);
    }

    public static Path getModulePath(Context context){
            return basePath(context).resolve("modules");
    }

    public static Path baseExternalPath(Context context){
        File moduleDir = context.getExternalFilesDir(null);
        if(moduleDir == null){
            moduleDir = context.getFilesDir();
        }
        return Paths.get(moduleDir.getAbsolutePath(), LOWER_CASE_NAME);
    }

    public static Path getModuleConfigPath(Context context){
        return baseExternalPath(context).resolve(CONFIG_EXTERNAL_MODULE);
    }
}
