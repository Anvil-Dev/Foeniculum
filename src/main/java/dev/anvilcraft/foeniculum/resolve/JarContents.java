package dev.anvilcraft.foeniculum.resolve;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * 读取 jar 的内容特征，用于生成 FML 元数据里茴香无法从依赖声明获知的字段。
 */
public final class JarContents {
    private JarContents() {}

    /**
     * 判断 jar 是否被混淆过。
     *
     * <p>FML 的元数据用 {@code isObfuscated} 标记内嵌库是否需要做映射转换，判定依据是 jar 清单里的
     * {@code Obfuscated-By} 属性，这与 ModDevGradle 自身生成原生条目时的判定一致。
     *
     * @return jar 的清单中含有 {@code Obfuscated-By} 时返回 {@code true}
     * @throws IllegalStateException jar 无法读取时抛出，包含文件路径
     */
    public static boolean isObfuscated(Path jar) {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            Manifest manifest = jarFile.getManifest();
            if (manifest == null) {
                return false;
            }
            Attributes attributes = manifest.getMainAttributes();
            return attributes.containsKey("Obfuscated-By");
        } catch (IOException e) {
            throw new IllegalStateException("无法读取 jar 文件以判定是否混淆：" + jar, e);
        }
    }
}
