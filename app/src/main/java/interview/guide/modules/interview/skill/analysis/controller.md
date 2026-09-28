
          
### 接收前端请求 — 通过 HTTP API 被调用

这是一个 `@RestController`，3 个方法都通过 HTTP 接收前端请求：

| 方法 | 前端调用 | 调用位置 |
|------|:---:|------|
| `listSkills()` | ✅ | `useInterviewConfig.ts`（面试配置页加载方向列表）<br>`InterviewHistoryPage.tsx`（历史页显示方向名称）<br>`VoiceInterviewPage.tsx`（语音面试页） |
| `getSkill(id)` | ❌ | 前端 API 层定义了 `skillApi.getSkill()`，但**当前页面未直接调用** |
| `parseJd()` | ✅ | `useInterviewConfig.ts`（用户粘贴 JD 后自动解析分类） |

---

### 被后端代码调用 — 通过 Service 层间接使用

Controller 方法委托给 `InterviewSkillService`，而该 Service **也被其他后端代码直接注入使用**：

| Service 方法 | 被谁内部调用 | 场景 |
|-------------|------------|------|
| `getSkill(skillId)` | `InterviewQuestionService.generateQuestionsBySkill()` | 出题时获取方向定义和分类 |
| `buildCustomSkill()` | `InterviewQuestionService` | JD 解析结果构建自定义方向 |
| `calculateAllocation()` | `InterviewQuestionService` | 计算题目分配 |
| `buildReferenceSection()` | `InterviewQuestionService` | 注入参考资料到出题 Prompt |
| `buildEvaluationReferenceSectionSafe()` | `AnswerEvaluationService` | 注入参考资料到评估 Prompt |

---

### 结论

这个 Controller **两头都服务**：
- 对外：提供 REST API，前端通过 `GET /api/interview/skills` 和 `POST /api/interview/skills/parse-jd` 调用
- 对内：底层的 `InterviewSkillService` 被 `InterviewQuestionService` 和 `AnswerEvaluationService` 直接注入使用，不经过 Controller 中转