# Monorepo 发布列车（Release Train）

一套完全本地运行的整批发布服务：给定一组相互依赖的包及其变更级别，服务决定
“哪些包该发、各自发到什么版本、按什么顺序发”，并保证整批发布不出现半发布状态。
不依赖任何外部仓库服务或凭据，制品与批次状态都落盘在本地目录。

## 1. 概念与流程

- 一次请求是一个**批次（batch，由 `batchId` 标识）**，包含包定义快照和变更列表。
- 处理流程：静态校验 → 计算并固化发布计划 → 按拓扑顺序逐个发布 → 每完成一个包即持久化进度。
- 批次状态：`IN_PROGRESS`（进行中）、`COMPLETED`（全部到达终态）、`PARTIALLY_FAILED`（中途失败，可重试）。
- 单个包的状态：`PENDING`（待发布）、`PUBLISHED`（已发布）、`FAILED`（发布失败，可重试）、`BLOCKED`（不可发布，确定性终态）。

## 2. 版本计算规则

版本采用语义化版本 `MAJOR.MINOR.PATCH[-prerelease]`。

| 变更级别 | 计算方式 | 示例 |
| --- | --- | --- |
| `MAJOR`（破坏性改动） | 主版本 +1，次/补丁清零 | `1.4.2 → 2.0.0` |
| `MINOR`（新功能） | 次版本 +1，补丁清零 | `1.0.0 → 1.1.0` |
| `PATCH`（修复） | 补丁 +1 | `2.0.3 → 2.0.4` |

**级联传播**：变更级别沿依赖方向（被依赖者 → 依赖方）传播。
一个包的有效级别 = `max(自身声明级别, 所有将发布的被依赖者的有效级别)`，
即依赖方跟随依赖链上的最高变更级别。自身没有变更、也没有任何被依赖者要发布的包，
不会进入本次发布序列。

规则约束：

- **预发布版本禁止正式发布**：任何包的当前版本带预发布后缀（如 `2.0.0-rc1`）时，
  整批在开始发布前以 `PRERELEASE_FORBIDDEN` 拒绝；版本区间也永不接受预发布版本。
- **变更信息必须显式给出**：变更级别缺失、为空或无法识别（不是 MAJOR/MINOR/PATCH），
  在开始发布前以 `INVALID_CHANGE` 拒绝；空变更列表同样拒绝——不会静默按 PATCH 处理。

## 3. 依赖约束的判定方式

开始发布前进行静态校验，问题类型通过错误码明确区分：

| 错误码 | 触发条件 |
| --- | --- |
| `DUPLICATE_PACKAGE` | 同一批里重复声明了同名包，或同一包出现多条变更 |
| `DUPLICATE_DEPENDENCY` | 同一个包对同一依赖书写了两遍（请求体 JSON 重复键） |
| `UNKNOWN_DEPENDENCY` | 依赖了本批中不存在的包 |
| `CYCLIC_DEPENDENCY` | 依赖图存在环 |
| `INVALID_VERSION` | 当前版本号或依赖版本区间表达式非法 |

以上任何一种失败都发生在第一个包发布之前，且不会产生批次记录或制品。

支持的版本区间表达式：

- `^1.2.3`：兼容区间 `[1.2.3, 2.0.0)`；`0.x` 时为 `[0.y.z, 0.(y+1).0)`
- `~1.2.3`：补丁兼容区间 `[1.2.3, 1.3.0)`
- 比较器组合：`>=1.2.3,<2.0.0`（支持 `>= > <= < =`，逗号或空格分隔）
- 精确版本：`1.2.3`

**级联后的区间校验**：计划阶段计算出每个包的新版本后，对每条依赖声明重新判定。
若被依赖者的新版本（未发布则看其当前版本）不再落在声明区间内，依赖方被标记为
`BLOCKED` 并附带原因（错误语义 `RANGE_CONFLICT`），**绝不照常发布**；
不可发布性沿依赖方向传导——依赖了 BLOCKED 包的包同样 BLOCKED。

## 4. 幂等、并发与重试语义

- **计划固化**：首次提交时把包定义快照与每个包的目标版本写入批次 JSON（原子落盘）。
  之后无论重放还是重试，都只复用这份计划，因此结果确定，不跳号、不改号。
- **重复提交幂等**：用相同 `batchId` 再次提交时：
  - 批次已到终态（`COMPLETED`）：直接返回既有结果，不新增版本号、不新增发布记录；
  - 批次正在执行中：以 `BATCH_CONFLICT`（HTTP 409）明确拒绝，而不是排队制造重复发布。
- **并发安全**：所有批次的执行由一把公平的列车锁串行化，制品写入为
  “临时文件 + 原子 move”。相同批次并发提交时，要么串行成功，要么其中之一收到
  `BATCH_CONFLICT`，二者都不会产生重复制品。
- **中途失败不回退**：某个包发布失败时立即“停车”：
  - 已 `PUBLISHED` 的包保持已发布，不回退；
  - 失败包记为 `FAILED` 并保存原因，未开始的包保持 `PENDING`；
  - 不存在半成品：制品只有“原子可见”或“不存在”两种状态，批次文件也是原子替换。
- **可查询、可重试**：`GET` 可随时查询整批进度与每个包的状态/原因；
  对 `PARTIALLY_FAILED` 批次调用重试接口，只推进 `PENDING/FAILED` 的包，
  已发布的包幂等跳过（本地发布器对同一 `包+版本` 天然去重），
  仍按固化的拓扑顺序与版本执行，直到 `COMPLETED`。

## 5. HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/release-trains` | 提交整批发布（同 batchId 重复提交为幂等重放） |
| POST | `/api/release-trains/{batchId}/retry` | 对中途失败的批次做确定性重试 |
| GET | `/api/release-trains/{batchId}` | 查询批次进度与逐包状态 |

请求示例：

```json
{
  "batchId": "train-2026-10-07",
  "packages": [
    {"name": "util", "version": "1.4.2", "dependencies": {}},
    {"name": "core", "version": "1.2.0", "dependencies": {"util": "^1.0.0"}}
  ],
  "changes": [
    {"packageName": "util", "level": "MAJOR"}
  ]
}
```

错误响应示例（HTTP 422/409/404）：

```json
{
  "code": "CYCLIC_DEPENDENCY",
  "message": "检测到循环依赖，涉及包: a, b",
  "description": "存在循环依赖",
  "timestamp": "2026-10-07T05:00:00Z"
}
```

## 6. 本地验证

前置：JDK 21+、Maven 3.6+（离线可用，依赖已在本地仓库）。

```bash
# 运行全部测试（18 个用例）
mvn test

# 只跑核心业务用例
mvn -Dtest=ReleaseTrainServiceTest test

# 启动服务（默认数据目录 ./release-train-data，可用 release-train.data-dir 覆盖）
mvn spring-boot:run
```

手工冒烟：

```bash
curl -s -XPOST localhost:8080/api/release-trains \
  -H 'Content-Type: application/json' \
  -d '{"batchId":"demo","packages":[
        {"name":"util","version":"1.0.0","dependencies":{}},
        {"name":"app","version":"1.0.0","dependencies":{"util":"^1.0.0"}}],
       "changes":[{"packageName":"util","level":"PATCH"}]}'

curl -s localhost:8080/api/release-trains/demo
```

查看落盘结果：`release-train-data/batches/demo.json`（批次计划与进度）、
`release-train-data/registry/<包名>/<版本>.txt`（已发布制品）。

## 7. 测试覆盖的业务

测试运行时通过 `BusinessCaseLogger` 在日志中逐条输出“业务用例 → 结果”，
可在 `mvn test` 输出中搜索 `BUSINESS-CASES` 查看覆盖点。

- **级联主流程**：MAJOR/MINOR 级联、拓扑发布顺序、区间不成立时 BLOCKED、制品实际落盘。
- **依赖边界**：循环依赖、未知依赖、重复包、重复依赖、区间冲突、预发布版本、非法/缺失变更级别。
- **幂等与并发**：同批重复提交不产生重复版本号/记录；8 线程并发同批，串行成功或冲突拒绝，制品唯一。
- **失败重试**：注入一次性发布失败，验证已发布不回退、无半成品、进度可查询、重试确定完成且不重复。
- **HTTP 层**：成功提交流程、各类错误码可区分、重复依赖被拒绝、BLOCKED 状态对外可见。
