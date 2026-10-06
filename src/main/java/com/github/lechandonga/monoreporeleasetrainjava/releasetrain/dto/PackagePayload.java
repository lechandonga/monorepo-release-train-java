package com.github.lechandonga.monoreporeleasetrainjava.releasetrain.dto;

import java.util.LinkedHashMap;
import java.util.Map;

/** 包定义的传输对象：依赖为 被依赖包名 -> 版本区间表达式。 */
public class PackagePayload {

    private String name;
    private String version;
    private Map<String, String> dependencies = new LinkedHashMap<>();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Map<String, String> getDependencies() {
        return dependencies;
    }

    public void setDependencies(Map<String, String> dependencies) {
        this.dependencies = dependencies == null ? new LinkedHashMap<>() : dependencies;
    }
}
