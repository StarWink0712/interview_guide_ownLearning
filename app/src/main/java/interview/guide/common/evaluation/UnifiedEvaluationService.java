/**
 * 统一面试评估服务
 *
 * 文字面试和语音面试共用的评估逻辑：分批评估 + 结构化输出 + 二次汇总 + 降级兜底。
 * 将问答记录按 batchSize 分批调用 LLM 评估，合并各批次结果后进行二次汇总，
 * 最终生成包含分类得分、逐题评估、参考答案的完整 EvaluationReport。
 * 单批次失败时零分兜底，二次汇总失败时降级到批次聚合结果。
 *
 * 联动关系：
 * - 依赖 StructuredOutputInvoker 执行结构化输出调用
 * - 依赖 InterviewEvaluationProperties 读取评估配置
 * - 被 AnswerEvaluationService（文字面试）、VoiceInterviewEvaluationService（语音面试）调用
 * - 产出 EvaluationReport 作为评估结果
 */
package interview.guide.common.evaluation;

import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.evaluation.EvaluationReport.CategoryScore;
import interview.guide.common.evaluation.EvaluationReport.QuestionEvaluation;
import interview.guide.common.evaluation.EvaluationReport.ReferenceAnswer;
import interview.guide.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 统一面试评估服务
 * 文字面试和语音面试共用的评估逻辑：分批评估 + 结构化输出 + 二次汇总 + 降级兜底
 */
@Service
public class UnifiedEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(UnifiedEvaluationService.class);
    /** 参考上下文最大字符数限制 */
    private static final int MAX_REFERENCE_CONTEXT_CHARS = 6000;

    /** 批次评估系统 Prompt 模板 */
    private final PromptTemplate systemPromptTemplate;
    /** 批次评估用户 Prompt 模板 */
    private final PromptTemplate userPromptTemplate;
    /** 批次评估结构化输出转换器 */
    private final BeanOutputConverter<BatchReportDTO> outputConverter;
    /** 汇总评估系统 Prompt 模板 */
    private final PromptTemplate summarySystemPromptTemplate;
    /** 汇总评估用户 Prompt 模板 */
    private final PromptTemplate summaryUserPromptTemplate;
    /** 汇总评估结构化输出转换器 */
    private final BeanOutputConverter<SummaryDTO> summaryOutputConverter;
    /** 结构化输出调用器（含重试和 JSON 修复） */
    private final StructuredOutputInvoker structuredOutputInvoker;
    /** 每批次评估的题目数量 */
    private final int evaluationBatchSize;
    /** 资源加载器，用于加载 Prompt 模板文件 */
    private final ResourceLoader resourceLoader;

    /** 单批次 LLM 评估返回的 DTO（含整体评分和逐题评分） */
    private record BatchReportDTO(
        int overallScore,
        String overallFeedback,
        List<String> strengths,
        List<String> improvements,
        List<QuestionEvalDTO> questionEvaluations
    ) {}

    /** 单题评估结果 DTO */
    private record QuestionEvalDTO(
        int questionIndex,
        int score,
        String feedback,
        String referenceAnswer,
        List<String> keyPoints
    ) {}

    /** 单个批次的评估结果（含起止索引） */
    private record BatchResult(
        int startIndex,
        int endIndex,
        BatchReportDTO report
    ) {}

    /** 二次汇总 LLM 返回的 DTO */
    private record SummaryDTO(
        String overallFeedback,
        List<String> strengths,
        List<String> improvements
    ) {}

    /**
     * 构造函数：加载 Prompt 模板并初始化结构化输出转换器
     *
     * @param structuredOutputInvoker 结构化输出调用器
     * @param resourceLoader 资源加载器
     * @param evaluationProperties 评估配置属性
     * @throws IOException Prompt 模板文件加载失败时抛出
     */
    public UnifiedEvaluationService(
            StructuredOutputInvoker structuredOutputInvoker,
            ResourceLoader resourceLoader,
            InterviewEvaluationProperties evaluationProperties) throws IOException {
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.resourceLoader = resourceLoader;
        this.systemPromptTemplate = new PromptTemplate(loadPrompt(evaluationProperties.getSystemPromptPath()));
        this.userPromptTemplate = new PromptTemplate(loadPrompt(evaluationProperties.getUserPromptPath()));
        this.outputConverter = new BeanOutputConverter<>(BatchReportDTO.class);
        this.summarySystemPromptTemplate = new PromptTemplate(loadPrompt(evaluationProperties.getSummarySystemPromptPath()));
        this.summaryUserPromptTemplate = new PromptTemplate(loadPrompt(evaluationProperties.getSummaryUserPromptPath()));
        this.summaryOutputConverter = new BeanOutputConverter<>(SummaryDTO.class);
        this.evaluationBatchSize = Math.max(1, evaluationProperties.getBatchSize());
    }

    /**
     * 评估面试问答（四参数便捷版本，无参考上下文）
     *
     * @param chatClient  LLM 客户端
     * @param sessionId   会话ID
     * @param qaRecords   问答记录列表
     * @param resumeText  简历摘要（可为 null）
     * @return 评估报告
     */
    public EvaluationReport evaluate(ChatClient chatClient,
                                     String sessionId,
                                     List<QaRecord> qaRecords,
                                     String resumeText) {
        return evaluate(chatClient, sessionId, qaRecords, resumeText, null);
    }

    /**
     * 评估面试问答（五参数完整版本）。
     *
     * <p>评估流程（三阶段）：
     * <ol>
     *   <li><b>分批评估阶段</b>：将全量问答按 batchSize 分批，每批独立调用 LLM 评估</li>
     *   <li><b>合并批次阶段</b>：将各批次的逐题评分、综合评语、优势/改进建议合并，缺失项零分兜底</li>
     *   <li><b>二次汇总阶段</b>：将所有批次的中间结果作为输入，再次调用 LLM 生成统一的综合评语
     *       （失败时降级到批次聚合结果）</li>
     * </ol>
     *
     * <p>降级策略：
     * <ul>
     *   <li>单批次评估失败 → 零分兜底（系统仍然可用）</li>
     *   <li>二次汇总失败 → 降级到批次聚合结果（不中断评估流程）</li>
     * </ul>
     *
     * @param chatClient       LLM 客户端（已配置 SkillsTool 和 Advisors）
     * @param sessionId        会话ID（用于日志追踪）
     * @param qaRecords        问答记录列表（按题目顺序排列）
     * @param resumeText       简历摘要（可为 null，超长自动截断为 3000 字符）
     * @param referenceContext 技能参考材料（可为 null，超长自动截断为 6000 字符）
     * @return 完整评估报告
     */
    public EvaluationReport evaluate(ChatClient chatClient,
                                     String sessionId,
                                     List<QaRecord> qaRecords,
                                     String resumeText,
                                     String referenceContext) {
        log.info("开始评估面试: sessionId={}, 共{}题", sessionId, qaRecords.size());

        // 简历上下文：空值保护 + 超长截断（保留前 3000 字符，约 1500~2000 tokens）
        String resumeContext = resumeText != null ? resumeText : "";
        if (resumeContext.length() > 3000) {
            resumeContext = resumeContext.substring(0, 3000) + "\n...(简历内容过长，已截断)";
        }
        // 参考上下文：空值保护 + 超长截断
        String referenceBaseline = referenceContext != null ? referenceContext.trim() : "";
        if (referenceBaseline.length() > MAX_REFERENCE_CONTEXT_CHARS) {
            referenceBaseline = referenceBaseline.substring(0, MAX_REFERENCE_CONTEXT_CHARS)
                + "\n...(参考基线过长，已截断)";
        }
   
        // ===== 阶段 1：分批评估 =====
        // 按 batchSize 将问答记录分组，每组独立调用 LLM
        List<BatchResult> batchResults = evaluateInBatches(
            chatClient, sessionId, resumeContext, qaRecords, referenceBaseline
        );

        // ===== 阶段 2：合并批次结果 =====
        // 将各批次的逐题评分合并（缺失项零分兜底）
        List<QuestionEvalDTO> mergedEvaluations = mergeQuestionEvaluations(batchResults);
        // 合并各批次的综合评语、优势、改进建议作为兜底数据
        String fallbackFeedback = mergeOverallFeedback(batchResults);
        List<String> fallbackStrengths = mergeListItems(batchResults, true);
        List<String> fallbackImprovements = mergeListItems(batchResults, false);

        // ===== 阶段 3：二次汇总 =====
        // 使用 LLM 对合并结果进行二次汇总（失败时降级到批次聚合结果）
        SummaryDTO summary = summarizeBatchResults(
            chatClient, sessionId, resumeContext, referenceBaseline, qaRecords,
            mergedEvaluations, fallbackFeedback, fallbackStrengths, fallbackImprovements
        );

        // 构建最终评估报告
        return buildReport(sessionId, qaRecords, mergedEvaluations,
            summary.overallFeedback(), summary.strengths(), summary.improvements());
    }

    /**
     * 从指定路径加载 Prompt 模板文件
     *
     * @param path Prompt 模板文件路径
     * @return Prompt 模板内容
     * @throws IOException 文件加载失败时抛出
     */
    private String loadPrompt(String path) throws IOException {
        Resource resource = resourceLoader.getResource(path);
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * 分批评估所有问答记录
     */
    private List<BatchResult> evaluateInBatches(ChatClient chatClient, String sessionId,
                                                 String resumeContext, List<QaRecord> qaRecords,
                                                 String referenceContext) {
        List<BatchResult> results = new ArrayList<>();
        for (int start = 0; start < qaRecords.size(); start += evaluationBatchSize) {
            int end = Math.min(start + evaluationBatchSize, qaRecords.size());
            List<QaRecord> batch = qaRecords.subList(start, end);
            // 评估当前批次
            BatchReportDTO report = evaluateBatch(chatClient, sessionId, resumeContext, referenceContext, batch);
            results.add(new BatchResult(start, end, report));
        }
        return results;
    }

    /**
     * 核心在这里，这里调用llm，调用结构化common/ai/StructuredOutputInvoker.java方法
     * 即plain不带skilltool的llm
     * 调用 LLM 评估单批次问答记录。失败返回 null（由上层合并逻辑零分兜底）
     */
    private BatchReportDTO evaluateBatch(ChatClient chatClient, String sessionId,
                                          String resumeContext, String referenceContext,
                                          List<QaRecord> batch) {
        String qaRecords = buildQARecords(batch);
        String systemPrompt = systemPromptTemplate.render();

        Map<String, Object> variables = new HashMap<>();
        variables.put("resumeText", resumeContext);
        variables.put("qaRecords", qaRecords);
        variables.put("referenceContext",
            (referenceContext != null && !referenceContext.isBlank()) ? referenceContext : "无");
        // 构建用户 Prompt
        String userPrompt = userPromptTemplate.render(variables);

        String systemPromptWithFormat = systemPrompt + "\n\n" + outputConverter.getFormat();
        try {
            // 调用 LLM 评估当前批次,structuredOutputInvoker的invoke方法会自动处理输出转换
            //也是执行llm调用的方法，内部通过chatclient的prompt方法调用llm
            log.info("评估批次: sessionId={}, resumeContext={}, referenceContext={}, qaRecords={}",
                sessionId, resumeContext, referenceContext, qaRecords);
            return structuredOutputInvoker.invoke(
                chatClient, systemPromptWithFormat, userPrompt, outputConverter,
                ErrorCode.INTERVIEW_EVALUATION_FAILED, "批次评估失败：", "批次评估", log
            );
        } catch (Exception e) {
            log.error("批次评估失败: sessionId={}, batchSize={}, error={}",
                sessionId, batch.size(), e.getMessage(), e);
            // 返回空报告，让合并逻辑用零分兜底
            return null;
        }
    }

    /**
     * 将单批次 QaRecord 列表格式化为 LLM Prompt 文本
     */
    private String buildQARecords(List<QaRecord> batch) {
        StringBuilder sb = new StringBuilder();
        for (QaRecord q : batch) {
            sb.append(String.format("问题%d [%s]: %s\n",
                q.questionIndex() + 1, q.category(), q.question()));
            sb.append(String.format("回答: %s\n\n",
                q.userAnswer() != null ? q.userAnswer() : "(未回答)"));
        }
        return sb.toString();
    }

    /**
     * 合并多个批次的逐题评估结果，缺失项零分兜底
     */
    private List<QuestionEvalDTO> mergeQuestionEvaluations(List<BatchResult> batchResults) {
        List<QuestionEvalDTO> merged = new ArrayList<>();
        for (BatchResult result : batchResults) {
            int expectedSize = result.endIndex() - result.startIndex();
            List<QuestionEvalDTO> current =
                result.report() != null && result.report().questionEvaluations() != null
                    ? result.report().questionEvaluations()
                    : List.of();
            for (int i = 0; i < expectedSize; i++) {
                if (i < current.size() && current.get(i) != null) {
                    merged.add(current.get(i));
                } else {
                    merged.add(new QuestionEvalDTO(
                        result.startIndex() + i, 0,
                        "该题未成功生成评估结果，系统按 0 分处理。", "", List.of()
                    ));
                }
            }
        }
        return merged;
    }

    /**
     * 合并多个批次的综合评语
     */
    private String mergeOverallFeedback(List<BatchResult> batchResults) {
        String feedback = batchResults.stream()
            .map(BatchResult::report)
            .filter(r -> r != null && r.overallFeedback() != null && !r.overallFeedback().isBlank())
            .map(BatchReportDTO::overallFeedback)
            .collect(Collectors.joining("\n\n"));
        return feedback.isBlank() ? "本次面试已完成分批评估，但未生成有效综合评语。" : feedback;
    }

    /**
     * 合并多个批次的列表项（strengthsMode=true 合并优势，false 合并改进建议）。
     * 去重并限制最多 8 条。
     */
    private List<String> mergeListItems(List<BatchResult> batchResults, boolean strengthsMode) {
        Set<String> merged = new LinkedHashSet<>();
        for (BatchResult result : batchResults) {
            BatchReportDTO report = result.report();
            if (report == null) continue;
            List<String> items = strengthsMode ? report.strengths() : report.improvements();
            if (items == null) continue;
            items.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim)
                .forEach(merged::add);
        }
        return merged.stream().limit(8).toList();
    }

    /**
     * 二次汇总：将所有批次评估结果聚合为最终的综合评语、优势和改进建议。
     * LLM 调用失败时降级到批次聚合结果。
     */
    private SummaryDTO summarizeBatchResults(
            ChatClient chatClient, String sessionId, String resumeContext, String referenceContext,
            List<QaRecord> qaRecords, List<QuestionEvalDTO> evaluations,
            String fallbackFeedback, List<String> fallbackStrengths, List<String> fallbackImprovements) {
        try {
            String summarySystem = summarySystemPromptTemplate.render();
            Map<String, Object> vars = new HashMap<>();
            vars.put("resumeText", resumeContext);
            vars.put("referenceContext",
                (referenceContext != null && !referenceContext.isBlank()) ? referenceContext : "无");
            vars.put("categorySummary", buildCategorySummary(qaRecords, evaluations));
            vars.put("questionHighlights", buildQuestionHighlights(qaRecords, evaluations));
            vars.put("fallbackOverallFeedback", fallbackFeedback);
            vars.put("fallbackStrengths", String.join("\n", fallbackStrengths));
            vars.put("fallbackImprovements", String.join("\n", fallbackImprovements));
            String summaryUser = summaryUserPromptTemplate.render(vars);

            String systemWithFormat = summarySystem + "\n\n" + summaryOutputConverter.getFormat();
            SummaryDTO dto = structuredOutputInvoker.invoke(
                chatClient, systemWithFormat, summaryUser, summaryOutputConverter,
                ErrorCode.INTERVIEW_EVALUATION_FAILED, "总结评估失败：", "总结评估", log
            );

            String feedback = dto != null && dto.overallFeedback() != null && !dto.overallFeedback().isBlank()
                ? dto.overallFeedback() : fallbackFeedback;
            List<String> strengths = sanitizeItems(dto != null ? dto.strengths() : null, fallbackStrengths);
            List<String> improvements = sanitizeItems(dto != null ? dto.improvements() : null, fallbackImprovements);
            return new SummaryDTO(feedback, strengths, improvements);
        } catch (Exception e) {
            log.warn("二次汇总评估失败，降级到批次聚合结果: sessionId={}, error={}", sessionId, e.getMessage());
            return new SummaryDTO(fallbackFeedback, fallbackStrengths, fallbackImprovements);
        }
    }

    /**
     * 清理列表项（优先使用 LLM 返回结果，为空则降级到批次聚合结果）。
     * 去重并限制最多 8 条。
     */
    private List<String> sanitizeItems(List<String> primary, List<String> fallback) {
        List<String> source = (primary != null && !primary.isEmpty()) ? primary : fallback;
        if (source == null || source.isEmpty()) return List.of();
        return source.stream()
            .filter(item -> item != null && !item.isBlank())
            .map(String::trim).distinct().limit(8).toList();
    }

    /**
     * 构建最终评估报告：逐题汇总 → 分类汇总 → 总分计算。
     *
     * <p>处理逻辑：
     * <ol>
     *   <li><b>统计已回答题目数</b>：未回答的题目不计入总分</li>
     *   <li><b>逐题汇总</b>：为每道题生成 QuestionEvaluation（评分+反馈）和 ReferenceAnswer（参考答案+要点）</li>
     *   <li><b>分类汇总</b>：按题目分类（如"Java"、"MySQL"）计算每个分类的平均分</li>
     *   <li><b>总分计算</b>：所有已回答题目的评分取平均值；全部未回答则为 0 分</li>
     *   <li><b>空值保护</b>：LLM 返回的评分/反馈/参考答案为 null 时使用默认值</li>
     * </ol>
     *
     * @param sessionId 会话ID
     * @param qaRecords 原始问答记录
     * @param evaluations 合并后的逐题评估结果
     * @param overallFeedback 综合评语
     * @param strengths 优势列表
     * @param improvements 改进建议列表
     * @return 完整评估报告
     */
    private EvaluationReport buildReport(String sessionId, List<QaRecord> qaRecords,
                                          List<QuestionEvalDTO> evaluations,
                                          String overallFeedback,
                                          List<String> strengths, List<String> improvements) {
        List<QuestionEvaluation> questionDetails = new ArrayList<>();
        List<ReferenceAnswer> referenceAnswers = new ArrayList<>();
        // 按分类收集各题的得分，用于计算分类平均分
        Map<String, List<Integer>> categoryScoresMap = new HashMap<>();

        // 统计实际回答的题目数（排除未回答的题目）
        long answeredCount = qaRecords.stream()
            .filter(q -> q.userAnswer() != null && !q.userAnswer().isBlank())
            .count();

        int evalSize = evaluations != null ? evaluations.size() : 0;

        // 遍历每一道题，构建评估详情和参考答案
        for (int i = 0; i < qaRecords.size(); i++) {
            QaRecord q = qaRecords.get(i);
            QuestionEvalDTO eval = i < evalSize ? evaluations.get(i) : null;

            // 判断该题是否已回答：未回答直接给 0 分
            boolean hasAnswer = q.userAnswer() != null && !q.userAnswer().isBlank();
            int score = hasAnswer && eval != null ? eval.score() : 0;
            // 反馈信息：LLM 返回为 null 时使用默认描述
            String feedback = eval != null && eval.feedback() != null
                ? eval.feedback() : "该题未成功生成评估反馈。";
            // 参考答案：LLM 返回为 null 时使用空字符串
            String refAnswer = eval != null && eval.referenceAnswer() != null
                ? eval.referenceAnswer() : "";
            // 关键要点：LLM 返回为 null 时使用空列表
            List<String> keyPoints = eval != null && eval.keyPoints() != null
                ? eval.keyPoints() : List.of();

            // 添加逐题评估详情
            questionDetails.add(new QuestionEvaluation(
                q.questionIndex(), q.question(), q.category(), q.userAnswer(), score, feedback
            ));
            // 添加参考答案
            referenceAnswers.add(new ReferenceAnswer(
                q.questionIndex(), q.question(), refAnswer, keyPoints
            ));
            // 按分类收集得分
            categoryScoresMap.computeIfAbsent(q.category(), k -> new ArrayList<>()).add(score);
        }

        // 计算每个分类的平均分
        List<CategoryScore> categoryScores = categoryScoresMap.entrySet().stream()
            .map(e -> new CategoryScore(
                e.getKey(),
                (int) e.getValue().stream().mapToInt(Integer::intValue).average().orElse(0),
                e.getValue().size()
            ))
            .collect(Collectors.toList());

        // 总分：所有已回答题目的评分取平均值；全部未回答则为 0 分
        int overallScore = answeredCount == 0 ? 0
            : (int) questionDetails.stream().mapToInt(QuestionEvaluation::score).average().orElse(0);

        // 构建并返回完整评估报告
        return new EvaluationReport(
            sessionId, qaRecords.size(), overallScore, categoryScores, questionDetails,
            overallFeedback,
            strengths != null ? strengths : List.of(),
            improvements != null ? improvements : List.of(),
            referenceAnswers
        );
    }

    /**
     * 构建分类得分汇总文本（用于二次汇总 Prompt）
     */
    private String buildCategorySummary(List<QaRecord> qaRecords, List<QuestionEvalDTO> evaluations) {
        Map<String, List<Integer>> categoryScores = new HashMap<>();
        for (int i = 0; i < qaRecords.size(); i++) {
            QaRecord q = qaRecords.get(i);
            QuestionEvalDTO eval = i < evaluations.size() ? evaluations.get(i) : null;
            int score = 0;
            if (eval != null && q.userAnswer() != null && !q.userAnswer().isBlank()) {
                score = eval.score();
            }
            categoryScores.computeIfAbsent(q.category(), k -> new ArrayList<>()).add(score);
        }
        return categoryScores.entrySet().stream()
            .map(entry -> {
                int avg = (int) entry.getValue().stream().mapToInt(Integer::intValue).average().orElse(0);
                return String.format("- %s: 平均分 %d, 题数 %d", entry.getKey(), avg, entry.getValue().size());
            })
            .sorted()
            .collect(Collectors.joining("\n"));
    }

    /**
     * 构建单题亮点摘要文本（用于二次汇总 Prompt，最多 20 条）
     */
    private String buildQuestionHighlights(List<QaRecord> qaRecords, List<QuestionEvalDTO> evaluations) {
        List<String> highlights = new ArrayList<>();
        for (int i = 0; i < qaRecords.size(); i++) {
            QaRecord q = qaRecords.get(i);
            QuestionEvalDTO eval = i < evaluations.size() ? evaluations.get(i) : null;
            int score = eval != null ? eval.score() : 0;
            String feedback = eval != null && eval.feedback() != null ? eval.feedback() : "";
            String shortQ = q.question().length() > 50 ? q.question().substring(0, 50) + "..." : q.question();
            String shortF = feedback.length() > 80 ? feedback.substring(0, 80) + "..." : feedback;
            highlights.add(String.format("- Q%d | %s | 分数:%d | 反馈:%s", q.questionIndex() + 1, shortQ, score, shortF));
        }
        return highlights.stream().limit(20).collect(Collectors.joining("\n"));
    }
}
