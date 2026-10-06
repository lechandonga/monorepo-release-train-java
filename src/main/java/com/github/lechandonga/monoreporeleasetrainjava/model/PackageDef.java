package com.github.lechandonga.monoreporeleasetrainjava.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 包定义：名称、当前已发布版本（没有则为 null），以及 name -> 要求区间 的依赖。
 */
public final class PackageDef {
    private final String name;
    private Version currentVersion;
    private final Map<String, String> dependencies = new LinkedHashMap<>();

    public PackageDef(String name, Version currentVersion) {
        this.name = name;
        this.currentVersion = currentVersion;
    }

    public String name() { return name; }
    public Version currentVersion() { return currentVersion; }
    public void currentVersion(Version v) { this.currentVersion = v; }
    public Map<String, String> dependencies() { return dependencies; }
}
