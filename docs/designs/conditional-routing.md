# Request-aware Backend Skip Rules

## 问题定义

不同 Anthropic 兼容后端的能力不同。DeepSeek 不接受 Claude 原生模型名（`claude-opus-4-7`），不支持 `image`/`document` 等内容类型，不支持 `cache_control`。当请求包含这些后端无法处理的内容时，应该在路由阶段就跳过该后端，而不是等到它返回错误（或更坏的：silent fallback 给出非预期结果）。

需要一种可配置的机制，让网关根据请求内容判断：这个后端能否处理这个请求？不能就跳过。

## 方案

### 核心思路

每个后端配置可选的 `skip-rules`。在 failover 循环中，尝试一个后端之前先检查它的 skip-rules：如果任何一条规则匹配，跳过该后端，尝试下一个。

**Skip 评估时机**：在原始请求 body 上评估（Baton 是透明代理，不对请求做任何转换/映射）。如果未来增加请求转换能力，skip-rules 始终在转换之前评估——先判断"原始请求能否被后端处理"，再考虑转换。

```
请求进入 → 遍历后端
  后端 1 → 检查 skip-rules → 匹配？→ 按 skip-mode 决定
    - strict：跳过（不尝试），继续后端 2
    - lenient：标记"待尝试"，继续后端 2
    - 所有后端都 strict-skip → 返回所有 lenient 标记的后端，依次尝试
  后端 2 → 检查 skip-rules → 不匹配 → 尝试转发 → 成功 → 返回
```

### Skip Mode

每个规则有一个 `mode: strict | lenient`，默认 `strict`。

- `strict`：规则匹配时跳过该后端，并且即使所有后端都失败也不回退尝试
- `lenient`：规则匹配时先跳过，但如果所有非 lenient 跳过的后端都失败了，仍回退尝试该后端

这解决假阳性问题——`lenient` 提供了逃生口：如果规则判断"不兼容"但实际可能兼容，请求仍然会被尝试。

### 配置设计

```yaml
baton:
  backends:
    - name: deepseek
      url: https://api.deepseek.com/anthropic
      priority: 1
      skip-rules:
        - type: model-pattern
          pattern: "^claude-"
          mode: strict
          description: "Claude 原生模型名 DeepSeek 不识别"
        - type: content-type
          values: [image, document, redacted_thinking]
          mode: strict
        - type: has-field
          field: cache_control
          mode: lenient
          description: "DeepSeek 忽略 cache_control，先试其他后端但不排除 DeepSeek"
        - type: header-present
          header: anthropic-beta
          mode: lenient
          description: "DeepSeek 忽略 beta header"
    - name: airouter
      url: https://airouter.cloud
      priority: 2
      skip-rules: []   # 无限制，接受所有请求
    - name: callapi
      url: https://callapi.top
      priority: 3
      skip-rules: []
```

### 规则类型

| 类型 | 匹配逻辑 | 适用场景 |
|------|---------|---------|
| `model-pattern` | 请求 body 中 `model` 字段匹配 Java regex | 后端不支持特定模型名 |
| `content-type` | 遍历所有 messages（所有 role）的 `content` 数组，检查是否包含指定 type | 后端不支持 image/document 等 |
| `has-field` | **递归搜索**整个 JSON tree，查找任意层级是否存在指定字段名（不是路径匹配，不依赖字段在当前层级的位置） | 后端忽略特定功能参数（如 cache_control） |
| `header-present` | 请求的**原始客户端 header** 中存在指定 header 名（不包括网关自行注入的 header） | 后端忽略特定 header 的功能 |

**匹配语义**：
- 跨规则：OR — 任意一条规则匹配即触发
- 多规则 mode 冲突：**strict 优先** — 如果同个后端多条规则命中，至少有一条是 strict 则整组作为 strict-skip；全部命中规则都是 lenient 才归入 lenient-skip
- 单规则内部：**短路求值** — content-type 和 has-field 找到第一个匹配立即返回，不遍历剩余节点

**content-type 检查 scope**：遍历 `messages` 数组中所有 role（user、assistant 均检查）的所有 content block。`system` 参数如果是数组格式，也纳入。**content 为 string 类型时**：等价于 `[{"type": "text", "text": "..."}]`，不触发 content-type 规则匹配。

### 在 FailoverRouter 中的集成

在 `doRoute()` 的循环中，分两轮处理：

```java
// Round 1: 尝试所有非 strict-skip 的后端
List<BackendConfig> lenientSkipped = new ArrayList<>();
for (BackendConfig backend : backends) {
    SkipResult skip = evaluator.evaluate(backend, headers, body);
    if (skip.strict) continue;        // strict: 跳过不回头
    if (skip.lenient) {
        lenientSkipped.add(backend);   // lenient: 记下来，稍后兜底
        continue;
    }
    RouteResult result = tryBackend(...);
    if (result.success() || result.noFailover()) return result;
}

// Round 2: 所有非 strict 的都失败了，尝试 lenient-skipped 的后端作为兜底
for (BackendConfig backend : lenientSkipped) {
    RouteResult result = tryBackend(...);
    if (result.success() || result.noFailover()) return result;
}
```

### 所有后端都 skip 或失败时的行为

- 全部被 strict-skip → 502，body：`"No compatible backend found for this request"`
- strict-skip + lenient-skip 后端都尝试了但全失败 → 502，body：`"All backends unavailable"`（与普通 failover 一致）

### Header 快照时机

`header-present` 规则的 header 来源：在 AnthropicProxyController 入口处（`@RequestHeader HttpHeaders`）捕获的原始客户端 headers。这个捕获发生在 Spring 框架解析 HTTP 请求之后、任何业务逻辑（包括 skip 评估）之前。网关自身的 filter/中间件注入的 header 不在这个集合内。

### 可观测性

每次 skip 决策必须记录结构化日志：
```
Skipping backend 'deepseek': rule=model-pattern, mode=strict, matched="^claude-" against model="claude-opus-4-7"
```

Metrics（通过 Spring Boot Actuator + Micrometer）：
- `baton.skip.evaluate` (counter) — tags: `backend`, `rule`, `mode`, `action` (strict_skip / lenient_defer / no_match)

### 请求体解析

skip-rules 需要解析 JSON body。解析方式：`ObjectMapper.readTree(body)` 获取 JsonNode 树，然后按规则类型查询。解析异常时（body 不是合法 JSON）：不跳过任何后端，让后端自己返回 400。

### 实现组件

1. **SkipRuleEvaluator** — 接收 rules + request headers + parsed body → 返回 SkipResult（含 mode 和命中规则信息）
2. **BatonProperties** 扩展 — BackendConfig 增加 `skip-rules` 字段
3. **FailoverRouter** 修改 — doRoute 循环中调用 SkipRuleEvaluator，分 Round 1/Round 2 处理 strict/lenient
4. **SkipMetrics** — Micrometer counter 记录 skip 事件，用于监控规则命中率

## 接口契约

无新增对外接口。skip 行为对调用方透明 — 请求被 skip 到下一个后端，调用方看到的是最终后端的响应。

管理接口 `/actuator/backends` 返回的后端状态中增加 `skipRulesCount` 字段，展示该后端配置了几条 skip-rule。

## 边界条件

- 请求体超过 `max-body-size`：在 skip 评估之前就被拒绝（413），独立处理
- 请求体不是合法 JSON：不跳过任何后端，原样转发
- 规则配置为空或 `skip-rules: []`：等同于无规则，behavior 不变
- 后端配置了 skip-rules 但同时被手动 disable：disable 优先（不会尝试该后端）
- model-pattern 的 regex 不合法：启动时校验，拒绝启动

## 失败场景

| 场景 | 行为 |
|------|------|
| 所有后端 strict-skip → 没后端可试 | 返回 502 + "No compatible backend found" |
| strict-skip 覆盖所有，但 lenient 后端兜底失败 | 返回 502 + "All backends unavailable" |
| 请求 body parse 失败 | 不做 skip 判断，全部尝试（降级为普通 failover） |
| regex pattern 编译失败 | 启动时抛出，拒绝启动 |
| content-type 规则但请求中无 content 字段 | 不匹配，不跳过 |
| has-field 规则但字段名在整个 tree 中不存在 | 不匹配，不跳过 |

## 质疑记录

### Round 1

1. **Q: skip 评估与请求转换的时序未定义** → A: 明确在原始请求上评估。Baton 是透明代理无转换，未来如有转换也在 skip 之后。
2. **Q: has-field path 语义不清（递归 vs 路径）** → A: 改为递归字段名搜索，遍历整个 JSON tree。不依赖路径，只匹配字段名。
3. **Q: 假阳性导致不可恢复 502，无逃生口** → A: 增加 `skip-mode: strict|lenient`。lenient 后端先跳过，但如果所有 strict 后端都失败仍兜底尝试。
4. **Q: content-type scope 未明确 role 范围** → A: 明确遍历所有 role 的 message（user、assistant），system 数组也纳入检查。
5. **Q: header-present 来源范围未限定** → A: 明确只检查原始客户端 header，不包括网关自行注入的。

### Round 2

1. **Q: strict+lenient 多规则同时命中时优先级未定义** → A: strict 优先。任一命中规则是 strict 则整组为 strict-skip。
2. **Q: content 为字符串时会 NPE** → A: 字符串 content 等价于 text content block，不触发 content-type 规则。
3. **Q: skip 决策无可观测性** → A: 增加结构化日志 + `baton.skip.evaluate` Micrometer counter。
4. **Q: "原始客户端 header"边界模糊** → A: 明确在 Controller 入口 `@RequestHeader` 处捕获，在任何 filter/中间件注入之前。
5. **Q: 规则内无短路语义** → A: 明确 content-type 和 has-field 首匹配即返回，不遍历剩余节点。


