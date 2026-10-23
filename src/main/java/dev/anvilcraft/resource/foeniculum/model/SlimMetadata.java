package dev.anvilcraft.resource.foeniculum.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.anvilcraft.resource.foeniculum.version.VersionRanges;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 独立的瘦身依赖描述文件 {@code -slim-metadata.json}。
 *
 * <p>该文件与瘦身包分离发布，记录当前项目通过 {@code slimJarJar} 直接声明的依赖，供下游项目递归解析整棵瘦身依赖树。
 * 它不描述原生 {@code jarJar} 依赖：原生内嵌内容始终留在瘦身包内部，不需要额外描述。
 */
public record SlimMetadata(int schemaVersion, List<String> repositories, List<SlimDependency> dependencies) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public SlimMetadata {
        repositories = List.copyOf(repositories);
        dependencies = List.copyOf(dependencies);
    }

    public static SlimMetadata of(List<String> repositories, List<SlimDependency> dependencies) {
        return new SlimMetadata(CURRENT_SCHEMA_VERSION, repositories, dependencies);
    }

    public static SlimMetadata read(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return read(reader);
        }
    }

    /** 读取描述文件；未知字段会被忽略，必要字段缺失或非法时抛出 {@link IllegalArgumentException}。 */
    public static SlimMetadata read(Reader reader) {
        JsonElement root = JsonParser.parseReader(reader);
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("描述文件根节点必须是 JSON 对象");
        }
        JsonObject object = root.getAsJsonObject();

        int schemaVersion = object.has("schemaVersion") ? object.get("schemaVersion").getAsInt() : CURRENT_SCHEMA_VERSION;
        if (schemaVersion > CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("描述文件的 schemaVersion 为 " + schemaVersion
                    + "，当前插件最高支持 " + CURRENT_SCHEMA_VERSION + "，请升级茴香插件");
        }

        List<String> repositories = new ArrayList<>();
        if (object.has("repositories")) {
            JsonElement element = object.get("repositories");
            if (!element.isJsonArray()) {
                throw new IllegalArgumentException("描述文件的 repositories 字段必须是数组");
            }
            JsonArray array = element.getAsJsonArray();
            for (JsonElement entry : array) {
                repositories.add(entry.getAsString());
            }
        }

        if (!object.has("dependencies") || !object.get("dependencies").isJsonArray()) {
            throw new IllegalArgumentException("描述文件缺少 dependencies 数组");
        }

        List<SlimDependency> dependencies = new ArrayList<>();
        for (JsonElement entry : object.getAsJsonArray("dependencies")) {
            dependencies.add(readDependency(entry));
        }

        return new SlimMetadata(schemaVersion, repositories, dependencies);
    }

    private static SlimDependency readDependency(JsonElement element) {
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("描述文件 dependencies 的元素必须是 JSON 对象");
        }
        JsonObject object = element.getAsJsonObject();

        String group = requiredString(object, "group");
        String artifact = requiredString(object, "artifact");
        String versionRange = requiredString(object, "versionRange");
        // 版本写法在此处就校验，避免非法数据在后续裁决阶段才暴露。
        VersionRanges.parse(versionRange, "描述文件依赖 " + group + ":" + artifact);

        String preferredVersion = object.has("preferredVersion") && !object.get("preferredVersion").isJsonNull()
                ? object.get("preferredVersion").getAsString()
                : null;
        boolean isSlim = !object.has("isSlim") || object.get("isSlim").getAsBoolean();

        return new SlimDependency(group, artifact, versionRange, preferredVersion, isSlim);
    }

    private static String requiredString(JsonObject object, String name) {
        if (!object.has(name) || object.get(name).isJsonNull()) {
            throw new IllegalArgumentException("描述文件依赖缺少字段 " + name);
        }
        return object.get(name).getAsString();
    }

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", schemaVersion);

        JsonArray repositoryArray = new JsonArray();
        for (String repository : repositories) {
            repositoryArray.add(repository);
        }
        root.add("repositories", repositoryArray);

        JsonArray dependencyArray = new JsonArray();
        for (SlimDependency dependency : dependencies) {
            JsonObject entry = new JsonObject();
            entry.addProperty("group", dependency.group());
            entry.addProperty("artifact", dependency.artifact());
            entry.addProperty("versionRange", dependency.versionRange());
            if (dependency.preferredVersion() != null) {
                entry.addProperty("preferredVersion", dependency.preferredVersion());
            }
            entry.addProperty("isSlim", dependency.isSlim());
            dependencyArray.add(entry);
        }
        root.add("dependencies", dependencyArray);
        return root;
    }

    public String toJsonString() {
        return GSON.toJson(toJson()) + "\n";
    }

    public void write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write(toJsonString());
        }
    }
}
