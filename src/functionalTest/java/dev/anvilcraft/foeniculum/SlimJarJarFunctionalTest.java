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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.gradle.testkit.runner.BuildResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 不安装 ModDevGradle 时插件的端到端行为。
 *
 * <p>覆盖三个产物的内容、发布挂载、递归压平与仓库告警。这些行为都不依赖原生 {@code jarJar}，
 * 因此可以先在最小环境里验证，避免每次运行都要解析 ModDevGradle 与 Minecraft 依赖。
 */
class SlimJarJarFunctionalTest extends BuildFixture {
    @TempDir
    Path root;

    private LocalMavenRepository repository;
    private Path project;

    @BeforeEach
    void setUp() throws IOException {
        repository = new LocalMavenRepository(root.resolve("repository"));
        // 三级依赖：top -> mid -> leaf；shared 被 mid 与 top 两个分支以不同区间同时依赖。
        repository.publish(Module.builder("com.example", "leaf", "1.0.0").build());
        repository.publish(Module.builder("com.example", "shared", "2.0.0").build());
        repository.publish(Module.builder("com.example", "mid", "1.0.0")
                .dependencies(
                        Dependency.of("com.example", "leaf", "[1.0,)", "1.0.0"),
                        Dependency.of("com.example", "shared", "[1.0,3.0)", "2.0.0"))
                .build());
        repository.publish(Module.builder("com.example", "top", "1.0.0")
                .dependencies(
                        Dependency.of("com.example", "mid", "[1.0,)", "1.0.0"),
                        Dependency.of("com.example", "shared", "[2.0,3.0)", "2.0.0"))
                .build());

        writeSettings(root, "rootProject.name = 'demo'\n");
        writeBuildFile(root, buildScript());
        writeFile(root, "src/main/java/com/demo/Demo.java", "package com.demo;\n\npublic class Demo {}\n");
        project = projectDirectory(root);
    }

    private String buildScript() {
        return """
                plugins {
                    id 'java-library'
                    id 'maven-publish'
                    id 'dev.anvilcraft.foeniculum'
                }

                version = '1.2.3'
                group = 'com.demo'

                repositories {
                    maven {
                        url = uri('%s')
                    }
                }

                dependencies {
                    slimJarJar 'com.example:top:1.0.0'
                }

                publishing {
                    publications {
                        mavenJava(MavenPublication) {
                            from components.java
                        }
                    }
                    repositories {
                        maven {
                            name = 'test'
                            url = uri(layout.buildDirectory.dir('published'))
                        }
                    }
                }
                """.formatted(repository.url());
    }

    @Test
    void 主包内嵌压平后的瘦身依赖且元数据只有一份() throws IOException {
        run(root, "jar");

        Path jar = project.resolve("build/libs/demo-1.2.3.jar");
        assertFileExists(jar);

        assertEquals(
                List.of("com.example:leaf", "com.example:mid", "com.example:shared", "com.example:top"),
                metadataModuleIds(jar),
                "元数据条目应按模块标识排序，且每个模块只出现一次");

        for (String path : metadataPaths(jar)) {
            assertContains(jar, path);
        }
        assertEquals(
                metadataPaths(jar).size(),
                jarJarEntryCount(jar) - 1,
                "jarjar 目录下除元数据外的条目数应与元数据记录的内嵌文件数一致");
    }

    @Test
    void 版本区间按交集合并并选出唯一版本() throws IOException {
        run(root, "jar");

        Path jar = project.resolve("build/libs/demo-1.2.3.jar");
        JsonObject shared = metadataEntry(jar, "com.example:shared");
        assertTrue(shared != null, "元数据中应包含 shared 模块");

        assertEquals("2.0.0", shared.getAsJsonObject("version").get("artifactVersion").getAsString());
        // [1.0,3.0) 与 [2.0,3.0) 的交集是 [2.0,3.0)，写入元数据时按声明原样保留。
        assertEquals("[2.0,3.0)", shared.getAsJsonObject("version").get("range").getAsString());
    }

    @Test
    void 瘦身包不含任何瘦身内嵌内容() throws IOException {
        run(root, "slimJar");

        Path slimJar = project.resolve("build/libs/demo-1.2.3-slim.jar");
        assertFileExists(slimJar);

        assertContains(slimJar, "com/demo/Demo.class");
        assertTrue(
                entries(slimJar).stream().noneMatch(name -> name.startsWith("META-INF/jarjar/")),
                "没有原生内嵌依赖时瘦身包不应包含 jarjar 目录，实际条目：" + entries(slimJar));
    }

    @Test
    void 发出的描述文件只记录直接声明与仓库列表() throws IOException {
        run(root, "slimMetadata");

        Path metadata = project.resolve("build/foeniculum/publish/demo-1.2.3-slim-metadata.json");
        assertFileExists(metadata);

        JsonObject json = JsonParser.parseString(Files.readString(metadata, StandardCharsets.UTF_8))
                .getAsJsonObject();

        assertEquals(1, json.get("schemaVersion").getAsInt());
        assertEquals(
                List.of("com.example:top"),
                json.getAsJsonArray("dependencies").asList().stream()
                        .map(element -> element.getAsJsonObject().get("group").getAsString()
                                + ":" + element.getAsJsonObject().get("artifact").getAsString())
                        .toList());

        JsonObject dependency = json.getAsJsonArray("dependencies").get(0).getAsJsonObject();
        assertEquals("[1.0.0,)", dependency.get("versionRange").getAsString());
        assertEquals("1.0.0", dependency.get("preferredVersion").getAsString());
        assertTrue(dependency.get("isSlim").getAsBoolean());
        assertEquals(
                List.of(repository.url()),
                json.getAsJsonArray("repositories").asList().stream()
                        .map(element -> element.getAsString())
                        .toList());
    }

    @Test
    void 发布时同时产出三个构件() throws IOException {
        run(root, "publish");

        Path published = project.resolve("build/published/com/demo/demo/1.2.3");
        assertFileExists(published.resolve("demo-1.2.3.jar"));
        assertFileExists(published.resolve("demo-1.2.3-slim.jar"));
        assertFileExists(published.resolve("demo-1.2.3-slim-metadata.json"));

        // 发布的瘦身包与本地构建产物内容一致：不含瘦身内嵌内容。
        assertTrue(
                entries(published.resolve("demo-1.2.3-slim.jar")).stream()
                        .noneMatch(name -> name.startsWith("META-INF/jarjar/")),
                "发布的瘦身包不应包含瘦身内嵌内容");

        // 描述文件同样随主构件发布，内容与本地文件一致。
        assertEquals(
                Files.readString(project.resolve("build/foeniculum/publish/demo-1.2.3-slim-metadata.json")),
                Files.readString(published.resolve("demo-1.2.3-slim-metadata.json")));
    }

    @Test
    void 下游描述文件引用未声明仓库时输出警告() throws IOException {
        repository.publish(Module.builder("com.example", "suspicious", "1.0.0")
                .repositories("https://never-declared.example.com/releases")
                .dependencies(Dependency.of("com.example", "leaf", "[1.0,)", "1.0.0"))
                .build());

        writeBuildFile(root, buildScript().replace(
                "slimJarJar 'com.example:top:1.0.0'", "slimJarJar 'com.example:suspicious:1.0.0'"));

        BuildResult result = run(root, "jar");

        assertTrue(
                result.getOutput().contains("https://never-declared.example.com/releases"),
                result.getOutput());
        assertTrue(result.getOutput().contains("未声明的仓库"), result.getOutput());
    }

    @Test
    void 项目依赖被拒绝并说明原因() throws IOException {
        writeSettings(root, "rootProject.name = 'demo'\ninclude 'lib'\n");
        writeFile(root, "lib/build.gradle", "plugins { id 'java-library' }\n");
        writeBuildFile(root, buildScript().replace(
                "slimJarJar 'com.example:top:1.0.0'", "slimJarJar project(':lib')"));

        BuildResult result = runExpectingFailure(root, "jar");

        assertTrue(result.getOutput().contains("不支持项目依赖"), result.getOutput());
    }

    @Test
    void 被混淆的内嵌模块在元数据中被标记() throws IOException {
        repository.publish(Module.builder("com.example", "obfuscated-lib", "1.0.0")
                .obfuscated()
                .build());
        writeBuildFile(root, buildScript().replace(
                "slimJarJar 'com.example:top:1.0.0'", "slimJarJar 'com.example:obfuscated-lib:1.0.0'"));

        run(root, "jar");

        JsonObject entry = metadataEntry(project.resolve("build/libs/demo-1.2.3.jar"), "com.example:obfuscated-lib");
        assertTrue(entry != null, "元数据中应包含被混淆的模块");
        assertTrue(entry.get("isObfuscated").getAsBoolean(), "清单含 Obfuscated-By 时应标记为已混淆");
    }

    @Test
    void 关闭自动挂载后发布不再包含瘦身构件() throws IOException {
        writeBuildFile(root, buildScript().replace(
                "dependencies {", "foeniculum { autoAttachPublishing = false }\n\ndependencies {"));

        run(root, "publish");

        Path published = project.resolve("build/published/com/demo/demo/1.2.3");
        assertFileExists(published.resolve("demo-1.2.3.jar"));
        assertFalse(Files.exists(published.resolve("demo-1.2.3-slim.jar")), "关闭自动挂载后不应发布瘦身包");
        assertFalse(
                Files.exists(published.resolve("demo-1.2.3-slim-metadata.json")),
                "关闭自动挂载后不应发布描述文件");
    }

    @Test
    void 声明无法解析的坐标时构建失败而不是静默丢弃() throws IOException {
        writeBuildFile(root, buildScript().replace(
                "slimJarJar 'com.example:top:1.0.0'", "slimJarJar 'com.example:not-published:9.9.9'"));

        BuildResult result = runExpectingFailure(root, "jar");

        assertTrue(result.getOutput().contains("com.example:not-published"), result.getOutput());
        assertTrue(result.getOutput().contains("无法解析"), result.getOutput());
    }

    @Test
    void 同一模块被重复声明时描述文件只记一条并取区间交集() throws IOException {
        writeBuildFile(root, buildScript().replace(
                "slimJarJar 'com.example:top:1.0.0'",
                "slimJarJar 'com.example:top:1.0.0'\n    slimJarJar 'com.example:top:[1.0.0,2.0.0)'"));

        run(root, "slimMetadata");

        Path metadata = project.resolve("build/foeniculum/publish/demo-1.2.3-slim-metadata.json");
        JsonObject json = JsonParser.parseString(Files.readString(metadata, StandardCharsets.UTF_8))
                .getAsJsonObject();

        assertEquals(1, json.getAsJsonArray("dependencies").size(), "同一模块只应记录一条");
        assertEquals(
                "[1.0.0,2.0.0)",
                json.getAsJsonArray("dependencies").get(0).getAsJsonObject().get("versionRange").getAsString());
    }
}
