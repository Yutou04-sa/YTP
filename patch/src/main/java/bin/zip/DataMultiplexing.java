package bin.zip;

import static org.ytp.share.Constants.LOG_PROCESS;

import org.ytp.patch.util.Logger;
import org.ytp.patch.util.StreamUtil;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 把外层 ZIP 中与内嵌 APK 内容相同的条目改写为虚拟目录项。
 * 虚拟项指向 STORED 宿主中的原始数据区域，因此大资源只需保存一份。
 * 复用前必须检查压缩方法、CRC、长度、注释、对齐和实际内容。
 */
public class DataMultiplexing {

    private final Logger logger;
    private final ScheduledExecutorService monitorExecutor;

    public DataMultiplexing(Logger  logger, ScheduledExecutorService monitorExecutor){
        this.logger = logger;
        this.monitorExecutor = monitorExecutor;
    }

    /**
     * @param input         输入文件
     * @param output        输出文件
     * @param hostEntryName 原包路径，如 assets/base.apk
     * @param printDetails  是否打印优化进度；详细日志模式也会打印
     */
    public void optimize(File input, File output, String hostEntryName, boolean printDetails) throws IOException {
        boolean detailed = printDetails || logger.verbose;
        try (ZipFile zipFile = new ZipFile(input)) {
            ZipEntry hostEntry = zipFile.getEntryNonNull(hostEntryName);
            List<ZipEntry> outerEntries = zipFile.getEntries();
            Set<String> children = new TreeSet<>();
            // 内嵌 ZIP 必须保持打开，直到宿主数据与所有虚拟条目写入完毕。
            try (ZipFile innerZipFile = openEntryAsZipFile(zipFile, hostEntry)) {
                collectChildren(zipFile, innerZipFile, hostEntry, outerEntries, children, detailed);
                if (children.isEmpty()) {
                    throw new IOException("No multiplexable data found");
                }
                try (ZipMaker zipMaker = new ZipMaker(output)) {
                    ZipMaker.HostEntryHolder holder = zipMaker.putNextHostEntry(hostEntry.getName(), innerZipFile);
                    int linked = 0;
                    int linkStep = Math.max(1, children.size() / 100);
                    for (String name : children) {
                        holder.putNextVirtualEntry(name);
                        linked++;
                        if (detailed && (linked == children.size() || linked % linkStep == 0)) {
                            logger.d(LOG_PROCESS + " -Optimized link: " + linked + "/" + children.size());
                        }
                    }

                    int totalCopy = outerEntries.size() - children.size() - 1;
                    int copyStep = Math.max(1, totalCopy / 100);
                    int copied = 0;
                    for (ZipEntry entry : outerEntries) {
                        if (entry == hostEntry || children.contains(entry.getName())) {
                            continue;
                        }
                        zipMaker.copyZipEntry(entry, zipFile);
                        copied++;
                        if (detailed && (copied == totalCopy || copied % copyStep == 0)) {
                            logger.d(LOG_PROCESS + " -Optimized ready: " + copied + "/" + totalCopy);
                        }
                    }
                }
            }
        }
    }

    /**
     * 按条目名验证两个 ZIP 的逻辑内容，供复用结果校验。
     * 比较只需两个固定缓冲区；流读取长度不同也不会误判。
     */
    public static boolean isZipFileContentEquals(File file1, File file2) throws IOException {
        try (ZipFile zipFile1 = new ZipFile(file1); ZipFile zipFile2 = new ZipFile(file2)) {
            if (zipFile1.getEntrySize() != zipFile2.getEntrySize()) {
                return false;
            }
            byte[] firstBuffer = new byte[64 * 1024];
            byte[] secondBuffer = new byte[firstBuffer.length];
            for (ZipEntry entry1 : zipFile1.getEntries()) {
                ZipEntry entry2 = zipFile2.getEntry(entry1.getName());
                if (entry2 == null) {
                    return false;
                }
                if (entry1.isDirectory() && entry2.isDirectory()) {
                    continue;
                }
                if (entry1.getMethod() != entry2.getMethod()) {
                    return false;
                }
                if (entry1.getCrc() != entry2.getCrc()) {
                    return false;
                }
                if (entry1.getSize() != entry2.getSize()) {
                    return false;
                }
                if (!Arrays.equals(entry1.getCommentData(), entry2.getCommentData())) {
                    return false;
                }
                if (!StreamUtil.contentEquals(zipFile1.getInputStream(entry1),
                        zipFile2.getInputStream(entry2), firstBuffer, secondBuffer)) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * 筛选能够直接引用内嵌 APK 数据的外层条目。
     * 原始压缩字节相同时不用解压；字节不同则比较解压后的内容，兼容压缩器差异。
     */
    private void collectChildren(ZipFile outer, ZipFile inner, ZipEntry hostEntry,
                                 List<ZipEntry> outerEntries, Set<String> children,
                                 boolean detailed) throws IOException {
        AtomicInteger num = new AtomicInteger(0);
        int size = outerEntries.size();
        // 非详细模式不启动定时线程；详细模式按秒更新，不按条目刷 UI。
        ScheduledFuture<?> scheduledFuture = detailed
                ? monitorExecutor.scheduleWithFixedDelay(() ->
                logger.d(LOG_PROCESS + " -Optimized collect: " + num.get() + "/" + size),
                1, 1, TimeUnit.SECONDS)
                : null;
        byte[] firstBuffer = new byte[64 * 1024];
        byte[] secondBuffer = new byte[firstBuffer.length];
        try {
            for (ZipEntry outerEntry : outerEntries) {
                num.incrementAndGet();
                if (outerEntry == hostEntry || outerEntry.isDirectory()) {
                    continue;
                }
                ZipEntry innerEntry = inner.getEntry(outerEntry.getName());
                if (innerEntry == null) {
                    continue;
                }
                if (outerEntry.getMethod() != innerEntry.getMethod()) {
                    continue;
                }
                if (outerEntry.getCrc() != innerEntry.getCrc()) {
                    continue;
                }
                if (outerEntry.getSize() != innerEntry.getSize()) {
                    continue;
                }
                if (!Arrays.equals(outerEntry.getCommentData(), innerEntry.getCommentData())) {
                    continue;
                }
                // 虚拟条目沿用宿主内偏移；resources.arsc 需要 4 字节对齐，
                // 未压缩 .so 需要页对齐，否则 Android 可能无法直接映射。
                if (innerEntry.getMethod() == ZipMaker.METHOD_STORED) {
                    String name = innerEntry.getName();
                    if (name.equals("resources.arsc") && innerEntry.getDataOffset() % 4 != 0) {
                        continue;
                    }
                    if (name.endsWith(".so") && innerEntry.getDataOffset() % 4096 != 0) {
                        continue;
                    }
                }
                boolean equals = outerEntry.getCompressedSize() == innerEntry.getCompressedSize()
                        && StreamUtil.contentEquals(inner.getRawInputStream(innerEntry),
                        outer.getRawInputStream(outerEntry), firstBuffer, secondBuffer);
                if (!equals) {
                    equals = StreamUtil.contentEquals(inner.getInputStream(innerEntry),
                            outer.getInputStream(outerEntry), firstBuffer, secondBuffer);
                }
                if (equals) {
                    children.add(innerEntry.getName());
                }
            }
        } finally {
            // 失败时也要停止定时日志，避免监控线程继续访问已关闭的 ZIP。
            if (scheduledFuture != null) {
                scheduledFuture.cancel(false);
                logger.d(LOG_PROCESS + " -Optimized collect: " + num.get() + "/" + size);
            }
        }
    }

    private static ZipFile openEntryAsZipFile(ZipFile zipFile, ZipEntry hostEntry) throws IOException {
        if (hostEntry.getMethod() == ZipMaker.METHOD_STORED) {
            return zipFile.openEntryAsZipFile(hostEntry);
        } else {
            throw new IOException("Entry must be packaged with the stored method: " + hostEntry.getName());
        }
    }

}
