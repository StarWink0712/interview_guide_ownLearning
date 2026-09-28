<br />

# 🎯 **PromptTemplate来源分析**

### **A. 不是Java底层，而是Spring AI框架**

从代码分析可以看出：

```java
import org.springframework.ai.chat.prompt.PromptTemplate;  // Spring AI包
```

**PromptTemplate是Spring AI框架提供的类**，不是Java底层类。

### **B. 项目中的依赖关系**

从`build.gradle`可以看到：

```gradle
// Spring AI 2.0 - OpenAI兼容模式
implementation "org.springframework.ai:spring-ai-starter-model-openai:${libs.versions.springAi.get()}"

// spring-ai-agent-utils core library  
implementation libs.spring.ai.agent.utils
```

## 📋 **PromptTemplate在项目中的使用**

### **1. 加载方式**

```java
// 从配置文件路径加载模板
private static PromptTemplate loadTemplate(ResourceLoader loader, String location) throws IOException {
    return new PromptTemplate(loader.getResource(location).getContentAsString(StandardCharsets.UTF_8));
}
```

### **2. 实际使用示例**

```java
// 构建系统提示词
String systemPrompt = resumeSystemPromptTemplate.render()
    + buildSkillPersonaSection(skill)      // 动态内容追加
    + "\n\n" + outputConverter.getFormat(); // JSON格式指令

// 构建用户提示词  
Map<String, Object> variables = new HashMap<>();
variables.put("questionCount", questionCount);
variables.put("followUpCount", followUpCount);
variables.put("skillName", skill.name());
variables.put("resumeText", resumeText);
// ... 其他变量

String userPrompt = resumeUserPromptTemplate.render(variables);
```

### **3. 模板文件位置**

项目使用`.st`文件作为模板文件：

```yaml
# 位置：app/src/main/resources/prompts/
├── resume-analysis-system.st     # 简历分析系统提示词
├── resume-analysis-user.st       # 简历分析用户提示词
├── interview-question-system.st  # 面试问题系统提示词
├── interview-question-user.st    # 面试问题用户提示词
└── voice-interview/*.st         # 语音面试相关模板
```

## 🔧 **Spring AI PromptTemplate工作机制**

### **A. StringTemplate引擎**

Spring AI使用**StringTemplate**作为模板引擎：

- `.st`文件使用StringTemplate语法
- 支持变量替换和条件判断
- 类似于Velocity或Freemarker，但更轻量

### **B. 模板渲染过程**

```java
// 1. 加载.st文件内容
String templateContent = "请生成{count}个关于{topic}的问题";

// 2. 创建PromptTemplate
PromptTemplate template = new PromptTemplate(templateContent);

// 3. 准备变量
Map<String, Object> variables = Map.of(
    "count", 5,
    "topic", "Java并发"
);

// 4. 渲染模板
String rendered = template.render(variables);
// 结果："请生成5个关于Java并发的问题"
```

## 📝 **模板文件示例分析**

### **简历分析系统提示词 (resume-analysis-system.st)**

```st
# Role
你是一位拥有 10 年以上经验的资深技术架构师...

# Task  
请对用户提供的简历内容进行深度技术审计...

# Scoring Rubrics (Total: 100)
1. **projectTechDepth (0-40分)**：是否避开了烂大街的项目...
2. **skillMatchScore (0-20分)**：技术栈专业度...
```

### **面试问题用户提示词 (interview-question-user.st)**

```st
请生成{questionCount}个关于{skillName}的面试问题，难度为{difficultyDescription}。
要求：
- 每个问题生成{followUpCount}个追问
- 避免重复历史题目：{historicalSection}
- 参考技能分类：{allocationTable}
```

## 🎯 **项目中的模板使用模式**

### **1. 四套提示词模板**

```java
// 技能相关
skillSystemPromptTemplate    // 系统级设定
skillUserPromptTemplate     // 用户级变量

// 简历相关  
resumeSystemPromptTemplate  // 系统级设定
resumeUserPromptTemplate   // 用户级变量
```

### **2. 动态内容追加**

```java
// 基础模板 + 动态内容 + 格式指令
String systemPrompt = baseTemplate.render()
    + buildSkillPersonaSection(skill)      // 动态技能角色
    + buildHistoricalSection(history)      // 动态历史题目
    + outputConverter.getFormat();          // JSON格式要求
```

### **3. 变量替换示例**

```java
// 用户提示词变量
Map<String, Object> variables = new HashMap<>();
variables.put("questionCount", 10);                    // 题目数量
variables.put("followUpCount", 2);                     // 追问数量
variables.put("skillName", "Java后端");                // 技能名称
variables.put("difficultyDescription", "1-3年经验");   // 难度描述
variables.put("resumeText", resumeContent);           // 简历内容
variables.put("historicalSection", historyText);       // 历史题目
```

## 🚀 **Spring AI PromptTemplate的优势**

### **1. 与Spring生态深度集成**

```java
// 自动注入ResourceLoader
@Autowired
private ResourceLoader resourceLoader;

// 支持classpath、file、http等多种资源路径
PromptTemplate template = new PromptTemplate(
    resourceLoader.getResource("classpath:prompts/example.st")
        .getContentAsString(StandardCharsets.UTF_8)
);
```

### **2. 类型安全的变量处理**

```java
// 支持多种变量类型
Map<String, Object> variables = Map.of(
    "count", 10,                    // Integer
    "name", "Java",                 // String  
    "isActive", true,               // Boolean
    "tags", List.of("java", "ai"),  // List
    "config", Map.of("timeout", 5000) // Map
);
```

### **3. 错误处理和验证**

```java
// 自动检测未使用的变量
// 自动检测缺失的必需变量
// 提供清晰的错误信息
```

## 🔄 **与传统模板引擎对比**

| 特性       | Spring AI PromptTemplate | 传统Velocity | 传统Freemarker |
| -------- | ------------------------ | ---------- | ------------ |
| **集成度**  | 与Spring AI深度集成           | 独立使用       | 独立使用         |
| **语法**   | StringTemplate语法         | Velocity语法 | Freemarker语法 |
| **AI优化** | 针对AI提示词优化                | 通用模板       | 通用模板         |
| **变量处理** | 支持复杂对象                   | 支持复杂对象     | 支持复杂对象       |
| **性能**   | 轻量级                      | 较重         | 较重           |

## 💡 **项目中的最佳实践**

### **1. 模板文件组织**

```yaml
prompts/
├── resume/              # 简历相关模板
│   ├── analysis-system.st
│   └── analysis-user.st
├── interview/          # 面试相关模板  
│   ├── question-system.st
│   └── question-user.st
└── voice-interview/    # 语音面试模板
    ├── asr-system.st
    └── tts-system.st
```

### **2. 模板设计原则**

```st
// 1. 清晰的角色定义
# Role
你是一位专业的面试官...

// 2. 明确的任务描述
# Task
请根据以下要求生成面试问题...

// 3. 结构化的输出格式
# Output Format
请返回JSON格式，包含以下字段：
- questions: 问题列表
- type: 问题类型
- category: 问题分类
```

### **3. 动态内容管理**

```java
// 分离静态模板和动态内容
String basePrompt = template.render();           // 基础模板
String dynamicContent = buildDynamicContent();    // 动态内容
String finalPrompt = basePrompt + dynamicContent; // 最终提示词
```

## 🎯 **总结**

**PromptTemplate是Spring AI框架提供的模板工具**，不是Java底层类。它在项目中的核心作用：

1. **模板管理**：将复杂的AI提示词模板化，便于维护
2. **变量替换**：支持动态内容注入，提高提示词的灵活性
3. **格式统一**：确保所有AI调用使用一致的提示词格式
4. **版本控制**：模板文件可以纳入版本管理，便于追踪变更

这种设计体现了**配置与代码分离**的最佳实践，让AI提示词的管理更加规范化和可维护。
