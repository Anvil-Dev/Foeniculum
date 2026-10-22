package dev.anvilcraft.foeniculum.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.anvilcraft.foeniculum.model.SlimDeclaration;
import dev.anvilcraft.foeniculum.model.SlimDependency;
import dev.anvilcraft.foeniculum.model.SlimMetadata;
import dev.anvilcraft.foeniculum.model.SlimModule;
import dev.anvilcraft.foeniculum.model.SlimTree;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SlimTreeResolverTest {
    private Path downloadDirectory;
    private FakeSource source;

    @BeforeEach
    void setUp(@TempDir Path temp) {
        downloadDirectory = temp.resolve("artifacts");
        source = new FakeSource(temp.resolve("repository"));
    }

    @Test
    void 没有声明时不产生任何模块() {
        SlimResolution resolution =
                new SlimTreeResolver(source, java.util.Set.of()).resolve(List.of(), downloadDirectory);

        assertTrue(resolution.tree().isEmpty());
        assertTrue(resolution.warnings().isEmpty());
    }

    @Test
    void 没有描述文件的依赖作为叶子被内嵌() {
        source.jar("com.example", "leaf", "1.0.0");

        SlimTree tree = resolve(declaration("com.example", "leaf", "1.0.0"));

        assertEquals(List.of("com.example:leaf"), moduleIds(tree));
        assertEquals("1.0.0", tree.find("com.example:leaf").version());
    }

    @Test
    void 三级依赖树被压平成一层() {
        source.metadata("com.example", "top", "1.0.0", List.of(slimDependency("com.example", "mid", "[1.0,)", "1.0.0")));
        source.metadata("com.example", "mid", "1.0.0", List.of(slimDependency("com.example", "leaf", "[1.0,)", "1.0.0")));
        source.jar("com.example", "top", "1.0.0");
        source.jar("com.example", "mid", "1.0.0");
        source.jar("com.example", "leaf", "1.0.0");

        SlimTree tree = resolve(declaration("com.example", "top", "1.0.0"));

        assertEquals(List.of("com.example:leaf", "com.example:mid", "com.example:top"), moduleIds(tree));
    }

    @Test
    void 同一模块在多个分支出现时只保留一个版本() {
        source.metadata("com.example", "a", "1.0.0", List.of(slimDependency("com.example", "shared", "[1.0,2.0)", "1.2.0")));
        source.metadata("com.example", "b", "1.0.0", List.of(slimDependency("com.example", "shared", "[1.0,2.0)", "1.5.0")));
        source.jar("com.example", "a", "1.0.0");
        source.jar("com.example", "b", "1.0.0");
        source.jar("com.example", "shared", "1.5.0");

        SlimTree tree = resolve(
                declaration("com.example", "a", "1.0.0"),
                declaration("com.example", "b", "1.0.0"));

        assertEquals(List.of("com.example:a", "com.example:b", "com.example:shared"), moduleIds(tree));
        assertEquals("1.5.0", tree.find("com.example:shared").version());
        assertEquals("[1.0,2.0)", tree.find("com.example:shared").versionRange());
    }

    @Test
    void 区间无交集时抛出带来源链的异常() {
        source.metadata("com.example", "a", "1.0.0", List.of(slimDependency("com.example", "shared", "[1.0,2.0)", "1.5.0")));
        source.metadata("com.example", "b", "1.0.0", List.of(slimDependency("com.example", "shared", "[2.0,3.0)", "2.5.0")));
        source.jar("com.example", "a", "1.0.0");
        source.jar("com.example", "b", "1.0.0");

        ModuleConflictException error = assertThrows(
                ModuleConflictException.class,
                () -> resolve(declaration("com.example", "a", "1.0.0"), declaration("com.example", "b", "1.0.0")));

        assertTrue(error.getMessage().contains("com.example:shared"), error.getMessage());
        assertTrue(error.getMessage().contains("[1.0,2.0)"), error.getMessage());
        assertTrue(error.getMessage().contains("[2.0,3.0)"), error.getMessage());
    }

    @Test
    void 分支推荐版本不兼容但仓库存在兼容版本时回落到仓库版本() {
        source.metadata("com.example", "a", "1.0.0", List.of(slimDependency("com.example", "shared", "[1.0,3.0)", "1.0.0")));
        source.metadata("com.example", "b", "1.0.0", List.of(slimDependency("com.example", "shared", "[2.0,3.0)", "1.0.0")));
        source.versions("com.example", "shared", List.of("1.0.0", "2.0.0", "2.4.0"));
        source.jar("com.example", "a", "1.0.0");
        source.jar("com.example", "b", "1.0.0");
        source.jar("com.example", "shared", "2.4.0");

        SlimResolution resolution = new SlimTreeResolver(source, java.util.Set.of())
                .resolve(
                        List.of(declaration("com.example", "a", "1.0.0"), declaration("com.example", "b", "1.0.0")),
                        downloadDirectory);

        assertEquals("2.4.0", resolution.tree().find("com.example:shared").version());
        assertTrue(
                resolution.warnings().stream().anyMatch(warning -> warning.contains("com.example:shared")),
                resolution.warnings().toString());
    }

    @Test
    void 已淘汰版本声明的约束不会残留在最终结果中() {
        // middle 1.0.0 指向 leaf 1.x，middle 2.0.0 指向 leaf 2.x。
        // 另一个分支把 middle 抬高到 2.0.0，因此 1.0.0 的约束必须被丢弃，
        // 否则 leaf 会被两条互斥的区间同时约束而误判为冲突。
        source.metadata("com.example", "middle", "1.0.0", List.of(slimDependency("com.example", "leaf", "[1.0,2.0)", "1.0.0")));
        source.metadata("com.example", "middle", "2.0.0", List.of(slimDependency("com.example", "leaf", "[2.0,3.0)", "2.0.0")));
        source.metadata("com.example", "forcer", "1.0.0", List.of(slimDependency("com.example", "middle", "[2.0,3.0)", "2.0.0")));
        source.jar("com.example", "middle", "1.0.0");
        source.jar("com.example", "middle", "2.0.0");
        source.jar("com.example", "forcer", "1.0.0");
        source.jar("com.example", "leaf", "2.0.0");

        SlimTree tree = resolve(
                declaration("com.example", "middle", "1.0.0"),
                declaration("com.example", "forcer", "1.0.0"));

        assertEquals("2.0.0", tree.find("com.example:middle").version());
        assertEquals("2.0.0", tree.find("com.example:leaf").version());
        assertEquals("[2.0,3.0)", tree.find("com.example:leaf").versionRange());
    }

    @Test
    void 循环依赖被截断而不会无限递归() {
        source.metadata("com.example", "a", "1.0.0", List.of(slimDependency("com.example", "b", "[1.0,)", "1.0.0")));
        source.metadata("com.example", "b", "1.0.0", List.of(slimDependency("com.example", "a", "[1.0,)", "1.0.0")));
        source.jar("com.example", "a", "1.0.0");
        source.jar("com.example", "b", "1.0.0");

        SlimTree tree = resolve(declaration("com.example", "a", "1.0.0"));

        assertEquals(List.of("com.example:a", "com.example:b"), moduleIds(tree));
    }

    @Test
    void 缺少瘦身包时回退普通jar并给出警告() {
        source.jar("com.example", "leaf", "1.0.0");

        SlimResolution resolution = new SlimTreeResolver(source, java.util.Set.of())
                .resolve(List.of(declaration("com.example", "leaf", "1.0.0")), downloadDirectory);

        SlimModule module = resolution.tree().find("com.example:leaf");
        assertEquals(false, module.isSlim());
        assertTrue(
                resolution.warnings().stream().anyMatch(warning -> warning.contains("没有发布瘦身包")),
                resolution.warnings().toString());
    }

    @Test
    void 瘦身包存在时被优先选用() {
        source.slimJar("com.example", "leaf", "1.0.0");

        SlimTree tree = resolve(declaration("com.example", "leaf", "1.0.0"));

        assertEquals(true, tree.find("com.example:leaf").isSlim());
        assertEquals(
                "com/example/leaf/1.0.0/leaf-1.0.0-slim.jar", tree.find("com.example:leaf").sourcePath());
    }

    @Test
    void 描述文件引用未声明仓库时给出警告() {
        source.metadataWithRepositories(
                "com.example",
                "top",
                "1.0.0",
                List.of("https://unknown.example.com/releases"),
                List.of(slimDependency("com.example", "leaf", "[1.0,)", "1.0.0")));
        source.jar("com.example", "top", "1.0.0");
        source.jar("com.example", "leaf", "1.0.0");

        SlimResolution resolution = new SlimTreeResolver(source, java.util.Set.of("https://repo1.maven.org/maven2"))
                .resolve(List.of(declaration("com.example", "top", "1.0.0")), downloadDirectory);

        assertTrue(
                resolution.warnings().stream()
                        .anyMatch(warning -> warning.contains("https://unknown.example.com/releases")),
                resolution.warnings().toString());
    }

    @Test
    void 已声明的仓库不产生警告() {
        source.metadataWithRepositories(
                "com.example",
                "top",
                "1.0.0",
                List.of("https://repo1.maven.org/maven2/"),
                List.of(slimDependency("com.example", "leaf", "[1.0,)", "1.0.0")));
        source.slimJar("com.example", "top", "1.0.0");
        source.slimJar("com.example", "leaf", "1.0.0");

        SlimResolution resolution =
                new SlimTreeResolver(source, java.util.Set.of("https://repo1.maven.org/maven2"))
                        .resolve(List.of(declaration("com.example", "top", "1.0.0")), downloadDirectory);

        assertTrue(resolution.warnings().isEmpty(), resolution.warnings().toString());
    }

    @Test
    void 构件缺失时抛出异常而不是静默跳过() {
        assertThrows(
                ModuleConflictException.class,
                () -> new SlimTreeResolver(new FakeSource(downloadDirectory), java.util.Set.of())
                        .resolve(List.of(declaration("com.example", "missing", "1.0.0")), downloadDirectory));
    }

    private SlimTree resolve(SlimDeclaration... declarations) {
        return new SlimTreeResolver(source, java.util.Set.of())
                .resolve(List.of(declarations), downloadDirectory)
                .tree();
    }

    private static List<String> moduleIds(SlimTree tree) {
        return tree.modules().stream().map(SlimModule::moduleId).toList();
    }

    private static SlimDeclaration declaration(String group, String artifact, String version) {
        return new SlimDeclaration(group, artifact, version, version);
    }

    private static SlimDependency slimDependency(
            String group, String artifact, String versionRange, String preferredVersion) {
        return new SlimDependency(group, artifact, versionRange, preferredVersion, true);
    }

    /** 用文件系统模拟一个 Maven 仓库，只实现解析逻辑用到的读取动作。 */
    private static final class FakeSource implements SlimArtifactSource {
        private final Path repository;

        FakeSource(Path repository) {
            this.repository = repository;
        }

        @Override
        public SlimMetadata findMetadata(String group, String artifact, String version) {
            Path file = metadataPath(group, artifact, version);
            if (!Files.isRegularFile(file)) {
                return null;
            }
            try {
                return SlimMetadata.read(file);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public List<String> findVersions(String group, String artifact) {
            return versions.getOrDefault(group + ":" + artifact, List.of());
        }

        @Override
        public boolean download(String group, String artifact, String version, String classifier, Path target) {
            Path file = artifactPath(group, artifact, version, classifier);
            if (!Files.isRegularFile(file)) {
                return false;
            }
            try {
                Files.createDirectories(target.getParent());
                Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        private final Map<String, List<String>> versions = new HashMap<>();

        private Path metadataPath(String group, String artifact, String version) {
            return repository.resolve(group.replace('.', '/'))
                    .resolve(artifact)
                    .resolve(version)
                    .resolve(artifact + "-" + version + "-slim-metadata.json");
        }

        private Path artifactPath(String group, String artifact, String version, String classifier) {
            String fileName = classifier == null
                    ? artifact + "-" + version + ".jar"
                    : artifact + "-" + version + "-" + classifier + ".jar";
            return repository.resolve(group.replace('.', '/'))
                    .resolve(artifact)
                    .resolve(version)
                    .resolve(fileName);
        }

        void metadata(
                String group, String artifact, String version, List<SlimDependency> dependencies) {
            metadataWithRepositories(group, artifact, version, List.of(), dependencies);
        }

        void metadataWithRepositories(
                String group,
                String artifact,
                String version,
                List<String> repositories,
                List<SlimDependency> dependencies) {
            Path file = metadataPath(group, artifact, version);
            try {
                Files.createDirectories(file.getParent());
                SlimMetadata.of(repositories, dependencies).write(file);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        void versions(String group, String artifact, List<String> values) {
            versions.put(group + ":" + artifact, values);
        }

        void jar(String group, String artifact, String version) {
            Path file = artifactPath(group, artifact, version, null);
            try {
                Files.createDirectories(file.getParent());
                try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
                    out.putNextEntry(new ZipEntry(artifact + ".class"));
                    out.write(new byte[] {0x00});
                    out.closeEntry();
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        void slimJar(String group, String artifact, String version) {
            jar(group, artifact, version);
            Path standard = artifactPath(group, artifact, version, null);
            Path slim = artifactPath(group, artifact, version, "slim");
            try {
                Files.copy(standard, slim, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
