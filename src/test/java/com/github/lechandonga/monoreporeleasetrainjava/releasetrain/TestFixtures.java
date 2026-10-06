package com.github.lechandonga.monoreporeleasetrainjava.releasetrain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.core.JsonParser;
import com.github.lechandonga.monoreporeleasetrainjava.releasetrain.dto.PackagePayload;
import com.github.lechandonga.monoreporeleasetrainjava.releasetrain.dto.ReleaseRequest;

import java.util.LinkedHashMap;
import java.util.Map;

/** 测试用请求构造工具。 */
public final class TestFixtures {

    private TestFixtures() {
    }

    public static ObjectMapper objectMapper() {
        return JsonMapper.builder()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .findAndAddModules()
                .build();
    }

    public static PackagePayload pkg(String name, String version, Map<String, String> deps) {
        PackagePayload payload = new PackagePayload();
        payload.setName(name);
        payload.setVersion(version);
        payload.setDependencies(deps == null ? new LinkedHashMap<>() : new LinkedHashMap<>(deps));
        return payload;
    }

    public static Map<String, String> deps(Object... kv) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], (String) kv[i + 1]);
        }
        return map;
    }

    public static ReleaseRequest request(String batchId,
                                         java.util.List<PackagePayload> packages,
                                         String... changeKv) {
        ReleaseRequest request = new ReleaseRequest();
        request.setBatchId(batchId);
        request.setPackages(packages);
        java.util.List<ReleaseRequest.Change> changes = new java.util.ArrayList<>();
        for (int i = 0; i < changeKv.length; i += 2) {
            ReleaseRequest.Change change = new ReleaseRequest.Change();
            change.setPackageName(changeKv[i]);
            change.setLevel(changeKv[i + 1]);
            changes.add(change);
        }
        request.setChanges(changes);
        return request;
    }
}
