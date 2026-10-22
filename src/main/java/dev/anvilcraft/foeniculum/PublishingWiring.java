package dev.anvilcraft.foeniculum;

import dev.anvilcraft.foeniculum.tasks.SlimJar;
import dev.anvilcraft.foeniculum.tasks.SlimMetadataFile;
import org.gradle.api.Project;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenArtifact;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.tasks.TaskProvider;

/**
 * 把瘦身包与描述文件挂到 {@code maven-publish} 的发布上。
 *
 * <p>下游项目能够递归解析的前提是这两个构件与主构件成套发布到同一个坐标下，因此默认自动挂载。
 * 挂载时机必须在发布配置完成之后：{@code from(components["java"])} 会先生成主 jar 构件，
 * 提前读取构件列表会看到不完整的内容。
 */
final class PublishingWiring {
    private static final String SLIM_CLASSIFIER = "slim";
    private static final String METADATA_CLASSIFIER = "slim-metadata";

    private PublishingWiring() {}

    static void attach(
            Project project,
            FoeniculumExtension extension,
            TaskProvider<SlimJar> slimJarTask,
            TaskProvider<SlimMetadataFile> metadataTask) {
        project.getPluginManager().withPlugin("maven-publish", plugin -> project.afterEvaluate(ignored -> {
            if (!extension.getAutoAttachPublishing().get()) {
                return;
            }
            var publications = project.getExtensions()
                    .getByType(PublishingExtension.class)
                    .getPublications()
                    .withType(MavenPublication.class);

            // 用 configureEach 而不是遍历当前集合：发布可能在 afterEvaluate 之后才注册
            // （例如由其他插件延后创建），configureEach 能覆盖之后加入的发布。
            publications.configureEach(publication -> {
                if (!holdsJavaComponent(publication)) {
                    // java-gradle-plugin 会自动创建插件标记发布，它是只有 POM 的坐标标记。
                    // 把瘦身构件挂上去会往仓库里多推一对以标记坐标命名的文件，对下游毫无用处。
                    return;
                }
                MavenArtifact slimArtifact = publication.artifact(slimJarTask.flatMap(SlimJar::getSlimJar));
                slimArtifact.setClassifier(SLIM_CLASSIFIER);

                MavenArtifact metadataArtifact =
                        publication.artifact(metadataTask.flatMap(SlimMetadataFile::getMetadataFile));
                metadataArtifact.setClassifier(METADATA_CLASSIFIER);
                metadataArtifact.setExtension("json");
            });
        }));
    }

    /**
     * 判断发布是否承载 java 组件。
     *
     * <p>按构件判断而不是按发布名字判断：发布名由插件自己命名，可能被项目改写；
     * 而承载 java 组件的发布必有构件，标记发布则没有。这样既不依赖命名的稳定性，
     * 也能覆盖项目自行创建的其他 Maven 发布。
     */
    private static boolean holdsJavaComponent(MavenPublication publication) {
        return !publication.getArtifacts().isEmpty();
    }
}
