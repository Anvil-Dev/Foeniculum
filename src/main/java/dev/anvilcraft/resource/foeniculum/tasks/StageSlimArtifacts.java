package dev.anvilcraft.resource.foeniculum.tasks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * 把一条项目依赖的产物按 Maven 目录结构暂存到构建内仓库，供本项目的解析任务读取。
 *
 * <p>{@code slimJarJar} 接受项目依赖时，该依赖不在任何 Maven 仓库里，而递归解析、版本区间合并与构件下载
 * 都建立在「按坐标访问 Maven 仓库」之上。把项目产物暂存成 {@code file:} 仓库，后续流程就能与消费已发布坐标
 * 走完全相同的路径，不必为项目依赖另写一套解析逻辑，也不会出现两条路径行为不一致的风险。
 *
 * <p>瘦身包与描述文件是可选的：被依赖的项目可能没有应用本插件。此时只暂存普通 jar，
 * 解析端会把它当作没有描述文件的叶子模块并回落到普通 jar，与消费一个尚未接入本插件的已发布库行为一致。
 *
 * <p>产物按坐标重命名后写入：项目产物按 {@code archivesName} 命名，与 Maven 仓库要求的
 * {@code artifact-version.jar} 不同，不重命名会让解析端按约定拼出的地址找不到文件。
 * artifactId 取项目名而不是 {@code archivesName}，因为 maven-publish 默认也用项目名，
 * 这样本地构建与远程发布的坐标保持一致。
 */
@CacheableTask
public abstract class StageSlimArtifacts extends DefaultTask {
    /** 暂存构件所属的 group。 */
    @Input
    public abstract Property<String> getGroupId();

    /** 暂存构件所属的 artifact。 */
    @Input
    public abstract Property<String> getArtifactId();

    /** 暂存构件的版本。 */
    @Input
    public abstract Property<String> getVersion();

    /** 被依赖项目的普通 jar。 */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getJarFile();

    /** 被依赖项目的瘦身包；该项目未应用本插件时为空。 */
    @Optional
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getSlimJar();

    /** 被依赖项目的瘦身依赖描述文件；该项目未应用本插件时为空。 */
    @Optional
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getMetadataFile();

    /** 暂存仓库根目录，解析任务把它当作 {@code file:} 仓库读取。 */
    @OutputDirectory
    public abstract DirectoryProperty getRepositoryDirectory();

    @TaskAction
    public void stage() throws IOException {
        String groupId = getGroupId().get();
        String artifactId = getArtifactId().get();
        String version = getVersion().get();
        if (version.isBlank() || "unspecified".equals(version)) {
            throw new GradleException("项目依赖 " + groupId + ":" + artifactId
                    + " 没有设置版本，无法暂存为 Maven 坐标；请在它的构建脚本中设置 version");
        }

        Path moduleDirectory =
                getRepositoryDirectory().get().getAsFile().toPath().resolve(groupId.replace('.', '/')).resolve(artifactId);
        Path versionDirectory = moduleDirectory.resolve(version);
        Files.createDirectories(versionDirectory);

        String prefix = artifactId + "-" + version;
        copy(getJarFile().get().getAsFile().toPath(), versionDirectory.resolve(prefix + ".jar"));
        if (getSlimJar().isPresent()) {
            copy(getSlimJar().get().getAsFile().toPath(), versionDirectory.resolve(prefix + "-slim.jar"));
        }
        if (getMetadataFile().isPresent()) {
            copy(getMetadataFile().get().getAsFile().toPath(),
                    versionDirectory.resolve(prefix + "-slim-metadata.json"));
        }
        Files.writeString(
                moduleDirectory.resolve("maven-metadata.xml"),
                versionList(groupId, artifactId, version),
                StandardCharsets.UTF_8);
    }

    /**
     * 版本列表。
     *
     * <p>解析端在没有任何候选版本满足合并后的区间时，会回落到这里列出的版本。项目依赖只暂存当前这一个版本，
     * 因此列表里只有它；缺少该文件会让回落路径在文件仓库上直接失败。
     */
    private static String versionList(String groupId, String artifactId, String version) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <metadata>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <versioning>
                    <versions>
                      <version>%s</version>
                    </versions>
                  </versioning>
                </metadata>
                """.formatted(groupId, artifactId, version);
    }

    private static void copy(Path source, Path target) {
        try {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new GradleException("无法暂存构件 " + source + " 到 " + target, e);
        }
    }
}
