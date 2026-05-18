# Baton — AI Gateway

Spring Boot AI 网关项目。统一接入多个 LLM provider，提供路由、降级、可观测性。

## 开发工作流

### 设计先行原则

任何非 trivial 的功能（涉及新接口、新模块、架构变更）必须走完整流程：

1. 在 `docs/designs/` 下创建设计文档（格式见下方模板）
2. 执行 `/challenge` 启动设计质疑循环
3. 质疑循环通过后（逻辑闭合），开始实现
4. 实现完成后，执行 `/challenge-code` 启动代码质疑循环
5. 代码质疑通过后（实现与设计一致），可以提交

**何时可以跳过质疑循环：**
- 单文件 bug fix
- 纯配置变更
- 依赖升级
- 格式化/重命名

### 质疑循环（/challenge + /challenge-code）

两阶段质疑，覆盖从设计到实现的完整链路：

| 阶段 | 命令 | 审查对象 | 审查标准 | 最大轮次 |
|------|------|---------|---------|---------|
| 设计 | `/challenge` | 自然语言设计文档 | 逻辑是否闭合 | 3 |
| 实现 | `/challenge-code` | Java 源代码 | 实现是否匹配设计 | 2 |

设计质疑者原则：`.claude/prompts/challenger.md`
代码质疑者原则：`.claude/prompts/code-challenger.md`

### 设计文档模板

```markdown
# [功能名]

## 问题定义
要解决什么问题？为什么现在要解决？

## 方案
具体怎么做？关键组件和它们的关系。

## 接口契约
对外暴露什么？入参、出参、错误码。

## 边界条件
什么情况下这个方案不适用？输入范围限制？

## 失败场景
哪些地方可能出错？出错了怎么办？降级策略？

## 质疑记录
（由 /challenge 自动填充）
```

## 代码规范

- Java 21, Spring Boot 3.5, Gradle Kotlin DSL
- 包结构：`com.qingshanyuluo.baton.<module>`
- REST controller 放 `web/` 包，业务逻辑放 `service/` 包，配置放 `config/` 包
- 用 record 做 DTO，不用 Lombok 的 @Data
- 异常统一通过 @ControllerAdvice 处理
- 配置用 @ConfigurationProperties 绑定，不散落 @Value
- 测试：单元测试用 JUnit 5 + Mockito，集成测试用 @SpringBootTest + TestContainers
