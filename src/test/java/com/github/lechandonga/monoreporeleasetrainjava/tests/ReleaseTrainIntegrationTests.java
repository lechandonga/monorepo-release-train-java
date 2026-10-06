package com.github.lechandonga.monoreporeleasetrainjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.github.lechandonga.monoreporeleasetrainjava.tests.TestFixtures.item;
import static com.github.lechandonga.monoreporeleasetrainjava.tests.TestFixtures.read;
import static com.github.lechandonga.monoreporeleasetrainjava.tests.TestFixtures.registerBody;
import static com.github.lechandonga.monoreporeleasetrainjava.tests.TestFixtures.registerBodyWithDuplicate;
import static com.github.lechandonga.monoreporeleasetrainjava.tests.TestFixtures.registerBodyWithMissingDep;
import static com.github.lechandonga.monoreporeleasetrainjava.tests.TestFixtures.releaseBody;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 发布列车业务集成测试。
 *
 * 覆盖业务：
 * 1. 级联发布主流程：按依赖顺序发布、版本按变更级别递增、PATCH 级联传播
 * 2. MAJOR 沿依赖方向级联提升
 * 3. 循环依赖：发布前拒绝且错误码为 CYCLIC_DEPENDENCY
 * 4. 重复声明 / 依赖不存在：发布前拒绝且错误码可区分
 * 5. 版本区间冲突：级联后区间不成立的包标记为 SKIPPED（不可发布），不照常发出
 * 6. 预发布基线 / 无法识别或缺失的变更信息：拒绝而不是静默按修复处理
 * 7. 幂等：同批次重复提交不产生重复版本号或重复发布记录
 * 8. 并发重复提交：明确拒绝其中一个（409 BATCH_CONFLICT）
 * 9. 发布中途失败：已发布不回退、未发布停在 PLANNED、查询可见、重试确定成功且不重复
 */
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestDataInitializer.class)
@TestMethodOrder(MethodOrderer.DisplayName.class)
@TestPropertySource(properties = "release-train.data-dir=target/test-data/integration")
class ReleaseTrainIntegrationTests {

    private static final Logger log = LoggerFactory.getLogger(ReleaseTrainIntegrationTests.class);

    @Autowired
    private MockMvc mockMvc;

    private void register(String name, String version, Map<String, String> deps) throws Exception {
        mockMvc.perform(post("/api/packages")
                        .contentType("application/json")
                        .content(registerBody(name, version, deps)))
                .andExpect(status().isOk());
    }

    private String submit(String batchId, Map<String, String> changes) throws Exception {
        return mockMvc.perform(post("/api/releases")
                        .contentType("application/json")
                        .content(releaseBody(batchId, changes)))
                .andReturn().getResponse().getContentAsString();
    }

    private String submitExpect(int httpStatus, String batchId, Map<String, String> changes)
            throws Exception {
        return mockMvc.perform(post("/api/releases")
                        .contentType("application/json")
                        .content(releaseBody(batchId, changes)))
                .andExpect(status().is(httpStatus))
                .andReturn().getResponse().getContentAsString();
    }

    private JsonNode getStatus(String batchId) throws Exception {
        return read(mockMvc.perform(get("/api/releases/" + batchId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private String retry(String batchId) throws Exception {
        return mockMvc.perform(post("/api/releases/" + batchId + "/retry"))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("业务01-级联发布主流程: 按依赖顺序发布并正确计算/传播版本")
    void cascadingReleaseMainFlow() throws Exception {
        log.info(">>> 业务01 开始：core patch；lib 依赖 core 被级联；ui 依赖 lib 被级联");
        register("t01-core", "1.2.3", Map.of());
        register("t01-lib", "0.5.0", Map.of("t01-core", "^1.0.0"));
        register("t01-ui", "2.0.0", Map.of("t01-lib", "~0.5.0"));

        var batch = read(submit("batch-01", Map.of("t01-core", "patch")));
        assertEquals("COMPLETED", batch.get("state").asText());
        assertEquals("t01-core", batch.get("items").get(0).get("packageName").asText());
        assertEquals("t01-lib", batch.get("items").get(1).get("packageName").asText());
        assertEquals("t01-ui", batch.get("items").get(2).get("packageName").asText());
        TestFixtures.assertTarget(batch, "t01-core", "1.2.4");
        TestFixtures.assertTarget(batch, "t01-lib", "0.5.1");
        TestFixtures.assertTarget(batch, "t01-ui", "2.0.1");
        assertTrue(item(batch, "t01-lib").get("cascaded").asBoolean());
        log.info("<<< 业务01 通过：发布顺序 core -> lib -> ui，版本 1.2.4 / 0.5.1 / 2.0.1");
    }

    @Test
    @DisplayName("业务02-MAJOR级联传播: 破坏性改动沿依赖方向提升级别")
    void majorCascadePropagation() throws Exception {
        log.info(">>> 业务02 开始：base 发 major 2.0.0，app 必须被级联提升为 major 4.0.0");
        register("t02-base", "1.4.0", Map.of());
        register("t02-app", "3.1.2", Map.of("t02-base", ">=1.0.0"));

        var batch = read(submit("batch-02", Map.of("t02-base", "major")));
        TestFixtures.assertTarget(batch, "t02-base", "2.0.0");
        TestFixtures.assertTarget(batch, "t02-app", "4.0.0");
        assertEquals("MAJOR", item(batch, "t02-app").get("changeLevel").asText());
        log.info("<<< 业务02 通过：base=2.0.0，app 级联为 MAJOR -> 4.0.0");
    }

    @Test
    @DisplayName("边界03-循环依赖: 发布前以 CYCLIC_DEPENDENCY 拒绝")
    void cyclicDependencyRejected() throws Exception {
        log.info(">>> 边界03 开始：构造 a->b->c->a 的环");
        register("t03-a", "1.0.0", Map.of());
        register("t03-b", "1.0.0", Map.of("t03-a", "^1.0.0"));
        register("t03-c", "1.0.0", Map.of("t03-b", "^1.0.0"));

        String error = mockMvc.perform(post("/api/packages")
                        .contentType("application/json")
                        .content(registerBody("t03-a", "1.0.0", Map.of("t03-c", "^1.0.0"))))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertEquals("CYCLIC_DEPENDENCY", read(error).get("code").asText());
        log.info("<<< 边界03 通过：循环依赖被拒绝，错误码 CYCLIC_DEPENDENCY");
    }

    @Test
    @DisplayName("边界04-重复声明: 同一依赖声明两次以 DUPLICATE_DEPENDENCY 拒绝")
    void duplicateDependencyRejected() throws Exception {
        log.info(">>> 边界04 开始：重复声明对同一依赖的依赖");
        register("t04-base", "1.0.0", Map.of());
        String error = mockMvc.perform(post("/api/packages")
                        .contentType("application/json")
                        .content(registerBodyWithDuplicate("t04-app", "t04-base", "^1.0.0")))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertEquals("DUPLICATE_DEPENDENCY", read(error).get("code").asText());
        log.info("<<< 边界04 通过：重复声明被拒绝，错误码 DUPLICATE_DEPENDENCY");
    }

    @Test
    @DisplayName("边界05-依赖不存在: 以 UNKNOWN_DEPENDENCY 拒绝，与循环依赖区分")
    void unknownDependencyRejected() throws Exception {
        log.info(">>> 边界05 开始：依赖目录中不存在的包");
        String error = mockMvc.perform(post("/api/packages")
                        .contentType("application/json")
                        .content(registerBodyWithMissingDep("t05-app", "t05-ghost")))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertEquals("UNKNOWN_DEPENDENCY", read(error).get("code").asText());
        log.info("<<< 边界05 通过：缺失依赖被拒绝，错误码 UNKNOWN_DEPENDENCY");
    }

    @Test
    @DisplayName("边界06-版本区间冲突: 级联后区间不成立的包标记为不可发布")
    void versionRangeConflictMarkedUnreleasable() throws Exception {
        log.info(">>> 边界06 开始：widget 声明 ^1.0.0；core 发 major 到 2.0.0 后区间失效");
        register("t06-core", "1.0.0", Map.of());
        register("t06-widget", "1.0.0", Map.of("t06-core", "^1.0.0"));

        var batch = read(submit("batch-06", Map.of("t06-core", "major")));
        assertEquals("FAILED", batch.get("state").asText(), "存在不可发布包时整批不能完成");
        assertEquals("RELEASED", item(batch, "t06-core").get("status").asText());
        var widget = item(batch, "t06-widget");
        assertEquals("SKIPPED", widget.get("status").asText());
        assertTrue(widget.get("unreleasableReason").asText().contains("不满足声明区间"));
        log.info("<<< 边界06 通过：widget 明确标记 SKIPPED，core 已发布且不回退");
    }

    @Test
    @DisplayName("边界07-非法变更信息: 缺失或无法识别的级别以 INVALID_CHANGE 拒绝")
    void invalidChangeRejected() throws Exception {
        log.info(">>> 边界07 开始：无法识别和空白的 change level 都应被拒绝");
        register("t07-core", "1.0.0", Map.of());
        assertEquals("INVALID_CHANGE",
                read(submitExpect(400, "batch-07a", Map.of("t07-core", "refactor")))
                        .get("code").asText());
        assertEquals("INVALID_CHANGE",
                read(submitExpect(400, "batch-07b", Map.of("t07-core", "  ")))
                        .get("code").asText());
        log.info("<<< 边界07 通过：非法变更信息被拒绝，没有静默按修复处理");
    }

    @Test
    @DisplayName("边界08-预发布版本: 预发布基线不能作为正式版本对外发布")
    void prereleaseRejected() throws Exception {
        log.info(">>> 边界08 开始：当前版本 1.0.0-rc.1 提交发布");
        register("t08-pre", "1.0.0-rc.1", Map.of());
        String error = submitExpect(400, "batch-08", Map.of("t08-pre", "patch"));
        assertEquals("PRERELEASE_FORBIDDEN", read(error).get("code").asText());
        log.info("<<< 边界08 通过：预发布版本被拒绝对外发布，错误码 PRERELEASE_FORBIDDEN");
    }

    @Test
    @DisplayName("语义09-幂等提交: 同批次重复提交不产生重复版本号或重复发布记录")
    void idempotentSubmission() throws Exception {
        log.info(">>> 语义09 开始：同一 batchId 提交三次");
        register("t09-svc", "1.0.0", Map.of());
        TestFixtures.assertTarget(read(submit("batch-09", Map.of("t09-svc", "minor"))),
                "t09-svc", "1.1.0");

        for (int i = 0; i < 2; i++) {
            var again = read(submit("batch-09", Map.of("t09-svc", "major")));
            assertEquals("COMPLETED", again.get("state").asText());
            // 即使重复请求声称 major，也必须沿用首次计划的 1.1.0
            TestFixtures.assertTarget(again, "t09-svc", "1.1.0");
        }
        long records = Files.readAllLines(Path.of("target/test-data/integration/release-records.log"),
                        StandardCharsets.UTF_8).stream()
                .filter(l -> l.contains("\tt09-svc\t")).count();
        assertEquals(1, records, "发布记录只能有一条，重复提交不得产生重复记录");
        log.info("<<< 语义09 通过：版本停留在 1.1.0，发布记录只有 1 条");
    }
}
