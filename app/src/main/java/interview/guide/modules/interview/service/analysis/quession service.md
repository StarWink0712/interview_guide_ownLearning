🎯 **核心功能概述**

这个服务负责**智能生成面试题目**，支持两种模式：

- **无简历模式**：单次 Skill 驱动出题
- **有简历模式**：并行调用（简历题 60% + 方向题 40%）

## 🏗️ **架构设计**

### **A. 双模式出题策略**

```java
// 核心算法：简历题60% + 方向题40%
boolean hasResume = resumeText != null && !resumeText.isBlank();
if (!hasResume) {
    return generateDirectionOnly(questionChatClient, skill, difficultyDesc, questionCount, historicalSection);
}

// 并行生成简历题和方向题
int resumeCount = Math.max(1, (int) Math.round(questionCount * RESUME_QUESTION_RATIO));  // 60%
int directionCount = questionCount - resumeCount;  // 40%

CompletableFuture<List<InterviewQuestionDTO>> resumeFuture = CompletableFuture.supplyAsync(
    () -> generateResumeQuestions(...), questionExecutor);

CompletableFuture<List<InterviewQuestionDTO>> directionFuture = CompletableFuture.supplyAsync(
    () -> generateDirectionOnly(...), questionExecutor);
```

### **B. 虚拟线程并行处理**

```java
// 使用虚拟线程池，高效并发
private final ExecutorService questionExecutor = Executors.newVirtualThreadPerTaskExecutor();
```

## 📋 **关键组件分析**

### **1. 提示词模板系统**

#### **四套提示词模板**

```java
// 技能相关提示词
private final PromptTemplate skillSystemPromptTemplate;  // 技能系统提示模板
private final PromptTemplate skillUserPromptTemplate;   // 技能用户提示模板

// 简历相关提示词  
private final PromptTemplate resumeSystemPromptTemplate; // 简历系统提示模板
private final PromptTemplate resumeUserPromptTemplate;  // 简历用户提示模板
```

#### **提示词内容构建**

```java
// 简历题提示词构建
String systemPrompt = resumeSystemPromptTemplate.render()
    + buildSkillPersonaSection(skill)      // 技能角色设定
    + "\n\n" + outputConverter.getFormat();  // JSON格式指令

// 方向题提示词构建（包含通用模式）
String systemPrompt = skillSystemPromptTemplate.render()
    + buildSkillPersonaSection(skill)
    + GENERIC_MODE_SYSTEM_APPEND           // 通用模式追加内容
    + outputConverter.getFormat();
```

### **2. 结构化输出机制**

```java
// 使用熟悉的BeanOutputConverter + StructuredOutputInvoker
private final BeanOutputConverter<QuestionListDTO> outputConverter;
private final StructuredOutputInvoker structuredOutputInvoker;

// 调用AI生成题目
QuestionListDTO dto = structuredOutputInvoker.invoke(
    questionClient, systemPrompt, userPrompt, outputConverter,
    ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
    "简历题生成失败：", "简历题", log);
```

### **3. 降级机制**

#### **多层降级策略**

```java
try {
    resumeQuestions = resumeFuture.join();
} catch (CompletionException e) {
    log.error("简历题生成失败，降级为全方向题", e.getCause());
    directionFuture.cancel(true);
    return generateDirectionOnly(...);
}

try {
    directionQuestions = directionFuture.join();
} catch (CompletionException e) {
    log.error("方向题生成失败，降级为全简历题", e.getCause());
    if (resumeQuestions.isEmpty()) {
        return generateFallbackQuestions(skill, questionCount);
    }
    return resumeQuestions;
}
```

#### **默认回退问题库**

```java
private static final String[][] GENERIC_FALLBACK_QUESTIONS = {
    {"请描述一个你主导解决的技术难题，你的分析思路是什么？", "GENERAL", "综合能力"},
    {"你在做技术方案选型时，通常考虑哪些因素？请举例说明。", "GENERAL", "综合能力"},
    {"请分享一次你处理线上故障的经历，从发现到修复的完整过程。", "GENERAL", "综合能力"},
    // ... 更多备用问题
};
```

## 🎯 **核心算法详解**

### **1. 问题分配算法**

```java
// 简历题60% + 方向题40%
private static final double RESUME_QUESTION_RATIO = 0.6;
int resumeCount = Math.max(1, (int) Math.round(questionCount * RESUME_QUESTION_RATIO));
int directionCount = questionCount - resumeCount;
```

### **2. 追问生成算法**

```java
// 最多生成2个追问
private static final int MAX_FOLLOW_UP_COUNT = 2;
private final int followUpCount = Math.max(0, Math.min(properties.getFollowUpCount(), MAX_FOLLOW_UP_COUNT));

// 追问类型构建
private String buildDefaultFollowUp(String mainQuestion, int order) {
    if (order == 1) {
        return "基于\"" + mainQuestion + "\"，请结合你亲自做过的一个真实场景展开说明。";
    }
    return "基于\"" + mainQuestion + "\"，如果线上出现异常，你会如何定位并给出修复方案？";
}
```

### **3. 历史题目去重**

```java
private String buildHistoricalSection(List<HistoricalQuestion> historicalQuestions) {
    Map<String, List<String>> grouped = new HashMap<>();
    for (HistoricalQuestion hq : historicalQuestions) {
        String type = hq.type() != null && !hq.type().isBlank() ? hq.type() : DEFAULT_QUESTION_TYPE;
        String summary = hq.topicSummary();
        if (summary == null || summary.isBlank()) {
            String q = hq.question();
            summary = q.length() > 30 ? q.substring(0, 30) + "…" : q;
        }
        grouped.computeIfAbsent(type, k -> new ArrayList<>()).add(summary);
    }
    
    StringBuilder sb = new StringBuilder("已考过的知识点（避免重复出题）：\n");
    for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
        sb.append("- ").append(entry.getKey()).append(": ");
        sb.append(String.join(", ", entry.getValue()));
        sb.append('\n');
    }
    return sb.toString();
}
```

### **4. 问题截断算法**

```java
private List<InterviewQuestionDTO> capToMainCount(List<InterviewQuestionDTO> questions, int maxMainCount) {
    long currentMainCount = questions.stream().filter(q -> !q.isFollowUp()).count();
    
    if (currentMainCount <= maxMainCount) {
        if (currentMainCount < maxMainCount) {
            log.warn("AI 生成主问题不足: 请求={}, 实际={}", maxMainCount, currentMainCount);
        }
        return questions;
    }
    
    // 截断到指定数量
    List<InterviewQuestionDTO> capped = new ArrayList<>();
    int mainSeen = 0;
    for (InterviewQuestionDTO q : questions) {
        if (!q.isFollowUp()) {
            mainSeen++;
        }
        if (mainSeen > maxMainCount) {
            break;
        }
        capped.add(q);
    }
    return capped;
}
```

## 🎨 **提示词设计亮点**

### **1. 技能角色设定**

```java
private String buildSkillPersonaSection(SkillDTO skill) {
    if (skill == null || skill.persona() == null || skill.persona().isBlank()) {
        return "";
    }
    return "\n\n# Skill Persona\n"
        + "以下内容来自当前面试方向的 SKILL.md，请作为面试官角色、风格与出题约束：\n"
        + promptSanitizer.wrapWithDelimiters("skill_persona", skill.persona());
}
```

### **2. 通用模式处理**

```java
private static final String GENERIC_MODE_SYSTEM_APPEND = """
    \n\n# 通用面试模式
    本次面试无候选人简历，请出该方向的标准面试题。
    - 禁止出现"你在简历中提到..."、"你在项目中..."等暗示存在简历的表述
    - 问题表述应与简历无关，直接考察该方向的技术能力
    """;
```

### **3. 职位描述集成**

```java
private String buildJdSection(String sourceJd) {
    if (sourceJd == null || sourceJd.isBlank()) {
        return "";
    }
    return PromptSecurityConstants.DATA_BOUNDARY_INSTRUCTION + "\n" +
        "## 职位描述（JD）\n根据以下 JD 关键要求出题，确保题目与岗位实际需求相关：\n" +
        promptSanitizer.wrapWithDelimiters("jd", promptSanitizer.sanitize(sourceJd));
}
```

## 🔄 **完整执行流程**

```
用户请求生成面试题目
    ↓
InterviewQuestionService.generateQuestionsBySkill()
    ↓
┌─────────────────────────────────────────┐
│  1. 解析技能和难度信息                   │
│  2. 判断是否有简历                       │
│  3. 确定出题策略                        │
└─────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────┐
│  有简历 → 并行生成：                    │
│    ├─ 简历题 (60%) → generateResumeQuestions() │
│    └─ 方向题 (40%) → generateDirectionOnly()   │
│  无简历 → 单独生成方向题                │
└─────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────┐
│  并行执行：                            │
│    ├─ 虚拟线程池执行简历题生成          │
│    └─ 虚拟线程池执行方向题生成          │
└─────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────┐
│  结果合并：                            │
│    ├─ 简历题生成失败 → 降级为全方向题    │
│    ├─ 方向题生成失败 → 降级为全简历题    │
│    └─ 都失败 → 使用默认问题库           │
└─────────────────────────────────────────┘
    ↓
返回合并后的面试题目列表
```

## 🚀 **技术优势**

### **1. 高并发处理**

- 使用虚拟线程池 (`newVirtualThreadPerTaskExecutor()`)
- 并行生成简历题和方向题，提高效率

### **2. 智能降级**

- 多层降级策略，确保系统可用性
- 默认问题库，避免生成失败

### **3. 精准出题**

- 基于简历内容生成相关题目
- 历史题目去重，避免重复
- 职位描述集成，确保岗位相关性

### **4. 结构化输出**

- 使用熟悉的BeanOutputConverter + StructuredOutputInvoker
- 保证题目格式的一致性

### **5. 灵活配置**

- 可配置追问数量 (最多2个)
- 可配置题目数量和难度
- 支持自定义技能和分类

## 🎯 **实际应用场景**

### **1. 标准面试流程**

```java
// 基于简历和技能生成面试题目
List<InterviewQuestionDTO> questions = questionService.generateQuestionsBySkill(
    "openai", "java-backend", "mid", resumeText, 10, 
    historicalQuestions, null, jdText);
```

### **2. 自定义技能面试**

```java
// 使用自定义技能分类
List<CategoryDTO> customCategories = ...; // 自定义分类
List<InterviewQuestionDTO> questions = questionService.generateQuestionsBySkill(
    "openai", "custom-skill", "senior", null, 8, 
    null, customCategories, null);
```

### **3. 降级场景处理**

- AI服务不可用时自动使用默认问题
- 单个维度生成失败时使用其他维度
- 确保面试流程的连续性

这个服务体现了**AI驱动的智能面试系统**的核心能力，通过精心设计的提示词、并行处理和降级机制，为面试官提供高质量、个性化的面试题目。
