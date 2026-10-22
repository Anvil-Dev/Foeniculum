package dev.anvilcraft.foeniculum.resolve;

import java.io.IOException;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * 读取 jar 的内容特征，用于生成 FML 元数据里茴香无法从依赖声明获知的字段。
 */
public final class JarContents {
    /** FML 用来标记内嵌库需要映射转换的清单属性名。 */
    private static final Attributes.Name OBFUSCATED_BY = new Attributes.Name("Obfuscated-By");

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
            // 清单属性的键是 Attributes.Name 而不是 String；直接传字符串会与 Name 的 equals 不匹配，
            // 导致这里永远判定为未混淆，进而让 FML 跳过本该执行的映射转换。
            Attributes attributes = manifest.getMainAttributes();
            return attributes.getValue(OBFUSCATED_BY) != null;
        } catch (IOException e) {
            throw new IllegalStateException("无法读取 jar 文件以判定是否混淆：" + jar, e);
        }
    }
}
