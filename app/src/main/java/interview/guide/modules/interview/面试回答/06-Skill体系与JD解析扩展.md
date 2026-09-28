# Skill 体系与 JD 解析扩展

对，本质上就是这个。具体来说拆成三部分注入 Prompt：

1. **persona**（`SKILL.md` 主体）→ 系统提示词中追加 `buildSkillPersonaSection`，告诉 LLM"你现在是什么角色的面试官、用什么风格出题"
2. **分类配额**（`skill.meta.yml` 的 categories）→ `calculateAllocation` 算出各分类出几题，拼成 Markdown 表格告诉 LLM 按这个分配出
3. **reference 参考材料**→ `buildReferenceSection` 把对应分类的知识基准文本注入，让 LLM 出的题有深度、有范围、不乱编

其余部分（优先级算法、JD 解析映射、路径安全校验、缓存等）都是为这三步服务的辅助逻辑，确保传进去的 Skill 内容是合法、完整、可控的。

## 1. 模块基础讲解

Skill 体系是面试模块“题目质量”和“岗位匹配度”的基础。相关代码位于 `interview/skill` 包，资源位于 `app/src/main/resources/skills` 和 `prompts`。

每个预设 Skill 通常包含：

- `SKILL.md`：标准 front matter 和面试官 persona。
- `skill.meta.yml`：展示配置和分类定义。
- `references` 或 shared references：分类对应的参考材料。

`InterviewSkillService` 启动时会扫描 `classpath:skills/*/SKILL.md`，加载预设 Skill，并用 SnakeYAML 解析 `skill.meta.yml`。加载后会构建一个 category key 到 reference 文件的索引，用于出题和 JD 解析时匹配参考材料。

Skill 分类包含：

- `key`：分类 key，例如 `JAVA`、`MYSQL`、`REDIS`。
- `label`：展示名称。
- `priority`：优先级，比如 `ALWAYS_ONE`、`CORE`、`NORMAL`。
- `ref`：参考材料文件名。
- `shared`：是否来自 `_shared/references`。

题目分配算法：

1. `ALWAYS_ONE` 类目优先各分 1 题。
2. `CORE` 类目再各分 1 题。
3. `NORMAL` 类目再各分 1 题。
4. 剩余名额按 CORE、NORMAL 轮转分配。
5. 所有分类都出现在 allocation 中，没分到题的数量为 0。

JD 解析流程：

1. 前端调用 `POST /api/interview/skills/parse-jd`。
2. Controller 使用 IP 限流保护。
3. `InterviewSkillService.parseJd` 校验 JD 至少 50 字。
4. 把本地 reference 文件清单注入 `jd-parse-system.st`。
5. 使用 LLM 提取 3-7 个面试考察方向。
6. 每个方向返回 key、label、priority、ref、shared。
7. 后端根据本地 `categoryRefIndex` 纠正模型返回的 reference 映射。
8. 创建会话时，如果 `skillId=custom`，用这些分类构建自定义 Skill。

面试讲法：

> 我们没有把面试方向硬编码在 Java 代码里，而是做成 Skill 配置体系。预设方向从 `resources/skills` 加载，分类和参考材料由 YAML 和 Markdown 管理。出题时先按分类优先级分配题目，再把对应 reference 注入 Prompt。对于自定义岗位，系统先用 AI 解析 JD 生成分类，再映射到本地 reference，保证 JD 个性化和知识基线可控。

## 2. 基础面试问题

**问题：Skill 体系解决了什么问题？**

回答：它把“面试方向”和代码解耦。新增 Java 后端、前端、算法、系统设计等方向时，主要修改资源文件和元数据，而不是改业务代码。它也让出题有稳定的 reference 基线，不完全依赖模型自由发挥。

**问题：`skill.meta.yml`** **的作用是什么？**

回答：它定义展示信息和分类列表。分类里配置 priority 和 reference 文件，后端根据这些信息做题目分配和参考材料注入。

**问题：什么是 shared reference？**

回答：shared reference 是多个 Skill 共用的参考材料，放在 `skills/_shared/references` 下，比如 Java、Redis、MySQL、分布式等。这样不同面试方向可以复用同一份基础知识材料。

**问题：JD 解析为什么还要匹配本地 reference？**

回答：LLM 可以从 JD 中提取方向，但如果完全相信模型返回的 reference 文件名，可能不可靠。后端用本地 category key 到 reference 的索引纠正映射，保证最终注入的是系统存在且安全的参考文件。

**问题：如何防止 reference 路径不安全？**

回答：`loadReferenceContent` 会先检查路径，禁止 `..`、绝对路径、反斜杠开头等危险形式，只允许字母数字、点、下划线、斜杠和横线。这样避免通过配置或模型输出读取非预期文件。

## 3. 进阶面试问题

**问题：题目分配算法为什么分 ALWAYS\_ONE、CORE、NORMAL？**

回答：不同分类的重要性不同。`ALWAYS_ONE` 确保关键方向至少覆盖一题；`CORE` 保证核心能力优先；`NORMAL` 覆盖辅助能力。剩余题量再轮转分配，避免某个分类独占所有题目。

**问题：reference 注入为什么要截断？**

回答：reference 可能很长，直接注入会超出 LLM 上下文窗口，也会增加成本。代码限制单文件最多 3000 字符，出题 reference 总长度最多 12000 字符，评估 reference 最多 6000 字符。截断是成本和质量之间的折中。

**问题：自定义 Skill 如何处理模型返回的不规范分类？**

回答：`buildCustomSkill` 会清洗 key 和 label。key 会截断长度、替换非法字符、转大写，首字符不是字母时加 `CAT_` 前缀。label 会移除换行并截断。这样避免 LLM 输出污染后续 Prompt 和路径。

**问题：Skill persona 和 reference 有什么区别？**

回答：persona 是面试官角色和风格约束，来自 `SKILL.md` 主体；reference 是分类知识基线，来自 Markdown reference 文件。persona 决定“怎么问”，reference 决定“问什么、深到哪里”。

**问题：为什么 JD 解析用** **`getDefaultChatClient`** **而不是指定 provider？**

回答：JD 解析接口当前没有接收 provider，直接使用默认 ChatClient。这样接口简单，但也意味着用户不能为 JD 解析单独选择模型。后续可以让前端传 provider，或者用配置指定低成本模型。

**问题：reference 缓存有什么意义？**

回答：classpath 资源基本不变，加载后缓存到 `ConcurrentHashMap` 可以减少重复 I/O 和字符串处理。对于高频创建会话或评估报告，reference 缓存能降低延迟。

## 4. 技术延伸问题

**问题：如果 Skill 要支持后台管理，怎么改？**

回答：可以把 Skill 元数据和 reference 存到数据库或对象存储，增加管理后台。启动时加载预设 Skill，运行时加载数据库 Skill。需要处理版本、发布状态、灰度、缓存失效和权限。

**问题：Skill reference 和 RAG 有什么区别？**

回答：当前 reference 是按分类静态注入，简单可控，但上下文可能大且不够精准。RAG 是按当前题目、JD、简历动态检索最相关片段，精度更高，也能支持大规模资料。两者可以结合：Skill 决定检索范围，RAG 决定具体片段。

**问题：如何让 JD 解析更稳定？**

回答：可以把 JD 解析拆成规则抽取和 LLM 结构化输出两步。先用规则识别技术栈、经验年限和职责关键词，再让 LLM 分类。输出后做本地校验，不满足 3-7 个方向、priority 非法或 key 重复时触发重试。

**问题：如何支持公司定制面试方向？**

回答：新增租户级 Skill。系统预设 Skill 作为默认，企业可以配置自己的分类、题目风格、reference 和评分标准。创建面试时根据 tenantId 加载对应 Skill，reference 也要做租户隔离。

**问题：如何把 Skill 用于最终评分？**

回答：当前已经通过 `buildEvaluationReferenceSectionSafe` 把 Skill reference 注入最终评估。进一步可以为每个分类配置 rubric，比如必须覆盖哪些关键点、常见错误、评分上限，这样评估更客观。

## 5. 改进方案问题

**问题：当前 Skill 加载有什么局限？**

回答：预设 Skill 在启动时扫描 classpath，运行时不能热更新。新增或修改 Skill 需要重新打包部署。后续可以增加配置中心或数据库加载，并实现缓存刷新。

**问题：JD 解析接口还可以怎么保护？**

回答：目前有 IP 限流和最小长度校验。还可以增加最大长度限制、敏感信息脱敏、provider 超时、成本预算、用户级限流和解析结果缓存。JD 文本也应全程作为不可信数据处理。

**问题：reference 文件质量怎么治理？**

回答：可以给 reference 增加元数据：适用 skill、难度、更新时间、负责人、版本。每次出题和评估记录 reference 版本，方便问题追溯。还可以写单测检查 skill.meta.yml 中的 ref 是否存在。

**问题：分类 key 统一性如何保证？**

回答：当前依赖配置和清洗。更好的做法是维护一份全局 category 字典，所有 Skill 引用字典中的 key。JD 解析结果也映射到字典，减少“Redis”“REDIS\_CACHE”“CACHE”这种语义重复分类。

**问题：Prompt 上下文过长怎么优化？**

回答：可以根据 allocation 只注入本次会出题的分类 reference；对 reference 做摘要；用 RAG 动态检索；或者把 reference 中的“题目模板、关键点、易错点”结构化存储，只注入必要字段。

## 6. 故障处理问题

**问题：启动时 Skill 没加载出来怎么办？**

回答：检查 `resources/skills/*/SKILL.md` 是否存在，front matter 是否符合格式，`skill.meta.yml` 是否可被 SnakeYAML 解析。看日志中的“加载预设 Skill”和“跳过无效 Skill”信息。

**问题：创建面试提示未找到主题怎么办？**

回答：`getSkill` 找不到 skillId 会抛 `BAD_REQUEST`。检查前端传的 skillId 是否和资源目录名一致，或者自定义面试是否正确传 `skillId=custom` 和 `customCategories`。

**问题：JD 解析结果为空怎么办？**

回答：先检查 JD 长度是否超过 50 字，再看 LLM 调用是否失败、结构化输出是否解析失败。可以让用户选择预设主题作为降级方案，或者提供手动编辑分类。

**问题：reference 未找到怎么办？**

回答：日志会输出 skillId、ref、shared 和候选 locations。检查 `skill.meta.yml` 中 ref 文件名、shared 标记、文件实际路径是否一致。对于 custom 模式，还要确认 category key 能在全局映射中匹配。

**问题：JD 中出现 Prompt 注入怎么办？**

回答：当前代码会用数据边界说明和 `PromptSanitizer` 包裹 JD。仍建议记录异常输出、加强输出校验，并限制模型只能返回 categories JSON，不允许把 JD 中的指令当系统指令执行。
