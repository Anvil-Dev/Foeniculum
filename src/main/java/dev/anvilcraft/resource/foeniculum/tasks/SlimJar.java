package dev.anvilcraft.resource.foeniculum.tasks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * 生成供下游项目继续使用的瘦身包 {@code -slim.jar}。
 *
 * <p>瘦身包必须与「不安装本插件时」的产物保持一致：只含项目自身代码与原生 {@code jarJar} 的内嵌内容。
 * 主 jar 已经把压平后的瘦身内嵌内容合并进去，因此这里采用「复制主 jar 并剔除 {@code META-INF/jarjar/} 全部内容，
 * 再把原生任务的输出原样写回」的方式，而不是重新筛选依赖。后者需要重新判定哪些条目属于原生内嵌，
 * 容易在文件名与元数据上产生偏差；前者只需保证原生内容能被完整取回。
 *
 * <p>只回写原生输出根下 {@code META-INF/jarjar/} 中的文件，与剔除条件对称：主 jar 已经包含该根的全部内容，
 * 若把根下其他文件也回写一遍，会与主 jar 里的同名条目撞车而以 {@code ZipException} 失败。
 *
 * <p>项目没有原生内嵌依赖时，输出中不会出现 {@code META-INF/jarjar/} 目录，与原生行为一致。
 */
@CacheableTask
public abstract class SlimJar extends DefaultTask {
    /** 瘦身包内保留的原生内嵌内容前缀，同时也是从主 jar 中剔除的范围。 */
    private static final String JARJAR_PREFIX = "META-INF/jarjar/";

    /** 项目主 jar，瘦身包在它的基础上剔除瘦身内嵌内容。 */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getMainJar();

    /**
     * 原生 {@code jarJar} 任务的输出，按文件集合接收。
     *
     * <p>用文件集合而不是目录属性，原因与 {@link MergeJarJarMetadata#getNativeOutputs()} 相同：
     * 原生任务是外部插件的产物，本插件只能取得其输出位置，不能引用其类型。集合为空表示没有原生内嵌内容。
     */
    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getNativeOutputs();

    /** 瘦身包输出文件。 */
    @OutputFile
    public abstract RegularFileProperty getSlimJar();

    @TaskAction
    public void createSlimJar() throws IOException {
        Path mainJar = getMainJar().get().getAsFile().toPath();
        Path output = getSlimJar().get().getAsFile().toPath();

        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Set<String> written = new LinkedHashSet<>();
        try (ZipFile source = new ZipFile(mainJar.toFile());
                ZipOutputStream target = new ZipOutputStream(Files.newOutputStream(output))) {
            Enumeration<? extends ZipEntry> entries = source.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().startsWith(JARJAR_PREFIX) || isSignature(entry.getName())) {
                    continue;
                }
                written.add(entry.getName());
                target.putNextEntry(copyOf(entry));
                try (var stream = source.getInputStream(entry)) {
                    stream.transferTo(target);
                }
                target.closeEntry();
            }

            for (Path root : nativeRoots()) {
                for (Path file : jarJarFiles(root)) {
                    // 原生输出目录的布局与 jar 内部布局一致（顶层的 META-INF/jarjar/…），
                    // 主 jar 也是原样把这个目录映射进去的，因此这里同样按相对路径写入，不再额外加前缀。
                    String name = root.relativize(file).toString().replace('\\', '/');
                    if (!written.add(name)) {
                        continue;
                    }
                    target.putNextEntry(new ZipEntry(name));
                    Files.copy(file, target);
                    target.closeEntry();
                }
            }
        }
    }

    /**
     * 判断条目是否为 jar 签名文件。
     *
     * <p>瘦身包的内容与主 jar 不同，原样保留签名文件只会得到一份「有签名但验签必然失败」的归档。
     * 剔除后至少是一个自洽的普通 jar，这也是重新打包类工具的通行做法。
     */
    private static boolean isSignature(String name) {
        if (!name.startsWith("META-INF/")) {
            return false;
        }
        String fileName = name.substring("META-INF/".length());
        if (fileName.contains("/")) {
            return false;
        }
        String upper = fileName.toUpperCase(java.util.Locale.ROOT);
        return upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC");
    }

    /**
     * 原生内嵌内容的有效根目录。
     *
     * <p>只有存在 {@code META-INF/jarjar/metadata.json} 的目录才算有效：原生任务没有内嵌依赖时不会产出该文件，
     * 而任务输出目录可能残留上一次构建的内容，误用会把过期依赖带回瘦身包。
     */
    private List<Path> nativeRoots() {
        List<Path> roots = new ArrayList<>();
        for (var file : getNativeOutputs().getFiles()) {
            Path root = file.toPath();
            if (Files.isRegularFile(root.resolve(JARJAR_PREFIX + "metadata.json"))) {
                roots.add(root);
            }
        }
        roots.sort(Comparator.comparing(Path::toString));
        return roots;
    }

    private static ZipEntry copyOf(ZipEntry entry) {
        ZipEntry copy = new ZipEntry(entry.getName());
        copy.setComment(entry.getComment());
        copy.setTime(entry.getTime());
        return copy;
    }

    /** 列出原生输出根下属于 jarjar 段的文件，并保证顺序稳定以便任务可缓存。 */
    private static List<Path> jarJarFiles(Path root) {
        Path jarJarRoot = root.resolve(JARJAR_PREFIX);
        try (var stream = Files.walk(jarJarRoot)) {
            List<Path> files = new ArrayList<>(stream.filter(Files::isRegularFile).toList());
            files.sort(Comparator.comparing(path -> root.relativize(path).toString()));
            return files;
        } catch (IOException e) {
            throw new GradleException("无法读取原生 jarJar 输出目录 " + jarJarRoot, e);
        }
    }
}
