package dev.anvilcraft.foeniculum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.anvilcraft.foeniculum.fixture.LocalMavenRepository;
import dev.anvilcraft.foeniculum.fixture.LocalMavenRepository.Dependency;
import dev.anvilcraft.foeniculum.fixture.LocalMavenRepository.Module;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.gradle.testkit.runner.BuildResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 与真实 ModDevGradle 混用时的行为。
 *
 * <p>这里刻意不配置 {@code neoForge} 扩展块：只要不触碰 Minecraft 相关任务，ModDevGradle 的原生
 * {@code jarJar} 就能独立工作，因此可以在不下载 Minecraft 与服务端资源的前提下验证两者共存的关键约束——
 * 主 jar 中只有一份 FML 元数据、原生条目原样保留、瘦身包与原生内容一致。
 */
class ModDevGradleCoexistenceTest extends BuildFixture {
    @TempDir
    Path root;

    private LocalMavenRepository repository;
    private Path project;

    @BeforeEach
    void setUp() throws IOException {
        repository = new LocalMavenRepository(root.resolve("repository"));
        repository.publish(Module.builder("com.example", "native-lib", "1.0.0").build());
        // 与原生内嵌模块同名且同版本，用于验证去重时保留原生侧且不产生版本冲突。
        repository.publish(Module.builder("com.example", "shared-lib", "1.0.0").build());
        // 更高版本供「原生版本不满足瘦身侧区间」的用例使用。
        repository.publish(Module.builder("com.example", "shared-lib", "2.0.0").build());
        repository.publish(Module.builder("com.example", "slim-lib", "1.0.0")
                .dependencies(Dependency.of("com.example", "shared-lib", "[1.0,2.0)", "1.0.0"))
                .build());
        // 另有一个模块只被瘦身侧引入，用于确认瘦身条目确实进入了主包。
        repository.publish(Module.builder("com.example", "slim-only", "1.0.0").build());

        writeSettings(root, """
                pluginManagement {
                    repositories {
                        mavenLocal()
                        gradlePluginPortal()
                        maven { url = 'https://maven.neoforged.net/releases' }
                    }
                }
                rootProject.name = 'demo'
                """);
        writeBuildFile(root, buildScript());
        writeFile(root, "src/main/java/com/demo/Demo.java", "package com.demo;\n\npublic class Demo {}\n");
        project = projectDirectory(root);
    }

    private String buildScript() {
        return """
                plugins {
                    id 'java-library'
                    id 'maven-publish'
                    id 'net.neoforged.moddev' version '2.0.148'
                    id 'dev.anvilcraft.foeniculum'
                }

                version = '1.0.0'
                group = 'com.demo'

                repositories {
                    // 独占内容过滤让 com.example 的请求只发往本地夹具仓库：ModDevGradle 会自动注入自己的仓库，
                    // 而这些仓库对未知坐标可能返回连接重置而非 404，会让 Gradle 直接中断解析。
                    exclusiveContent {
                        forRepository {
                            maven {
                                url = uri('%s')
                            }
                        }
                        filter {
                            includeGroup 'com.example'
                        }
                    }
                    mavenCentral()
                }

                dependencies {
                    jarJar 'com.example:native-lib:1.0.0'
                    jarJar 'com.example:shared-lib:1.0.0'
                    slimJarJar 'com.example:slim-lib:1.0.0'
                    slimJarJar 'com.example:slim-only:1.0.0'
                }
                """.formatted(repository.url());
    }

    @Test
    void 主包同时含原生内嵌与扁平的瘦身内嵌且元数据唯一() throws IOException {
        run(root, "jar");

        Path jar = project.resolve("build/libs/demo-1.0.0.jar");
        assertFileExists(jar);

        // 原生两个模块 + 瘦身侧独有模块；shared-lib 两侧都有，只保留原生那一份。
        assertEquals(
                List.of("com.example:native-lib", "com.example:shared-lib", "com.example:slim-lib", "com.example:slim-only"),
                metadataModuleIds(jar));

        for (String path : metadataPaths(jar)) {
            assertContains(jar, path);
        }
        assertEquals(
                metadataPaths(jar).size(),
                jarJarEntryCount(jar) - 1,
                "jarjar 目录下除元数据外的条目数应与元数据记录的内嵌文件数一致");
    }

    @Test
    void 与原生重复的模块保留原生版本并输出警告() throws IOException {
        run(root, "jar");

        Path jar = project.resolve("build/libs/demo-1.0.0.jar");
        JsonObject shared = metadataEntry(jar, "com.example:shared-lib");
        assertTrue(shared != null, "元数据中应保留原生内嵌的 shared-lib");
        assertEquals("1.0.0", shared.getAsJsonObject("version").get("artifactVersion").getAsString(),
                "重复模块应保留原生侧版本");
        assertEquals("META-INF/jarjar/shared-lib-1.0.0.jar", shared.get("path").getAsString());
    }

    @Test
    void 原生内嵌版本不满足瘦身侧区间时构建失败() throws IOException {
        // 原生把 shared-lib 内嵌为 1.0.0，另有一个瘦身模块要求 [2.0,3.0)。
        // 保留原生侧会让运行时 FML 报 JiJ 冲突，因此这种情况必须在构建期就失败。
        repository.publish(Module.builder("com.example", "strict-lib", "1.0.0")
                .dependencies(Dependency.of("com.example", "shared-lib", "[2.0,3.0)", "2.0.0"))
                .build());
        writeBuildFile(root, buildScript().replace(
                "slimJarJar 'com.example:slim-lib:1.0.0'", "slimJarJar 'com.example:strict-lib:1.0.0'"));

        BuildResult result = runExpectingFailure(root, "jar");

        assertTrue(result.getOutput().contains("com.example:shared-lib"), result.getOutput());
        assertTrue(result.getOutput().contains("[2.0,3.0)"), result.getOutput());
    }

    @Test
    void 瘦身包与不装插件时的内容一致_只含原生内嵌内容() throws IOException {
        run(root, "slimJar");

        Path slimJar = project.resolve("build/libs/demo-1.0.0-slim.jar");
        assertFileExists(slimJar);

        assertEquals(
                List.of(
                        "META-INF/jarjar/metadata.json",
                        "META-INF/jarjar/native-lib-1.0.0.jar",
                        "META-INF/jarjar/shared-lib-1.0.0.jar"),
                entries(slimJar).stream()
                        .filter(name -> name.startsWith("META-INF/jarjar/"))
                        .filter(name -> !name.endsWith("/"))
                        .toList());

        // 瘦身内容不应出现在瘦身包里，它们只存在于描述文件中。
        assertFalse(
                entries(slimJar).contains("META-INF/jarjar/slim-lib-1.0.0-slim.jar"),
                "瘦身包不应包含由 slimJarJar 引入的模块");
        assertFalse(
                entries(slimJar).contains("META-INF/jarjar/slim-only-1.0.0-slim.jar"),
                "瘦身包不应包含由 slimJarJar 引入的模块");
        assertContains(slimJar, "com/demo/Demo.class");
    }

    @Test
    void 瘦身包内的原生元数据与主包中的原生条目一致() throws IOException {
        run(root, "jar", "slimJar");

        Path mainJar = project.resolve("build/libs/demo-1.0.0.jar");
        Path slimJar = project.resolve("build/libs/demo-1.0.0-slim.jar");

        JsonObject mainMetadata = entryJson(mainJar, "META-INF/jarjar/metadata.json");
        JsonObject slimMetadata = entryJson(slimJar, "META-INF/jarjar/metadata.json");

        // 主包中的瘦身条目应全部追加在原生条目之后：前两条与原生产物完全一致。
        for (int i = 0; i < slimMetadata.getAsJsonArray("jars").size(); i++) {
            assertEquals(
                    slimMetadata.getAsJsonArray("jars").get(i),
                    mainMetadata.getAsJsonArray("jars").get(i),
                    "第 " + i + " 条原生条目在主包与瘦身包中应保持一致");
        }
    }

    @Test
    void 未使用瘦身依赖时产物与原生行为完全一致() throws IOException {
        writeBuildFile(root, buildScript()
                .replace("    slimJarJar 'com.example:slim-lib:1.0.0'\n", "")
                .replace("    slimJarJar 'com.example:slim-only:1.0.0'\n", ""));

        run(root, "jar", "slimJar", "slimMetadata");

        Path mainJar = project.resolve("build/libs/demo-1.0.0.jar");
        Path slimJar = project.resolve("build/libs/demo-1.0.0-slim.jar");

        assertEquals(
                entryText(slimJar, "META-INF/jarjar/metadata.json"),
                entryText(mainJar, "META-INF/jarjar/metadata.json"),
                "未使用 slimJarJar 时主包的元数据应与原生产物逐字相同");

        // 没有声明时描述文件仍然生成，只是依赖列表为空，这样发布配置始终成套。
        Path metadata = project.resolve("build/foeniculum/publish/demo-1.0.0-slim-metadata.json");
        assertFileExists(metadata);
        assertEquals(
                0,
                JsonParser.parseString(Files.readString(metadata)).getAsJsonObject()
                        .getAsJsonArray("dependencies")
                        .size());
    }
}
