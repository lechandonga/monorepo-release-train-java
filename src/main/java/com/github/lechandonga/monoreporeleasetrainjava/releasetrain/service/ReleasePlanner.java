package com.github.lechandonga.monoreporeleasetrainjava.releasetrain.service;

import com.github.lechandonga.monoreporeleasetrainjava.releasetrain.model.ChangeLevel;
import com.github.lechandonga.monoreporeleasetrainjava.releasetrain.model.PackageDefinition;
import com.github.lechandonga.monoreporeleasetrainjava.releasetrain.model.PackagePlan;
import com.github.lechandonga.monoreporeleasetrainjava.releasetrain.model.SemanticVersion;
import com.github.lechandonga.monoreporeleasetrainjava.releasetrain.model.VersionRange;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发布计划器：依据变更级别与依赖方向计算每个包的新版本与可发布性。
 * 级联规则：变更级别沿依赖方向（被依赖者 -> 依赖方）传播，依赖方的
 * 有效级别 = max(自身声明级别, 所有将发布的被依赖者级别)。计划一旦产出即固化，
 * 是幂等重放与失败重试确定性的来源。
 */
public final class ReleasePlanner {

    public List<PackagePlan> plan(DependencyGraph graph, Map<String, ChangeLevel> changes) {
        Map<String, ChangeLevel> effectiveLevels = new HashMap<>(changes);

        // 第一遍：按拓扑顺序沿依赖方向级联变更级别（取自身与被依赖者的最大级别）。
        for (String name : graph.topologicalOrder()) {
            ChangeLevel level = effectiveLevels.get(name);
            for (String dependency : graph.packageOf(name).dependencies().keySet()) {
                ChangeLevel cascaded = effectiveLevels.get(dependency);
                if (cascaded != null) {
                    level = level == null ? cascaded : level.max(cascaded);
                }
            }
            if (level != null) {
                effectiveLevels.put(name, level);
            }
        }

        // 第二遍：计算新版本，并对每条依赖声明做区间判定；不可发布会沿依赖方向传导。
        Map<String, PackagePlan> plansByName = new LinkedHashMap<>();
        for (String name : graph.topologicalOrder()) {
            ChangeLevel level = effectiveLevels.get(name);
            if (level == null) {
                continue;
            }
            PackageDefinition definition = graph.packageOf(name);
            SemanticVersion from = definition.semanticVersion();
            SemanticVersion to = level.apply(from);
            PackagePlan plan = new PackagePlan(name, from.toString(), to.toString(),
                    level, PackagePlan.State.PENDING);
            plansByName.put(name, plan);
        }

        for (String name : graph.topologicalOrder()) {
            PackagePlan plan = plansByName.get(name);
            if (plan == null) {
                continue;
            }
            PackageDefinition definition = graph.packageOf(name);
            for (Map.Entry<String, String> entry : definition.dependencies().entrySet()) {
                String dependencyName = entry.getKey();
                PackagePlan dependencyPlan = plansByName.get(dependencyName);
                if (dependencyPlan != null
                        && PackagePlan.State.BLOCKED.name().equals(dependencyPlan.getState())) {
                    block(plan, "依赖包 " + dependencyName + " 不可发布: "
                            + dependencyPlan.getReason());
                    break;
                }
                SemanticVersion dependencyVersion = dependencyPlan != null
                        ? SemanticVersion.parse(dependencyPlan.getToVersion())
                        : graph.packageOf(dependencyName).semanticVersion();
                VersionRange range = VersionRange.of(entry.getValue());
                if (!range.contains(dependencyVersion)) {
                    block(plan, "依赖 " + dependencyName + " 级联后的版本 "
                            + dependencyVersion + " 不满足声明区间 " + range);
                    break;
                }
            }
        }
        return new ArrayList<>(plansByName.values());
    }

    private void block(PackagePlan plan, String reason) {
        plan.setState(PackagePlan.State.BLOCKED.name());
        plan.setReason(reason);
    }

}
