package dev.anvilcraft.foeniculum;

import dev.anvilcraft.foeniculum.tasks.SlimJar;
import dev.anvilcraft.foeniculum.tasks.SlimMetadataFile;
import org.gradle.api.Project;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenArtifact;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;

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
                MavenArtifact slimArtifact = publication.artifact(slimJarTask.flatMap(SlimJar::getSlimJar));
                slimArtifact.setClassifier(SLIM_CLASSIFIER);

                MavenArtifact metadataArtifact =
                        publication.artifact(metadataTask.flatMap(SlimMetadataFile::getMetadataFile));
                metadataArtifact.setClassifier(METADATA_CLASSIFIER);
                metadataArtifact.setExtension("json");
            });
        }));
    }
}
