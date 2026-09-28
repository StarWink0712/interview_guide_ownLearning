# AI 实施计划：自适应模拟面试引擎

## 一、执行前提

请先阅读：

- `AGENTS.md`
- `app/src/main/java/interview/guide/modules/interview/service/InterviewSessionService.java`
- `app/src/main/java/interview/guide/modules/interview/service/InterviewQuestionService.java`
- `app/src/main/java/interview/guide/modules/interview/service/AnswerEvaluationService.java`
- `app/src/main/java/interview/guide/common/evaluation/UnifiedEvaluationService.java`
- `app/src/main/resources/prompts/`
- `app/src/main/resources/skills/`

不要一开始重写现有面试流程。先以增量方式加入自适应能力。

## 二、第一阶段：新增即时评估模型

新增 DTO/record：

- `TurnEvaluationDTO`
- `AdaptiveDecisionDTO`
- `AbilityProfileDTO`
- `AbilityDimensionDTO`

建议字段：

`TurnEvaluationDTO`：

- `questionIndex`
- `score`
- `depthLevel`
- `missingKeyPoints`
- `followUpSuggestion`
- `category`

`AdaptiveDecisionDTO`：

- `action`
- `targetCategory`
- `nextDifficulty`
- `reason`
- `nextQuestion`

## 三、第二阶段：新增 Prompt

新增 prompt 文件：

```text
app/src/main/resources/prompts/interview-turn-evaluation-system.st
app/src/main/resources/prompts/interview-turn-evaluation-user.st
app/src/main/resources/prompts/interview-adaptive-decision-system.st
app/src/main/resources/prompts/interview-adaptive-decision-user.st
```

要求：

- 使用结构化 JSON 输出。
- 通过 `StructuredOutputInvoker` 调用。
- 输出字段必须可解析为 DTO。

## 四、第三阶段：新增服务

新增包：

```text
app/src/main/java/interview/guide/modules/interview/adaptive/
```

新增服务：

- `TurnEvaluationService`
- `AbilityProfileService`
- `AdaptiveDecisionService`

职责：

- `TurnEvaluationService`：评估当前回答。
- `AbilityProfileService`：根据每轮评分更新能力画像。
- `AdaptiveDecisionService`：决定下一步是追问、换题、升难度还是降难度。

## 五、第四阶段：接入回答提交流程

改造：

- `InterviewSessionService`
- `AnswerEvaluationService`

在用户提交答案后：

1. 保存原始回答。
2. 调用 `TurnEvaluationService`。
3. 更新能力画像。
4. 生成一条自适应追问或下一题建议。
5. 将追问追加到当前会话题目列表，或保存到单独表。

注意：

- 如果即时评估失败，不影响原有提交答案流程。
- 自适应追问可以先作为增强字段返回给前端，不强行改变原有题单。

## 六、第五阶段：数据持久化

方案一：轻量方案。

- 在 `InterviewSessionEntity` 增加：
  - `abilityProfileJson`
  - `adaptiveTraceJson`

方案二：规范方案。

新增实体：

- `InterviewTurnEvaluationEntity`
- `InterviewAbilityProfileEntity`
- `InterviewAdaptiveDecisionEntity`

推荐先做方案一，降低改造成本。

## 七、第六阶段：前端展示

前端可后置。第一版可以只让接口返回自适应结果。

后续页面可展示：

- 当前能力画像雷达图。
- 每轮追问原因。
- 系统为什么问下一题。
- 最终报告中的薄弱点。

涉及文件：

- `frontend/src/pages/InterviewPage.tsx`
- `frontend/src/components/InterviewPanel.tsx`
- `frontend/src/components/AnalysisPanel.tsx`
- `frontend/src/components/RadarChart.tsx`

## 八、测试要求

新增测试：

- `TurnEvaluationServiceTest`
- `AbilityProfileServiceTest`
- `AdaptiveDecisionServiceTest`

重点用例：

- 回答高分时生成升难度决策。
- 回答缺少关键点时生成追问决策。
- 多轮回答后能力画像正确更新。
- LLM 调用失败时不影响原有答题流程。

## 九、验收标准

完成后必须满足：

- 提交答案后能生成即时评估。
- 至少能根据即时评估生成一条动态追问。
- 会话中能保存能力画像。
- 面试报告能看到自适应分析结果。
- 原有静态题单面试流程仍可正常使用。

