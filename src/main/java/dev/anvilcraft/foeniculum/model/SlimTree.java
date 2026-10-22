package dev.anvilcraft.foeniculum.model;

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
import java.util.List;

/**
 * 扁平化之后的瘦身依赖树，是解析任务与打包任务之间的中间产物。
 *
 * <p>以文件形式落盘而不是留在内存里，是为了让解析任务具备正常的输入输出契约：下载开销可以被跳过复用，
 * 打包任务只消费结果，不关心依赖是如何被发现的。
 *
 * @param modules 压平后每个模块唯一确定的内嵌构件
 */
public record SlimTree(List<SlimModule> modules) {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public SlimTree {
        modules = List.copyOf(modules);
    }

    public static SlimTree empty() {
        return new SlimTree(List.of());
    }

    public boolean isEmpty() {
        return modules.isEmpty();
    }

    /** 按模块标识查找已裁决的模块，用于把直接声明映射成内嵌后的实际情况。 */
    public SlimModule find(String moduleId) {
        for (SlimModule module : modules) {
            if (module.moduleId().equals(moduleId)) {
                return module;
            }
        }
        return null;
    }

    public static SlimTree read(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return read(reader);
        }
    }

    public static SlimTree read(Reader reader) {
        JsonElement root = JsonParser.parseReader(reader);
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("瘦身依赖树根节点必须是 JSON 对象");
        }
        JsonElement modulesElement = root.getAsJsonObject().get("modules");
        if (modulesElement == null || !modulesElement.isJsonArray()) {
            throw new IllegalArgumentException("瘦身依赖树缺少 modules 数组");
        }

        List<SlimModule> modules = new ArrayList<>();
        JsonArray array = modulesElement.getAsJsonArray();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("瘦身依赖树 modules 的元素必须是 JSON 对象");
            }
            JsonObject object = element.getAsJsonObject();
            modules.add(new SlimModule(
                    object.get("group").getAsString(),
                    object.get("artifact").getAsString(),
                    object.get("version").getAsString(),
                    object.get("versionRange").getAsString(),
                    object.get("isSlim").getAsBoolean(),
                    object.has("isObfuscated") && object.get("isObfuscated").getAsBoolean(),
                    object.get("sourcePath").getAsString(),
                    object.get("fileName").getAsString()));
        }
        return new SlimTree(modules);
    }

    public void write(Path file) throws IOException {
        JsonObject root = new JsonObject();
        JsonArray array = new JsonArray();
        for (SlimModule module : modules) {
            JsonObject object = new JsonObject();
            object.addProperty("group", module.group());
            object.addProperty("artifact", module.artifact());
            object.addProperty("version", module.version());
            object.addProperty("versionRange", module.versionRange());
            object.addProperty("isSlim", module.isSlim());
            object.addProperty("isObfuscated", module.isObfuscated());
            object.addProperty("sourcePath", module.sourcePath());
            object.addProperty("fileName", module.fileName());
            array.add(object);
        }
        root.add("modules", array);

        Files.createDirectories(file.getParent());
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write(GSON.toJson(root));
            writer.write('\n');
        }
    }
}
