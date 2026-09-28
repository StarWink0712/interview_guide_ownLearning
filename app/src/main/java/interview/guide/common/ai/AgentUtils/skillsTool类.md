`SkillsTool` 的源码我已经从 Gradle 缓存里提取出来了，下面是完整的代码层面分析。

***

## SkillsTool 的成员变量（内部结构）

```java
public class SkillsTool {

    // ========== 三个静态常量 ==========

    /** AI 的技能调用说明模板（告诉 LLM 有哪些技能可用） */
    private static String TOOL_DESCRIPTION_TEMPLATE = """
            ## Skills
            ...
            Whenever a user asks you to perform a task, 
            check if any of the available skills below...""";

    /** LLM 调用技能后，SkillsTool 返回给 LLM 的内容模板 */
    private static String SKILL_TEMPLATE = """
            Base directory for this skill: %s

            NOTE: Response always starts start with...
            Skill description follows after the base directory line.
            ...
            <available_skills>
            %s
            </available_skills>
            """;

    // ========== 内部类 ==========

    /** 技能调用时的输入参数（LLM 传输技能名） */
    public static record SkillsInput(
        @ToolParam(description = "技能名，如 java-backend")
        String command
    ) {}

    /** 技能的执行逻辑 —— 根据技能名从 Map 中查找并返回 SKILL.md 内容 */
    public static class SkillsFunction implements Function<SkillsInput, String> {
        private Map<String, Skill> skillsMap;  // ← 核心：技能名 → 技能对象的映射

        @Override
        public String apply(SkillsInput input) {
            Skill skill = this.skillsMap.get(input.command());  // 用 LLM 传来的技能名查找
            if (skill != null) {
                return SKILL_TEMPLATE.formatted(skill.basePath(), skill.content());
            }
            return "Skill not found: " + input.command();
        }
    }

    /** 技能数据对象：封装一个 SKILL.md 的完整信息 */
    public static record Skill(
        String basePath,                    // 技能目录路径，如 "skills/java-backend"
        Map<String, Object> frontMatter,    // YAML 头部的元数据 {name: "java-backend", description: "..."}
        String content                      // SKILL.md 的 Markdown 正文
    ) {
        public String name() {
            return this.frontMatter.get("name").toString();  // 从 YAML 头取技能名
        }
    }

    /** 构造器（Builder 模式） */
    public static class Builder {
        private List<Skill> skills = new ArrayList<>();  // ← 存储所有加载到的技能
        private String toolDescriptionTemplate = TOOL_DESCRIPTION_TEMPLATE;

        // 核心方法：接收 Resource（目录），调用 Skills.loadResource() 解析
        public Builder addSkillsResource(Resource skillsResource) {
            this.skills.addAll(Skills.loadResource(skillsResource));
            return this;
        }

        // build() 方法：把所有技能打包成 ToolCallback
        public ToolCallback build() {
            String skillsXml = this.skills.stream()
                .map(Skill::toXml)
                .collect(Collectors.joining("\n"));

            return FunctionToolCallback.builder("Skill", new SkillsFunction(toSkillsMap(skills)))
                .description(toolDescriptionTemplate.formatted(skillsXml))
                .inputType(SkillsInput.class)
                .build();
        }
    }
}
```

***

## 从 Resource 到 Skill 对象的完整链路

你调的是 `addSkillsResource(skillsRootResource)`，传入的是 `classpath:skills` 目录。内部链路：

```
addSkillsResource(Resource skillsRootResource)   ← 你传入的
        │
        ▼
Skills.loadResource(skillsRootResource)          ← 委托给 Skills 工具类
        │
        │  内部逻辑：
        │  1. 扫描 skillsRootResource 目录下的所有子文件夹
        │  2. 找到每个子文件夹下的 SKILL.md 文件
        │  3. 解析 SKILL.md 的 YAML 头部（---...---） → frontMatter
        │  4. 提取 Markdown 正文 → content
        │  5. 构造 Skill 对象
        │
        ▼
List<Skill> skills = [
    Skill(
        basePath    = "classpath:skills/java-backend",
        frontMatter = {name: "java-backend", description: "用于 Java 后端面试出题..."},
        content     = "# Overview\n你是一位 Java 后端面试官..."
    ),
    Skill(basePath = "classpath:skills/algorithm", ...),
    Skill(basePath = "classpath:skills/system-design", ...),
    ...  // 10 个技能
]
        │
        ▼
build() 方法：
  1. skills.stream().map(Skill::toXml)  → 生成技能摘要 XML 给 LLM 看
     <skill>
       <name>java-backend</name>
       <description>用于 Java 后端面试出题...</description>
     </skill>

  2. toSkillsMap(skills)  → Map<String, Skill>
     {"java-backend" → Skill对象, "algorithm" → Skill对象, ...}

  3. 构造 FunctionToolCallback：
     - description = TOOL_DESCRIPTION_TEMPLATE + skillsXml
       （告诉 LLM：你有哪些技能可以调用）
     - inputType = SkillsInput.class
       （LLM 调用时传入的技能名参数结构）
     - function = new SkillsFunction(skillsMap)
       （LLM 调用时执行的逻辑：根据技能名返回 SKILL.md 全文）
```

***

## 运行时：LLM 实际调用技能的过程

```
第 1 轮：
  LLM 读取 ToolCallback.description，看到：
  ┌─────────────────────────────────────────┐
  │ ## Skills                               │
  │ Whenever a user asks you to perform a   │
  │ task, check if any of the available     │
  │ skills below can help...                │
  │                                         │
  │ <available_skills>                      │
  │ <skill>                                 │
  │   <name>java-backend</name>             │
  │   <description>用于 Java 后端面试出题... │
  │ </skill>                                │
  │ <skill>                                 │
  │   <name>algorithm</name>                │
  │   <description>算法面试出题</description> │
  │ </skill>                                │
  │ ...                                     │
  │ </available_skills>                     │
  └─────────────────────────────────────────┘

  用户说："帮我模拟 Java 面试"
  LLM 判断：有个 java-backend 技能，我应该调用它
  → 返回 tool call: name="Skill", command="java-backend"

第 2 轮：
  SkillsFunction.apply(SkillsInput("java-backend"))
    → skillsMap.get("java-backend")
    → 找到 Skill 对象
    → 返回 SKILL_TEMPLATE.formatted(basePath, content)

  LLM 收到：
  ┌──────────────────────────────────────────────┐
  │ Base directory: classpath:skills/java-backend│
  │                                              │
  │ # Overview                                   │
  │ 你是一位 Java 后端面试官，目标是识别候选人... │
  │                                              │
  │ # Instructions                               │
  │ 1. 优先结合候选人简历和项目经历提问。          │
  │ 2. 提问顺序：使用经验→原理→边界条件...         │
  └──────────────────────────────────────────────┘

  LLM 读到这段后，按照这个"人设"生成面试题
```

***

## 一句话总结

> **SkillsTool 的核心就是一个** **`Map<String, Skill>`，key 是技能名，value 是解析好的 SKILL.md 内容。`addSkillsResource()`** **方法调用** **`Skills.loadResource()`** **扫描目录、解析 YAML 头、提取正文，构造 Skill 对象。`build()`** **时把这些 Skill 打包成 LLM 可调用的** **`ToolCallback`——LLM 看到技能列表后决定调用哪个，SkillTool 根据 skill 名返回对应的 SKILL.md 全文。**
>
> <br />

<br />

**是的，每次调用都会发送。** 这是 Spring AI Function Calling 机制决定的——LLM 必须知道有哪些工具可用，才能决定是否调用。

## 每次请求发送的内容

当使用带 SkillsTool 的 ChatClient 时，HTTP 请求体大致是这样的：

```json
{
  "model": "deepseek-chat",
  "messages": [
    {"role": "user", "content": "帮我模拟一次 Java 面试"}
  ],
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "Skill",
        "description": "## Skills\nWhenever a user asks you to perform a task, check if any...\n\n<available_skills>\n<skill>\n  <name>java-backend</name>\n  <description>用于 Java 后端面试出题...</description>\n</skill>\n<skill>\n  <name>algorithm</name>\n  <description>算法面试出题</description>\n</skill>\n...\n</available_skills>",
        "parameters": {
          "type": "object",
          "properties": {
            "command": {"type": "string", "description": "技能名，如 java-backend"}
          }
        }
      }
    }
  ]
}
```

每次请求，`tools` 数组里都会带上所有 10 个技能的摘要信息。

## 这就是为什么项目有三种 ChatClient 变体

```
Default（带 SkillsTool）
  → 每次多消耗 ~2000 tokens 的技能列表
  → 适用于需要 AI 调用技能的对话

Plain（不带 SkillsTool）
  → 零额外 token 消耗
  → 适用于结构化输出（出题 JSON、评分 JSON）
  → AI 直接返回结果，不会触发技能调用
```

项目设计这个区别的原因就在此——**不是所有场景都需要技能，能省就省**。

## Token 消耗对比

| 场景           | 是否带 SkillsTool |    额外 token 消耗   |
| :----------- | :------------: | :--------------: |
| 模拟面试对话       |       ✅ 带      | \~2000 tokens/请求 |
| 出题（结构化 JSON） |      ❌ 不带      |         0        |
| 评分（结构化 JSON） |      ❌ 不带      |         0        |
| 简历解析         |      ❌ 不带      |         0        |

10 个技能，每个一段描述，加模板文字，每次约多消耗 2000 tokens。按 DeepSeek 的价格（输入 1 元/百万 token），多消耗的成本可以忽略——但更重要的是**占用上下文窗口**，影响 LLM 对长对话的处理能力。

## 一句话总结

> **是的，每次调带 SkillsTool 的 ChatClient 都会把全部技能摘要发给 LLM。这不是 SkillsTool 的设计缺陷，而是 OpenAI/Spring AI Function Calling 机制决定的——工具列表必须在请求中声明，LLM 才能自主判断用不用。这也是为什么项目中区分 Default（带技能）和 Plain（不带技能）两种 ChatClient。**

