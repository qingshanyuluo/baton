# Anthropic API Failover 网关

## 问题定义

本地有 3 个 Anthropic 格式的 API 端点（可能是不同的代理、不同的 key pool、或不同的部署实例）。需要一个统一入口，对外暴露标准 Anthropic API 格式，内部用 failover 逻辑在 3 个后端之间切换：一个挂了自动切到下一个，对调用方透明。

为什么现在要解决：这是 AI 网关的最基础能力 — 高可用接入。没有这个，单点故障直接影响所有下游。

## 方案

### 架构

```
调用方 → Baton (Anthropic API) → Backend 1 (primary)
                                → Backend 2 (secondary)
                                → Backend 3 (tertiary)
```

### 核心组件

1. **AnthropicProxyController** — 接收 Anthropic 格式请求（`POST /v1/messages`），透传 headers（含 `x-api-key`、`anthropic-version`）
2. **BackendRegistry** — 管理 3 个后端的地址和状态，从配置文件加载
3. **FailoverRouter** — 按优先级尝试后端，失败时切换到下一个
4. **HealthTracker** — 记录每个后端的健康状态，决定是否跳过已知不健康的后端

### Failover 逻辑

请求进来时：
1. **缓存请求体**：将完整 request body 读入内存（Anthropic Messages API 的请求体是 JSON，典型大小 1KB-1MB，即使包含 base64 图片也在可控范围内）。缓存是 failover 重试的前提 — 不缓存就无法重发。
2. 按优先级排序可用后端（跳过被标记为 unhealthy 的）
3. 向第一个后端转发请求（使用缓存的 body）
4. 如果成功 → 返回响应
5. 如果失败（连接超时、5xx、529、429、连接拒绝）→ 尝试下一个后端
   - **触发 failover 的状态码**：5xx（服务端错误）、529（Anthropic overloaded）、429（rate limited）、连接拒绝、连接超时
   - **不触发 failover 的状态码**：400（bad request）、401（unauthorized）、403（forbidden）、404、422 等 — 这些是调用方的问题，换后端也不会好，直接返回给调用方
   - **unhealthy 标记策略（区分硬故障和软故障）**：
     - 硬故障（5xx、529、连接拒绝、连接超时）→ 立即标记 unhealthy，走完整恢复流程（3 次健康检查）
     - 软故障（429 rate limited）→ 触发 failover 但**不标记 unhealthy**。429 表示"暂时过载"而非"服务不可用"，后端本身是健康的。如果 3 个后端都 429（共享 key pool 场景），最终返回最后一个 429 响应给调用方（让调用方看到 rate limit 信息并自行决定重试时机）
6. 所有后端都失败 → 返回 502 Bad Gateway
7. **全局请求超时**：从收到调用方请求开始计时，总耗时超过 `global-timeout`（默认 60s）则立即停止 failover 尝试，返回 504 Gateway Timeout。**精确语义：global-timeout 覆盖从"收到请求"到"任意后端返回第一个响应字节"的整个阶段，包含所有 failover 切换和等待时间。** 即使某个后端 TCP 连接成功但迟迟不返回字节，只要总时间超过 60s 就截断。一旦某个后端开始返回数据（第一个字节到达），global-timeout 停止，改由 `streaming-idle-timeout` 控制。

### 健康恢复

被标记为 unhealthy 的后端不会永远被跳过：
- 每 30 秒尝试一次健康检查
- 健康检查方式：`HTTP GET {backend.url}/v1/models`（Anthropic 兼容端点，返回 200 即通过，不消耗 token）。如果后端不支持此端点（返回 404），则降级为 TCP connect 检测（能建立连接即通过）
- 连续 3 次健康检查通过 → 恢复为 healthy
- 恢复后重新加入优先级队列
- 健康检查超时：3s（与 connect-timeout 一致）
- **TCP fallback 的已知限制**：当后端是 nginx 反代且上游挂了时，TCP 检查会通过但请求仍会 5xx，导致每 30s 有一批请求被发到坏后端后 failover。这是可接受的降级行为（不丢数据，只增加一次 failover 延迟）。缓解措施：TCP fallback 时 threshold 提高到 5 次（而非 3 次），减少振荡频率。

**HealthTracker 并发语义：**
- `unhealthy` 标记：任何请求线程发现后端失败时原子设置（CAS），无锁竞争问题
- `healthy` 恢复：仅由健康检查定时线程设置（通过 3 次连续成功后）
- 如果健康检查恢复过程中（已通过 1-2 次），有请求线程发现该后端失败 → 重置计数器归零，重新开始 3 次计数
- 最坏情况：一个请求被发到"刚恢复但实际仍有问题"的后端 → 该请求走正常 failover 流程，后端再次被标记 unhealthy。无数据丢失风险。

### 请求透传规则

- Request body：先完整读入内存缓存（用于 failover 重试），再转发。不做解析不做修改。**入口校验：body 超过 `max-body-size`（默认 20MB）直接返回 413 Request Entity Too Large，不进入 failover 流程。**
- Request headers：**全部透传**，仅剥离 hop-by-hop headers（`Connection`、`Keep-Alive`、`Transfer-Encoding`、`TE`、`Trailer`、`Upgrade`、`Proxy-Authorization`、`Proxy-Authenticate`）。这确保 `anthropic-beta`、`anthropic-dangerous-direct-browser-access`、`x-request-id` 等功能性 header 和未来新增 header 自动透传，无需网关逐个适配。
- Response（非 streaming）：原样返回给调用方，包括 status code 和 headers
- Response（streaming/SSE）：透传 `text/event-stream` 响应。一旦开始向调用方发送 SSE 数据，failover 不再可能（HTTP 200 已发出，无法改 status code）。**Streaming 阶段的超时由 `streaming-idle-timeout`（默认 5m）控制 — 两个 SSE event 之间的最大静默时间。** 设为 5 分钟是因为 Claude extended thinking 可以持续数分钟无输出，30s 的 read-timeout 会误杀正常请求。如果 5 分钟内无任何数据，判定后端已断开，向调用方发送 error event 后关闭连接：
  ```
  event: error
  data: {"type":"error","error":{"type":"api_error","message":"Backend connection lost during streaming"}}
  ```
  Anthropic SDK 能识别 `event: error` 类型并抛出对应异常，不会 parse error。

### 配置

```yaml
baton:
  backends:
    - name: primary
      url: http://localhost:8081
      priority: 1
    - name: secondary
      url: http://localhost:8082
      priority: 2
    - name: tertiary
      url: http://localhost:8083
      priority: 3
  failover:
    connect-timeout: 3s
    read-timeout: 30s
    streaming-idle-timeout: 5m
    global-timeout: 60s
    max-body-size: 20MB
    max-concurrent-requests: 200
    health-check-interval: 30s
    health-check-threshold: 3
```

## 接口契约

### 对外接口（与 Anthropic API 完全一致）

```
POST /v1/messages
Headers: x-api-key, anthropic-version, content-type
Body: Anthropic Messages API request body
Response: Anthropic Messages API response (normal or streaming)
```

### 额外暴露的管理接口

管理接口作为 Spring Boot Actuator `@Endpoint` 实现，绑定在 management port（默认 8090），路径为 `/actuator/backends`。

```
GET  /actuator/backends — 查看后端状态
POST /actuator/backends/{name}  {"action": "enable|disable"}  — 启用/禁用
```

**鉴权假设**：管理接口绑定独立端口（`management-port`，默认 8090，与业务端口 8080 分离），仅内网可达。v1 不做应用层鉴权，依赖网络隔离（防火墙/k8s NetworkPolicy）。这是已知的安全边界假设。

**实现说明**：`isStreamingRequest` 使用 Jackson 解析 JSON `stream` 字段判断是否 streaming 模式。这是必要的轻量解析（仅读取一个 boolean 字段），不做修改或重新序列化，body 原样转发。设计约束"不解析 body"修正为"不做修改，仅最小解析"。

## 边界条件

- 3 个后端全部不可用：返回 502，body 为 Anthropic error 格式 `{"type":"error","error":{"type":"overloaded_error","message":"All backends unavailable"}}`
- 请求体较大（含 base64 图片，可达数 MB）：仍然完整缓存到内存，但入口有 `max-body-size` 校验（超过直接 413）。并发保护：`max-concurrent-requests`（默认 200）限制同时处理的请求数，超过返回 503 Service Unavailable。最坏内存占用：200 × 20MB = 4GB body 缓存，JVM 需配置足够堆内存。
- 后端响应慢但没超时：不算失败，等待直到 read-timeout 或 global-timeout 先到
- 并发请求同时触发 failover：每个请求独立决策，不互相阻塞
- 全局超时到达时正在等待后端响应：立即断开与后端的连接，返回 504 给调用方

## 失败场景

| 场景 | 行为 |
|------|------|
| Backend 1 连接拒绝 | 标记 unhealthy，立即尝试 Backend 2 |
| Backend 1 返回 5xx | 标记 unhealthy，立即尝试 Backend 2 |
| Backend 1 返回 4xx | 区分：429 触发 failover 但不标记 unhealthy（软故障）；529 触发 failover 且标记 unhealthy（硬故障）；400/401/403/404/422 不 failover，直接返回 |
| Backend 1 read timeout | 标记 unhealthy，尝试 Backend 2（受 global-timeout 约束，总时间不超过 60s） |
| Streaming 中途 5m 无数据 | 判定断连，发送 `event: error` 后关闭连接，不重试 |
| 所有后端 unhealthy | 按优先级依次全部尝试（unhealthy 标记的跳过优化此时无意义，全试一遍） |
| 配置为空（0 个后端） | 启动时报错，拒绝启动 |

## 质疑记录

### Round 1

1. **Q: Streaming + 不缓存 body + failover 矛盾** → A: 修正为"完整缓存请求体到内存"。请求体是 JSON（≤20MB），缓存是 failover 的前提。响应才是流式的。
2. **Q: Streaming 中途断开的 error event 格式** → A: 明确为 `event: error\ndata: {"type":"error",...}`，Anthropic SDK 原生支持此 event type。
3. **Q: 健康检查的具体请求** → A: `GET /v1/models`（不消耗 token），404 则降级为 TCP connect。超时 3s。
4. **Q: 所有后端 unhealthy 时试一个还是全试** → A: 全部按优先级依次尝试（此时跳过优化无意义）。
5. **Q: 90s 最坏延迟无全局超时** → A: 增加 global-timeout=60s，从收到请求开始计时，到期立即返回 504。

### Round 2

1. **Q: 429 应该触发 failover** → A: 细化 failover 触发条件：429（rate limited）和 529（overloaded）触发 failover；400/401/403/404/422 不触发。
2. **Q: global-timeout 与 streaming 响应冲突** → A: 明确 global-timeout 仅约束"拿到第一个响应字节之前"的阶段。streaming 开始后由 read-timeout（chunk 间空闲超时）控制，不会截断长生成。
3. **Q: 并发内存无上界** → A: 增加 max-body-size=20MB（入口校验，超过返回 413）+ max-concurrent-requests=200（超过返回 503）。最坏 4GB，需配置 JVM 堆。
4. **Q: HealthTracker 并发语义** → A: unhealthy 由请求线程原子设置；healthy 仅由健康检查线程设置（3 次连续通过）；请求失败重置计数器。
5. **Q: 管理接口无鉴权** → A: 管理接口绑定独立端口（8090），依赖网络隔离，v1 不做应用层鉴权，声明为已知安全边界假设。

### Round 3

1. **Q: read-timeout 30s 会误杀 extended thinking** → A: 引入 `streaming-idle-timeout`（默认 5m），streaming 阶段用此超时而非 read-timeout。5 分钟足够覆盖 Claude 最长思考时间。
2. **Q: 429 标记 unhealthy 在共享 key pool 下会导致全部 unhealthy** → A: 429 改为"软故障"：触发 failover 但不标记 unhealthy。全部 429 时返回最后一个 429 响应给调用方。
3. **Q: global-timeout 与 read-timeout 在 failover 中的交互** → A: 明确 global-timeout 覆盖整个"首字节前"阶段（含所有 failover 切换 + 等待），无论后端是否 TCP 连接成功。60s 到即截断。
4. **Q: TCP 健康检查导致振荡** → A: 承认为已知限制（不丢数据，只增延迟）。缓解：TCP fallback 时 threshold 提高到 5 次。
5. **Q: Header 白名单不完整** → A: 改为全部透传，仅剥离 hop-by-hop headers。未来新增 header 自动兼容。
