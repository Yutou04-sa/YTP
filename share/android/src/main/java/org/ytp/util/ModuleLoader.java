package org.ytp.util;

import android.os.SharedMemory;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.util.Log;

import org.lsposed.lspd.models.PreLoadedApk;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

public class ModuleLoader {

    private static final String TAG = "YTP";

    private static final String LEGACY_JAVA_INIT = "assets/xposed_init";
    private static final String LEGACY_NATIVE_INIT = "assets/native_init";
    private static final String MODERN_MODULE_PROP = "META-INF/xposed/module.prop";
    private static final String MODERN_JAVA_INIT = "META-INF/xposed/java_init.list";
    private static final String MODERN_NATIVE_INIT = "META-INF/xposed/native_init.list";

    private static boolean readDexes(ZipFile apkFile, List<SharedMemory> preLoadedDexes) {
        int secondary = 2;
        for (var dexFile = apkFile.getEntry("classes.dex"); dexFile != null;
             dexFile = apkFile.getEntry("classes" + secondary + ".dex"), secondary++) {
            SharedMemory memory = null;
            ByteBuffer byteBuffer = null;
            try (var in = apkFile.getInputStream(dexFile)) {
                long dexSize = dexFile.getSize();
                if (dexSize <= 0 || dexSize > Integer.MAX_VALUE) {
                    throw new IOException("Invalid dex size: " + dexSize);
                }

                memory = SharedMemory.create(null, (int) dexSize);
                byteBuffer = memory.mapReadWrite();
                var channel = Channels.newChannel(in);
                while (byteBuffer.hasRemaining()) {
                    if (channel.read(byteBuffer) < 0) {
                        throw new IOException("Unexpected end of " + dexFile.getName());
                    }
                }

                SharedMemory.unmap(byteBuffer);
                byteBuffer = null;
                if (!memory.setProtect(OsConstants.PROT_READ)) {
                    throw new IOException("Can not make dex memory read-only: " + dexFile.getName());
                }
                preLoadedDexes.add(memory);
                memory = null;
            } catch (IOException | ErrnoException e) {
                Log.w(TAG, "Can not load " + dexFile + " in " + apkFile, e);
                return false;
            } finally {
                if (byteBuffer != null) {
                    SharedMemory.unmap(byteBuffer);
                }
                if (memory != null) {
                    memory.close();
                }
            }
        }
        return true;
    }

    private static void closeDexes(List<SharedMemory> preLoadedDexes) {
        preLoadedDexes.forEach(SharedMemory::close);
        preLoadedDexes.clear();
    }

    private static void readName(ZipFile apkFile, String initName, List<String> names) {
        var initEntry = apkFile.getEntry(initName);
        if (initEntry == null) return;
        try (var in = apkFile.getInputStream(initEntry)) {
            var reader = new BufferedReader(new InputStreamReader(in));
            String name;
            while ((name = reader.readLine()) != null) {
                name = name.trim();
                if (name.isEmpty() || name.startsWith("#") || names.contains(name)) continue;
                names.add(name);
            }
        } catch (IOException e) {
            Log.e(TAG, "Can not open " + initEntry, e);
        }
    }

    public static PreLoadedApk loadModule(String path) {
        if (path == null) return null;
        var file = new PreLoadedApk();
        var preLoadedDexes = new ArrayList<SharedMemory>();
        var moduleClassNames = new ArrayList<String>(1);
        var moduleLibraryNames = new ArrayList<String>(1);
        try (var apkFile = new ZipFile(path)) {
            boolean hasLegacyJavaEntry = apkFile.getEntry(LEGACY_JAVA_INIT) != null;
            boolean hasModernMetadata = apkFile.getEntry(MODERN_MODULE_PROP) != null;
            if (hasLegacyJavaEntry || !hasModernMetadata) {
                file.legacy = true;
                readName(apkFile, LEGACY_JAVA_INIT, moduleClassNames);
                readName(apkFile, LEGACY_NATIVE_INIT, moduleLibraryNames);
            } else {
                file.legacy = false;
                readName(apkFile, MODERN_JAVA_INIT, moduleClassNames);
                readName(apkFile, MODERN_NATIVE_INIT, moduleLibraryNames);
            }
            if (moduleClassNames.isEmpty() && moduleLibraryNames.isEmpty()) {
                Log.w(TAG, "No Xposed entry point found in " + path);
                return null;
            }

            if (!moduleClassNames.isEmpty()) {
                boolean allDexesLoaded = readDexes(apkFile, preLoadedDexes);
                if (!allDexesLoaded || preLoadedDexes.isEmpty()) {
                    Log.w(TAG, "Can not load Java Xposed entry points without all dex files: " + path);
                    closeDexes(preLoadedDexes);
                    return null;
                }
            }
        } catch (IOException e) {
            closeDexes(preLoadedDexes);
            Log.e(TAG, "Can not open " + path, e);
            return null;
        }
        file.preLoadedDexes = preLoadedDexes;
        file.moduleClassNames = moduleClassNames;
        file.moduleLibraryNames = moduleLibraryNames;
        return file;
    }
}
