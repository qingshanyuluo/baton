# Product Evolution Roadmap

基于 TensorZero 网关拆分为数据面 + 控制面的新产品。保留 provider 实现、路由逻辑、OTEL 可观测层，用自建控制面替代 DB 直写和管理面板。

## 目标架构

```
                         OTEL events
gateway (数据面) ───────────────────> control-plane (控制面)
  :3000                                :XXXX
  无状态/零DB/可扩缩                     收OTEL事件/清洗/存储/查询API
  推理路由                              反馈收集/配置管理
  发OTEL span                          按需落入ClickHouse/Postgres

配置中心 ──JSON──> gateway (热加载)
```

## 里程碑一：gateway 纯数据面

**目标**：gateway 不直连任何 DB，只做路由推理，OTEL span 承载所有观测数据。

- [ ] `observability.enabled` 默认 `false`
- [ ] 不设 `TENSORZERO_CLICKHOUSE_URL` / `TENSORZERO_POSTGRES_URL` 时正常启动
- [ ] 删除 `write_inference` / `write_model_inference` 的 DB 写入逻辑
- [ ] 确保 OTEL span 包含所有必要字段（timing、tokens、function/variant/model、raw_request/response 摘要）
- [ ] `cargo test-unit-fast` 全绿
- [ ] gateway 可作为无状态服务水平扩容

## 里程碑二：配置中心化

**目标**：TOML 文件配置 → 从配置中心拉 JSON，支持热加载。

- [ ] `UninitializedConfig` 支持 JSON 反序列化
- [ ] 写 `config-watcher`：HTTP 拉配置 + watch 变更 + 通知 gateway reload
- [ ] 不碰现有 provider 实现、variant 路由逻辑
- [ ] 兼容现有 TOML 配置格式（过渡期）

## 里程碑三：控制面独立

**目标**：独立的 control-plane 服务，收 OTEL 事件，做数据清洗和存储。

- [ ] 新 crate 或独立服务，收 OTEL collector 转发的 span
- [ ] 数据清洗/变换/聚合管道
- [ ] 按需落入 ClickHouse/Postgres/自建存储
- [ ] 暴露查询 API（推理历史、反馈聚合、延迟统计）
- [ ] 反馈收集：gateway 收反馈 → OTEL event → control-plane 消费

## 不动（直接继承）

| 组件 | 说明 |
|---|---|
| 20+ provider 实现 | OpenAI, Anthropic, AWS Bedrock, GCP, Azure, DeepSeek, ... |
| variant/model 路由 | chat_completion, model fallback, shorthand 解析 |
| tensorzero-http | HTTP/2 连接池 + traceparent 注入 |
| tensorzero-auth | API key 鉴权（后续按需启用） |
| OTEL 可观测层 | tracing subscriber、OTLP export、GenAI conventions |
| 配置类型系统 | UninitializedConfig、FunctionConfig、ModelTable |

## 暂时不管（不编译进 binary）

- Autopilot（autopilot-client/worker/tools）
- Evaluations
- tensorzero-optimizers
- tensorzero-mcp
- ui/ 前端
- tensorzero-node（Node.js 绑定）
- ts-executor-pool（V8 沙箱）

## 商业化清理（已完成）

- [x] Howdy 遥测删除
- [x] OpenRouter HTTP-Referer 移除
- [x] Autopilot 默认 URL 改为 localhost
