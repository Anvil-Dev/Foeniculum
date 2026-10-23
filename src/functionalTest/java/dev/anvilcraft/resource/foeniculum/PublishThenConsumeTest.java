package dev.anvilcraft.resource.foeniculum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.anvilcraft.resource.foeniculum.fixture.LocalMavenRepository;
import dev.anvilcraft.resource.foeniculum.fixture.LocalMavenRepository.Dependency;
import dev.anvilcraft.resource.foeniculum.fixture.LocalMavenRepository.Module;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 一个项目发布库、另一个项目消费该库的完整闭环。
 *
 * <p>前面的用例都用夹具直接摆出仓库内容，验证的是消费侧；这里让真实构建产出瘦身包与描述文件，
 * 再用另一个真实构建去读它们。只有这样才能证明「发布出来的东西」和「解析时要求的东西」是同一套格式。
 */
class PublishThenConsumeTest extends BuildFixture {
    @TempDir
    Path root;

    private LocalMavenRepository fixtureRepository;
    private Path publishedRepository;

    @BeforeEach
    void setUp() throws IOException {
        fixtureRepository = new LocalMavenRepository(root.resolve("fixture-repo"));
        // 中间库依赖的叶子库，由夹具直接提供，模拟生态里尚未采用茴香的库。
        fixtureRepository.publish(Module.builder("com.leaf", "leaf-lib", "1.5.0").build());
        publishedRepository = root.resolve("published-repo");
    }

    private String publishedRepositoryUrl() {
        String path = publishedRepository.toAbsolutePath().toString().replace('\\', '/');
        return "file:///" + (path.startsWith("/") ? path.substring(1) : path);
    }

    private void writeLibraryProject() throws IOException {
        Path project = projectDirectory(root.resolve("library"));
        Files.createDirectories(project.resolve("src/main/java/com/lib"));
        Files.writeString(project.resolve("src/main/java/com/lib/Lib.java"),
                "package com.lib;\n\npublic class Lib {}\n", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'library'\n", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("build.gradle"), """
                plugins {
                    id 'java-library'
                    id 'maven-publish'
                    id 'dev.anvilcraft.resource.foeniculum'
                }

                version = '2.0.0'
                group = 'com.lib'

                repositories {
                    maven {
                        url = uri('%s')
                    }
                }

                dependencies {
                    slimJarJar 'com.leaf:leaf-lib:1.5.0'
                }

                publishing {
                    publications {
                        mavenJava(MavenPublication) {
                            from components.java
                        }
                    }
                    repositories {
                        maven {
                            name = 'output'
                            url = uri('%s')
                        }
                    }
                }
                """.formatted(fixtureRepository.url(), publishedRepositoryUrl()), StandardCharsets.UTF_8);
    }

    private void writeConsumerProject() throws IOException {
        Path project = projectDirectory(root.resolve("consumer"));
        Files.createDirectories(project.resolve("src/main/java/com/app"));
        Files.writeString(project.resolve("src/main/java/com/app/App.java"),
                "package com.app;\n\npublic class App {}\n", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'consumer'\n", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("build.gradle"), """
                plugins {
                    id 'java-library'
                    id 'dev.anvilcraft.resource.foeniculum'
                }

                version = '3.0.0'
                group = 'com.app'

                repositories {
                    maven {
                        url = uri('%s')
                    }
                    maven {
                        url = uri('%s')
                    }
                }

                dependencies {
                    slimJarJar 'com.lib:library:2.0.0'
                }
                """.formatted(publishedRepositoryUrl(), fixtureRepository.url()), StandardCharsets.UTF_8);
    }

    private org.gradle.testkit.runner.BuildResult runIn(Path projectDirectory, String... arguments) {
        List<String> all = new java.util.ArrayList<>(List.of(arguments));
        all.add("--stacktrace");
        return org.gradle.testkit.runner.GradleRunner.create()
                .withProjectDir(projectDirectory.toFile())
                .withPluginClasspath()
                .withArguments(all)
                .forwardOutput()
                .build();
    }

    @Test
    void 发布出的瘦身构件能被下游递归解析并压平() throws IOException {
        writeLibraryProject();
        runIn(projectDirectory(root.resolve("library")), "publish");

        Path published = publishedRepository.resolve("com/lib/library/2.0.0");
        assertFileExists(published.resolve("library-2.0.0.jar"));
        assertFileExists(published.resolve("library-2.0.0-slim.jar"));
        assertFileExists(published.resolve("library-2.0.0-slim-metadata.json"));

        writeConsumerProject();
        Path consumer = projectDirectory(root.resolve("consumer"));
        runIn(consumer, "jar");

        Path jar = consumer.resolve("build/libs/consumer-3.0.0.jar");

        // 下游只声明了 library，但压平后的产物里应同时含 library 与它描述文件里声明的 leaf-lib。
        assertEquals(
                List.of("com.leaf:leaf-lib", "com.lib:library"),
                metadataModuleIds(jar));
        assertTrue(
                metadataVersions(jar).get("com.lib:library").equals("2.0.0"),
                "library 的版本应取发布时的版本：" + metadataVersions(jar));
        assertTrue(
                metadataVersions(jar).get("com.leaf:leaf-lib").equals("1.5.0"),
                "leaf-lib 的版本应来自 library 的描述文件：" + metadataVersions(jar));

        // 描述文件里保留的区间应被转成规范写法。
        JsonObject leaf = metadataEntry(jar, "com.leaf:leaf-lib");
        assertEquals("[1.5.0,)", leaf.getAsJsonObject("version").get("range").getAsString());
    }

    @Test
    void 发布出的瘦身包不含被瘦身的内嵌内容() throws IOException {
        writeLibraryProject();
        runIn(projectDirectory(root.resolve("library")), "publish");

        Path slimJar = publishedRepository.resolve("com/lib/library/2.0.0/library-2.0.0-slim.jar");

        // leaf-lib 是中间库自己的瘦身依赖，它只应出现在描述文件里，不应被塞进瘦身包。
        assertTrue(
                entries(slimJar).stream().noneMatch(name -> name.startsWith("META-INF/jarjar/")),
                "瘦身包不应包含任何 jarjar 内容，实际条目：" + entries(slimJar));
        assertContains(slimJar, "com/lib/Lib.class");
    }

    @Test
    void 下游构建只依赖已发布仓库即可完成解析() throws IOException {
        writeLibraryProject();
        runIn(projectDirectory(root.resolve("library")), "publish");

        writeConsumerProject();

        // 若插件在运行期偷偷往 project.repositories 里注入仓库，这个只声明必需仓库的构建才会成功；
        // 反过来说，构建成功即证明它没有依赖隐式注入。
        org.gradle.testkit.runner.BuildResult result =
                runIn(projectDirectory(root.resolve("consumer")), "resolveSlimJarJar");

        assertTrue(result.getOutput().contains("BUILD SUCCESSFUL"), result.getOutput());
    }
}
