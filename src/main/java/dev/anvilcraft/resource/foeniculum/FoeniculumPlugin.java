package dev.anvilcraft.resource.foeniculum;

import dev.anvilcraft.resource.foeniculum.model.SlimDeclaration;
import dev.anvilcraft.resource.foeniculum.model.SlimRepository;
import dev.anvilcraft.resource.foeniculum.tasks.MergeJarJarMetadata;
import dev.anvilcraft.resource.foeniculum.tasks.ResolveSlimJarJar;
import dev.anvilcraft.resource.foeniculum.tasks.SlimJar;
import dev.anvilcraft.resource.foeniculum.tasks.SlimMetadataFile;
import dev.anvilcraft.resource.foeniculum.tasks.StageSlimArtifacts;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.artifacts.component.ProjectComponentSelector;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.credentials.PasswordCredentials;
import org.gradle.internal.artifacts.repositories.AuthenticationSupportedInternal;
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
                    task.getResolveDirectory().set(resolveTask.flatMap(ResolveSlimJarJar::getResolveDirectory));
                });

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

        // 项目依赖的产物不在任何 Maven 仓库里，因此每个应用本插件的项目把自身的瘦身构件暂存成一个
        // 构建内 Maven 仓库（file:），下游解析时就与消费已发布坐标走完全相同的路径。
        // 暂存任务注册在项目自己身上：它需要读取本项目自己的任务时不存在配置顺序问题，
        // 而消费方只需要按依赖闭包取用这些目录。
        TaskProvider<StageSlimArtifacts> stageTask = project.getTasks().register(
                "stageSlimArtifacts", StageSlimArtifacts.class, task -> {
                    task.setGroup("foeniculum");
                    task.setDescription("Stages the slim artifacts into an in-build Maven repository for sibling projects.");
                    task.getGroupId().set(project.provider(() -> project.getGroup().toString()));
                    task.getArtifactId().set(project.getName());
                    task.getVersion().set(project.provider(() -> project.getVersion().toString()));
                    task.getJarFile().set(jarTask.flatMap(Jar::getArchiveFile));
                    task.getSlimJar().set(slimJarTask.flatMap(SlimJar::getSlimJar));
                    task.getMetadataFile().set(metadataTask.flatMap(SlimMetadataFile::getMetadataFile));
                    task.getRepositoryDirectory()
                            .convention(project.getLayout().getBuildDirectory().dir("foeniculum/local-repository"));
                });

        // 依赖闭包必须等全部项目配置完成才能读取：slimJarJar 的声明写在构建脚本正文里，
        // 而被引用的项目是否已应用本插件（从而是否已有自己的暂存任务）同样要到那时才能确定。
        // 递归收集而不是只看直接依赖：某个同级模块的描述文件还会引用其他同级模块，
        // 而这些模块的产物同样不在任何远程仓库里，漏掉它们会让解析退回到远程地址并失败。
        project.getGradle().projectsEvaluated(gradle -> {
            for (Project dependencyProject : slimDependencyClosure(project, new LinkedHashSet<>())) {
                TaskProvider<StageSlimArtifacts> stage = stageTaskOf(project, dependencyProject);
                resolveTask.configure(task -> task.getLocalRepositoryDirectories().from(stage));
            }
        });

        project.getTasks().named("assemble").configure(task -> {
            task.dependsOn(slimJarTask);
            task.dependsOn(metadataTask);
        });

        PublishingWiring.attach(project, extension, slimJarTask, metadataTask);
    }

    /**
     * 收集 {@code slimJarJar} 依赖里属于本构建的项目，按依赖闭包递归展开。
     *
     * <p>只看直接依赖不够：某个同级模块的描述文件还会引用其他同级模块，而这些模块的产物同样不在任何
     * Maven 仓库里。漏掉它们会让递归解析退回远程地址，最终表现成「仓库里找不到构件」。
     *
     * <p>只沿着本配置递归，不做全局依赖遍历：本配置声明为 {@code transitive(false)}，
     * 传递关系由各模块自己的描述文件表达，因此这里展开的是「被引用的项目本身还引用了哪些项目」。
     */
    private static List<Project> slimDependencyClosure(Project project, Set<String> visited) {
        var configuration = project.getConfigurations().findByName(SLIM_NAME);
        if (configuration == null) {
            // 被引用的项目可能没有应用本插件（例如纯注解处理器模块），它没有瘦身声明可供展开。
            return List.of();
        }

        List<Project> projects = new ArrayList<>();
        for (var dependency : configuration.getDependencies()) {
            if (!(dependency instanceof ProjectDependency projectDependency)) {
                continue;
            }
            Project dependencyProject = project.getRootProject().findProject(projectDependency.getPath());
            if (dependencyProject == null) {
                throw new GradleException("slimJarJar 声明的项目依赖 " + projectDependency.getPath()
                        + " 不属于当前构建，无法确定其坐标与产物");
            }
            if (!visited.add(dependencyProject.getPath())) {
                continue;
            }
            projects.add(dependencyProject);
            projects.addAll(slimDependencyClosure(dependencyProject, visited));
        }
        return projects;
    }

    /**
     * 取得一个项目对应的暂存任务，作为解析任务的本地仓库来源。
     *
     * <p>应用了本插件的项目已有自己的 {@code stageSlimArtifacts}，直接复用，得到的坐标与它对外发布的完全一致。
     * 未应用本插件的项目没有该任务（典型情况是纯注解处理器模块），此时在消费方项目里补一个只暂存普通 jar 的
     * 任务：该模块在瘦身树里是没有描述文件的叶子，解析端会回落到普通 jar，与消费一个尚未接入本插件的
     * 已发布库行为一致。
     */
    private static TaskProvider<StageSlimArtifacts> stageTaskOf(Project project, Project dependencyProject) {
        if (dependencyProject.getTasks().findByName("stageSlimArtifacts") != null) {
            return dependencyProject.getTasks().named("stageSlimArtifacts", StageSlimArtifacts.class);
        }

        String taskName = "stageSlim" + upperFirst(dependencyProject.getName());
        return project.getTasks().register(taskName, StageSlimArtifacts.class, task -> {
            task.setGroup("foeniculum");
            task.setDescription("Stages the jar of " + dependencyProject.getPath()
                    + " into an in-build Maven repository.");
            task.getGroupId().set(dependencyProject.provider(() -> dependencyProject.getGroup().toString()));
            task.getArtifactId().set(dependencyProject.getName());
            task.getVersion().set(dependencyProject.provider(() -> dependencyProject.getVersion().toString()));
            task.getJarFile().set(dependencyProject.getTasks()
                    .named("jar", Jar.class)
                    .flatMap(Jar::getArchiveFile));
            task.getRepositoryDirectory().convention(project.getLayout()
                    .getBuildDirectory()
                    .dir("foeniculum/local-repository/" + taskName));
        });
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

                // 项目依赖在解析结果里没有可用的坐标版本：它由同一构建的其他项目产出。
                // 这类声明的 group 与版本取自被依赖项目本身，版本写作固定值，与传入项目时的实际产物一致。
                if (resolved.getRequested() instanceof ProjectComponentSelector) {
                    Project dependencyProject = projectOf(project, resolved);
                    declarations.add(new SlimDeclaration(
                            dependencyProject.getGroup().toString(),
                            dependencyProject.getName(),
                            dependencyProject.getVersion().toString(),
                            dependencyProject.getVersion().toString()));
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

    /**
     * 解析一条项目依赖指向的项目。
     *
     * <p>{@code ProjectComponentSelector} 只给出项目路径，没有项目实例，因此用路径在当前构建中查找。
     * 查找失败说明该依赖指向的不是本构建的项目，此时直接失败：静默跳过会让产物缺少这个内嵌模块。
     */
    private static Project projectOf(Project project, ResolvedDependencyResult resolved) {
        String path = ((ProjectComponentSelector) resolved.getRequested()).getProjectPath();
        Project dependencyProject = project.getRootProject().findProject(path);
        if (dependencyProject == null) {
            throw new GradleException("slimJarJar 声明的项目依赖 " + path + " 不属于当前构建，无法确定其坐标与产物");
        }
        return dependencyProject;
    }

    private static boolean supportsCredentials(MavenArtifactRepository repository) {
        String scheme = repository.getUrl().getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
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
            // 公共 getCredentials() 会创建空凭据并开启认证，连匿名 HTTP 仓库也会因此失效。
            // Gradle 的只读凭据 Provider 位于内部接口中；这里只读取已配置的值，绝不调用创建凭据的 getter。
            if (supportsCredentials(maven) && maven instanceof AuthenticationSupportedInternal authenticated) {
                var configured = authenticated.getConfiguredCredentials().getOrNull();
                if (configured instanceof PasswordCredentials credentials) {
                    username = credentials.getUsername();
                    password = credentials.getPassword();
                }
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

    /**
     * 把项目名转换成可直接拼接成任务名的片段。
     *
     * <p>项目名允许包含连字符与点，而任务名里这些字符虽然合法，拼接后却难以阅读，且容易与别的任务名冲突。
     * 这里只保留字母数字并做首字母大写，得到 {@code stageSlimModuleCodec} 这样的名字。
     */
    private static String upperFirst(String name) {
        StringBuilder result = new StringBuilder();
        boolean nextUpper = true;
        for (char c : name.toCharArray()) {
            if (!Character.isLetterOrDigit(c)) {
                nextUpper = true;
                continue;
            }
            result.append(nextUpper ? Character.toUpperCase(c) : c);
            nextUpper = false;
        }
        return result.toString();
    }
}
