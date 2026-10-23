package dev.anvilcraft.resource.foeniculum.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NeoForge FML 的 Jar-in-Jar 元数据文件 {@code META-INF/jarjar/metadata.json}。
 *
 * <p>原生 {@code jarJar} 会生成一份这样的文件，茴香需要把压平后的瘦身条目合并进去，而不是另起一份，
 * 否则同一个 jar 里会出现两份元数据，FML 只会读取其中一份。
 *
 * <p>原生条目按原始 JSON 对象原样保留：字段顺序、取值、未知字段都不做改写，避免破坏 ModDevGradle 写下的内容。
 */
public record JarJarMetadata(List<JsonObject> jars) {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public JarJarMetadata {
        jars = List.copyOf(jars);
    }

    public static JarJarMetadata empty() {
        return new JarJarMetadata(List.of());
    }

    public static JarJarMetadata read(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return read(reader);
        }
    }

    public static JarJarMetadata read(Reader reader) {
        JsonElement root = JsonParser.parseReader(reader);
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("jarjar 元数据根节点必须是 JSON 对象");
        }
        JsonElement jarsElement = root.getAsJsonObject().get("jars");
        if (jarsElement == null || !jarsElement.isJsonArray()) {
            throw new IllegalArgumentException("jarjar 元数据缺少 jars 数组");
        }

        List<JsonObject> entries = new ArrayList<>();
        for (JsonElement element : jarsElement.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("jarjar 元数据 jars 的元素必须是 JSON 对象");
            }
            JsonObject entry = element.getAsJsonObject();
            // 读取时立即校验模块标识：格式错误的条目若带进产物，只会在运行时由 FML 报错，难以定位。
            moduleIdOf(entry);
            entries.add(entry);
        }
        return new JarJarMetadata(entries);
    }

    /** 每条原生条目对应的模块标识 {@code group:artifact}，用于与瘦身条目去重。 */
    public Map<String, JsonObject> byModuleId() {
        Map<String, JsonObject> result = new LinkedHashMap<>();
        for (JsonObject entry : jars) {
            result.put(moduleIdOf(entry), entry);
        }
        return result;
    }

    /**
     * 合并原生条目与瘦身条目：原生条目保持原顺序与原内容，瘦身条目追加在其后并按 {@code group:artifact} 排序。
     *
     * @param nativeEntries 原生 {@code jarJar} 生成的条目
     * @param slimEntries 瘦身依赖树生成的条目
     */
    public static JarJarMetadata merge(List<JsonObject> nativeEntries, List<JsonObject> slimEntries) {
        Map<String, JsonObject> merged = new LinkedHashMap<>();
        for (JsonObject entry : nativeEntries) {
            merged.put(moduleIdOf(entry), entry);
        }
        List<JsonObject> appended = new ArrayList<>();
        for (JsonObject entry : slimEntries) {
            String moduleId = moduleIdOf(entry);
            if (merged.containsKey(moduleId)) {
                continue;
            }
            appended.add(entry);
        }
        appended.sort(Comparator.comparing(JarJarMetadata::moduleIdOf));
        for (JsonObject entry : appended) {
            merged.put(moduleIdOf(entry), entry);
        }
        return new JarJarMetadata(new ArrayList<>(merged.values()));
    }

    /**
     * 为一个内嵌模块构造 FML 标准条目。
     *
     * @param module 扁平化后的模块，其文件名已是最终写入 {@code META-INF/jarjar/} 的名字
     */
    public static JsonObject entryOf(SlimModule module) {
        JsonObject identifier = new JsonObject();
        identifier.addProperty("group", module.group());
        identifier.addProperty("artifact", module.artifact());

        JsonObject version = new JsonObject();
        version.addProperty("range", module.versionRange());
        version.addProperty("artifactVersion", module.version());

        JsonObject entry = new JsonObject();
        entry.add("identifier", identifier);
        entry.add("version", version);
        entry.addProperty("path", module.embeddedPath());
        entry.addProperty("isObfuscated", module.isObfuscated());
        return entry;
    }

    /** 从条目中取出模块标识；缺少必要字段时抛出异常，避免把格式错误的数据带进产物。 */
    public static String moduleIdOf(JsonObject entry) {
        JsonObject identifier = entry.getAsJsonObject("identifier");
        if (identifier == null) {
            throw new IllegalArgumentException("jarjar 元数据条目缺少 identifier 字段");
        }
        JsonElement group = identifier.get("group");
        JsonElement artifact = identifier.get("artifact");
        if (group == null || group.isJsonNull() || artifact == null || artifact.isJsonNull()) {
            throw new IllegalArgumentException("jarjar 元数据条目的 identifier 缺少 group 或 artifact");
        }
        return group.getAsString() + ":" + artifact.getAsString();
    }

    public String toJsonString() {
        JsonObject root = new JsonObject();
        JsonArray array = new JsonArray();
        for (JsonObject entry : jars) {
            array.add(entry);
        }
        root.add("jars", array);
        return GSON.toJson(root) + "\n";
    }

    public void write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write(toJsonString());
        }
    }
}
