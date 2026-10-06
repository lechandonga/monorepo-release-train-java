package com.github.lechandonga.monoreporeleasetrainjava.releasetrain.service;

import com.github.lechandonga.monoreporeleasetrainjava.releasetrain.dto.ReleaseRequest;
import com.github.lechandonga.monoreporeleasetrainjava.releasetrain.exception.ReleaseErrorCode;
import com.github.lechandonga.monoreporeleasetrainjava.releasetrain.exception.ReleaseException;
import com.github.lechandonga.monoreporeleasetrainjava.releasetrain.model.ChangeLevel;
import com.github.lechandonga.monoreporeleasetrainjava.releasetrain.model.PackageDefinition;
import com.github.lechandonga.monoreporeleasetrainjava.releasetrain.model.PackagePlan;
import com.github.lechandonga.monoreporeleasetrainjava.releasetrain.model.ReleaseBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 发布列车编排：校验 -> 固化计划 -> 按拓扑顺序执行 -> 持久化进度。
 * <ul>
 *   <li>相同 batchId 的重复提交：已终结则返回既有结果（幂等），执行中则明确拒绝。</li>
 *   <li>不同批次通过全局列车锁安全串行。</li>
 *   <li>中途失败：已发布的不回退，失败包标记 FAILED，可重试；重试复用计划，不跳号不重复。</li>
 * </ul>
 */
@Service
public class ReleaseTrainService {

    private static final Logger log = LoggerFactory.getLogger(ReleaseTrainService.class);

    private final BatchStore store;
    private final PackagePublisher publisher;
    private final ReleasePlanner planner = new ReleasePlanner();
    private final ReentrantLock trainLock = new ReentrantLock(true);
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public ReleaseTrainService(BatchStore store, PackagePublisher publisher) {
        this.store = store;
        this.publisher = publisher;
    }

    public ReleaseBatch submit(ReleaseRequest request) {
        validateBatchId(request.getBatchId());
        trainLock.lock();
        try {
            Optional<ReleaseBatch> existing = store.find(request.getBatchId());
            if (existing.isPresent()) {
                ReleaseBatch batch = existing.get();
                if (!isTerminal(batch.getStatus()) && inFlight.contains(batch.getBatchId())) {
                    throw new ReleaseException(ReleaseErrorCode.BATCH_CONFLICT,
                            "批次 " + batch.getBatchId() + " 正在发布中，拒绝并发重复提交");
                }
                log.info("[列车] 批次 {} 为重复提交，返回既有结果(状态 {})",
                        batch.getBatchId(), batch.getStatus());
                return execute(batch);
            }
            ReleaseBatch batch = createBatch(request);
            return execute(batch);
        } finally {
            trainLock.unlock();
        }
    }

    public ReleaseBatch retry(String batchId) {
        validateBatchId(batchId);
        trainLock.lock();
        try {
            ReleaseBatch batch = store.find(batchId)
                    .orElseThrow(() -> new ReleaseException(ReleaseErrorCode.BATCH_NOT_FOUND,
                            "批次不存在: " + batchId));
            if (ReleaseBatch.Status.COMPLETED.name().equals(batch.getStatus())) {
                log.info("[列车] 批次 {} 已完成，重试为幂等重放", batchId);
            }
            return execute(batch);
        } finally {
            trainLock.unlock();
        }
    }

    public ReleaseBatch getBatch(String batchId) {
        validateBatchId(batchId);
        return store.find(batchId)
                .orElseThrow(() -> new ReleaseException(ReleaseErrorCode.BATCH_NOT_FOUND,
                        "批次不存在: " + batchId));
    }

    private ReleaseBatch createBatch(ReleaseRequest request) {
        List<PackageDefinition> definitions = request.getPackages().stream()
                .map(payload -> new PackageDefinition(payload.getName(), payload.getVersion(),
                        payload.getDependencies()))
                .toList();
        DependencyGraph graph = DependencyGraph.build(definitions);

        Map<String, ChangeLevel> changes = parseChanges(request.getChanges(), graph);
        for (PackageDefinition definition : definitions) {
            if (definition.semanticVersion().isPrerelease()) {
                throw new ReleaseException(ReleaseErrorCode.PRERELEASE_FORBIDDEN,
                        "包 " + definition.name() + " 当前为预发布版本 "
                                + definition.version() + "，不能作为正式版本对外发布");
            }
        }

        ReleaseBatch batch = new ReleaseBatch();
        batch.setBatchId(request.getBatchId());
        batch.setCreatedAt(Instant.now());
        batch.setStatus(ReleaseBatch.Status.IN_PROGRESS.name());
        batch.setPackages(new ArrayList<>(definitions));
        batch.setPlans(planner.plan(graph, changes));
        persist(batch);
        log.info("[列车] 批次 {} 计划固化: {} 个包进入发布序列",
                batch.getBatchId(), batch.getPlans().size());
        return batch;
    }

    private Map<String, ChangeLevel> parseChanges(List<ReleaseRequest.Change> changePayloads,
                                                  DependencyGraph graph) {
        Map<String, ChangeLevel> changes = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (ReleaseRequest.Change payload : changePayloads) {
            if (payload.getPackageName() == null || payload.getPackageName().isBlank()) {
                throw new ReleaseException(ReleaseErrorCode.INVALID_CHANGE,
                        "存在缺少包名的变更条目");
            }
            if (!graph.packages().containsKey(payload.getPackageName())) {
                throw new ReleaseException(ReleaseErrorCode.INVALID_CHANGE,
                        "变更条目引用了不存在的包: " + payload.getPackageName());
            }
            if (!seen.add(payload.getPackageName())) {
                throw new ReleaseException(ReleaseErrorCode.DUPLICATE_PACKAGE,
                        "同一个包出现多条变更声明: " + payload.getPackageName());
            }
            if (payload.getLevel() == null || payload.getLevel().isBlank()) {
                throw new ReleaseException(ReleaseErrorCode.INVALID_CHANGE,
                        "包 " + payload.getPackageName() + " 缺少变更级别，不允许静默按修复处理");
            }
            ChangeLevel level;
            try {
                level = ChangeLevel.valueOf(payload.getLevel().trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new ReleaseException(ReleaseErrorCode.INVALID_CHANGE,
                        "包 " + payload.getPackageName() + " 的变更级别无法识别: "
                                + payload.getLevel());
            }
            changes.put(payload.getPackageName(), level);
        }
        if (changes.isEmpty()) {
            throw new ReleaseException(ReleaseErrorCode.INVALID_CHANGE,
                    "未提供任何变更信息，不允许静默按修复级别发布");
        }
        return changes;
    }

    /**
     * 按拓扑顺序推进所有 PENDING 计划。BLOCKED 为确定性终态，永远跳过；
     * 单个包发布失败时立即停车，已发布的保持 PUBLISHED，其余保持 PENDING（无半成品）。
     */
    private ReleaseBatch execute(ReleaseBatch batch) {
        if (ReleaseBatch.Status.COMPLETED.name().equals(batch.getStatus())) {
            log.info("[列车] 批次 {} 已完成，直接返回固化结果", batch.getBatchId());
            return batch;
        }
        inFlight.add(batch.getBatchId());
        try {
            DependencyGraph graph = DependencyGraph.build(definitionsOf(batch));
            Map<String, PackagePlan> byName = new LinkedHashMap<>();
            batch.getPlans().forEach(plan -> byName.put(plan.getPackageName(), plan));

            boolean progress = false;
            for (String name : graph.topologicalOrder()) {
                PackagePlan plan = byName.get(name);
                if (plan == null || !isRetriable(plan.getState())) {
                    continue;
                }
                try {
                    publisher.publish(name, plan.getToVersion());
                } catch (RuntimeException ex) {
                    plan.setState(PackagePlan.State.FAILED.name());
                    plan.setReason(ex.getMessage());
                    batch.setStatus(ReleaseBatch.Status.PARTIALLY_FAILED.name());
                    persist(batch);
                    log.warn("[列车] 批次 {} 在 {} 处停车: {}",
                            batch.getBatchId(), name, ex.getMessage());
                    return batch;
                }
                plan.setAttempts(plan.getAttempts() + 1);
                plan.setState(PackagePlan.State.PUBLISHED.name());
                plan.setReason(null);
                progress = true;
                batch.setStatus(ReleaseBatch.Status.IN_PROGRESS.name());
                persist(batch);
            }
            if (hasRetriable(batch)) {
                batch.setStatus(ReleaseBatch.Status.PARTIALLY_FAILED.name());
            } else {
                batch.setStatus(ReleaseBatch.Status.COMPLETED.name());
            }
            persist(batch);
            log.info("[列车] 批次 {} 推进完成，状态 {}", batch.getBatchId(), batch.getStatus());
            return batch;
        } finally {
            inFlight.remove(batch.getBatchId());
        }
    }

    private List<PackageDefinition> definitionsOf(ReleaseBatch batch) {
        return batch.getPackages();
    }

    private boolean isRetriable(String state) {
        return PackagePlan.State.PENDING.name().equals(state)
                || PackagePlan.State.FAILED.name().equals(state);
    }

    private boolean hasRetriable(ReleaseBatch batch) {
        return batch.getPlans().stream()
                .anyMatch(plan -> isRetriable(plan.getState()));
    }

    private boolean isTerminal(String status) {
        return ReleaseBatch.Status.COMPLETED.name().equals(status)
                || ReleaseBatch.Status.PARTIALLY_FAILED.name().equals(status);
    }

    private void persist(ReleaseBatch batch) {
        batch.setUpdatedAt(Instant.now());
        store.save(batch);
    }

    private void validateBatchId(String batchId) {
        if (batchId == null || !batchId.matches("[A-Za-z0-9._-]+")) {
            throw new ReleaseException(ReleaseErrorCode.INVALID_CHANGE,
                    "batchId 缺失或非法（仅允许字母数字 . _ -）");
        }
    }
}
