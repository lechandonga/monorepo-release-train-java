package com.github.lechandonga.monoreporeleasetrainjava.service;

import com.github.lechandonga.monoreporeleasetrainjava.model.ChangeLevel;
import com.github.lechandonga.monoreporeleasetrainjava.model.PackageDef;
import com.github.lechandonga.monoreporeleasetrainjava.model.ReleaseBatch;
import com.github.lechandonga.monoreporeleasetrainjava.model.ReleaseItem;
import com.github.lechandonga.monoreporeleasetrainjava.model.Version;
import com.github.lechandonga.monoreporeleasetrainjava.semver.VersionRange;
import com.github.lechandonga.monoreporeleasetrainjava.store.PackageCatalog;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.github.lechandonga.monoreporeleasetrainjava.service.ReleaseErrorCode.INVALID_CHANGE;
import static com.github.lechandonga.monoreporeleasetrainjava.service.ReleaseErrorCode.INVALID_VERSION_RANGE;
import static com.github.lechandonga.monoreporeleasetrainjava.service.ReleaseErrorCode.PRERELEASE_FORBIDDEN;
import static com.github.lechandonga.monoreporeleasetrainjava.service.ReleaseErrorCode.UNKNOWN_PACKAGE;
import static com.github.lechandonga.monoreporeleasetrainjava.service.ReleaseErrorCode.VERSION_RANGE_CONFLICT;

/**
 * 发布规划：计算本批真正要发的包、每个包的目标版本，
 * 并在发布前判定级联后版本区间是否仍然成立。
 */
@Component
public class ReleasePlanner {

    private final PackageCatalog catalog;

    public ReleasePlanner(PackageCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * @param requestedChanges 调用方提交的 包名 -> 变更级别原文；null/空白/无法识别都会拒绝
     */
    public ReleaseBatch plan(String batchId, Map<String, String> requestedChanges) {
        Map<String, ChangeLevel> levels = parseChanges(requestedChanges);

        Set<String> releaseSet = expandClosure(levels.keySet());
        List<String> order = DependencyGraph.topologicalOrder(releaseSet, catalog.all());

        ReleaseBatch batch = new ReleaseBatch(batchId);
        Map<String, ReleaseItem> itemsByName = new LinkedHashMap<>();
        for (String name : order) {
            PackageDef def = catalog.all().get(name);
            ChangeLevel level = levels.get(name);
            boolean cascaded = !levels.containsKey(name);
            if (cascaded) {
                // 级联传播：依赖发生发布时，依赖方至少以 PATCH 级别重发
                level = ChangeLevel.PATCH;
            }
            ReleaseItem item = new ReleaseItem(name, level, cascaded, def.currentVersion());
            batch.items().add(item);
            itemsByName.put(name, item);
        }

        // 按拓扑顺序计算目标版本，同时检查版本区间约束
        for (ReleaseItem item : batch.items()) {
            PackageDef def = catalog.all().get(item.packageName());
            if (def.currentVersion() != null && def.currentVersion().isPrerelease()) {
                throw new ReleaseException(PRERELEASE_FORBIDDEN,
                        "包 " + item.packageName() + " 当前版本 " + def.currentVersion()
                                + " 是预发布版本，不能作为正式版本对外发布的基线");
            }
            if (!cascadedLevelSatisfies(item, def, itemsByName)) {
                continue;
            }
            Version target = bumpVersion(def.currentVersion(), item.changeLevel());
            if (target.isPrerelease()) {
                throw new ReleaseException(PRERELEASE_FORBIDDEN,
                        "包 " + item.packageName() + " 计算出的目标版本为预发布版本，禁止对外发布");
            }
            item.targetVersion(target);
        }
        return batch;
    }

    private Map<String, ChangeLevel> parseChanges(Map<String, String> requestedChanges) {
        if (requestedChanges == null || requestedChanges.isEmpty()) {
            throw new ReleaseException(INVALID_CHANGE, "至少需要一个包的变更信息");
        }
        Map<String, ChangeLevel> levels = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : requestedChanges.entrySet()) {
            String name = entry.getKey();
            if (catalog.find(name).isEmpty()) {
                throw new ReleaseException(UNKNOWN_PACKAGE, "提交了目录中不存在的包: " + name);
            }
            try {
                levels.put(name, ChangeLevel.fromRaw(entry.getValue()));
            } catch (IllegalArgumentException e) {
                throw new ReleaseException(INVALID_CHANGE,
                        "包 " + name + " 的变更信息无效: " + e.getMessage());
            }
        }
        return levels;
    }

    /**
     * 沿依赖反方向（依赖方方向）扩展闭包：任何依赖了本批变更包的包也要发布。
     */
    private Set<String> expandClosure(Set<String> seeds) {
        Set<String> result = new LinkedHashSet<>(seeds);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (PackageDef def : new ArrayList<>(catalog.all().values())) {
                if (result.contains(def.name())) {
                    continue;
                }
                for (String dep : def.dependencies().keySet()) {
                    if (result.contains(dep)) {
                        result.add(def.name());
                        grew = true;
                        break;
                    }
                }
            }
        }
        return result;
    }

    /**
     * 检查该包对依赖的声明区间在依赖的目标版本下是否成立；
     * 同时根据区间被破坏的严重程度提升该包的变更级别（major 越界传播为 MAJOR）。
     */
    private boolean cascadedLevelSatisfies(ReleaseItem item, PackageDef def,
                                           Map<String, ReleaseItem> itemsByName) {
        ChangeLevel effective = item.changeLevel();
        for (Map.Entry<String, String> depEntry : def.dependencies().entrySet()) {
            ReleaseItem depItem = itemsByName.get(depEntry.getKey());
            if (depItem == null || depItem.targetVersion() == null) {
                continue;
            }
            VersionRange range;
            try {
                range = VersionRange.parse(depEntry.getValue());
            } catch (IllegalArgumentException e) {
                throw new ReleaseException(INVALID_VERSION_RANGE,
                        "包 " + item.packageName() + " 声明的依赖区间无法解析: "
                                + depEntry.getValue());
            }
            if (!range.satisfies(depItem.targetVersion())) {
                item.markUnreleasable("依赖 " + depEntry.getKey() + " 级联后的目标版本 "
                        + depItem.targetVersion() + " 不满足声明区间 "
                        + depEntry.getValue() + "，该包不可发布");
                return false;
            }
            // 被依赖方做了 major 升级时，依赖方也按 major 级联传播
            if (depItem.changeLevel() == ChangeLevel.MAJOR
                    && effective.rank() < ChangeLevel.MAJOR.rank()) {
                effective = ChangeLevel.MAJOR;
            }
        }
        if (effective != item.changeLevel()) {
            // 用提升后的级别重写（ReleaseItem 不可变字段，借助包装记录实现）
            item.upgradeLevel(effective);
        }
        return true;
    }

    /**
     * 语义化版本递增：无当前版本时从 0.1.0 起步（首次发布）。
     */
    static Version bumpVersion(Version current, ChangeLevel level) {
        if (current == null) {
            return switch (level) {
                case MAJOR -> new Version(1, 0, 0, null, null);
                case MINOR -> new Version(0, 1, 0, null, null);
                case PATCH -> new Version(0, 1, 0, null, null);
            };
        }
        return switch (level) {
            case MAJOR -> new Version(current.major() + 1, 0, 0, null, null);
            case MINOR -> new Version(current.major(), current.minor() + 1, 0, null, null);
            case PATCH -> new Version(current.major(), current.minor(), current.patch() + 1,
                    null, null);
        };
    }
}
