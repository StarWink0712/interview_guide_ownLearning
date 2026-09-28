
          
## 代码分析

这是一个 **Java `record` 类型的数据载体类（DTO）**，用于表示**通用面试评估报告**。

### 代码特征

| 特征 | 说明 |
|---|---|
| **类型** | Java `record`（不可变数据类，Java 14+ 引入） |
| **用途** | 文字面试和语音面试共用的评估结果 DTO |
| **层级** | 通用基础层（`common/evaluation`），不属于 Controller/Service/Repository |

### 结构组成

`EvaluationReport` 是一个顶层 record，包含以下字段和内嵌 record：

- **顶层字段**（9 个）：
  - `sessionId` — 会话 ID
  - `totalQuestions` / `overallScore` — 总题数 / 总分
  - `categoryScores` — 分类得分列表
  - `questionDetails` — 逐题评估详情
  - `overallFeedback` / `strengths` / `improvements` — 综合评语、优势、改进建议
  - `referenceAnswers` — 参考答案列表

- **3 个内嵌 record**：
  - `CategoryScore` — 分类得分（分类名、得分、题目数）
  - `QuestionEvaluation` — 逐题评估（题号、题目、分类、用户回答、得分、反馈）
  - `ReferenceAnswer` — 参考答案（题号、题目、参考答案、关键要点）

### 设计特点

1. **不可变**：`record` 自动生成 `final` 字段、全参构造器、`equals/hashCode/toString`，字段不可修改
2. **纯数据载体**：没有任何业务逻辑，完全符合项目中 DTO 的命名和使用规范
3. **共用模型**：被 `UnifiedEvaluationService` 构建，由文字面试和语音面试两个模块共用
4. **嵌套 record**：将关联的子数据结构以内嵌 `record` 形式组织，保持高内聚

按项目编码规范，这属于 `common/` 下的通用数据模型，不直接暴露 Entity，符合"禁止直接返回 Entity 给前端"的规则。