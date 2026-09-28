# AgentUtils 模块面试讲解指南

## 一、概述：如何在面试中介绍本模块

当面试官问到"你做的项目中技能工具是怎么集成的"，建议按以下逻辑展开：

> **一句话概括** → **设计动机** → **核心流程** → **关键细节** → **设计取舍**

---

## 二、一句话概括（开场白）

> "AgentUtils 模块负责将预定义的面试技能规则文件（SKILL.md）加载为 Spring Bean，注入到 ChatClient 中，让 LLM 在对话时能按预设规则出题和评估。它的核心是两个类：一个配置属性类负责绑定 YAML 配置，一个配置类负责装配 Bean。"

---

## 三、模块讲解顺序

### 第 1 步：从问题出发（为什么需要这个模块）

**面试官可能问**："为什么需要 SkillsTool？直接用 Prompt 不就行了吗？"

**建议回答**：

如果只用 Prompt，每次对话都要在 user message 中拼接一长串规则文本，会带来三个问题：

1. **Token 浪费**：每次请求都重复发送规则，消耗大量 Token
2. **上下文污染**：规则提示词占据大量上下文窗口，压缩了真正有用的对话空间
3. **规则管理混乱**：规则散落在各业务代码中，修改不便、难以复用

SkillsTool 的机制是：LLM **按需调用**技能，只在需要时才加载对应 SKILL.md，不需要时不会出现在上下文中。这更接近 OpenAI Function Calling 的设计理念。

### 第 2 步：整体架构（一图胜千言）

可以使用如下流程图辅助讲解：

```
YAML 配置 (skills-root)
       ↓
AgentUtilsProperties  ← @ConfigurationProperties 绑定
       ↓
AgentUtilsConfiguration  ← @Configuration 装配
       ↓
SkillsTool.builder()
  .addSkillsResource()  → 扫描 resources/skills/ 目录
  .build()              → 解析 SKILL.md，构造 FunctionToolCallback
       ↓
ToolCallback Bean ("interviewSkillsToolCallback")
       ↓
LlmProviderRegistry 注入到 ChatClient
       ↓
LLM 在对话中按需调用技能
```

### 第 3 步：两个核心类的职责

| 类 | 职责 | 关键注解 | 设计模式 |
|----|------|---------|---------|
| `AgentUtilsProperties` | 绑定 YAML 配置 | `@ConfigurationProperties` | 配置对象模式 |
| `AgentUtilsConfiguration` | 加载资源并创建 Bean | `@Configuration` + `@Bean` | 工厂方法模式 |

**强调要点**：
- 遵循项目编码规范：配置集中到 `@ConfigurationProperties`，不使用散落的 `@Value`
- 构造函数注入而非字段注入，便于单元测试

### 第 4 步：核心执行流程（深入细节）

讲解 `interviewSkillsToolCallback()` 方法的 5 个步骤：

1. **读配置**：`agentUtilsProperties.getSkillsRoot()` → `"classpath:skills"`
2. **标准化路径**：`normalizeSkillsRoot()` 处理 5 种边界情况
3. **加载资源**：`resourceLoader.getResource()` 支持多协议
4. **快速失败**：`exists()` 检测，目录不存在直接抛异常
5. **构建 SkillsTool**：Builder 模式，内部扫描目录、解析 YAML 头

**亮点强调**：`normalizeSkillsRoot()` 是防御性编程的典型案例，体现了工程思维的严谨性。

### 第 5 步：SkillsTool 内部机制（进阶话题）

如果面试官追问 SkillsTool 内部怎么工作：

```
SkillsTool.builder()
  .addSkillsResource(resource)
        │
        ▼ 内部调用 Skills.loadResource()
        │  ├── 扫描子目录
        │  ├── 找到 SKILL.md
        │  ├── 解析 YAML 头部 → frontMatter
        │  └── 提取 Markdown 正文 → content
        │
        ▼ 返回 List<Skill>
        │
        ▼ build() 打包
        │  ├── 生成技能摘要 XML → 放在 description 中（LLM 以此判断）
        │  ├── 构造 Map<String, Skill> → skillsMap
        │  └── 封装为 FunctionToolCallback
        │
        ▼ 运行时 LLM 调用
           SkillsFunction.apply(SkillsInput("java-backend"))
           → skillsMap.get("java-backend")
           → 返回 SKILL.md 全文给 LLM
```

---

## 四、常见面试问题与推荐回答

### Q1："为什么需要 Plain ChatClient？"

**核心矛盾**：带 SkillsTool 的 ChatClient 在接收"返回 JSON"的指令时，LLM 可能先调用技能而不是直接输出 JSON，导致结构化输出解析失败。

**解决方案**：Plain ChatClient 不注入任何 ToolCallback，确保 LLM 直接返回结果。

**对比**：
| | Default | Plain |
|---|---|---|
| SkillsTool | 有 | 无 |
| 适用场景 | 对话式面试 | 结构化输出（出题 JSON、评分 JSON） |

### Q2："为什么使用 @ConfigurationProperties 而不是 @Value？"

| | @ConfigurationProperties | @Value |
|---|---|---|
| 配置集中度 | 一个类绑定一组配置 | 散落在各字段 |
| 类型安全 | 是（编译期校验） | 否（运行时注入） |
| IDE 支持 | 自动补全 | 无提示 |
| 松耦合 | 是 | 否 |

遵循项目规范：配置应集中到 `@ConfigurationProperties`。

### Q3："为什么用 ResourceLoader 而不是直接 File IO？"

`ResourceLoader` 是 Spring 的抽象层，支持多种协议：
- `classpath:` → 从 JAR/classpath 加载（生产环境打包后仍可用）
- `file:` → 从文件系统加载（开发/调试便利）
- `url:` → 从远程地址加载

如果用 `new File()`，打包成 JAR 后就无法读取 classpath 内的资源了。

### Q4："normalizeSkillsRoot() 为什么要处理那么多边界情况？"

这是防御性编程的实践。配置来自于外部（YAML 文件、环境变量），用户可能：
- 配成文件路径而非目录路径（带 `/SKILL.md` 尾缀）
- 从 Windows 复制路径（`\` 分隔符）
- 误加通配符（`skills/*`）
- 末尾多写斜杠

不做处理的话，`resourceLoader.getResource()` 可能加载失败，而这个错误在运行时才暴露。规范化处理让系统更鲁棒。

### Q5："如果我想新增一个技能，怎么做？"

1. 在 `resources/skills/` 下新建目录（如 `golang-backend/`）
2. 创建 `SKILL.md`（YAML 头部 + Markdown 规则）
3. 创建 `skill.meta.yml`（元信息）
4. 重启应用即可，无需改一行 Java 代码

这是模块扩展性的体现。

### Q6："LLM 怎么知道有哪些技能可用？"

SkillsTool 在 build() 时会将所有技能摘要（名称 + 描述）生成为 XML 片段，放在 `ToolCallback.description` 中。当 ChatClient 发起请求时，这个 description 会作为 tools 定义的一部分发送给 LLM。LLM 根据用户意图和技能描述匹配合适的技能。

---

## 五、模块设计亮点与创新

1. **按需加载，节省 Token**：LLM 只在实际调用技能时才收到完整的 SKILL.md 内容，而非每次请求都携带全部规则
2. **扩展零代码**：新增技能只需加 Markdown 文件，符合开闭原则
3. **快速失败**：启动时校验资源目录存在性，将错误暴露在最早期
4. **防御性路径处理**：健壮的输入处理，减少因配置格式问题导致的线上故障
5. **职责分离清晰**：Properties 管配置、Configuration 管装配、SKILL.md 管规则，各司其职

---

## 六、可改进方向（展现思考深度）

在面试中适当提及改进方向可以展示你的思考深度：

1. **技能热加载**：目前启动时一次性加载，可引入文件监听机制支持运行时刷新
2. **技能缓存策略**：频繁调用的技能可以考虑缓存 SKILL.md 内容，减少重复解析
3. **技能调用 Metrics**：增加调用频率统计，识别高频/低频技能，辅助技能内容优化
4. **多语言技能**：当前 SKILL.md 为中文，可扩展多语言版本，根据面试语言动态切换
5. **技能组合调用**：支持一次对话中 LLM 依次调用多个技能（如先简历分析再针对性出题）

---

## 七、面试话术模板

### 自我介绍模板

> "AgentUtils 是我负责的 AI 面试平台中负责技能集成的模块。核心思路是将面试规则从代码中剥离，用 Markdown 文件定义，通过 Spring AI 的 ToolCallback 机制让 LLM 按需调用。
>
> 技术上用到 Spring 的 @ConfigurationProperties 做配置绑定，@Configuration + @Bean 做装配，ResourceLoader 做资源加载，防御性编程做路径标准化。整个模块遵循开闭原则——新增技能无需改代码，只需加 Markdown 文件。
>
> 模块产出的 Bean 被 LlmProviderRegistry 注入到三个 ChatClient 变体中：Default（带技能）、Plain（纯结构化输出）、Voice（流式语音）。"

### 回答"你做了什么优化"模板

> "我在路径规范化方法中覆盖了 5 种边界情况——null、空白、Windows 分隔符、误配文件尾缀、通配符——使得模块在各种异常配置下都能健壮运行。同时采用了快速失败策略，资源目录不存在时启动即报错，避免隐藏问题到运行时才发现。"

### 回答"遇到什么挑战"模板

> "挑战在于 Plain ChatClient 的设计决策。一开始所有场景都用带 SkillsTool 的 ChatClient，但结构化输出场景下 LLM 会先触发技能调用，导致 JSON 解析失败。最终通过提供无工具的 Plain 变体解决，这是对 LLM 行为特性的理解在工程设计中的体现。"
