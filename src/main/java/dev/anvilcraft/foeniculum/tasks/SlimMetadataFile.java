package dev.anvilcraft.foeniculum.tasks;

import dev.anvilcraft.foeniculum.model.SlimDeclaration;
import dev.anvilcraft.foeniculum.model.SlimDeclaration;
import dev.anvilcraft.foeniculum.model.SlimDependency;
import dev.anvilcraft.foeniculum.model.SlimMetadata;
import dev.anvilcraft.foeniculum.model.SlimModule;
import dev.anvilcraft.foeniculum.model.SlimTree;
import dev.anvilcraft.foeniculum.version.VersionRanges;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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
        List<SlimDependency> dependencies = new ArrayList<>();

        for (SlimDeclaration declaration : getDeclarations().get()) {
            SlimModule resolved = tree.find(declaration.moduleId());
            if (resolved == null) {
                throw new GradleException("直接声明的依赖 " + declaration.moduleId()
                        + " 没有出现在解析结果中；请确认该依赖能在已声明的仓库中找到");
            }
            dependencies.add(new SlimDependency(
                    declaration.group(),
                    declaration.artifact(),
                    VersionRanges.parse(declaration.versionRange(),
                            "直接依赖 " + declaration.moduleId()).format(),
                    resolved.version(),
                    resolved.isSlim()));
        }

        SlimMetadata.of(new ArrayList<>(new TreeSet<>(getRepositoryUrls().get())), dependencies)
                .write(getMetadataFile().get().getAsFile().toPath());
    }
}
