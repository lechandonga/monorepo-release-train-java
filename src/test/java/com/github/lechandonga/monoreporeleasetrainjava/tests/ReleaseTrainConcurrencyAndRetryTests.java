package com.github.lechandonga.monoreporeleasetrainjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import static com.github.lechandonga.monoreporeleasetrainjava.tests.TestFixtures.releaseBody;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 并发重复提交与发布中途失败重试的集成测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestDataInitializer.class)
@TestPropertySource(properties = "release-train.data-dir=target/test-data/retry")
class ReleaseTrainConcurrencyAndRetryTests {

    private static final Logger log = LoggerFactory.getLogger(ReleaseTrainConcurrencyAndRetryTests.class);

    @Autowired
    private MockMvc mockMvc;

    private void register(String name, String version, Map<String, String> deps) throws Exception {
        mockMvc.perform(post("/api/packages")
                        .contentType("application/json")
                        .content(registerBody(name, version, deps)))
                .andExpect(status().isOk());
    }

    private int doPostStatus(String url, String body) throws Exception {
        return mockMvc.perform(post(url)
                        .contentType("application/json")
                        .content(body))
                .andReturn().getResponse().getStatus();
    }

    private String doPost(String url, String body) throws Exception {
        return mockMvc.perform(post(url)
                        .contentType("application/json")
                        .content(body))
                .andReturn().getResponse().getContentAsString();
    }

    private String doPost(String url) throws Exception {
        return mockMvc.perform(post(url))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("语义10-并发重复提交: 批次推进中的并发请求被明确拒绝(409)")
    void concurrentDuplicateRejected() throws Exception {
        log.info(">>> 语义10 开始：提交批次并在包发布处阻塞，另一个线程并发重试");
        register("t10-a", "1.0.0", Map.of());
        register("t10-b", "1.0.0", Map.of("t10-a", "^1.0.0"));

        // 在 t10-a 的发布点安装阻塞
        doPost("/api/test/blocks/t10-a", "{}");

        var submitFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return doPostStatus("/api/releases",
                        releaseBody("batch-10", Map.of("t10-a", "patch")));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // 等待提交线程真正进入发布阻塞点，此时批次处于 IN_PROGRESS
        waitForState("batch-10", "IN_PROGRESS");

        int concurrentStatus = doPostStatus("/api/releases/batch-10/retry", "");
        String concurrentBody = doPost("/api/releases/batch-10/retry", "");
        assertEquals(409, concurrentStatus, "并发推进同一批次必须被明确拒绝");
        assertEquals("BATCH_CONFLICT", read(concurrentBody).get("code").asText());

        // 释放阻塞，原提交顺利完成
        doPost("/api/test/blocks/t10-a/release", "{}");
        int submitStatus = submitFuture.get(10, TimeUnit.SECONDS);
        assertEquals(200, submitStatus);

        var batch = read(doPost("/api/releases/batch-10/retry", ""));
        assertEquals("COMPLETED", batch.get("state").asText());
        log.info("<<< 语义10 通过：并发请求收到 409/BATCH_CONFLICT，原请求完成，最终重试幂等");
    }

    @Test
    @DisplayName("语义11-中途失败重试: 已发布不回退、未发布不留半成品、重试确定成功且不重复")
    void failureAndRetry() throws Exception {
        log.info(">>> 语义11 开始：让 mid 包第一次发布失败，验证进度查询与重试");
        register("t11-base", "1.0.0", Map.of());
        register("t11-mid", "2.0.0", Map.of("t11-base", "^1.0.0"));
        register("t11-top", "3.0.0", Map.of("t11-mid", "^2.0.0"));

        // mid 的第 1 次发布尝试失败，之后恢复
        doPost("/api/test/failures",
                "{\"packageName\":\"t11-mid\",\"times\":1}");

        var first = read(doPost("/api/releases",
                releaseBody("batch-11", Map.of("t11-base", "patch"))));
        assertEquals("FAILED", first.get("state").asText());
        assertEquals("RELEASED", item(first, "t11-base").get("status").asText(),
                "已发布的包保持 RELEASED");
        assertEquals("1.0.1", item(first, "t11-base").get("targetVersion").asText());
        assertEquals("FAILED", item(first, "t11-mid").get("status").asText(),
                "失败包停在 FAILED，不产生半成品");
        assertTrue(item(first, "t11-mid").get("failureReason").asText().contains("可重试"));
        assertEquals("PLANNED", item(first, "t11-top").get("status").asText(),
                "尚未轮到的包必须保持 PLANNED");

        // 进度可查询
        JsonNode queried = read(mockMvc.perform(get("/api/releases/batch-11"))
                .andReturn().getResponse().getContentAsString());
        assertEquals("FAILED", queried.get("state").asText());
        log.info("批次进度可查询：base=RELEASED, mid=FAILED, top=PLANNED");

        // 重试：结果确定，版本号不跳号、不重复
        var retried = read(doPost("/api/releases/batch-11/retry", ""));
        assertEquals("COMPLETED", retried.get("state").asText());
        TestFixtures.assertTarget(retried, "t11-base", "1.0.1");
        TestFixtures.assertTarget(retried, "t11-mid", "2.0.1");
        TestFixtures.assertTarget(retried, "t11-top", "3.0.1");

        // 再次重试必须完全幂等，发布记录不增加
        long recordsBefore = countRecords();
        var again = read(doPost("/api/releases/batch-11/retry", ""));
        assertEquals("COMPLETED", again.get("state").asText());
        assertEquals(recordsBefore, countRecords(), "重试完成后再次重试不得新增发布记录");
        log.info("<<< 语义11 通过：重试后 1.0.1 / 2.0.1 / 3.0.1，发布记录每包恰好一条");
    }

    private long countRecords() throws Exception {
        Path file = Path.of("target/test-data/retry/release-records.log");
        if (!Files.exists(file)) {
            return 0;
        }
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .filter(l -> l.contains("batch-11")).count();
    }

    private void waitForState(String batchId, String expectedState) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                JsonNode batch = read(mockMvc.perform(get("/api/releases/" + batchId))
                        .andReturn().getResponse().getContentAsString());
                if (expectedState.equals(batch.get("state").asText())) {
                    return;
                }
            } catch (Exception ignored) {
                // 批次尚未创建
            }
            Thread.sleep(50);
        }
        throw new AssertionError("等待批次进入 " + expectedState + " 超时");
    }
}
