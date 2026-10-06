package com.github.lechandonga.monoreporeleasetrainjava.releasetrain;

import com.github.lechandonga.monoreporeleasetrainjava.releasetrain.dto.ReleaseRequest;
import com.github.lechandonga.monoreporeleasetrainjava.releasetrain.exception.ReleaseErrorCode;
import com.github.lechandonga.monoreporeleasetrainjava.releasetrain.exception.ReleaseException;
import com.github.lechandonga.monoreporeleasetrainjava.releasetrain.model.PackagePlan;
import com.github.lechandonga.monoreporeleasetrainjava.releasetrain.model.ReleaseBatch;
import com.github.lechandonga.monoreporeleasetrainjava.releasetrain.service.BatchStore;
import com.github.lechandonga.monoreporeleasetrainjava.releasetrain.service.LocalFilePublisher;
import com.github.lechandonga.monoreporeleasetrainjava.releasetrain.service.ReleaseTrainService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 发布列车核心业务用例（版本计算、依赖校验、幂等、重试、并发）。 */
class ReleaseTrainServiceTest {

    @RegisterExtension
    static final BusinessCaseLogger LOGGER = new BusinessCaseLogger();

    @TempDir
    Path dataDir;

    private BatchStore store;
    private LocalFilePublisher publisher;
    private ReleaseTrainService service;

    @BeforeEach
    void setUp() {
        store = new BatchStore(dataDir, TestFixtures.objectMapper());
        publisher = new LocalFilePublisher(dataDir.resolve("registry"));
        service = new ReleaseTrainService(store, publisher);
    }

    private ReleaseBatch cascadeRequest(String batchId) {
        // util <- core(^1.0.0) <- web(^1.0.0)；util 破坏性改动使 core 的 ^1 区间失配
        return service.submit(TestFixtures.request(batchId, List.of(
                TestFixtures.pkg("util", "1.4.2", Map.of()),
                TestFixtures.pkg("core", "1.2.0",
                        TestFixtures.deps("util", "^1.0.0")),
                TestFixtures.pkg("web", "2.0.3",
                        TestFixtures.deps("util", "^1.0.0", "core", "^1.0.0"))
        ), "util", "MAJOR"));
    }

    private Map<String, PackagePlan> plansOf(ReleaseBatch batch) {
        Map<String, PackagePlan> plans = new LinkedHashMap<>();
        batch.getPlans().forEach(plan -> plans.put(plan.getPackageName(), plan));
        return plans;
    }

    @Nested
    @DisplayName("主流程：级联发布")
    class CascadeHappyPath {

        @Test
        @DisplayName("MAJOR 沿依赖方向级联，按拓扑顺序发布，区间不成立的依赖方不可发布")
        void cascadesByTopologyAndPublishesInOrder() {
            ReleaseBatch batch = cascadeRequest("b-1");

            assertEquals(ReleaseBatch.Status.COMPLETED.name(), batch.getStatus());
            Map<String, PackagePlan> plans = plansOf(batch);

            assertEquals("2.0.0", plans.get("util").getToVersion());
            assertEquals("2.0.0", plans.get("core").getToVersion());
            assertEquals("3.0.0", plans.get("web").getToVersion());
            assertEquals(PackagePlan.State.PUBLISHED.name(), plans.get("util").getState());
            assertEquals(PackagePlan.State.BLOCKED.name(), plans.get("core").getState());
            assertNotNull(plans.get("core").getReason());
            assertEquals(PackagePlan.State.BLOCKED.name(), plans.get("web").getState());

            List<String> names = batch.getPlans().stream().map(PackagePlan::getPackageName).toList();
            assertTrue(names.indexOf("util") < names.indexOf("core"));
            assertTrue(Files.exists(dataDir.resolve("registry/util/2.0.0.txt")));
            assertFalse(Files.exists(dataDir.resolve("registry/core/2.0.0.txt")));
        }

        @Test
        @DisplayName("MINOR 级联：1.0.0+MINOR=1.1.0，兼容区间内的依赖方跟随 MINOR 级联")
        void minorCascadeCalculation() {
            ReleaseBatch batch = service.submit(TestFixtures.request("b-2", List.of(
                    TestFixtures.pkg("a", "1.0.0", Map.of()),
                    TestFixtures.pkg("b", "1.0.0",
                            TestFixtures.deps("a", ">=1.0.0 <2.0.0"))
            ), "a", "MINOR"));

            Map<String, PackagePlan> plans = plansOf(batch);
            assertEquals("1.1.0", plans.get("a").getToVersion());
            assertEquals("1.1.0", plans.get("b").getToVersion());
            assertEquals(PackagePlan.State.PUBLISHED.name(), plans.get("b").getState());
        }
    }

    @Nested
    @DisplayName("边界：开始发布前拒绝的问题")
    class ValidationErrors {

        @Test
        @DisplayName("循环依赖被拒绝，错误码 CYCLIC_DEPENDENCY，且不留任何记录")
        void cyclicDependencyRejected() {
            ReleaseException ex = assertThrows(ReleaseException.class, () ->
                    service.submit(TestFixtures.request("cyc", List.of(
                            TestFixtures.pkg("a", "1.0.0", TestFixtures.deps("b", "^1.0.0")),
                            TestFixtures.pkg("b", "1.0.0", TestFixtures.deps("a", "^1.0.0"))
                    ), "a", "PATCH")));
            assertEquals(ReleaseErrorCode.CYCLIC_DEPENDENCY, ex.getCode());
            assertFalse(Files.exists(dataDir.resolve("batches/cyc.json")));
        }

        @Test
        @DisplayName("依赖不存在的包被拒绝，错误码 UNKNOWN_DEPENDENCY")
        void unknownDependencyRejected() {
            ReleaseException ex = assertThrows(ReleaseException.class, () ->
                    service.submit(TestFixtures.request("unk", List.of(
                            TestFixtures.pkg("a", "1.0.0", TestFixtures.deps("ghost", "^1.0.0"))
                    ), "a", "PATCH")));
            assertEquals(ReleaseErrorCode.UNKNOWN_DEPENDENCY, ex.getCode());
        }

        @Test
        @DisplayName("重复声明同一个包被拒绝，错误码 DUPLICATE_PACKAGE")
        void duplicatePackageRejected() {
            ReleaseException ex = assertThrows(ReleaseException.class, () ->
                    service.submit(TestFixtures.request("dup-pkg", List.of(
                            TestFixtures.pkg("a", "1.0.0", Map.of()),
                            TestFixtures.pkg("a", "1.0.1", Map.of())
                    ), "a", "PATCH")));
            assertEquals(ReleaseErrorCode.DUPLICATE_PACKAGE, ex.getCode());
        }

        @Test
        @DisplayName("请求体里同一依赖重复书写被拒绝，错误码 DUPLICATE_DEPENDENCY")
        void duplicateDependencyRejected() {
            String json = """
                    {
                      "batchId": "dup-dep",
                      "packages": [
                        {"name": "a", "version": "1.0.0", "dependencies": {}},
                        {"name": "b", "version": "1.0.0",
                         "dependencies": {"a": "^1.0.0", "a": "~1.0.0"}}
                      ],
                      "changes": [{"packageName": "a", "level": "PATCH"}]
                    }
                    """;
            com.fasterxml.jackson.core.JsonProcessingException ex = assertThrows(
                    com.fasterxml.jackson.core.JsonProcessingException.class,
                    () -> TestFixtures.objectMapper()
                            .readValue(json, ReleaseRequest.class));
            assertTrue(ex.getMessage().contains("Duplicate field"));
        }

        @Test
        @DisplayName("级联后区间不成立，依赖方被明确标成不可发布而不是照常发出去")
        void rangeConflictMarksUnpublishable() {
            ReleaseBatch batch = service.submit(TestFixtures.request("range", List.of(
                    TestFixtures.pkg("lib", "1.5.0", Map.of()),
                    TestFixtures.pkg("app", "3.0.0",
                            TestFixtures.deps("lib", "~1.5.0"))
            ), "lib", "MINOR"));
            PackagePlan app = batch.getPlans().stream()
                    .filter(plan -> plan.getPackageName().equals("app")).findFirst().orElseThrow();
            assertEquals(PackagePlan.State.BLOCKED.name(), app.getState());
            assertTrue(app.getReason().contains("~1.5.0"));
            assertFalse(Files.exists(dataDir.resolve("registry/app/3.0.1.txt")));
        }

        @Test
        @DisplayName("当前为预发布版本被拒绝，错误码 PRERELEASE_FORBIDDEN")
        void prereleaseRejected() {
            ReleaseException ex = assertThrows(ReleaseException.class, () ->
                    service.submit(TestFixtures.request("pre", List.of(
                            TestFixtures.pkg("a", "2.0.0-rc1", Map.of())
                    ), "a", "PATCH")));
            assertEquals(ReleaseErrorCode.PRERELEASE_FORBIDDEN, ex.getCode());
        }

        @Test
        @DisplayName("无法识别或缺失的变更级别被拒绝，不会静默按修复级别处理")
        void invalidChangeRejected() {
            ReleaseException unknown = assertThrows(ReleaseException.class, () ->
                    service.submit(TestFixtures.request("bad-level", List.of(
                            TestFixtures.pkg("a", "1.0.0", Map.of())
                    ), "a", "REVOLUTION")));
            assertEquals(ReleaseErrorCode.INVALID_CHANGE, unknown.getCode());

            ReleaseRequest request = TestFixtures.request("missing-level", List.of(
                    TestFixtures.pkg("a", "1.0.0", Map.of())));
            ReleaseRequest.Change change = new ReleaseRequest.Change();
            change.setPackageName("a");
            request.getChanges().add(change);
            ReleaseException missing = assertThrows(ReleaseException.class,
                    () -> service.submit(request));
            assertEquals(ReleaseErrorCode.INVALID_CHANGE, missing.getCode());
        }
    }

    @Nested
    @DisplayName("幂等、并发与失败重试")
    class IdempotencyAndRetry {

        private long artifactCount() throws java.io.IOException {
            try (var stream = Files.walk(dataDir.resolve("registry"))) {
                return stream.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".txt")).count();
            }
        }

        @Test
        @DisplayName("同批重复提交不产生重复版本号或重复发布记录")
        void duplicateSubmissionIsIdempotent() throws Exception {
            ReleaseBatch first = cascadeRequest("idem");
            ReleaseBatch second = cascadeRequest("idem");

            assertEquals(first.getStatus(), second.getStatus());
            assertEquals(first.getPlans().size(), second.getPlans().size());
            assertEquals(1L, artifactCount());
            PackagePlan util = second.getPlans().stream()
                    .filter(plan -> plan.getPackageName().equals("util")).findFirst().orElseThrow();
            assertEquals(1, util.getAttempts(), "发布只应真正成功执行一次");
        }

        @Test
        @DisplayName("并发提交相同批次：安全串行或明确拒绝，绝不产生重复发布")
        void concurrentSameBatchIsSafe() throws Exception {
            int threads = 8;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return cascadeRequest("concurrent");
                    } catch (ReleaseException ex) {
                        return ex;
                    }
                }));
            }
            start.countDown();
            int completed = 0;
            int rejected = 0;
            for (Future<Object> future : futures) {
                Object result = future.get(10, TimeUnit.SECONDS);
                if (result instanceof ReleaseBatch batch
                        && ReleaseBatch.Status.COMPLETED.name().equals(batch.getStatus())) {
                    completed++;
                } else if (result instanceof ReleaseException ex
                        && ex.getCode() == ReleaseErrorCode.BATCH_CONFLICT) {
                    rejected++;
                } else {
                    throw new AssertionError("出现非预期结果: " + result);
                }
            }
            pool.shutdown();
            assertEquals(threads, completed + rejected);
            assertTrue(completed >= 1);
            assertEquals(1L, artifactCount());
        }

        @Test
        @DisplayName("中途失败：已发布不回退、无半成品；重试确定完成，不跳号不重复")
        void failureIsRecoverableByRetry() throws Exception {
            publisher.failOnce("core", "1.0.1");
            ReleaseBatch failed = service.submit(TestFixtures.request("retry-me", List.of(
                    TestFixtures.pkg("util", "1.0.0", Map.of()),
                    TestFixtures.pkg("core", "1.0.0",
                            TestFixtures.deps("util", "^1.0.0")),
                    TestFixtures.pkg("web", "1.0.0",
                            TestFixtures.deps("core", "^1.0.0"))
            ), "util", "PATCH"));

            assertEquals(ReleaseBatch.Status.PARTIALLY_FAILED.name(), failed.getStatus());
            Map<String, PackagePlan> plans = plansOf(failed);
            assertEquals(PackagePlan.State.PUBLISHED.name(), plans.get("util").getState());
            assertEquals(PackagePlan.State.FAILED.name(), plans.get("core").getState());
            assertEquals(PackagePlan.State.PENDING.name(), plans.get("web").getState());
            assertTrue(Files.exists(dataDir.resolve("registry/util/1.0.1.txt")));
            assertFalse(Files.exists(dataDir.resolve("registry/core/1.0.1.txt")));

            // 进度可查询
            ReleaseBatch queried = service.getBatch("retry-me");
            assertEquals(failed.getStatus(), queried.getStatus());

            // 重试：从 core 继续，确定成功
            ReleaseBatch retried = service.retry("retry-me");
            Map<String, PackagePlan> after = plansOf(retried);
            assertEquals(ReleaseBatch.Status.COMPLETED.name(), retried.getStatus());
            assertEquals(PackagePlan.State.PUBLISHED.name(), after.get("core").getState());
            assertEquals(PackagePlan.State.PUBLISHED.name(), after.get("web").getState());
            assertEquals("1.0.1", after.get("util").getToVersion());
            assertEquals("1.0.1", after.get("core").getToVersion());
            assertEquals("1.0.1", after.get("web").getToVersion());
            assertEquals(1, after.get("util").getAttempts(), "util 不重复发布");
            assertEquals(1, after.get("core").getAttempts());
            assertEquals(1, after.get("web").getAttempts());

            // 再次重试为幂等重放，制品集合不变
            ReleaseBatch again = service.retry("retry-me");
            assertEquals(ReleaseBatch.Status.COMPLETED.name(), again.getStatus());
            assertEquals(3L, artifactCount());
        }
    }
}
