# Skill（面试技能方向）系统分析

## 概念定义

**Skill（面试技能方向）** 是面试平台的核心概念，指的是**不同的技术领域或职位方向的面试配置**。每个 Skill 定义了一个特定的面试场景，包括：

1. **面试角色定义** - AI 面试官的人设和指导原则
2. **技术分类** - 该方向包含哪些技术领域
3. **参考资料** - 每个分类对应的学习资料
4. **UI 展示配置** - 图标、颜色等视觉元素

## 数据结构

### 核心字段
- **id**: 技能唯一标识（如 `java-backend`、`frontend`）
- **name**: 技能名称
- **description**: 技能描述
- **categories**: 技术分类列表
- **isPreset**: 是否为预设技能
- **display**: 展示配置（图标、渐变色等）

### 分类（Category）结构
每个分类包含：
- **key**: 分类键（如 `JAVA`、`MYSQL`）
- **label**: 分类标签（如 "Java"、"MySQL"）
- **priority**: 优先级（`CORE`、`NORMAL`、`ALWAYS_ONE`）
- **ref**: 参考文件路径
- **shared**: 是否为共享资源

### 示例：Java 后端技能
```yaml
id: "java-backend"
name: "Java 后端开发"
description: "用于 Java 后端面试出题；优先围绕 Java 核心、MySQL、Redis、Spring 与项目实战，追问设计取舍、故障处理和性能优化。"
categories:
  - key: "JAVA"
    label: "Java"
    priority: "CORE"
    ref: "java.md"
    shared: true
  - key: "MYSQL"
    label: "MySQL"
    priority: "CORE"
    ref: "mysql.md"
    shared: true
  - key: "REDIS"
    label: "Redis"
    priority: "NORMAL"
    ref: "redis.md"
    shared: true
  - key: "SPRING"
    label: "Spring"
    priority: "NORMAL"
    ref: "spring.md"
    shared: true
  - key: "PROJECT"
    label: "项目经历"
    priority: "ALWAYS_ONE"
```

## 完整面试流程

### 1. Skill 选择阶段
```
用户进入系统 → 获取所有 Skill 列表 → 选择特定 Skill（如 java-backend）
```

### 2. 系统配置阶段
根据选择的 Skill，系统会：

1. **加载 Skill 配置**
   - 从 `InterviewSkillService.getSkill(skillId)` 获取技能详情
   - 包含该技能的所有分类和优先级

2. **构建题目分配策略**
   ```java
   // calculateAllocation 方法会根据优先级分配题目数量
   // ALWAYS_ONE 类别：每个至少 1 题
   // CORE 类别：优先分配剩余题目
   // NORMAL 类别：轮转分配剩余题目
   Map<String, Integer> allocation = skillService.calculateAllocation(skillId, totalQuestions);
   ```

3. **准备参考资料**
   ```java
   // 将相关参考资料注入到 AI 提示词中
   String referenceSection = skillService.buildReferenceSection(skill, allocation);
   ```

### 3. 面试执行阶段

**面试出题时：**
- 系统根据 Skill 的 `categories` 和分配策略出题
- 每个分类的题目数量按 `allocation` 结果分配
- AI 参考 `references` 目录下的资料提问

**答题评估时：**
- 系统使用 `buildEvaluationReferenceSection()` 获取评估基准
- 根据该技能的标准答案和知识点评估用户回答

### 4. 具体例子

假设用户选择了 **Java 后端** 技能：

**题目分配结果（6 题面试）：**
- JAVA: 2 题（CORE 优先级）
- MYSQL: 2 题（CORE 优先级）  
- Redis: 1 题（NORMAL 优先级）
- Spring: 1 题（NORMAL 优先级）
- 项目经历: 1 题（ALWAYS_ONE 保证）

## 技术实现

### 核心组件

1. **InterviewSkillController** - REST API 接口
   - `GET /api/interview/skills` - 获取所有技能
   - `GET /api/interview/skills/{id}` - 获取特定技能
   - `POST /api/interview/skills/parse-jd` - 解析JD创建自定义技能

2. **InterviewSkillService** - 业务逻辑层
   - 技能配置加载和缓存
   - 题目分配算法
   - 参考资料管理
   - JD 解析功能

3. **InterviewSkillProperties** - 数据结构定义
   - Skill 配置的数据模型
   - 分类和展示配置

### 前端集成

```typescript
// 获取所有技能
const skills = await skillApi.listSkills();

// 获取特定技能详情
const skill = await skillApi.getSkill('java-backend');

// 解析JD文档创建自定义技能
const categories = await skillApi.parseJd(jdText);
```

### 前端使用场景

1. **技能选择界面** - 用户选择想要面试的技术方向
2. **动态配置** - 根据选择的 Skill 动态调整面试内容
3. **自定义技能** - 通过解析 JD 文档创建个性化技能

## 预设技能列表

系统包含以下预设 Skill：

- **java-backend**: Java 后端开发
- **frontend**: 前端开发  
- **python-backend**: Python 后端开发
- **system-design**: 系统设计
- **algorithm**: 算法
- **test-development**: 测试开发
- **ai-agent-dev**: AI 代理开发
- **bytedance-backend**: 字节后端
- **ali-backend**: 阿里后端

## 核心特性

### 1. 智能题目分配算法
```java
// 三级优先级分配策略
- ALWAYS_ONE: 每个分类至少保证 1 题
- CORE: 优先分配剩余题目
- NORMAL: 轮转分配剩余题目
```

### 2. 动态技能构建
```java
// 用户输入 JD 文档
String jdText = "我们正在招聘 Java 后端开发工程师，需要熟悉 Spring Boot、MySQL、Redis...";

// AI 解析 JD 提取技术分类
List<CategoryDTO> categories = skillService.parseJd(jdText);

// 构建自定义 Skill
SkillDTO customSkill = skillService.buildCustomSkill(categories, jdText);
```

### 3. 参考资料管理
- **共享资源**: `skills/_shared/references/`
- **技能专属**: `skills/{skillId}/references/`
- **自动缓存**: 启动时加载并缓存所有参考资料

### 4. 限流保护
- JD 解析接口使用 `@RateLimit(dimension = RateLimit.Dimension.IP, count = 5)`
- 防止滥用 AI 解析服务

## 系统优势

1. **标准化** - 为每个技术方向定义标准的面试范围和深度
2. **个性化** - 根据不同职位要求定制面试内容
3. **自动化** - 自动分配题目比例和准备参考资料
4. **可扩展** - 支持添加新的技能方向和自定义技能
5. **一致性** - 确保相同技能方向的面试保持专业性和一致性

## 文件结构

```
skills/
├── _shared/
│   └── references/          # 共享参考资料
├── java-backend/
│   ├── SKILL.md            # 技能定义（front matter + persona）
│   └── skill.meta.yml      # 元数据配置（categories, display）
├── frontend/
│   ├── SKILL.md
│   └── skill.meta.yml
└── ...
```

## 总结

Skill 系统的核心价值是：**一次配置，多次复用**，确保相同技能方向的面试保持一致性和专业性。通过定义清晰的技能分类和题目分配策略，系统能够为用户提供标准化、个性化的面试体验。