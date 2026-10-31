package dev.anvilcraft.resource.foeniculum.tasks;

import dev.anvilcraft.resource.foeniculum.model.SlimDeclaration;
import dev.anvilcraft.resource.foeniculum.model.SlimRepository;
import dev.anvilcraft.resource.foeniculum.resolve.RepositorySlimArtifactSource;
import dev.anvilcraft.resource.foeniculum.resolve.SlimResolution;
import dev.anvilcraft.resource.foeniculum.resolve.SlimTreeResolver;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * 递归读取瘦身描述文件并压平依赖树。
 *
 * <p>这是唯一一个会产生网络访问的任务，因此单独成任务并声明为可缓存：依赖树稳定时后续构建可以直接复用结果，
 * 不再重复请求仓库。凭据被标注为内部属性，以免写入构建缓存。
 */
@CacheableTask
public abstract class ResolveSlimJarJar extends DefaultTask {
    /** 当前项目直接声明的瘦身依赖。 */
    @Input
    public abstract ListProperty<SlimDeclaration> getDeclarations();

    /** 当前项目已声明仓库的规范化地址，用于校验下游描述文件是否引用了未知仓库。 */
    @Input
    public abstract ListProperty<String> getRepositoryUrls();

    /** 访问仓库需要的凭据，含密码，因此不参与任务指纹。 */
    @Internal
    public abstract ListProperty<SlimRepository> getRepositories();

    /**
     * 同一次构建中被项目依赖引入的模块暂存目录，每个目录都是一个 {@code file:} Maven 仓库。
     *
     * <p>声明为输入文件而不是仅由插件拼进 {@link #getRepositories()}，是为了让 Gradle 从这些目录所属的暂存任务
     * 自动推导出执行顺序：{@code slimJarJar} 引用同构建内的项目时，本项目解析前必须先完成对方的暂存。
     */
    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getLocalRepositoryDirectories();

    /** 构件下载目录，同时作为依赖树的输出位置。 */
    @OutputDirectory
    public abstract DirectoryProperty getResolveDirectory();

    @TaskAction
    public void resolve() {
        Path directory = getResolveDirectory().get().getAsFile().toPath();
        List<String> sourceWarnings = new ArrayList<>();

        List<SlimRepository> repositories = new ArrayList<>();
        Set<String> declaredRepositories = new LinkedHashSet<>(getRepositoryUrls().get());
        // 项目依赖的暂存仓库排在最前：它的内容就是本次构建刚产出的构件，
        // 优先命中才能保证下游拿到的是当前源码的产物，而不是远程仓库里的同版本旧包。
        for (var local : getLocalRepositoryDirectories().getFiles()) {
            SlimRepository repository = new SlimRepository(local.toURI().toString(), null, null);
            repositories.add(repository);
            // 暂存仓库由本构建自己生成，下游描述文件引用它属于正常情况，
            // 记入已声明仓库才不会把它误报成「未声明的仓库」。
            declaredRepositories.add(SlimRepository.normalize(repository.url()));
        }
        repositories.addAll(getRepositories().get());

        RepositorySlimArtifactSource source = new RepositorySlimArtifactSource(repositories, sourceWarnings);

        SlimTreeResolver resolver = new SlimTreeResolver(source, declaredRepositories);
        SlimResolution resolution;
        try {
            resolution = resolver.resolve(getDeclarations().get(), directory.resolve("artifacts"));
        } catch (GradleException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new GradleException("解析瘦身依赖失败：" + e.getMessage(), e);
        }

        Set<String> messages = new LinkedHashSet<>(sourceWarnings);
        messages.addAll(resolution.warnings());
        for (String message : messages) {
            getLogger().warn("[茴香] {}", message);
        }

        try {
            resolution.tree().write(directory.resolve("slim-tree.json"));
        } catch (IOException e) {
            throw new GradleException("无法写入瘦身依赖树 " + directory.resolve("slim-tree.json"), e);
        }
    }
}
