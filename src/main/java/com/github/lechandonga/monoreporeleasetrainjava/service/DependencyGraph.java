package com.github.lechandonga.monoreporeleasetrainjava.service;

import com.github.lechandonga.monoreporeleasetrainjava.model.PackageDef;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.github.lechandonga.monoreporeleasetrainjava.service.ReleaseErrorCode.CYCLIC_DEPENDENCY;
import static com.github.lechandonga.monoreporeleasetrainjava.service.ReleaseErrorCode.DUPLICATE_DEPENDENCY;
import static com.github.lechandonga.monoreporeleasetrainjava.service.ReleaseErrorCode.UNKNOWN_DEPENDENCY;

/**
 * 依赖图工具：发布前校验（重复声明 / 依赖不存在 / 循环依赖）与拓扑排序。
 * 边的方向为 dependency -> dependent（被依赖方指向依赖方），
 * 因此拓扑序即发布顺序：被依赖的包先发。
 */
public final class DependencyGraph {

    private DependencyGraph() {
    }

    /**
     * 对整批提交的原始依赖声明做静态校验。
     *
     * @param rawDependencies 每个包声明的依赖名列表（原始顺序保留，用于识别重复声明）
     */
    public static void validate(Map<String, List<String>> rawDependencies, Set<String> knownPackages) {
        for (Map.Entry<String, List<String>> entry : rawDependencies.entrySet()) {
            Set<String> seen = new HashSet<>();
            for (String dep : entry.getValue()) {
                if (!seen.add(dep)) {
                    throw new ReleaseException(DUPLICATE_DEPENDENCY,
                            "包 " + entry.getKey() + " 重复声明了对 " + dep + " 的依赖");
                }
                if (!knownPackages.contains(dep)) {
                    throw new ReleaseException(UNKNOWN_DEPENDENCY,
                            "包 " + entry.getKey() + " 依赖了不存在的包 " + dep);
                }
            }
        }

        Map<String, Set<String>> edges = new LinkedHashMap<>();
        for (String pkg : knownPackages) {
            edges.put(pkg, new HashSet<>());
        }
        for (Map.Entry<String, List<String>> entry : rawDependencies.entrySet()) {
            for (String dep : entry.getValue()) {
                edges.get(dep).add(entry.getKey());
            }
        }
        detectCycle(edges);
    }

    private static void detectCycle(Map<String, Set<String>> edges) {
        Set<String> visited = new HashSet<>();
        for (String node : edges.keySet()) {
            if (!visited.contains(node)) {
                dfs(node, edges, visited, new HashSet<>(), new ArrayDeque<>());
            }
        }
    }

    private static void dfs(String node, Map<String, Set<String>> edges,
                            Set<String> visited, Set<String> onStack, Deque<String> path) {
        visited.add(node);
        onStack.add(node);
        path.push(node);
        for (String next : edges.getOrDefault(node, Set.of())) {
            if (onStack.contains(next)) {
                List<String> cycle = new ArrayList<>();
                boolean started = false;
                for (String p : (Iterable<String>) path::descendingIterator) {
                    if (p.equals(next)) started = true;
                    if (started) cycle.add(p);
                }
                cycle.add(next);
                throw new ReleaseException(CYCLIC_DEPENDENCY,
                        "检测到循环依赖: " + String.join(" -> ", cycle));
            }
            if (!visited.contains(next)) {
                dfs(next, edges, visited, onStack, path);
            }
        }
        path.pop();
        onStack.remove(node);
    }

    /**
     * 对指定的包集合按被依赖方先发的顺序做拓扑排序。
     */
    public static List<String> topologicalOrder(Set<String> packageNames,
                                                Map<String, PackageDef> catalog) {
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, Set<String>> outgoing = new LinkedHashMap<>();
        for (String pkg : packageNames) {
            indegree.put(pkg, 0);
            outgoing.put(pkg, new HashSet<>());
        }
        for (String pkg : packageNames) {
            for (String dep : catalog.get(pkg).dependencies().keySet()) {
                if (packageNames.contains(dep)) {
                    outgoing.get(dep).add(pkg);
                    indegree.merge(pkg, 1, Integer::sum);
                }
            }
        }
        // 名称排序保证在无依赖关系时结果仍然确定
        Deque<String> ready = new ArrayDeque<>(packageNames.stream()
                .filter(p -> indegree.get(p) == 0).sorted().toList());
        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String current = ready.poll();
            order.add(current);
            List<String> newlyReady = new ArrayList<>();
            for (String dependent : outgoing.get(current)) {
                if (indegree.merge(dependent, -1, Integer::sum) == 0) {
                    newlyReady.add(dependent);
                }
            }
            newlyReady.stream().sorted().forEach(ready::addLast);
        }
        if (order.size() != packageNames.size()) {
            throw new ReleaseException(CYCLIC_DEPENDENCY, "发布集合中存在循环依赖");
        }
        return order;
    }
}
