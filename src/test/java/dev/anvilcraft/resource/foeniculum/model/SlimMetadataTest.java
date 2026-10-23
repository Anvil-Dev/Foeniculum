package dev.anvilcraft.resource.foeniculum.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.StringReader;
import java.util.List;
import org.junit.jupiter.api.Test;

class SlimMetadataTest {
    @Test
    void 写入后读回得到相同内容() {
        SlimMetadata original = SlimMetadata.of(
                List.of("https://repo1.maven.org/maven2"),
                List.of(new SlimDependency("com.example", "core", "[2.0.0,3.0.0)", "2.4.1", true)));

        SlimMetadata restored = SlimMetadata.read(new StringReader(original.toJsonString()));

        assertEquals(original, restored);
    }

    @Test
    void 未知字段被忽略() {
        String json = """
                {
                  "schemaVersion": 1,
                  "repositories": [],
                  "dependencies": [
                    {"group": "a", "artifact": "b", "versionRange": "[1.0,)", "isSlim": false, "extra": 1}
                  ],
                  "futureField": {"x": 1}
                }
                """;

        SlimMetadata metadata = SlimMetadata.read(new StringReader(json));

        assertEquals(1, metadata.dependencies().size());
        assertFalse(metadata.dependencies().get(0).isSlim());
    }

    @Test
    void 缺少依赖数组时被拒绝() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SlimMetadata.read(new StringReader("{\"schemaVersion\": 1}")));
    }

    @Test
    void 更高的schema版本被拒绝并提示升级() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> SlimMetadata.read(new StringReader(
                        "{\"schemaVersion\": 2, \"repositories\": [], \"dependencies\": []}")));

        assertTrue(error.getMessage().contains("升级"), error.getMessage());
    }

    @Test
    void 依赖缺少必要字段时被拒绝() {
        String json = """
                {"schemaVersion": 1, "repositories": [], "dependencies": [{"group": "a", "artifact": "b"}]}
                """;

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> SlimMetadata.read(new StringReader(json)));

        assertTrue(error.getMessage().contains("versionRange"), error.getMessage());
    }

    @Test
    void 未指定isSlim时默认为true() {
        String json = """
                {"schemaVersion": 1, "repositories": [],
                 "dependencies": [{"group": "a", "artifact": "b", "versionRange": "[1.0,)"}]}
                """;

        assertTrue(SlimMetadata.read(new StringReader(json)).dependencies().get(0).isSlim());
    }

    @Test
    void 缺少preferredVersion时不写出该字段() {
        SlimMetadata metadata = SlimMetadata.of(
                List.of(), List.of(new SlimDependency("a", "b", "[1.0,)", null, true)));

        JsonObject json = JsonParser.parseString(metadata.toJsonString()).getAsJsonObject();
        JsonObject dependency = json.getAsJsonArray("dependencies").get(0).getAsJsonObject();

        assertFalse(dependency.has("preferredVersion"));
    }
}
