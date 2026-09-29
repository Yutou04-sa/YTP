package org.lsposed.lspd.util;

import static de.robv.android.xposed.XposedBridge.TAG;

import android.os.Build;
import android.os.SharedMemory;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import org.lsposed.lspd.nativebridge.HookBridge;
import org.lsposed.lspd.util.Utils.Log;

import hidden.ByteBufferDexClassLoader;
import sun.misc.CompoundEnumeration;

@SuppressWarnings("ConstantConditions")
public final class LspModuleClassLoader extends ByteBufferDexClassLoader {
    private static final String zipSeparator = "!/";
    private static final List<File> systemNativeLibraryDirs =
            splitPaths(System.getProperty("java.library.path"));
    private final String apk;
    private final List<File> nativeLibraryDirs = new ArrayList<>();
    /** 复用的 apk 资源句柄：返回的 URL 引用它，所以只创建一次、随 classloader 存活。 */
    private volatile ClassPathURLStreamHandler urlHandler;

    private static List<File> splitPaths(String searchPath) {
        var result = new ArrayList<File>();
        if (searchPath == null) return result;
        for (var path : searchPath.split(File.pathSeparator)) {
            result.add(new File(path));
        }
        return result;
    }

    private LspModuleClassLoader(ByteBuffer[] dexBuffers,
                                 ClassLoader parent,
                                 String apk) {
        super(dexBuffers, parent);
        this.apk = apk;
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private LspModuleClassLoader(ByteBuffer[] dexBuffers,
                                 String librarySearchPath,
                                 ClassLoader parent,
                                 String apk) {
        super(dexBuffers, librarySearchPath, parent);
        initNativeLibraryDirs(librarySearchPath);
        this.apk = apk;
    }

    private void initNativeLibraryDirs(String librarySearchPath) {
        nativeLibraryDirs.addAll(splitPaths(librarySearchPath));
        nativeLibraryDirs.addAll(systemNativeLibraryDirs);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        var cl = findLoadedClass(name);
        if (cl != null) {
            return cl;
        }
        try {
            return Object.class.getClassLoader().loadClass(name);
        } catch (ClassNotFoundException ignored) {
        }
        ClassNotFoundException fromSuper;
        try {
            return findClass(name);
        } catch (ClassNotFoundException ex) {
            fromSuper = ex;
        }
        try {
            return getParent().loadClass(name);
        } catch (ClassNotFoundException cnfe) {
            throw fromSuper;
        }
    }

    @Override
    public String findLibrary(String libraryName) {
        var fileName = System.mapLibraryName(libraryName);
        for (var file : nativeLibraryDirs) {
            var path = file.getPath();
            if (path.contains(zipSeparator)) {
                var split = path.split(zipSeparator, 2);
                try (var jarFile = new JarFile(split[0])) {
                    var entryName = split[1] + '/' + fileName;
                    var entry = jarFile.getEntry(entryName);
                    if (entry != null && entry.getMethod() == ZipEntry.STORED) {
                        return split[0] + zipSeparator + entryName;
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Can not open " + split[0], e);
                }
            } else if (file.isDirectory()) {
                var entryPath = new File(file, fileName).getPath();
                try {
                    var fd = Os.open(entryPath, OsConstants.O_RDONLY, 0);
                    Os.close(fd);
                    return entryPath;
                } catch (ErrnoException ignored) {
                }
            }
        }
        return null;
    }

    @Override
    public String getLdLibraryPath() {
        var result = new StringBuilder();
        for (var directory : nativeLibraryDirs) {
            if (result.length() > 0) {
                result.append(':');
            }
            result.append(directory);
        }
        return result.toString();
    }

    @Override
    protected URL findResource(String name) {
        try {
            // ClassPathURLStreamHandler 会打开 apk（持有 fd），并且返回的 URL 引用它，
            // 所以不能提前关闭；这里每个 classloader 复用一个实例，避免每次查资源都新开一个 fd。
            var urlHandler = urlHandler();
            if (urlHandler == null) return null;
            return urlHandler.getEntryUrlOrNull(name);
        } catch (IOException e) {
            return null;
        }
    }

    private ClassPathURLStreamHandler urlHandler() throws IOException {
        var handler = this.urlHandler;
        if (handler == null) {
            synchronized (this) {
                handler = this.urlHandler;
                if (handler == null) {
                    handler = new ClassPathURLStreamHandler(apk);
                    this.urlHandler = handler;
                }
            }
        }
        return handler;
    }

    @Override
    protected Enumeration<URL> findResources(String name) {
        var result = new ArrayList<URL>();
        var url = findResource(name);
        if (url != null) result.add(url);
        return Collections.enumeration(result);
    }

    @Override
    public URL getResource(String name) {
        var resource = Object.class.getClassLoader().getResource(name);
        if (resource != null) return resource;
        resource = findResource(name);
        if (resource != null) return resource;
        final var cl = getParent();
        return (cl == null) ? null : cl.getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        @SuppressWarnings("unchecked") final var resources = (Enumeration<URL>[]) new Enumeration<?>[]{
                Object.class.getClassLoader().getResources(name),
                findResources(name),
                getParent() == null ? null : getParent().getResources(name)};
        return new CompoundEnumeration<>(resources);
    }

    @NonNull
    @Override
    public String toString() {
        if (apk == null) return "LspModuleClassLoader[instantiating]";
        return "LspModuleClassLoader[module=" + apk + ", " + super.toString() + "]";
    }

    public static ClassLoader loadApk(String apk,
                                      List<SharedMemory> dexes,
                                      String librarySearchPath,
                                      ClassLoader parent) {
        if (dexes.isEmpty()) {
            throw new IllegalArgumentException("No dex files for " + apk);
        }
        var dexBuffers = new ByteBuffer[dexes.size()];
        int mappedCount = 0;
        try {
            for (int i = 0; i < dexes.size(); i++) {
                try {
                    dexBuffers[i] = dexes.get(i).mapReadOnly();
                    mappedCount++;
                } catch (ErrnoException e) {
                    Log.w(TAG, "Can not map " + dexes.get(i), e);
                    throw new IllegalStateException("Can not map all dex files for " + apk, e);
                }
            }
            LspModuleClassLoader cl;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cl = new LspModuleClassLoader(dexBuffers, librarySearchPath, parent, apk);
            } else {
                cl = new LspModuleClassLoader(dexBuffers, parent, apk);
                cl.initNativeLibraryDirs(librarySearchPath);
            }
            HookBridge.setTrustedClassLoader(cl);
            return cl;
        } finally {
            for (int i = 0; i < mappedCount; i++) {
                SharedMemory.unmap(dexBuffers[i]);
            }
            dexes.forEach(SharedMemory::close);
        }
    }
}
