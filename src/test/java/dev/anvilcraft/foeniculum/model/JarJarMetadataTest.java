package dev.anvilcraft.foeniculum.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.StringReader;
import java.util.List;
import org.junit.jupiter.api.Test;

class JarJarMetadataTest {
    private static JarJarMetadata nativeMetadata() {
        String json = """
                {
                  "jars": [
                    {
                      "identifier": {"group": "com.native", "artifact": "legacy"},
                      "version": {"range": "[1.0.0,)", "artifactVersion": "1.0.0"},
                      "path": "META-INF/jarjar/legacy-1.0.0.jar",
                      "isObfuscated": false
                    }
                  ]
                }
                """;
        return JarJarMetadata.read(new StringReader(json));
    }

    private static SlimModule module(String group, String artifact, String version) {
        return new SlimModule(
                group,
                artifact,
                version,
                "[" + version + ",)",
                true,
                false,
                group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-" + version + "-slim.jar",
                artifact + "-" + version + ".jar");
    }

    private static JsonObject entry(String group, String artifact, String version) {
        SlimModule module = module(group, artifact, version);
        return JarJarMetadata.entryOf(module);
    }

    @Test
    void 原生条目在合并后保持原样且顺序不变() {
        JarJarMetadata nativeMetadata = nativeMetadata();
        JsonObject nativeEntry = nativeMetadata.jars().get(0);

        JarJarMetadata merged =
                JarJarMetadata.merge(nativeMetadata.jars(), List.of(entry("com.slim", "core", "2.0.0")));

        assertEquals(2, merged.jars().size());
        assertEquals(nativeEntry, merged.jars().get(0));

        JsonObject serialized = JsonParser.parseString(merged.toJsonString())
                .getAsJsonObject()
                .getAsJsonArray("jars")
                .get(0)
                .getAsJsonObject();
        assertEquals("com.native", serialized.getAsJsonObject("identifier").get("group").getAsString());
        assertEquals("[1.0.0,)", serialized.getAsJsonObject("version").get("range").getAsString());
        assertEquals("META-INF/jarjar/legacy-1.0.0.jar", serialized.get("path").getAsString());
    }

    @Test
    void 瘦身条目按模块标识排序追加在原生条目之后() {
        JarJarMetadata merged = JarJarMetadata.merge(
                nativeMetadata().jars(),
                List.of(
                        entry("com.slim", "zeta", "1.0.0"),
                        entry("com.slim", "alpha", "1.0.0"),
                        entry("com.another", "beta", "1.0.0")));

        assertEquals(
                List.of("com.native:legacy", "com.another:beta", "com.slim:alpha", "com.slim:zeta"),
                merged.jars().stream().map(JarJarMetadata::moduleIdOf).toList());
    }

    @Test
    void 同一模块同时存在于两侧时保留原生条目() {
        JarJarMetadata nativeMetadata = nativeMetadata();

        JarJarMetadata merged =
                JarJarMetadata.merge(nativeMetadata.jars(), List.of(entry("com.native", "legacy", "1.0.0")));

        assertEquals(1, merged.jars().size());
        assertEquals(nativeMetadata.jars().get(0), merged.jars().get(0));
    }

    @Test
    void 生成的条目符合FML字段结构() {
        JsonObject entry = entry("com.slim", "core", "2.0.0");

        assertEquals("com.slim", entry.getAsJsonObject("identifier").get("group").getAsString());
        assertEquals("core", entry.getAsJsonObject("identifier").get("artifact").getAsString());
        assertEquals("[2.0.0,)", entry.getAsJsonObject("version").get("range").getAsString());
        assertEquals("2.0.0", entry.getAsJsonObject("version").get("artifactVersion").getAsString());
        assertEquals("META-INF/jarjar/core-2.0.0.jar", entry.get("path").getAsString());
        assertEquals(false, entry.get("isObfuscated").getAsBoolean());
    }

    @Test
    void 内嵌文件名可被覆盖以避开命名冲突() {
        SlimModule module = module("com.slim", "core", "2.0.0");

        JsonObject entry = JarJarMetadata.entryOf(module.withFileName("com.slim-core-2.0.0.jar"));

        assertEquals("META-INF/jarjar/com.slim-core-2.0.0.jar", entry.get("path").getAsString());
        assertEquals("com.slim", entry.getAsJsonObject("identifier").get("group").getAsString());
    }

    @Test
    void 缺少jars数组时被拒绝() {
        assertThrows(IllegalArgumentException.class, () -> JarJarMetadata.read(new StringReader("{}")));
    }

    @Test
    void 条目缺少identifier时被拒绝并指出原因() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> JarJarMetadata.read(new StringReader(
                        "{\"jars\": [{\"path\": \"META-INF/jarjar/a.jar\"}]}")));

        assertTrue(error.getMessage().contains("identifier"), error.getMessage());
    }
}
