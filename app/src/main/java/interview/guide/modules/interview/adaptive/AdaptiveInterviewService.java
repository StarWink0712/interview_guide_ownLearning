package interview.guide.modules.interview.adaptive;

import interview.guide.modules.interview.adaptive.model.AbilityProfileDTO;
import interview.guide.modules.interview.adaptive.model.AdaptiveDecisionDTO;
import interview.guide.modules.interview.adaptive.model.AdaptiveInterviewResultDTO;
import interview.guide.modules.interview.adaptive.model.AdaptiveTraceDTO;
import interview.guide.modules.interview.adaptive.model.TurnEvaluationDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 自适应面试 - 核心编排服务
 *
 * <p>自适应面试的入口门面，负责编排 TurnEvaluationService、AbilityProfileService、
 * AdaptiveDecisionService 和 InterviewPersistenceService 共同完成每轮答题后的
 * 即时评估、能力画像更新、自适应决策和结果持久化整个流程。
 *
 * <p>调用链：InterviewController → InterviewSessionService → AdaptiveInterviewService
 * →（TurnEvaluationService / AbilityProfileService / AdaptiveDecisionService /
 * InterviewPersistenceService）
 *
 * <p>核心编排流程（evaluateAndDecide）：
 * <pre>
 *   1. TurnEvaluationService.evaluateTurn()      → 单轮即时评估
 *   2. AbilityProfileService.updateProfile()     → 更新累积能力画像
 *   3. AdaptiveDecisionService.decideNextStep()      → LLM 驱动决策（主路径）
 *      AdaptiveDecisionService.fallbackDecision()    → 本地规则兜底（LLM 失败时）
 *   4. 返回 AdaptiveInterviewResultDTO（评估+画像+决策）
 * </pre>
 *
 * <p><strong>联动关系：</strong>
 * <ul>
 *   <li>被 InterviewSessionService 调用（提交答案时 evaluateAndDecideSafely 做即时评估，
 *       生成报告时 attachAdaptiveAnalysis 附加自适应分析到报告）</li>
 *   <li>依赖 TurnEvaluationService 进行单轮答题即时评估，产出 TurnEvaluationDTO</li>
 *   <li>依赖 AbilityProfileService 更新和序列化/反序列化能力画像</li>
 *   <li>依赖 AdaptiveDecisionService 进行 LLM 驱动决策（主路径）和本地规则兜底</li>
 *   <li>依赖 InterviewPersistenceService 将会话状态写回 InterviewSessionEntity
 *       （updateAnswerEvaluation 更新答题评分、updateAdaptiveState 更新画像和追踪链）</li>
 *   <li>依赖 ObjectMapper 进行 AdaptiveTraceDTO 列表的 JSON 序列化与反序列化</li>
 *   <li>使用 AdaptiveInterviewResultDTO 封装单轮的评估、画像和决策三合一结果</li>
 *   <li>使用 AdaptiveTraceDTO 记录每轮答题的完整决策链路，持久化到 adaptiveTraceJson</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdaptiveInterviewService {

  /**
   * 单轮答题评估服务
   *
   * <p>通过 LLM 对面试者单轮答题进行即时评估，产出得分、能力分类、缺失知识点和追问建议。
   */
  private final TurnEvaluationService turnEvaluationService;

  /**
   * 能力画像服务
   *
   * <p>累积合并多轮评估结果为维度化的能力画像，支持 JSON 序列化/反序列化。
   */
  private final AbilityProfileService abilityProfileService;

  /**
   * 自适应决策服务
   *
   * <p>LLM 驱动的主路径决策 + 本地规则兜底，决定下一题的难度、分类和策略。
   */
  private final AdaptiveDecisionService adaptiveDecisionService;

  /**
   * 面试持久化服务
   *
   * <p>负责会话查询、答题评分更新和自适应状态（画像 JSON、追踪链 JSON）的数据库写操作。
   */
  private final InterviewPersistenceService persistenceService;

  /**
   * Jackson ObjectMapper 实例
   *
   * <p>用于 AdaptiveTraceDTO 列表的 JSON 序列化与反序列化。
   * 与 AbilityProfileService 中的 ObjectMapper 职责分离：
   * 这里的只处理追踪链，AbilityProfileService 中的处理能力画像。
   */
  private final ObjectMapper objectMapper;

  /**
   * 带容错的自适应即时评估（对外安全入口）
   *
   * <p>从数据库加载会话信息后调用核心编排方法 {@link #evaluateAndDecide}，
   * 完成评估后将结果持久化。整个调用包裹在 try-catch 中，任何异常都不会
   * 中断主流程，仅记录 warn 日志并返回 null。
   *
   * <p>此方法的"安全"体现在两个方面：
   * <ul>
   *   <li>会话不存在时直接返回 null，不抛异常</li>
   *   <li>评估过程中的任何异常都会被捕获，保留原有答题流程不受影响</li>
   * </ul>
   *
   * <p>调用位置：InterviewSessionService — 提交答案时
   *
   * @param sessionId 面试会话 UUID
   * @param question 本轮题目
   * @param answer 面试者的回答文本
   * @return 包含评估、画像和决策的三合一结果，异常或会话不存在时返回 null
   */
  public AdaptiveInterviewResultDTO evaluateAndDecideSafely(
      String sessionId,
      InterviewQuestionDTO question,
      String answer
  ) {
    try {
      // 从数据库加载会话记录
      Optional<InterviewSessionEntity> sessionOpt = persistenceService.findBySessionId(sessionId);
      if (sessionOpt.isEmpty()) {
        log.warn("跳过自适应即时评估，会话不存在: sessionId={}", sessionId);
        return null;
      }

      // 从数据库会话记录中读取 LLM 提供商、当前难度和历史能力画像 JSON
      InterviewSessionEntity session = sessionOpt.get();
      AdaptiveInterviewResultDTO result = evaluateAndDecide(
          session.getLlmProvider(),
          session.getDifficulty(),
          session.getAbilityProfileJson(),
          question,
          answer
      );
      // 将评估分数写回 InterviewAnswerEntity，将画像和追踪链写回会话记录
      persistResult(sessionId, session.getAdaptiveTraceJson(), question, result);
      return result;
    } catch (Exception e) {
      log.warn("自适应即时评估失败，保留原有答题流程: sessionId={}, questionIndex={}",
          sessionId, question.questionIndex(), e);
      return null;
    }
  }

  /**
   * 将自适应分析附加到最终面试报告
   *
   * <p>从数据库读取会话的能力画像和自适应追踪链，反序列化后注入到
   * InterviewReportDTO 的对应字段，生成包含能力分析的完整报告。
   *
   * <p>调用位置：InterviewSessionService — 生成最终报告时
   *
   * @param sessionId 面试会话 UUID
   * @param report 不含自适应分析的原始报告（评分、反馈等基础信息）
   * @return 附加了能力画像和自适应追踪链的完整报告，会话不存在时返回原始报告
   */
  public InterviewReportDTO attachAdaptiveAnalysis(String sessionId, InterviewReportDTO report) {
    Optional<InterviewSessionEntity> sessionOpt = persistenceService.findBySessionId(sessionId);
    if (sessionOpt.isEmpty()) {
      return report;
    }
    InterviewSessionEntity session = sessionOpt.get();
    AbilityProfileDTO profile = abilityProfileService.parseProfile(session.getAbilityProfileJson());
    List<AdaptiveTraceDTO> trace = parseTrace(session.getAdaptiveTraceJson());
    return new InterviewReportDTO(
        report.sessionId(),
        report.totalQuestions(),
        report.overallScore(),
        report.categoryScores(),
        report.questionDetails(),
        report.overallFeedback(),
        report.strengths(),
        report.improvements(),
        report.referenceAnswers(),
        profile,
        trace
    );
  }

  /**
   * 自适应面试核心编排方法
   *
   * <p>由 {@link #evaluateAndDecideSafely} 在会话存在时调用；
   * 该公开方法也可供不需要持久化的测试或内部编排直接使用。
   *
   * <p>按固定顺序编排三个子服务的调用，完成一轮完整的自适应评估+决策：
   *
   * <ol>
   *   <li>调用 TurnEvaluationService 对单轮答题即时评估</li>
   *   <li>调用 AbilityProfileService 将本轮评估合并到累积能力画像</li>
   *   <li>调用 AdaptiveDecisionService 获取下一步策略（LLM 主路径，
   *       失败时自动降级到本地规则兜底）</li>
   * </ol>
   *
   * <p>LLM 决策失败时不会中断流程，catch 块中静默转为 fallbackDecision。
   *
   * @param llmProvider LLM 提供商标识（如 "dashscope"），透传给下游 AI 调用
   * @param currentDifficulty 当前难度等级：junior / mid / senior
   * @param previousProfileJson 上一轮的能力画像 JSON 字符串，首次调用时为 null
   * @param question 本轮题目
   * @param answer 面试者的回答文本
   * @return 封装了单轮评估、累积画像和下一步策略的三合一结果
   */
  public AdaptiveInterviewResultDTO evaluateAndDecide(
      String llmProvider,
      String currentDifficulty,
      String previousProfileJson,
      InterviewQuestionDTO question,
      String answer
  ) {
    // 步骤1：单轮即时评估
    TurnEvaluationDTO evaluation = turnEvaluationService.evaluateTurn(
        llmProvider,
        question,
        answer
    );
    // 步骤2：将本轮评估合并到累积能力画像
    AbilityProfileDTO profile = abilityProfileService.updateProfile(
        previousProfileJson,
        evaluation
    );
    // 步骤3：获取下一步决策，LLM 失败时降级到本地规则兜底
    AdaptiveDecisionDTO decision;
    try {
      decision = adaptiveDecisionService.decideNextStep(
          llmProvider,
          currentDifficulty,
          question,
          answer,
          evaluation,
          profile
      );
    } catch (Exception e) {
      log.warn("LLM 自适应决策失败，使用本地兜底策略: sessionQuestionIndex={}",
          question.questionIndex(), e);
      decision = adaptiveDecisionService.fallbackDecision(
          question,
          evaluation,
          currentDifficulty
      );
    }
    return new AdaptiveInterviewResultDTO(evaluation, profile, decision);
  }

  /**
   * 将本轮评估结果持久化到数据库
   *
   * <p>执行两个写操作：
   * <ul>
   *   <li>通过 InterviewPersistenceService.updateAnswerEvaluation 将即时评分和
   *       反馈写入对应的 InterviewAnswerEntity</li>
   *   <li>通过 InterviewPersistenceService.updateAdaptiveState 将能力画像 JSON
   *       和自适应追踪链 JSON 写入 InterviewSessionEntity</li>
   * </ul>
   *
   * <p>追踪链会在历史追踪链基础上追加本轮记录（questionIndex + category +
   * TurnEvaluationDTO + AdaptiveDecisionDTO），形成完整的自适应决策历程。
   *
   * <p>持久化过程中的异常会被捕获并记录 warn 日志，不向上层抛出，
   * 保证评估结果的即时反馈不因持久化失败而丢失。
   *
   * @param sessionId 面试会话 UUID
   * @param previousTraceJson 历史追踪链 JSON（反序列化后追加本轮记录再覆盖写回）
   * @param question 本轮题目
   * @param result 本轮的评估+画像+决策结果
   */
  private void persistResult(
      String sessionId,
      String previousTraceJson,
      InterviewQuestionDTO question,
      AdaptiveInterviewResultDTO result
  ) {
    try {
      // 在历史追踪链基础上追加本轮记录
      List<AdaptiveTraceDTO> trace = new ArrayList<>(parseTrace(previousTraceJson));
      trace.add(new AdaptiveTraceDTO(
          question.questionIndex(),
          question.category(),
          result.turnEvaluation(),
          result.adaptiveDecision()
      ));

      // 将即时评分和反馈写入 InterviewAnswerEntity
      persistenceService.updateAnswerEvaluation(
          sessionId,
          question.questionIndex(),
          result.turnEvaluation().score(),
          buildInstantFeedback(result)
      );
      // 将能力画像和追踪链写入 InterviewSessionEntity
      persistenceService.updateAdaptiveState(
          sessionId,
          abilityProfileService.toJson(result.abilityProfile()),
          toJson(trace)
      );
    } catch (Exception e) {
      log.warn("保存自适应评估结果失败: sessionId={}, questionIndex={}",
          sessionId, question.questionIndex(), e);
    }
  }

  /**
   * 将自适应追踪链 JSON 反序列化为对象列表
   *
   * <p>由 {@link #attachAdaptiveAnalysis} 和 {@link #persistResult}
   * 读取历史追踪链时调用。
   *
   * <p>用于从数据库读取历史追踪链并反序列化，支持追加新纪录后覆盖写回。
   * 输入为 null 或空白时返回空列表，反序列化失败时记录 warn 并返回空列表。
   *
   * @param traceJson 自适应追踪链 JSON 字符串，来自 InterviewSessionEntity.adaptiveTraceJson
   * @return 追踪链对象列表，解析失败或输入为空时返回空列表
   */
  private List<AdaptiveTraceDTO> parseTrace(String traceJson) {
    if (traceJson == null || traceJson.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(traceJson, new TypeReference<List<AdaptiveTraceDTO>>() {});
    } catch (JacksonException e) {
      log.warn("解析自适应追踪失败，使用空追踪", e);
      return List.of();
    }
  }

  /**
   * 将自适应追踪链序列化为 JSON 字符串
   *
   * <p>仅由 {@link #persistResult} 在追加本轮追踪后调用。
   *
   * <p>用于将完整的追踪链列表写入数据库的 adaptiveTraceJson 字段。
   * 序列化异常不在此方法内捕获，由调用方 persistResult 统一处理。
   *
   * @param trace 追踪链对象列表
   * @return 序列化后的 JSON 字符串
   * @throws JacksonException 序列化失败时抛出，由调用方捕获
   */
  private String toJson(List<AdaptiveTraceDTO> trace) throws JacksonException {
    return objectMapper.writeValueAsString(trace);
  }

  /**
   * 构建即时评估反馈文本
   *
   * <p>仅由 {@link #persistResult} 写回答案即时反馈前调用。
   *
   * <p>组装给面试者的即时反馈文本，格式为：
   * "即时评估：{决策原因}"，如存在追问建议则追加 " 建议追问：{追问建议}"。
   *
   * <p>决策原因为空时使用兜底文本，追问建议为空时不追加。
   *
   * @param result 本轮的评估+画像+决策结果
   * @return 组装后的即时反馈文本
   */
  private String buildInstantFeedback(AdaptiveInterviewResultDTO result) {
    TurnEvaluationDTO evaluation = result.turnEvaluation();
    AdaptiveDecisionDTO decision = result.adaptiveDecision();
    // 决策原因为空时使用兜底文本
    String reason = decision.reason() == null || decision.reason().isBlank()
        ? "已完成本轮即时评估。"
        : decision.reason();
    String followUp = evaluation.followUpSuggestion() == null
        || evaluation.followUpSuggestion().isBlank()
        ? ""
        : " 建议追问：" + evaluation.followUpSuggestion();
    return "即时评估：" + reason + followUp;
  }
}
