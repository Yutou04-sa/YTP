package bin.zip;

import static org.ytp.share.Constants.LOG_PROCESS;

import org.ytp.patch.util.Logger;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
     * @param printDetails  是否打印优化详情
     */
    public void optimize(File input, File output, String hostEntryName, boolean printDetails) throws IOException {
        try (ZipFile zipFile = new ZipFile(input)) {
            ZipEntry hostEntry = zipFile.getEntryNonNull(hostEntryName);
            Set<String> children = new TreeSet<>();
            // 返回的innerZipFile已经close了，但内部的entries还在
            ZipFile innerZipFile = collectChildren(zipFile, hostEntry, children);
            if (innerZipFile == null) {
                throw new IOException("No multiplexable data found");
            }
            try (ZipMaker zipMaker = new ZipMaker(output)) {
                ZipMaker.HostEntryHolder holder = zipMaker.putNextHostEntry(hostEntry.getName(), innerZipFile);
                int num = 1;
                for (String name : children) {
                    holder.putNextVirtualEntry(name);
                    logger.d(LOG_PROCESS+" -Optimized link: "+num+"/"+children.size());
                    num++;
                }

                // 直接遍历zipFile的entries，而不是存储到列表中，以避免OOM
                num = 0;
                for (ZipEntry entry : zipFile.getEntries()) {
                    if (entry != hostEntry && !children.contains(entry.getName())) {
                        zipMaker.copyZipEntry(entry, zipFile);
                        logger.d(LOG_PROCESS+" -Optimized ready: "+num+"/"+children.size());
                    }else{
                        num++;
                    }
                }
                System.gc();
                logger.d(LOG_PROCESS+" -Optimized ready: "+children.size()+"/"+children.size());
            }
        }
       // System.out.printf("Data multiplexing optimize: %s (%s) -> %s (%s)  [%.2f%%]\n", input.getName(), formatFileSize(inputLen), output.getName(), formatFileSize(outputLen), (outputLen - inputLen) * 100f / inputLen);
    }

    private ZipFile collectChildren(ZipFile outer, ZipEntry hostEntry, Set<String> children) throws IOException {
        AtomicInteger num = new AtomicInteger(0);
        int size = outer.getEntries().size();
        ScheduledFuture<?> scheduledFuture = monitorExecutor.scheduleWithFixedDelay(() -> {
            logger.d(LOG_PROCESS + " -Optimized collect: " + num.get() + "/" + size);
        }, 1, 1, TimeUnit.SECONDS);
        try (ZipFile inner = openEntryAsZipFile(outer, hostEntry)) {
            for (ZipEntry outerEntry : outer.getEntries()) {
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
                // 必须4k对齐
                if (innerEntry.getMethod() == ZipMaker.METHOD_STORED) {
                    String name = innerEntry.getName();
                    if (name.equals("resources.arsc") && innerEntry.getDataOffset() % 4 != 0) {
                        continue;
                    }
                    if (name.endsWith(".so") && innerEntry.getDataOffset() % 4096 != 0) {
                        continue;
                    }
                }
                boolean equals = outerEntry.getCompressedSize() == innerEntry.getCompressedSize() &&
                        isInputStreamContentEquals(inner.getRawInputStream(innerEntry), outer.getRawInputStream(outerEntry)) ||
                        isInputStreamContentEquals(inner.getInputStream(innerEntry), outer.getInputStream(outerEntry));
                if (equals) {
                    children.add(innerEntry.getName());
                }

                num.incrementAndGet();
            }
            scheduledFuture.cancel(true);
            logger.d(LOG_PROCESS+" -Optimized collect: "+num+"/"+size);
            System.gc();
            return children.isEmpty() ? null : inner;
        }
    }

    private static ZipFile openEntryAsZipFile(ZipFile zipFile, ZipEntry hostEntry) throws IOException {
        if (hostEntry.getMethod() == ZipMaker.METHOD_STORED) {
            return zipFile.openEntryAsZipFile(hostEntry);
        } else {
            throw new IOException("Entry must be packaged with the stored method: " + hostEntry.getName());
        }
    }

    private static boolean isInputStreamContentEquals(InputStream input1, InputStream input2) throws IOException {
        if (input1 == input2) {
            return true;
        }
        if (!(input1 instanceof BufferedInputStream)) {
            input1 = new BufferedInputStream(input1);
        }
        if (!(input2 instanceof BufferedInputStream)) {
            input2 = new BufferedInputStream(input2);
        }

        int ch = input1.read();
        while (-1 != ch) {
            final int ch2 = input2.read();
            if (ch != ch2) {
                return false;
            }
            ch = input1.read();
        }

        final int ch2 = input2.read();
        return ch2 == -1;
    }
}
