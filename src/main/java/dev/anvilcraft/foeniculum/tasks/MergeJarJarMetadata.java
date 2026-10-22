package dev.anvilcraft.foeniculum.tasks;

import com.google.gson.JsonObject;
import dev.anvilcraft.foeniculum.model.JarJarMetadata;
import dev.anvilcraft.foeniculum.model.SlimModule;
import dev.anvilcraft.foeniculum.model.SlimTree;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * 生成需要追加到主 jar 的 {@code META-INF/jarjar/} 内容：压平后的瘦身内嵌文件，以及与原生条目合并后的元数据。
 *
 * <p>输出目录的顶层直接放内嵌文件与 {@code metadata.json}，由插件在主 jar 上以
 * {@code into 'META-INF/jarjar'} 映射进去。这种布局是有意为之：主 jar 需要排除原生任务的元数据以避免出现两份，
 * 而 Gradle 的排除规则作用于映射前的相对路径，顶层布局能让合并后的元数据不被自己的排除规则命中。
 *
 * <p>原生内嵌文件本身由 ModDevGradle 自己的接线放进主 jar，这里不复制它们，以免同一个文件被写入两次。
 *
 * <p>没有瘦身条目时原样复制原生元数据，而不是重新序列化：项目只是安装了插件、并未使用
 * {@code slimJarJar} 时，产物内容不应发生任何变化。
 */
@CacheableTask
public abstract class MergeJarJarMetadata extends DefaultTask {
    /**
     * 原生 {@code jarJar} 任务的输出，按文件集合接收。
     *
     * <p>用文件集合而不是目录属性，是因为原生任务是外部插件的产物，只能通过 {@code from(taskProvider)}
     * 取得其输出位置；本插件不引用 ModDevGradle 的内部任务类型，也就无法调用它的输出目录方法。
     * 集合中的每个目录都当作一个候选根目录处理，项目没有安装 ModDevGradle 时集合为空。
     */
    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getNativeOutputs();

    /** 解析任务的输出目录，其中含有 {@code slim-tree.json} 与 {@code artifacts/}。 */
    @InputDirectory
    @PathSensitive(PathSensitivity.NONE)
    public abstract DirectoryProperty getResolveDirectory();

    /** 追加内容输出目录。 */
    @OutputDirectory
    public abstract DirectoryProperty getMergeDirectory();

    @TaskAction
    public void merge() throws IOException {
        Path output = getMergeDirectory().get().getAsFile().toPath();
        deleteDirectory(output);
        Files.createDirectories(output);

        Path nativeMetadata = findNativeMetadata();
        List<JsonObject> nativeEntries = readNativeEntries(nativeMetadata);
        Set<String> nativeModuleIds =
                new LinkedHashSet<>(JarJarMetadata.merge(nativeEntries, List.of()).byModuleId().keySet());
        Set<String> nativeFileNames = new LinkedHashSet<>();
        for (JsonObject entry : nativeEntries) {
            nativeFileNames.add(Path.of(entry.get("path").getAsString()).getFileName().toString());
        }

        Path resolveDirectory = getResolveDirectory().get().getAsFile().toPath();
        SlimTree tree = readSlimTree(resolveDirectory);

        List<JsonObject> slimEntries = new ArrayList<>();
        for (SlimModule module : tree.modules()) {
            if (nativeModuleIds.contains(module.moduleId())) {
                getLogger().warn("[茴香] 模块 {} 已由原生 jarJar 内嵌，瘦身侧的同名条目已被忽略", module.moduleId());
                continue;
            }
            String fileName = embeddedFileName(module, nativeFileNames);
            if (!nativeFileNames.add(fileName)) {
                throw new GradleException("内嵌文件名 " + fileName + " 被模块 " + module.moduleId()
                        + " 重复使用；请检查各库的 artifact 与版本是否冲突");
            }
            slimEntries.add(JarJarMetadata.entryOf(module, fileName));
            Path source = resolveDirectory.resolve("artifacts").resolve(module.sourcePath());
            if (!Files.isRegularFile(source)) {
                throw new GradleException("找不到模块 " + module.moduleId() + " " + module.version()
                        + " 的构件文件 " + source);
            }
            Files.copy(source, output.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
        }

        if (slimEntries.isEmpty()) {
            if (nativeMetadata == null) {
                // 两侧都没有内嵌依赖时，产物中不应出现空的 jarjar 目录，否则下游工具会误以为这里配置过 JiJ。
                getLogger().info("[茴香] 原生与瘦身两侧都没有内嵌依赖，不生成 jarjar 元数据");
                return;
            }
            // 没有瘦身条目时保持原生元数据原样，避免仅安装插件就让产物内容发生变化。
            Files.copy(nativeMetadata, output.resolve("metadata.json"), StandardCopyOption.REPLACE_EXISTING);
            return;
        }

        JarJarMetadata.merge(nativeEntries, slimEntries).write(output.resolve("metadata.json"));
    }

    /**
     * 为瘦身模块挑一个不与原生命中冲突的内嵌文件名。
     *
     * <p>先在 {@code artifact-version.jar} 上尝试，冲突时用 {@code group-artifact-version.jar}，
     * 这既保持了与 ModDevGradle 一致的可读性，又不会让两个来自不同 group 的同名模块互相覆盖。
     */
    private static String embeddedFileName(SlimModule module, Set<String> nativeFileNames) {
        String candidate = module.fileName();
        if (!nativeFileNames.contains(candidate)) {
            return candidate;
        }
        return module.group() + "-" + candidate;
    }

    private Path findNativeMetadata() {
        for (Path root : nativeRoots()) {
            Path metadata = root.resolve("META-INF/jarjar/metadata.json");
            if (Files.isRegularFile(metadata)) {
                return metadata;
            }
        }
        return null;
    }

    /**
     * 原生内嵌内容的候选根目录。
     *
     * <p>只把确实含有元数据文件的目录算作有效根目录：原生任务没有内嵌依赖时不会产出该文件，
     * 而任务输出目录可能残留上一次构建的内容，误用会把过期条目带进产物。
     */
    private List<Path> nativeRoots() {
        List<Path> roots = new ArrayList<>();
        for (var file : getNativeOutputs().getFiles()) {
            Path root = file.toPath();
            if (Files.isRegularFile(root.resolve("META-INF/jarjar/metadata.json"))) {
                roots.add(root);
            }
        }
        roots.sort(Comparator.comparing(Path::toString));
        return roots;
    }

    private static List<JsonObject> readNativeEntries(Path metadata) {
        if (metadata == null) {
            return List.of();
        }
        try (Reader reader = Files.newBufferedReader(metadata, StandardCharsets.UTF_8)) {
            return JarJarMetadata.read(reader).jars();
        } catch (IOException e) {
            throw new GradleException("无法读取原生 jarJar 元数据 " + metadata, e);
        }
    }

    private static SlimTree readSlimTree(Path resolveDirectory) {
        Path file = resolveDirectory.resolve("slim-tree.json");
        if (!Files.isRegularFile(file)) {
            return SlimTree.empty();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return SlimTree.read(reader);
        } catch (IOException e) {
            throw new GradleException("无法读取瘦身依赖树 " + file, e);
        }
    }

    private static void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var stream = Files.walk(directory)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }
}
