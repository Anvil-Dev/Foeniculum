package dev.anvilcraft.foeniculum.fixture;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 功能测试用的本地 Maven 仓库夹具。
 *
 * <p>端到端测试需要真实的仓库内容：瘦身包、普通 jar 与描述文件都必须按 Maven 的目录结构放在磁盘上，
 * 插件才会像访问远程仓库一样通过 {@code file:} 协议把它们读出来。用内存假实现替代会绕开真正的读取路径，
 * 让仓库地址拼接、协议处理与描述文件解析都得不到验证。
 */
public final class LocalMavenRepository {
    private final Path root;
    private final Map<String, TreeSet<String>> versionsByModule = new LinkedHashMap<>();

    public LocalMavenRepository(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    /** 可直接写进构建脚本的仓库地址。 */
    public String url() {
        String normalized = root.toAbsolutePath().toString().replace('\\', '/');
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        return "file://" + normalized;
    }

    /**
     * 发布一个模块，包括 jar、可选的瘦身包、可选的描述文件、POM 与版本列表。
     *
     * @return 该模块在仓库中的版本目录
     */
    public Path publish(Module module) throws IOException {
        Path directory = moduleDirectory(module.group(), module.artifact()).resolve(module.version());
        Files.createDirectories(directory);

        writeJar(directory.resolve(module.artifact() + "-" + module.version() + ".jar"), module.jarEntries());
        if (module.slim()) {
            writeJar(
                    directory.resolve(module.artifact() + "-" + module.version() + "-slim.jar"),
                    module.slimJarEntries());
        }
        if (module.hasMetadata()) {
            Files.writeString(
                    directory.resolve(module.artifact() + "-" + module.version() + "-slim-metadata.json"),
                    module.metadataJson(),
                    StandardCharsets.UTF_8);
        }

        writePom(directory, module);
        versionsByModule
                .computeIfAbsent(module.group() + ":" + module.artifact(), key -> new TreeSet<>())
                .add(module.version());
        writeVersionList(module.group(), module.artifact());
        return directory;
    }

    private Path moduleDirectory(String group, String artifact) {
        return root.resolve(group.replace('.', '/')).resolve(artifact);
    }

    private void writeVersionList(String group, String artifact) throws IOException {
        StringBuilder versions = new StringBuilder();
        for (String version : versionsByModule.get(group + ":" + artifact)) {
            versions.append("      <version>").append(version).append("</version>\n");
        }

        String content = """
                <?xml version="1.0" encoding="UTF-8"?>
                <metadata>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <versioning>
                    <versions>
                %s    </versions>
                  </versioning>
                </metadata>
                """.formatted(group, artifact, versions);
        Files.writeString(
                moduleDirectory(group, artifact).resolve("maven-metadata.xml"), content, StandardCharsets.UTF_8);
    }

    private static void writeJar(Path file, Map<String, String> entries) throws IOException {
        try (OutputStream out = Files.newOutputStream(file);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    private static void writePom(Path directory, Module module) throws IOException {
        String content = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <packaging>jar</packaging>
                </project>
                """.formatted(module.group(), module.artifact(), module.version());
        Files.writeString(
                directory.resolve(module.artifact() + "-" + module.version() + ".pom"),
                content,
                StandardCharsets.UTF_8);
    }

    /** 一个待发布到本地仓库的模块。 */
    public record Module(
            String group,
            String artifact,
            String version,
            boolean slim,
            Map<String, String> jarEntries,
            Map<String, String> slimJarEntries,
            List<Dependency> dependencies,
            List<String> repositories) {
        public static Builder builder(String group, String artifact, String version) {
            return new Builder(group, artifact, version);
        }

        boolean hasMetadata() {
            return dependencies != null;
        }

        String metadataJson() {
            StringBuilder json = new StringBuilder("{\n  \"schemaVersion\": 1,\n  \"repositories\": [");
            List<String> declaredRepositories = repositories == null ? List.of() : repositories;
            for (int i = 0; i < declaredRepositories.size(); i++) {
                json.append(i == 0 ? "" : ", ").append('"').append(declaredRepositories.get(i)).append('"');
            }
            json.append("],\n  \"dependencies\": [");

            List<Dependency> declared = dependencies == null ? List.of() : dependencies;
            for (int i = 0; i < declared.size(); i++) {
                Dependency dependency = declared.get(i);
                json.append(i > 0 ? "," : "").append("\n    {\"group\": \"")
                        .append(dependency.group())
                        .append("\", \"artifact\": \"").append(dependency.artifact())
                        .append("\", \"versionRange\": \"").append(dependency.versionRange())
                        .append("\", \"preferredVersion\": \"").append(dependency.preferredVersion())
                        .append("\", \"isSlim\": ").append(dependency.slim())
                        .append('}');
            }
            return json.append("\n  ]\n}\n").toString();
        }

        /** 模块构建器，让测试数据保持可读。 */
        public static final class Builder {
            private final String group;
            private final String artifact;
            private final String version;
            private boolean slim = true;
            private final Map<String, String> jarEntries = new LinkedHashMap<>();
            private Map<String, String> slimJarEntries;
            private List<Dependency> dependencies;
            private List<String> repositories = List.of();

            private Builder(String group, String artifact, String version) {
                this.group = group;
                this.artifact = artifact;
                this.version = version;
                this.jarEntries.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n");
                this.jarEntries.put(artifact + "/" + artifact + ".class", "class");
            }

            public Builder slim(boolean value) {
                this.slim = value;
                return this;
            }

            public Builder obfuscated() {
                jarEntries.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nObfuscated-By: test\n");
                return this;
            }

            public Builder entry(String name, String content) {
                jarEntries.put(name, content);
                return this;
            }

            public Builder slimEntry(String name, String content) {
                if (slimJarEntries == null) {
                    slimJarEntries = new LinkedHashMap<>();
                }
                slimJarEntries.put(name, content);
                return this;
            }

            public Builder dependencies(Dependency... values) {
                this.dependencies = new ArrayList<>(List.of(values));
                return this;
            }

            public Builder repositories(String... values) {
                this.repositories = List.of(values);
                return this;
            }

            public Module build() {
                Map<String, String> slimEntries = slimJarEntries == null
                        ? Map.of("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
                        : Map.copyOf(slimJarEntries);
                return new Module(
                        group,
                        artifact,
                        version,
                        slim,
                        Map.copyOf(jarEntries),
                        slimEntries,
                        dependencies == null ? null : List.copyOf(dependencies),
                        repositories);
            }
        }
    }

    /** 模块对外声明的一条依赖。 */
    public record Dependency(
            String group, String artifact, String versionRange, String preferredVersion, boolean slim) {
        public static Dependency of(String group, String artifact, String versionRange, String preferredVersion) {
            return new Dependency(group, artifact, versionRange, preferredVersion, true);
        }
    }
}
