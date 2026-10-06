package com.github.lechandonga.monoreporeleasetrainjava.releasetrain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP 接口业务：成功提交、错误码区分、重复依赖拒绝、进度查询。 */
@SpringBootTest(properties = "release-train.data-dir=target/release-train-webtest")
@AutoConfigureMockMvc
class ReleaseTrainWebTest {

    @RegisterExtension
    static final BusinessCaseLogger LOGGER = new BusinessCaseLogger();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 主流程_提交整批发布并查询进度() throws Exception {
        String body = """
                {
                  "batchId": "web-happy",
                  "packages": [
                    {"name": "util", "version": "1.0.0", "dependencies": {}},
                    {"name": "app", "version": "1.0.0",
                     "dependencies": {"util": "^1.0.0"}}
                  ],
                  "changes": [
                    {"packageName": "util", "level": "PATCH"},
                    {"packageName": "app", "level": "MINOR"}
                  ]
                }
                """;
        mockMvc.perform(post("/api/release-trains")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.plans[0].state").value("PUBLISHED"));

        mockMvc.perform(get("/api/release-trains/web-happy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value("web-happy"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void 边界_循环依赖返回CYCLIC_DEPENDENCY错误码() throws Exception {
        String body = """
                {
                  "batchId": "web-cyc",
                  "packages": [
                    {"name": "a", "version": "1.0.0", "dependencies": {"b": "^1.0.0"}},
                    {"name": "b", "version": "1.0.0", "dependencies": {"a": "^1.0.0"}}
                  ],
                  "changes": [{"packageName": "a", "level": "PATCH"}]
                }
                """;
        mockMvc.perform(post("/api/release-trains")
                        .contentType("application/json").content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CYCLIC_DEPENDENCY"));
    }

    @Test
    void 边界_未知依赖返回UNKNOWN_DEPENDENCY错误码() throws Exception {
        String body = """
                {
                  "batchId": "web-unk",
                  "packages": [
                    {"name": "a", "version": "1.0.0", "dependencies": {"ghost": "^1.0.0"}}
                  ],
                  "changes": [{"packageName": "a", "level": "PATCH"}]
                }
                """;
        mockMvc.perform(post("/api/release-trains")
                        .contentType("application/json").content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNKNOWN_DEPENDENCY"));
    }

    @Test
    void 边界_重复依赖声明返回DUPLICATE_DEPENDENCY错误码() throws Exception {
        String body = """
                {
                  "batchId": "web-dup-dep",
                  "packages": [
                    {"name": "a", "version": "1.0.0", "dependencies": {}},
                    {"name": "b", "version": "1.0.0",
                     "dependencies": {"a": "^1.0.0", "a": "~1.0.0"}}
                  ],
                  "changes": [{"packageName": "a", "level": "PATCH"}]
                }
                """;
        mockMvc.perform(post("/api/release-trains")
                        .contentType("application/json").content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DUPLICATE_DEPENDENCY"));
    }

    @Test
    void 边界_区间冲突时依赖方在响应中被标记为BLOCKED() throws Exception {
        String body = """
                {
                  "batchId": "web-range",
                  "packages": [
                    {"name": "lib", "version": "1.5.0", "dependencies": {}},
                    {"name": "app", "version": "3.0.0",
                     "dependencies": {"lib": "~1.5.0"}}
                  ],
                  "changes": [{"packageName": "lib", "level": "MINOR"}]
                }
                """;
        mockMvc.perform(post("/api/release-trains")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.plans[?(@.packageName=='app')].state")
                        .value(org.hamcrest.Matchers.contains("BLOCKED")));
    }
}
