package org.ytp.update;

import org.ytp.share.YTPConfig;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

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
        // 读取文件内容（UTF-8 编码）
        String json = String.format(Files.readString(file.toPath(), StandardCharsets.UTF_8), YTPConfig.instance.VERSION_CODE);
        System.out.println(json);
        byte[] bytesUTF_16 = json.getBytes(StandardCharsets.UTF_16);
        return Arrays.toString(bytesUTF_16);
    }
}