# Monorepo 发布列车服务

面向一组相互依赖的包做整批发布：提交一批包的变更信息，服务计算实际需要发布的包、
每个包的目标版本，并按依赖方向顺序推进发布。全部状态保存在本地文件
（默认 `data/` 目录），不依赖任何外部服务或凭据。

## 1. 版本计算规则

- 版本遵循语义化版本（SemVer）`MAJOR.MINOR.PATCH`，可选 `-prerelease` 与 `+build`。
- 变更级别三档，中文与英文关键字等价：
  - 破坏性改动：`major` / `breaking` / `破坏性改动`
  - 新功能：`minor` / `feature` / `新功能`
  - 修复：`patch` / `fix` / `修复`
- 递增规则：
  - `MAJOR`：`(major+1).0.0`
  - `MINOR`：`major.(minor+1).0`
  - `PATCH`：`major.minor.(patch+1)`
  - 首次发布（无当前版本）：`major` 从 `1.0.0` 起步，其余从 `0.1.0` 起步。
- 变更信息为 `null`、空白或无法识别的字面量时，整批请求以 `INVALID_CHANGE` 拒绝，
  **不会**静默按修复级别处理。
- 预发布版本（如 `1.0.0-rc.1`）不能作为正式发布的基线，计算结果也不允许带预发布后缀，
  违反时以 `PRERELEASE_FORBIDDEN` 拒绝。

### 级联传播

- 只要包的（传递）依赖出现在本批发布集合中，该包也必须发布，即使调用方没有直接提交它；
  响应中 `cascaded=true` 标识这类包。
- 被级联的包默认至少按 `PATCH` 重发。
- 被依赖方做 `MAJOR` 发布时，破坏性改动沿依赖方向传播，依赖方的级别提升为 `MAJOR`。
- 传播闭包在拓扑排序之前计算；拓扑序即发布顺序（被依赖方先发）。

## 2. 依赖约束的判定方式

依赖声明为“被依赖包名 + 版本区间”，支持的区间语法（多个比较器用空格连接表示交集）：

| 写法 | 含义 |
| --- | --- |
| `^1.2.3` | `>=1.2.3 <2.0.0`；`0.x` 下按 minor/patch 收窄 |
| `~1.2.3` | `>=1.2.3 <1.3.0` |
| `>=` `>` `<=` `<` `=` | 比较器，`=` 可省略（`1.2.3` 即精确版本） |
| `*` / `x` / `any` | 任意版本 |

级联计算出依赖的目标版本后，对每个依赖方声明的区间逐一判定：

- 区间仍满足：正常发布（必要时按 MAJOR 级联提升）。
- 区间不再满足：该包**明确标记为不可发布**（条目状态 `SKIPPED`，
  并给出 `unreleasableReason`，例如“依赖 core 级联后的目标版本 2.0.0 不满足声明区间 ^1.0.0”），
  不会照常发出去；批次最终状态为 `FAILED`，已发布的包保持已发布不回退。
- 区间表达式本身无法解析时以 `INVALID_VERSION_RANGE` 拒绝。

### 发布前必须通过的校验（错误码可区分）

- `DUPLICATE_DEPENDENCY`：同一个包重复声明对同一依赖的依赖。
- `UNKNOWN_DEPENDENCY`：依赖了目录中不存在的包。
- `CYCLIC_DEPENDENCY`：依赖图存在环（错误信息包含环路径）。

以上全部在任何发布动作发生之前校验，失败不会产生任何版本或发布记录。

## 3. 发布顺序与失败语义

- 批次按拓扑序逐个发布；同一进程内用公平锁串行推进，同一批次另有批次级锁。
- 单个包的发布是“追加发布记录 + 更新目录当前版本”的本地操作，记录形如：
  `时间戳\t批次ID\t包名\t版本`（`data/release-records.log`）。
- 某包发布失败时：
  - 已发布包保持 `RELEASED`，**不回退**；
  - 失败包停在 `FAILED` 并记录原因；
  - 尚未轮到的包保持 `PLANNED`，不存在半成品；
  - 批次状态为 `FAILED`，进度通过查询接口随时可见。
- 调用 `POST /api/releases/{batchId}/retry` 从第一个未完成包继续：
  - 已 `RELEASED` 的包跳过；发布器以“批次+包+版本”为键做幂等，
    即使记录已写而目录更新失败，重试也只会得到一次发布记录；
  - 目标版本在规划时就固定并随批次持久化，重试**结果确定、不跳号、不重复**。

## 4. 幂等与并发

- **同一 `batchId` 重复提交**：复用首次规划（版本集合固定不变），
  随后做一次幂等推进；不产生新版本号、不产生重复发布记录。
  即使重复请求里填写了不同的变更级别也以首次计划为准。
- **并发推进**：
  - 同一批次正在推进时，并发的提交/重试立即收到 `409 BATCH_CONFLICT`，
    不会有两个线程同时发布同一批次。
  - 不同批次的推进在同一进程内互斥；拿不到执行权的请求同样收到
    `409 BATCH_CONFLICT`（语义为“安全串行或明确拒绝”中的明确拒绝，稍后重试即可）。
- 批次进度持久化在 `data/batches.json`，包目录在 `data/catalog.json`，
  进程重启后进度仍可查询、可继续重试。

## 5. HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/packages` | 注册/更新包（含依赖声明，发布前校验图） |
| GET | `/api/packages` | 查看包目录 |
| POST | `/api/releases` | 提交整批发布 `{batchId, changes}` |
| GET | `/api/releases/{batchId}` | 查询批次进度与每个包的状态/版本/原因 |
| POST | `/api/releases/{batchId}/retry` | 重试未完成批次（并发推进返回 409） |
| POST | `/api/test/failures` | 本地测试：让某包接下来 N 次发布失败 |
| POST | `/api/test/blocks/{name}` / `.../release` | 本地测试：阻塞/释放发布点，用于并发验证 |

错误响应统一为 `{"code": "<错误码>", "message": "..."}`，
`BATCH_NOT_FOUND` 为 404，`BATCH_CONFLICT` 为 409，其余发布前校验错误为 400。

### curl 快速示例

```bash
curl -s localhost:8080/api/packages -H 'Content-Type: application/json' -d '{
  "name":"core", "currentVersion":"1.2.3", "dependencies":[]}'
curl -s localhost:8080/api/packages -H 'Content-Type: application/json' -d '{
  "name":"web", "currentVersion":"2.0.0",
  "dependencies":[{"name":"core","versionRange":"^1.0.0"}]}'
curl -s localhost:8080/api/releases -H 'Content-Type: application/json' -d '{
  "batchId":"b-001", "changes":{"core":"patch"}}'
curl -s localhost:8080/api/releases/b-001
curl -s -X POST localhost:8080/api/releases/b-001/retry
```

## 6. 本地验证

前置：JDK 21+、Maven 3.9+（已在本地 Maven 仓库有 Spring Boot 3.4 依赖时可离线运行）。

```bash
mvn test                 # 运行全部 12 个测试
mvn -o test              # 离线模式
mvn spring-boot:run      # 启动服务，然后用上面的 curl 验证
```

测试与业务的对应关系（`@DisplayName` 与日志中的 `>>> 业务xx / 边界xx / 语义xx` 一致，
Surefire 报告位于 `target/surefire-reports/`）：

- 业务01：级联发布主流程（顺序 + PATCH 级联版本）
- 业务02：MAJOR 沿依赖方向级联提升
- 边界03：循环依赖 → `CYCLIC_DEPENDENCY`
- 边界04：重复声明 → `DUPLICATE_DEPENDENCY`
- 边界05：依赖不存在 → `UNKNOWN_DEPENDENCY`
- 边界06：版本区间冲突 → 依赖方 `SKIPPED` 且批次 `FAILED`
- 边界07：缺失/无法识别的变更信息 → `INVALID_CHANGE`
- 边界08：预发布基线 → `PRERELEASE_FORBIDDEN`
- 语义09：同批次重复提交幂等（版本不递增、发布记录不重复）
- 语义10：并发重复提交 → 一个成功、另一个 `409 BATCH_CONFLICT`
- 语义11：中途失败 → 已发布不回退、其余半成品保护、进度可查、重试确定成功且不重复
