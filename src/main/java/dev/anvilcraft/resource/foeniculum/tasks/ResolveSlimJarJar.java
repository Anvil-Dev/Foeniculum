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
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
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

    /** 构件下载目录，同时作为依赖树的输出位置。 */
    @OutputDirectory
    public abstract DirectoryProperty getResolveDirectory();

    @TaskAction
    public void resolve() {
        Path directory = getResolveDirectory().get().getAsFile().toPath();
        List<String> sourceWarnings = new ArrayList<>();
        RepositorySlimArtifactSource source =
                new RepositorySlimArtifactSource(getRepositories().get(), sourceWarnings);

        SlimTreeResolver resolver = new SlimTreeResolver(source, Set.copyOf(getRepositoryUrls().get()));
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
