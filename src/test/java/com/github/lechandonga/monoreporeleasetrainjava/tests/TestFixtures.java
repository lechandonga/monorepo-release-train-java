package com.github.lechandonga.monoreporeleasetrainjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 测试辅助：注册包、提交批次的 JSON 请求构造与断言。
 */
final class TestFixtures {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private TestFixtures() {
    }

    static String registerBody(String name, String currentVersion,
                               Map<String, String> dependencies) {
        List<Map<String, String>> deps = dependencies.entrySet().stream()
                .map(e -> Map.of("name", e.getKey(), "versionRange", e.getValue()))
                .toList();
        return write(Map.of(
                "name", name,
                "currentVersion", currentVersion == null ? "" : currentVersion,
                "dependencies", deps));
    }

    static String registerBodyWithDuplicate(String name, String dep, String range) {
        return write(Map.of(
                "name", name,
                "currentVersion", "",
                "dependencies", List.of(
                        Map.of("name", dep, "versionRange", range),
                        Map.of("name", dep, "versionRange", range))));
    }

    static String registerBodyWithMissingDep(String name, String missingDep) {
        return write(Map.of(
                "name", name,
                "currentVersion", "",
                "dependencies", List.of(Map.of("name", missingDep, "versionRange", "*"))));
    }

    static String releaseBody(String batchId, Map<String, String> changes) {
        return write(Map.of("batchId", batchId, "changes", changes));
    }

    static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static JsonNode item(JsonNode batch, String packageName) {
        for (JsonNode item : batch.get("items")) {
            if (item.get("packageName").asText().equals(packageName)) {
                return item;
            }
        }
        throw new AssertionError("批次中不存在包: " + packageName);
    }

    static void assertTarget(JsonNode batch, String packageName, String expectedVersion) {
        JsonNode item = item(batch, packageName);
        assertEquals(expectedVersion, item.get("targetVersion").asText(),
                packageName + " 的目标版本不正确");
        assertEquals("RELEASED", item.get("status").asText(),
                packageName + " 应当发布成功");
    }
}
