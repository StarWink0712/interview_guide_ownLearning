# 修正 UnifiedEvaluationService 评估流程举例文档

## Why
之前的对话中，我对 `UnifiedEvaluationService` 的完整评估流程进行了逐步分析，但存在以下问题：
1. 步骤 1 的输出错误地描述为 `List<QaRecord>`，实际 `AnswerEvaluationService.evaluateInterview()` 的返回值是 `InterviewReportDTO`
2. 部分步骤的输入/输出描述不精确，需要整体复核

## What Changes
- 对全部 7 个步骤的输入/输出数据结构进行系统性复核
- 修正步骤 1 的输入/输出描述
- 将修正后的完整内容写入 `/app/src/main/java/interview/guide/common/evaluation/analysis/流程举例.md`

## Impact
- Affected specs: 无
- Affected code: `analysis/流程举例.md`（新建文档）

## ADDED Requirements
### Requirement: 流程举例文档
系统 SHALL 将修正后的 `UnifiedEvaluationService` 完整评估流程举例写入指定文档。

#### Scenario: 步骤 1 输入输出修正
- **WHEN** 描述 AnswerEvaluationService.evaluateInterview() 的输入输出
- **THEN** 输入应为 `ChatClient`, `String sessionId`, `String resumeText`, `List<InterviewQuestionDTO>`
- **AND** 输出应为 `InterviewReportDTO`

#### Scenario: 所有步骤输入输出复核
- **WHEN** 对全部步骤的输入/输出进行复核
- **THEN** 每个步骤的输入输出应准确反映对应代码逻辑
