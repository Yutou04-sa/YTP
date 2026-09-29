package org.ytp.update;

import org.ytp.share.YTPConfig;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

public class Update {

    private static final String basePath = "update-msg/src/main/template/";
    private static final String readFile  = basePath + "update.json";
    private static final String writeFile = basePath + "update2";

    public static void main(String[] args) throws IOException {

        //读取update.json文件
        String encoding = getString();
        try {
            // 使用项目根目录作为输出路径
            File outputFile = new File(writeFile);
            if(!outputFile.exists()){
                outputFile.createNewFile();
            }
            // 直接写入 JSON 字符串（UTF-8 编码）
            try (FileWriter writer = new FileWriter(outputFile, StandardCharsets.UTF_8)) {
                writer.write(encoding);
            }

            System.out.println("√ file Success: " + outputFile.getAbsolutePath());
            System.out.println("√ File size: " + outputFile.length() + " bytes");

        } catch (IOException e) {
            System.err.println("x file Failed: " + e.getMessage());
            e.printStackTrace();
        } catch (SecurityException e) {
            System.err.println("x No permission to write to the file：" + e.getMessage());
        }
    }

    private static String getString() throws IOException {
        File file = new File(readFile);
        // 读取文件内容（UTF-8 编码）。用 Locale.ROOT 保证 %d 一定输出 ASCII 数字，
        // 否则在阿拉伯语等区域会得到非 ASCII 数字，从而生成非法 JSON。
        String json = String.format(Locale.ROOT, Files.readString(file.toPath(), StandardCharsets.UTF_8), YTPConfig.instance.VERSION_CODE);
        System.out.println(json);
        // update2 的约定格式是「UTF-16 字节值组成的 JSON 数组」：manager 的 UpdateChecker.checkUpdate
        // 先用 Gson 把它解析成 ByteArray，再用 UTF_16 还原出真正的 update JSON。
        // 这里显式拼接 JSON 数组，不再依赖 Arrays.toString 的偶然格式。
        return toJsonArray(json.getBytes(StandardCharsets.UTF_16));
    }

    private static String toJsonArray(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 4 + 2).append('[');
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(bytes[i]);
        }
        return sb.append(']').toString();
    }
}
