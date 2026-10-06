package com.github.lechandonga.monoreporeleasetrainjava.service;

import com.github.lechandonga.monoreporeleasetrainjava.model.ReleaseBatch;
import com.github.lechandonga.monoreporeleasetrainjava.model.ReleaseItem;
import com.github.lechandonga.monoreporeleasetrainjava.store.BatchStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import static com.github.lechandonga.monoreporeleasetrainjava.service.ReleaseErrorCode.BATCH_CONFLICT;
import static com.github.lechandonga.monoreporeleasetrainjava.service.ReleaseErrorCode.BATCH_NOT_FOUND;

/**
 * 发布列车编排服务。
 *
 * 语义：
 * - 同一 batchId 重复提交：返回原批次与原结果，不产生重复版本号/发布记录（幂等）。
 * - 不同 batchId 并发提交：进程内全局锁串行推进，保证不会有两个批次同时写状态。
 * - 批次中途失败：已发布的包保持 RELEASED 不回退，后续包保持 PLANNED 不产生半成品；
 *   查询可看到进度，重试从第一个未完成的包继续，结果确定，不跳号、不重复。
 */
@Service
public class ReleaseTrainService {

    private static final Logger log = LoggerFactory.getLogger(ReleaseTrainService.class);

    private final ReleasePlanner planner;
    private final BatchStore batchStore;
    private final PackagePublisher publisher;
    private final ReentrantLock executionLock = new ReentrantLock(true);
    private final Map<String, ReentrantLock> batchLocks = new ConcurrentHashMap<>();

    public ReleaseTrainService(ReleasePlanner planner, BatchStore batchStore,
                               PackagePublisher publisher) {
        this.planner = planner;
        this.batchStore = batchStore;
        this.publisher = publisher;
    }

    /**
     * 提交一个发布批次并尝试推进完成。
     */
    public ReleaseBatch submit(String batchId, Map<String, String> changes) {
        ReleaseBatch batch = batchStore.find(batchId).orElse(null);
        if (batch != null) {
            log.info("[幂等提交] 批次 {} 已存在（状态 {}），直接返回原计划，不重新计算版本",
                    batchId, batch.state());
            return advance(batchId, false);
        }
        // 规划阶段的全部校验（循环依赖/重复声明/依赖缺失/区间冲突/非法变更/预发布）在发布前完成
        ReleaseBatch planned = planner.plan(batchId, changes);
        ReleaseBatch stored = batchStore.putIfAbsent(planned);
        if (stored != planned) {
            log.info("[幂等提交] 批次 {} 在规划期间已被创建，复用已有批次", batchId);
            return stored;
        }
        batchStore.save(planned);
        return advance(batchId, false);
    }

    /**
     * 查询批次进度。
     */
    public ReleaseBatch status(String batchId) {
        ReleaseBatch batch = batchStore.get(batchId);
        if (batch == null) {
            throw new ReleaseException(BATCH_NOT_FOUND, "发布批次不存在: " + batchId);
        }
        return batch;
    }

    /**
     * 重试失败的批次：从第一个未发布的可发布包继续。
     *
     * @param rejectIfRunning true 表示批次正在被其他线程推进时直接拒绝（并发保护）
     */
    public ReleaseBatch advance(String batchId, boolean rejectIfRunning) {
        ReleaseBatch batch = status(batchId);
        ReentrantLock lock = batchLocks.computeIfAbsent(batchId, k -> new ReentrantLock());
        if (rejectIfRunning && lock.isLocked()) {
            throw new ReleaseException(BATCH_CONFLICT,
                    "批次 " + batchId + " 正在推进中，拒绝并发重复提交");
        }
        lock.lock();
        try {
            return doAdvance(batch);
        } finally {
            lock.unlock();
        }
    }

    private ReleaseBatch doAdvance(ReleaseBatch batch) {
        if (batch.state() == ReleaseBatch.State.COMPLETED) {
            log.info("[批次完成-幂等] 批次 {} 已全部发布完成，重试不产生任何副作用", batch.id());
            return batch;
        }
        if (!executionLock.tryLock()) {
            throw new ReleaseException(BATCH_CONFLICT,
                    "另一个发布批次正在推进，为保证状态安全本次提交被拒绝，请稍后重试");
        }
        batch.state(ReleaseBatch.State.IN_PROGRESS);
        batchStore.save(batch);
        try {
            for (ReleaseItem item : batch.items()) {
                if (item.status() == ReleaseItem.Status.RELEASED) {
                    continue;
                }
                if (item.status() == ReleaseItem.Status.SKIPPED) {
                    // 规划期标记为不可发布的包，重试不会改变结论
                    continue;
                }
                try {
                    publisher.publish(batch.id(), item.packageName(), item.targetVersion());
                    item.status(ReleaseItem.Status.RELEASED);
                    item.failureReason(null);
                    batchStore.save(batch);
                } catch (ReleaseException e) {
                    item.status(ReleaseItem.Status.FAILED);
                    item.failureReason(e.getMessage());
                    batch.state(ReleaseBatch.State.FAILED);
                    batchStore.save(batch);
                    log.warn("[批次挂起] 批次 {} 在包 {} 处失败，已发布的包不回退，可查询可重试",
                            batch.id(), item.packageName());
                    return batch;
                }
            }
            boolean hasUnreleasable = batch.items().stream()
                    .anyMatch(i -> i.status() == ReleaseItem.Status.SKIPPED);
            batch.state(hasUnreleasable ? ReleaseBatch.State.FAILED
                    : ReleaseBatch.State.COMPLETED);
            batchStore.save(batch);
            if (hasUnreleasable) {
                log.warn("[批次结束-含不可发布包] 批次 {} 已发布项完成，但存在被标记不可发布的包",
                        batch.id());
            } else {
                log.info("[批次完成] 批次 {} 全部发布完成", batch.id());
            }
            return batch;
        } finally {
            executionLock.unlock();
        }
    }

    /**
     * 注册包目录时调用的静态校验入口，保证问题在任何发布开始前就被拒绝。
     */
    public void validateGraph(Map<String, List<String>> rawDependencies,
                              java.util.Set<String> knownPackages) {
        DependencyGraph.validate(rawDependencies, knownPackages);
    }
}
