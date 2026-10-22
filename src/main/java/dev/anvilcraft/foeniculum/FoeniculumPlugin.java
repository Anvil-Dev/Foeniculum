package dev.anvilcraft.foeniculum;

import dev.anvilcraft.foeniculum.model.SlimDeclaration;
import dev.anvilcraft.foeniculum.model.SlimRepository;
import dev.anvilcraft.foeniculum.tasks.MergeJarJarMetadata;
import dev.anvilcraft.foeniculum.tasks.ResolveSlimJarJar;
import dev.anvilcraft.foeniculum.tasks.SlimJar;
import dev.anvilcraft.foeniculum.tasks.SlimMetadataFile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.artifacts.result.UnresolvedDependencyResult;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;

/**
 * 茴香插件入口：在不改变 ModDevGradle 原生 {@code jarJar} 行为的前提下，增加 {@code slimJarJar} 机制。
 *
 * <p>插件只按名称约定对接 ModDevGradle 的配置与任务（配置名与任务名均为 {@code jarJar}），不引用它的内部类型。
 * 这些类型全部被标注为 Gradle 内部 API，直接依赖会让插件在 ModDevGradle 升级时被迫跟进；
 * 而这里真正需要的只是“哪个目录存放了原生内嵌内容”这一事实。
 *
 * <p>所有输入都以 {@link Provider} 形式声明，执行期不再访问 {@link Project}，因此任务图可以在配置缓存下复用。
 */
public class FoeniculumPlugin implements Plugin<Project> {
    /** ModDevGradle 为主源集注册的原生内嵌配置与任务名。 */
    static final String NATIVE_NAME = "jarJar";
    /** 茴香注册的瘦身内嵌配置名。 */
    static final String SLIM_NAME = "slimJarJar";

    @Override
    public void apply(Project project) {
        FoeniculumExtension extension = project.getExtensions().create("foeniculum", FoeniculumExtension.class);
        extension.getAutoAttachPublishing().convention(true);

        Configuration slimConfiguration = project.getConfigurations().create(SLIM_NAME, configuration -> {
            configuration.setDescription("Dependencies embedded through the Foeniculum slim jar-in-jar mechanism.");
            configuration.setTransitive(false);
            configuration.setCanBeResolved(true);
            configuration.setCanBeConsumed(false);
        });
        ModDevGradleAttributes.configure(project, slimConfiguration);

        TaskProvider<Jar> jarTask = project.getTasks().named(JavaPlugin.JAR_TASK_NAME, Jar.class);
        Provider<List<SlimDeclaration>> declarations = declarations(project, slimConfiguration);

        TaskProvider<ResolveSlimJarJar> resolveTask = project.getTasks().register(
                "resolveSlimJarJar", ResolveSlimJarJar.class, task -> {
                    task.setGroup("foeniculum");
                    task.setDescription("Resolves the slim jar-in-jar dependency tree and flattens it into one level.");
                    task.getResolveDirectory().convention(
                            project.getLayout().getBuildDirectory().dir("foeniculum/resolved"));
                    task.getDeclarations().set(declarations);
                    task.getRepositoryUrls().set(project.provider(() -> repositoryUrls(project)));
                    task.getRepositories().set(project.provider(() -> repositories(project)));
                });

        TaskProvider<MergeJarJarMetadata> mergeTask = project.getTasks().register(
                "mergeJarJar", MergeJarJarMetadata.class, task -> {
                    task.setGroup("foeniculum");
                    task.setDescription("Merges native jar-in-jar entries with the flattened slim dependency tree.");
                    task.getMergeDirectory().convention(
                            project.getLayout().getBuildDirectory().dir("foeniculum/jarjar"));
                    task.getNativeOutputs().from(nativeOutput(project));
                    task.getResolveDirectory().set(resolveTask.flatMap(ResolveSlimJarJar::getResolveDirectory));                });

        // 主 jar 只追加“合并后的元数据与瘦身内嵌文件”；原生内嵌文件已由 ModDevGradle 自己接进 jar，
        // 因此这里再排除原生那份元数据，避免同一路径下出现两份。
        // 排除只匹配源根相对路径：合并后的元数据是以 into 映射进去的，其相对路径是 metadata.json，不受影响。
        jarTask.configure(task -> {
            task.from(mergeTask.flatMap(MergeJarJarMetadata::getMergeDirectory), spec -> spec.into("META-INF/jarjar"));
            task.exclude(element -> element.getPath().equals("META-INF/jarjar/metadata.json"));
        });

        TaskProvider<SlimJar> slimJarTask = project.getTasks().register("slimJar", SlimJar.class, task -> {
            task.setGroup("foeniculum");
            task.setDescription("Builds the slim jar that contains only the native jarJar embedded content.");
            task.getMainJar().set(jarTask.flatMap(Jar::getArchiveFile));
            task.getNativeOutputs().from(nativeOutput(project));
            task.getSlimJar().convention(project.getLayout().getBuildDirectory()
                    .file(archiveName(jarTask).map(name -> "libs/" + name + "-slim.jar")));
            task.dependsOn(jarTask);
        });

        TaskProvider<SlimMetadataFile> metadataTask = project.getTasks().register(
                "slimMetadata", SlimMetadataFile.class, task -> {
                    task.setGroup("foeniculum");
                    task.setDescription("Writes the standalone slim dependency metadata file.");
                    task.getMetadataFile().convention(project.getLayout().getBuildDirectory()
                            .file(archiveName(jarTask).map(name -> "foeniculum/publish/" + name + "-slim-metadata.json")));
                    task.getDeclarations().set(declarations);
                    task.getSlimTreeFile().set(resolveTask.flatMap(
                            ResolveSlimJarJar::getResolveDirectory).map(directory -> directory.file("slim-tree.json")));
                    task.getRepositoryUrls().set(project.provider(() -> repositoryUrls(project)));
                });

        project.getTasks().named("assemble").configure(task -> {
            task.dependsOn(slimJarTask);
            task.dependsOn(metadataTask);
        });

        PublishingWiring.attach(project, extension, slimJarTask, metadataTask);
    }

    /**
     * 原生 {@code jarJar} 任务的输出。
     *
     * <p>按任务名约定对接而不引用 ModDevGradle 的类型：项目可能没有安装 ModDevGradle，或使用不同版本，
     * 而本插件只需要它的输出位置。任务不存在时返回空文件集合，表示不存在原生内嵌内容。
     */
    private static Object nativeOutput(Project project) {
        var task = project.getTasks().findByName(NATIVE_NAME);
        return task == null ? project.files() : task;
    }

    /**
     * 把 {@code slimJarJar} 的声明转换为可序列化记录。
     *
     * <p>用 {@link Provider} 延迟到任务执行前求值：声明的具体版本只有经过依赖解析才能确定，
     * 而配置阶段解析配置会拖慢构建并破坏配置缓存。
     */
    private static Provider<List<SlimDeclaration>> declarations(Project project, Configuration configuration) {
        return configuration.getIncoming().getResolutionResult().getRootComponent().map(root -> {
            for (var dependency : configuration.getDependencies()) {
                if (dependency instanceof ProjectDependency) {
                    throw new GradleException("slimJarJar 不支持项目依赖：" + dependency.getGroup() + ":"
                            + dependency.getName() + "；该机制要求构件经 Maven 仓库发布后再声明坐标");
                }
            }

            List<SlimDeclaration> declarations = new ArrayList<>();
            for (var result : root.getDependencies()) {
                if (result instanceof UnresolvedDependencyResult unresolved) {
                    // 解析失败绝不能静默跳过：跳过后构建仍会成功，但产物里缺了这个依赖，
                    // 描述文件还会声称它不存在，问题只能推迟到运行时变成找不到类。
                    throw new GradleException("slimJarJar 声明的依赖 " + unresolved.getRequested().getDisplayName()
                            + " 无法解析：" + unresolved.getFailure().getMessage(),
                            unresolved.getFailure());
                }
                if (!(result instanceof ResolvedDependencyResult resolved)) {
                    continue;
                }
                if (!(resolved.getRequested() instanceof ModuleComponentSelector requested)) {
                    continue;
                }
                if (!(resolved.getSelected().getId() instanceof ModuleComponentIdentifier selected)) {
                    continue;
                }
                declarations.add(new SlimDeclaration(
                        selected.getGroup(),
                        selected.getModule(),
                        requested.getVersion(),
                        selected.getVersion()));
            }
            declarations.sort(Comparator.comparing(SlimDeclaration::moduleId));
            return declarations;
        });
    }

    private static List<String> repositoryUrls(Project project) {
        List<String> urls = new ArrayList<>();
        for (SlimRepository repository : repositories(project)) {
            urls.add(SlimRepository.normalize(repository.url()));
        }
        return urls;
    }

    /** 收集可通过 Maven 协议访问的仓库及其凭据。 */
    private static List<SlimRepository> repositories(Project project) {
        List<SlimRepository> repositories = new ArrayList<>();
        for (var repository : project.getRepositories()) {
            if (!(repository instanceof MavenArtifactRepository maven)) {
                continue;
            }
            String username = null;
            String password = null;
            var credentials = maven.getCredentials();
            if (credentials != null) {
                username = credentials.getUsername();
                password = credentials.getPassword();
            }
            repositories.add(new SlimRepository(maven.getUrl().toString(), username, password));
        }
        return repositories;
    }

    /** 主 jar 归档名（不含扩展名），瘦身包与描述文件据此命名，保证与主构件成套。 */
    private static Provider<String> archiveName(TaskProvider<Jar> jarTask) {
        return jarTask.flatMap(task -> task.getArchiveBaseName().zip(
                task.getArchiveVersion().orElse(""),
                (base, version) -> version.isEmpty() ? base : base + "-" + version));
    }
}
