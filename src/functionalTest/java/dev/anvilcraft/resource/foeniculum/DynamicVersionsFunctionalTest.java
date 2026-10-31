package dev.anvilcraft.resource.foeniculum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.anvilcraft.resource.foeniculum.fixture.LocalMavenRepository;
import dev.anvilcraft.resource.foeniculum.fixture.LocalMavenRepository.Module;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 用真实 Maven 版本列表验证动态选择、发布描述文件和配置缓存。 */
class DynamicVersionsFunctionalTest extends BuildFixture {
    @TempDir
    Path root;
    private LocalMavenRepository repository;

    @BeforeEach
    void setUp() {
        repository = new LocalMavenRepository(root.resolve("repository"));
    }

    @Test
    void latestReleaseSelectsNewestVersionAndRefreshesWithConfigurationCache() throws IOException {
        publishLeaf("1.0.0");
        publishLeaf("2.0.0");
        writeProject(root, "demo", "com.example:leaf:latest.release", repository.url());

        run(root, "build", "--configuration-cache");
        assertSelected(root, "demo", "2.0.0");
        assertTrue(run(root, "build", "--configuration-cache").getOutput().contains("Configuration cache entry reused"));

        publishLeaf("3.0.0");
        run(root, "build", "--configuration-cache", "--refresh-dependencies");
        assertSelected(root, "demo", "3.0.0");
    }

    @Test
    void buildSuffixWildcardSelectsHighestMatchingVersion() throws IOException {
        publishLeaf("1.2.3+build.2");
        publishLeaf("1.2.3+build.10");
        publishLeaf("1.2.4+build.20");
        writeProject(root, "demo", "com.example:leaf:1.2.3+build.+", repository.url());

        run(root, "build", "--configuration-cache");

        assertSelected(root, "demo", "1.2.3+build.10");
    }

    @Test
    void publishedDynamicDependencyRemainsConcreteForDownstreamConsumers() throws IOException {
        publishLeaf("1.0.0");
        publishLeaf("2.0.0");
        Path library = root.resolve("library");
        writeProject(library, "library", "com.example:leaf:latest.release", repository.url());
        run(library, "publish", "--configuration-cache");

        Path published = root.resolve("published");
        JsonObject dependency = JsonParser.parseString(Files.readString(
                        published.resolve("com/demo/library/1.0/library-1.0-slim-metadata.json")))
                .getAsJsonObject().getAsJsonArray("dependencies").get(0).getAsJsonObject();
        assertEquals("[2.0.0,)", dependency.get("versionRange").getAsString());
        assertEquals("2.0.0", dependency.get("preferredVersion").getAsString());

        // 仓库后来新增的版本不应使已发布描述文件中的 latest.release 再次被求值。
        publishLeaf("3.0.0");
        Path consumer = root.resolve("consumer");
        writeProject(consumer, "consumer", "com.demo:library:1.0",
                published.toUri().toString(), repository.url());
        run(consumer, "build", "--configuration-cache");

        Path jar = projectDirectory(consumer).resolve("build/libs/consumer-1.0.jar");
        assertEquals("2.0.0", metadataVersions(jar).get("com.example:leaf"));
        assertEquals(2, metadataModuleIds(jar).size());
    }

    private void publishLeaf(String version) throws IOException {
        repository.publish(Module.builder("com.example", "leaf", version).build());
    }

    private void writeProject(Path directory, String name, String dependency, String... repositories) throws IOException {
        writeSettings(directory, "rootProject.name = '" + name + "'\n");
        StringBuilder repositoryBlock = new StringBuilder();
        for (String url : repositories) {
            repositoryBlock.append("maven { url = uri('").append(url).append("') }\n");
        }
        writeBuildFile(directory, """
                plugins {
                    id 'java-library'
                    id 'maven-publish'
                    id 'dev.anvilcraft.resource.foeniculum'
                }
                group = 'com.demo'
                version = '1.0'
                repositories {
                    %s
                }
                dependencies {
                    slimJarJar '%s'
                }
                publishing {
                    publications {
                        mavenJava(MavenPublication) { from components.java }
                    }
                    repositories {
                        maven { url = uri('%s') }
                    }
                }
                """.formatted(repositoryBlock, dependency, root.resolve("published").toUri()));
        writeFile(directory, "src/main/java/Demo.java", "public class Demo {}\n");
    }

    private void assertSelected(Path directory, String name, String version) throws IOException {
        Path project = projectDirectory(directory);
        Path jar = project.resolve("build/libs/" + name + "-1.0.jar");
        JsonObject embedded = metadataEntry(jar, "com.example:leaf");
        assertEquals(version, embedded.getAsJsonObject("version").get("artifactVersion").getAsString());
        assertEquals("[" + version + ",)", embedded.getAsJsonObject("version").get("range").getAsString());
        assertTrue(entries(jar).contains("META-INF/jarjar/leaf-" + version + "-slim.jar"));
        JsonObject dependency = JsonParser.parseString(Files.readString(
                        project.resolve("build/foeniculum/publish/" + name + "-1.0-slim-metadata.json"),
                        StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonArray("dependencies").get(0).getAsJsonObject();
        assertEquals("[" + version + ",)", dependency.get("versionRange").getAsString());
        assertEquals(version, dependency.get("preferredVersion").getAsString());
    }
}
