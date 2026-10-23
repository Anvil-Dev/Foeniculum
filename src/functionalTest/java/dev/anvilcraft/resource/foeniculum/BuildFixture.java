package dev.anvilcraft.resource.foeniculum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.UnexpectedBuildFailure;

/**
 * 功能测试的公共支撑：搭建构建脚本、执行 Gradle 并读取归档内容。
 *
 * <p>测试直接运行真实的 Gradle 构建，而不是对插件内部方法做单元测试：
 * 任务接线、配置属性继承、发布挂载这些行为只有在完整构建中才会暴露问题。
 */
abstract class BuildFixture {
    protected Path projectDirectory(Path root) {
        return root.resolve("project");
    }

    protected void writeSettings(Path root, String content) throws IOException {
        Path directory = projectDirectory(root);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("settings.gradle"), content, StandardCharsets.UTF_8);
    }

    protected void writeBuildFile(Path root, String content) throws IOException {
        Path directory = projectDirectory(root);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("build.gradle"), content, StandardCharsets.UTF_8);
    }

    protected void writeFile(Path root, String relativePath, String content) throws IOException {
        Path file = projectDirectory(root).resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    protected BuildResult run(Path root, String... arguments) {
        List<String> all = new ArrayList<>(List.of(arguments));
        all.add("--stacktrace");
        return GradleRunner.create()
                .withProjectDir(projectDirectory(root).toFile())
                .withPluginClasspath()
                .withArguments(all)
                .forwardOutput()
                .build();
    }

    /** 执行一个预期失败的构建，返回结果以便断言失败原因。 */
    protected BuildResult runExpectingFailure(Path root, String... arguments) {
        try {
            return run(root, arguments);
        } catch (UnexpectedBuildFailure failure) {
            return failure.getBuildResult();
        }
    }

    /** 读取归档中所有条目名。 */
    protected static Set<String> entries(Path archive) throws IOException {
        Set<String> names = new TreeSet<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> iterator = zip.entries();
            while (iterator.hasMoreElements()) {
                names.add(iterator.nextElement().getName());
            }
        }
        return names;
    }

    /** 读取归档中的一个文本条目。 */
    protected static String entryText(Path archive, String name) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry entry = zip.getEntry(name);
            if (entry == null) {
                throw new AssertionError("归档 " + archive.getFileName() + " 中不存在条目 " + name
                        + "，实际条目：" + entries(archive));
            }
            try (Reader reader = new java.io.InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                StringBuilder text = new StringBuilder();
                char[] buffer = new char[4096];
                int read;
                while ((read = reader.read(buffer)) >= 0) {
                    text.append(buffer, 0, read);
                }
                return text.toString();
            }
        }
    }

    protected static JsonObject entryJson(Path archive, String name) throws IOException {
        return JsonParser.parseString(entryText(archive, name)).getAsJsonObject();
    }

    /** 断言归档中存在指定条目，并在缺失时给出全部条目以便定位。 */
    protected static void assertContains(Path archive, String name) throws IOException {
        assertTrue(entries(archive).contains(name),
                "归档 " + archive.getFileName() + " 缺少条目 " + name + "，实际条目：" + entries(archive));
    }

    /** 归档中 jarjar 元数据记录的条目，按记录顺序返回。 */
    protected static List<JsonObject> metadataJars(Path jar) throws IOException {
        List<JsonObject> jars = new ArrayList<>();
        for (var element : entryJson(jar, "META-INF/jarjar/metadata.json").getAsJsonArray("jars")) {
            jars.add(element.getAsJsonObject());
        }
        return jars;
    }

    /** 元数据条目的模块标识，即 {@code group:artifact}。 */
    protected static String moduleIdOf(JsonObject entry) {
        JsonObject identifier = entry.getAsJsonObject("identifier");
        return identifier.get("group").getAsString() + ":" + identifier.get("artifact").getAsString();
    }

    /** 主 jar 中 jarjar 元数据记录的模块标识，保持记录顺序。 */
    protected static List<String> metadataModuleIds(Path jar) throws IOException {
        List<String> ids = new ArrayList<>();
        for (JsonObject entry : metadataJars(jar)) {
            ids.add(moduleIdOf(entry));
        }
        return ids;
    }

    /** 元数据中模块标识到版本的映射，便于断言某个模块最终内嵌了哪个版本。 */
    protected static Map<String, String> metadataVersions(Path jar) throws IOException {
        Map<String, String> versions = new LinkedHashMap<>();
        for (JsonObject entry : metadataJars(jar)) {
            versions.put(
                    moduleIdOf(entry),
                    entry.getAsJsonObject("version").get("artifactVersion").getAsString());
        }
        return versions;
    }

    /** 取元数据中某个模块的条目；不存在时返回 {@code null}。 */
    protected static JsonObject metadataEntry(Path jar, String moduleId) throws IOException {
        for (JsonObject entry : metadataJars(jar)) {
            if (moduleIdOf(entry).equals(moduleId)) {
                return entry;
            }
        }
        return null;
    }

    /** 主 jar 中 jarjar 元数据记录的内嵌路径。 */
    protected static List<String> metadataPaths(Path jar) throws IOException {
        List<String> paths = new ArrayList<>();
        for (JsonObject entry : metadataJars(jar)) {
            paths.add(entry.get("path").getAsString());
        }
        return paths;
    }

    /** 归档中 {@code META-INF/jarjar/} 下的条目数量，用于确认没有重复写入。 */
    protected static long jarJarEntryCount(Path archive) throws IOException {
        return entries(archive).stream()
                .filter(name -> name.startsWith("META-INF/jarjar/") && !name.endsWith("/"))
                .count();
    }

    protected static void assertFileExists(Path file) {
        assertTrue(Files.isRegularFile(file), "期望文件存在：" + file);
    }
}
