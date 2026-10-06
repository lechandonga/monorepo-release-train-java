package com.github.lechandonga.monoreporeleasetrainjava.releasetrain.service;

import com.github.lechandonga.monoreporeleasetrainjava.releasetrain.exception.ReleaseErrorCode;
import com.github.lechandonga.monoreporeleasetrainjava.releasetrain.exception.ReleaseException;
import com.github.lechandonga.monoreporeleasetrainjava.releasetrain.model.PackageDefinition;
import com.github.lechandonga.monoreporeleasetrainjava.releasetrain.model.VersionRange;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 依赖图：构建前完成全部静态校验（重复声明/未知依赖/循环依赖），
 * 并给出按依赖方向（被依赖者在前）的拓扑顺序。
 */
public final class DependencyGraph {

    private final Map<String, PackageDefinition> packages;
    private final List<String> topologicalOrder;

    private DependencyGraph(Map<String, PackageDefinition> packages, List<String> topologicalOrder) {
        this.packages = packages;
        this.topologicalOrder = topologicalOrder;
    }

    public static DependencyGraph build(List<PackageDefinition> definitions) {
        Map<String, PackageDefinition> packages = new LinkedHashMap<>();
        for (PackageDefinition definition : definitions) {
            if (definition.name() == null || definition.name().isBlank()) {
                throw new ReleaseException(ReleaseErrorCode.INVALID_VERSION, "存在未命名的包");
            }
            if (packages.putIfAbsent(definition.name(), definition) != null) {
                throw new ReleaseException(ReleaseErrorCode.DUPLICATE_PACKAGE,
                        "包被重复声明: " + definition.name());
            }
        }
        for (PackageDefinition definition : packages.values()) {
            for (String dependency : definition.dependencies().keySet()) {
                if (!packages.containsKey(dependency)) {
                    throw new ReleaseException(ReleaseErrorCode.UNKNOWN_DEPENDENCY,
                            "包 " + definition.name() + " 依赖了不存在的包: " + dependency);
                }
            }
            try {
                definition.semanticVersion();
            } catch (IllegalArgumentException ex) {
                throw new ReleaseException(ReleaseErrorCode.INVALID_VERSION,
                        "包 " + definition.name() + " 的当前版本非法: " + ex.getMessage());
            }
            definition.dependencies().forEach((name, range) -> {
                try {
                    VersionRange.of(range);
                } catch (IllegalArgumentException ex) {
                    throw new ReleaseException(ReleaseErrorCode.INVALID_VERSION,
                            "包 " + definition.name() + " 对 " + name
                                    + " 的版本区间非法: " + ex.getMessage());
                }
            });
        }
        List<String> order = topologicalSort(packages);
        return new DependencyGraph(packages, order);
    }

    private static List<String> topologicalSort(Map<String, PackageDefinition> packages) {
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> dependents = new LinkedHashMap<>();
        packages.keySet().forEach(name -> {
            indegree.put(name, 0);
            dependents.put(name, new ArrayList<>());
        });
        for (PackageDefinition definition : packages.values()) {
            for (String dependency : definition.dependencies().keySet()) {
                indegree.merge(definition.name(), 1, Integer::sum);
                dependents.get(dependency).add(definition.name());
            }
        }
        Deque<String> queue = new ArrayDeque<>();
        indegree.forEach((name, degree) -> {
            if (degree == 0) {
                queue.add(name);
            }
        });
        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            String name = queue.poll();
            order.add(name);
            for (String dependent : dependents.get(name)) {
                if (indegree.merge(dependent, -1, Integer::sum) == 0) {
                    queue.add(dependent);
                }
            }
        }
        if (order.size() != packages.size()) {
            Set<String> remaining = new HashSet<>(packages.keySet());
            remaining.removeAll(order);
            throw new ReleaseException(ReleaseErrorCode.CYCLIC_DEPENDENCY,
                    "检测到循环依赖，涉及包: " + String.join(", ", sorted(remaining)));
        }
        return order;
    }

    private static List<String> sorted(Set<String> names) {
        List<String> list = new ArrayList<>(names);
        java.util.Collections.sort(list);
        return list;
    }

    public PackageDefinition packageOf(String name) {
        return packages.get(name);
    }

    public Map<String, PackageDefinition> packages() {
        return packages;
    }

    public List<String> topologicalOrder() {
        return topologicalOrder;
    }
}
