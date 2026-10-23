package dev.anvilcraft.resource.foeniculum.tasks;

import dev.anvilcraft.resource.foeniculum.model.SlimDeclaration;
import dev.anvilcraft.resource.foeniculum.model.SlimDependency;
import dev.anvilcraft.resource.foeniculum.model.SlimMetadata;
import dev.anvilcraft.resource.foeniculum.model.SlimModule;
import dev.anvilcraft.resource.foeniculum.model.SlimTree;
import dev.anvilcraft.resource.foeniculum.version.VersionRanges;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * 生成独立的瘦身依赖描述文件 {@code -slim-metadata.json}。
 *
 * <p>描述文件只记录本项目直接声明的瘦身依赖：下游据此递归读取各库自己的描述文件，从而还原整棵依赖树。
 * 若把压平后的整棵树都写进去，扁平清单会丢失“谁依赖谁”的信息，下游再次合并时无法与自己的约束求交，
 * 也无法随中间库升级而自动跟进。
 *
 * <p>同一模块被多次声明时合并成一条，区间取交集：下游读到重复条目会把同一模块的约束叠加两次，
 * 这既不符合「每条记录对应一个模块」的语义，也会让下游的区间计算重复计入同一来源。
 *
 * <p>仓库列表仅供参考：下游解析时会把它与自身仓库比较并提示差异，插件从不据此修改项目仓库。
 */
@CacheableTask
public abstract class SlimMetadataFile extends DefaultTask {
    /** 当前项目通过 {@code slimJarJar} 直接声明的依赖。 */
    @Input
    public abstract ListProperty<SlimDeclaration> getDeclarations();

    /** 解析任务输出的瘦身依赖树，用于确认每个直接依赖最终以何种形式内嵌。 */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getSlimTreeFile();

    /** 当前项目声明的 Maven 仓库地址。 */
    @Input
    public abstract ListProperty<String> getRepositoryUrls();

    /** 描述文件输出位置。 */
    @OutputFile
    public abstract RegularFileProperty getMetadataFile();

    @TaskAction
    public void write() throws IOException {
        SlimTree tree = SlimTree.read(getSlimTreeFile().get().getAsFile().toPath());
        Map<String, List<SlimDeclaration>> byModule = new LinkedHashMap<>();

        for (SlimDeclaration declaration : getDeclarations().get()) {
            byModule.computeIfAbsent(declaration.moduleId(), key -> new ArrayList<>()).add(declaration);
        }

        List<SlimDependency> dependencies = new ArrayList<>();
        for (Map.Entry<String, List<SlimDeclaration>> entry : byModule.entrySet()) {
            SlimDeclaration first = entry.getValue().get(0);
            SlimModule resolved = tree.find(entry.getKey());
            if (resolved == null) {
                throw new GradleException("直接声明的依赖 " + entry.getKey()
                        + " 没有出现在解析结果中；请确认该依赖能在已声明的仓库中找到");
            }

            List<VersionRanges.Range> ranges = new ArrayList<>();
            for (SlimDeclaration declaration : entry.getValue()) {
                ranges.add(VersionRanges.parse(declaration.versionRange(), "直接依赖 " + entry.getKey()));
            }
            String versionRange;
            try {
                versionRange = VersionRanges.intersect(ranges).format();
            } catch (IllegalArgumentException e) {
                throw new GradleException("直接依赖 " + entry.getKey()
                        + " 被声明了多个互相冲突的版本区间：" + ranges + "，请只保留一条能覆盖所需版本的声明", e);
            }

            dependencies.add(new SlimDependency(
                    first.group(),
                    first.artifact(),
                    versionRange,
                    resolved.version(),
                    resolved.isSlim()));
        }

        SlimMetadata.of(new ArrayList<>(new TreeSet<>(getRepositoryUrls().get())), dependencies)
                .write(getMetadataFile().get().getAsFile().toPath());
    }
}
