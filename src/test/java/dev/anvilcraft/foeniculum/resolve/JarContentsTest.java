package dev.anvilcraft.foeniculum.resolve;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JarContentsTest {
    @Test
    void 清单含ObfuscatedBy时判定为已混淆(@TempDir Path temp) throws IOException {
        Path jar = jar(temp, "with-obfuscation.jar", Map.of(
                "META-INF/MANIFEST.MF", manifest("Obfuscated-By: proguard")));

        assertTrue(JarContents.isObfuscated(jar));
    }

    @Test
    void 清单不含ObfuscatedBy时判定为未混淆(@TempDir Path temp) throws IOException {
        Path jar = jar(temp, "plain.jar", Map.of(
                "META-INF/MANIFEST.MF", manifest("Implementation-Title: demo")));

        assertFalse(JarContents.isObfuscated(jar));
    }

    @Test
    void 没有清单时按未混淆处理(@TempDir Path temp) throws IOException {
        // 缺少清单并不代表混淆；FML 侧的判定只看 Obfuscated-By，这里保持一致以免误标记。
        Path jar = jar(temp, "no-manifest.jar", Map.of("demo/Demo.class", "class"));

        assertFalse(JarContents.isObfuscated(jar));
    }

    @Test
    void 无法读取的文件被拒绝并带上路径(@TempDir Path temp) throws IOException {
        Path broken = temp.resolve("broken.jar");
        Files.writeString(broken, "not a jar", StandardCharsets.UTF_8);

        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> JarContents.isObfuscated(broken));

        assertTrue(error.getMessage().contains("broken.jar"), error.getMessage());
    }

    /** 清单必须以空行结尾，否则解析器会忽略最后一条属性。 */
    private static String manifest(String attribute) {
        return "Manifest-Version: 1.0\n" + attribute + "\n\n";
    }

    private static Path jar(Path directory, String name, Map<String, String> entries) throws IOException {
        Path file = directory.resolve(name);
        Map<String, String> ordered = new LinkedHashMap<>(entries);
        try (OutputStream out = Files.newOutputStream(file);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> entry : ordered.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }
}
