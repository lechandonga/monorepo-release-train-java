package com.github.lechandonga.monoreporeleasetrainjava.releasetrain.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 参与发布列车的包定义：当前版本 + 对其它包的版本区间声明（保持声明顺序）。
 */
public record PackageDefinition(String name, String version, Map<String, String> dependencies) {

    public PackageDefinition {
        dependencies = dependencies == null ? Map.of() : new LinkedHashMap<>(dependencies);
    }

    public SemanticVersion semanticVersion() {
        return SemanticVersion.parse(version);
    }
}
