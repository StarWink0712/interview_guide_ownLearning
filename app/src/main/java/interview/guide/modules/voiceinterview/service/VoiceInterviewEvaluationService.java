/**
 * 语音面试评估服务
 *
 * 负责语音面试的评估生成与查询。复用 UnifiedEvaluationService 的分批评估 + 结构化输出 + 降级兜底能力，
 * 将对话消息构建为 QaRecord 后调用统一评估服务生成 EvaluationReport，再持久化为 EvaluationEntity。
 * LLM 调用在事务外执行，仅 DB 写入在事务内，避免占用数据库连接。
 *
 * 联动关系：
 * - 被 VoiceEvaluateStreamConsumer 调用（异步消费时执行评估生成）
 * - 被 VoiceInterviewController 调用（查询评估结果）
 * - 依赖 UnifiedEvaluationService（通用评估引擎，分批评估 + 结构化输出）
 * - 依赖 LlmProviderRegistry（获取 ChatClient）
 * - 依赖 InterviewSkillService（构建评估参考上下文）
 * - 依赖 VoiceInterviewEvaluationRepository / MessageRepository / SessionRepository
 */
package interview.guide.modules.voiceinterview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.evaluation.EvaluationReport;
import interview.guide.common.evaluation.QaRecord;
import interview.guide.common.evaluation.UnifiedEvaluationService;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.voiceinterview.dto.VoiceEvaluationDetailDTO;
import interview.guide.modules.voiceinterview.dto.VoiceEvaluationDetailDTO.AnswerDetail;
import interview.guide.modules.voiceinterview.model.VoiceInterviewEvaluationEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewMessageEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewMessageRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 语音面试评估服务
 * 调用llm评估语音面试对话记录，生成评估结果
 * 负责语音面试的评估生成与查询
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VoiceInterviewEvaluationService {

    /**
     * 统一评估服务
     * 提供通用的分批评估 + 结构化输出 + 降级兜底能力
     */
    private final UnifiedEvaluationService unifiedEvaluationService;

    /**
     * LLM 提供器注册表
     * 用于获取 ChatClient 实例
     */
    private final LlmProviderRegistry llmProviderRegistry;

    /**
     * 评估结果数据仓库
     */
    private final VoiceInterviewEvaluationRepository evaluationRepository;

    /**
     * 消息数据仓库
     */
    private final VoiceInterviewMessageRepository messageRepository;

    /**
     * 会话数据仓库
     */
    private final VoiceInterviewSessionRepository sessionRepository;

    /**
     * JSON 对象映射器
     * 用于评估结果的 JSON 序列化/反序列化
     */
    private final ObjectMapper objectMapper;

    /**
     * 面试技能服务
     * 用于构建评估参考上下文
     */
    private final InterviewSkillService skillService;

    /**
     * 根据完整对话生成并持久化语音面试评估。
     *
     * 由 {@code VoiceEvaluateStreamConsumer.processBusiness} 调用。方法读取会话和有序消息，转换为
     * {@link QaRecord} 后调用 {@link UnifiedEvaluationService}；LLM 调用保持在事务外，仅最终保存
     * 进入事务。无有效消息时写入可轮询的空评估结果，而不是让任务失败。
     *
     * @param sessionId 会话 ID
     */
    public void generateEvaluation(Long sessionId) {
        try {
            log.info("开始生成语音面试评估: sessionId={}", sessionId);

            // 获取会话信息
            VoiceInterviewSessionEntity session = getSession(sessionId);
            List<VoiceInterviewMessageEntity> messages = messageRepository
                .findBySessionIdOrderBySequenceNumAsc(sessionId);

            // 无消息则保存空评估
            if (messages.isEmpty()) {
                log.warn("语音面试会话无对话记录，生成空评估结果: sessionId={}", sessionId);
                saveEmptyEvaluationTransactional(sessionId, session);
                return;
            }

            // 构建 QaRecord 列表，Qarecord是评估服务的输入，包含用户问题和模型回答
            List<QaRecord> qaRecords = buildQaRecords(messages);

            // 获取 LLM ChatClient
            String provider = session.getLlmProvider();
            ChatClient chatClient = llmProviderRegistry.getChatClientOrDefault(provider);

            // 构建评估参考上下文
            String sessionIdStr = String.valueOf(sessionId);
            String referenceContext = skillService.buildEvaluationReferenceSectionSafe(session.getSkillId());

            // 调用统一评估服务，在common.evaluation包下，因为是语音面试和普通面试通用的评估服务
            //所以代码解耦设计。
            EvaluationReport report = unifiedEvaluationService.evaluate(
                chatClient, sessionIdStr, qaRecords, null, referenceContext);

            // 保存评估结果
            saveEvaluationTransactional(sessionId, session, report);

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("生成语音面试评估失败: sessionId={}", sessionId, e);
            throw new BusinessException(ErrorCode.VOICE_EVALUATION_FAILED,
                "生成评估失败: " + e.getMessage());
        }
    }

    /**
     * 查询持久化评估并转换为前端详情 DTO。
     *
     * 由评估状态 REST 接口在任务完成后调用。结果通过前端轮询获取，不经 WebSocket 主动推送；
     * Repository 未找到记录时抛出语音评估领域异常。
     * @param sessionId 会话 ID
     * @return 评估详情 DTO
     */
    public VoiceEvaluationDetailDTO getEvaluation(Long sessionId) {
        // 从数据库查询评估结果
        VoiceInterviewEvaluationEntity evaluation = evaluationRepository.findBySessionId(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_EVALUATION_NOT_FOUND,
                "评估结果不存在: " + sessionId));

        return buildDetailDTO(evaluation);
    }

    /**
     * 将语音对话消息转换为统一评估引擎的问答记录。
     *
     * 由 {@link #generateEvaluation(Long)} 调用。AI 文本作为面试问题，用户识别文本作为回答；完全
     * 空白的轮次会被过滤，保留下来的记录重新生成连续索引。
     *
     * @param messages 消息列表
     * @return QaRecord 列表
     */
    private List<QaRecord> buildQaRecords(List<VoiceInterviewMessageEntity> messages) {
        List<QaRecord> records = new ArrayList<>();
        int index = 0;

        for (VoiceInterviewMessageEntity msg : messages) {
            String aiText = msg.getAiGeneratedText();
            String userText = msg.getUserRecognizedText();

            if ((aiText != null && !aiText.isBlank()) || (userText != null && !userText.isBlank())) {
                records.add(new QaRecord(
                    index,
                    aiText != null ? aiText.trim() : "",
                    inferCategory(aiText),
                    userText != null && !userText.isBlank() ? userText.trim() : null
                ));
                index++;
            }
        }

        return records;
    }

    /**
     * 根据 AI 问题中的关键词推断评估分类。
     *
     * 仅由 {@link #buildQaRecords(List)} 调用，为统一评估提供粗粒度分类；无法识别时归入技术问题，
     * 空文本归入综合。
     *
     * @param aiText AI 回复文本
     * @return 分类名称
     */
    private String inferCategory(String aiText) {
        if (aiText == null) return "综合";
        if (aiText.contains("项目") || aiText.contains("实习") || aiText.contains("工作经历")) return "项目深挖";
        if (aiText.contains("自我介绍") || aiText.contains("介绍一下自己")) return "自我介绍";
        if (aiText.contains("职业规划") || aiText.contains("为什么") || aiText.contains("优缺点")) return "HR问题";
        return "技术问题";
    }

    /**
     * 在事务中将统一评估报告序列化并保存。
     *
     * 由 {@link #generateEvaluation(Long)} 在 LLM 返回后调用。逐题结果、优势、改进项和参考答案以
     * JSON 文本落库，序列化或数据库异常统一转换为语音评估业务异常。
     *
     * @param sessionId 会话 ID
     * @param session 会话实体
     * @param report 评估报告
     */
    @Transactional
    public void saveEvaluationTransactional(Long sessionId, VoiceInterviewSessionEntity session,
                                 EvaluationReport report) {
        try {
            List<EvaluationReport.QuestionEvaluation> questionItems = report.questionDetails();
            List<EvaluationReport.ReferenceAnswer> refAnswerItems = report.referenceAnswers();

            VoiceInterviewEvaluationEntity entity = VoiceInterviewEvaluationEntity.builder()
                .sessionId(sessionId)
                .overallScore(report.overallScore())
                .overallFeedback(report.overallFeedback())
                .questionEvaluationsJson(objectMapper.writeValueAsString(questionItems))
                .strengthsJson(objectMapper.writeValueAsString(report.strengths()))
                .improvementsJson(objectMapper.writeValueAsString(report.improvements()))
                .referenceAnswersJson(objectMapper.writeValueAsString(refAnswerItems))
                .interviewerRole(session.getRoleType())
                .interviewDate(session.getStartTime())
                .build();

            evaluationRepository.save(entity);
            log.info("评估结果已保存: sessionId={}, score={}", sessionId, entity.getOverallScore());
        } catch (Exception e) {
            log.error("保存评估结果失败: sessionId={}", sessionId, e);
            throw new BusinessException(ErrorCode.VOICE_EVALUATION_FAILED,
                "保存评估失败: " + e.getMessage());
        }
    }

    /**
     * 在无有效问答时保存一份可展示的空评估。
     *
     * 由 {@link #generateEvaluation(Long)} 的空消息分支调用。若记录已存在则复用并覆盖为空结果，
     * 保证 Consumer 仍可将任务标记完成，前端也能获得明确说明。
     *
     * @param sessionId 会话 ID
     * @param session 会话实体
     */
    @Transactional
    public void saveEmptyEvaluationTransactional(Long sessionId, VoiceInterviewSessionEntity session) {
        try {
            VoiceInterviewEvaluationEntity entity = evaluationRepository.findBySessionId(sessionId)
                .orElseGet(() -> VoiceInterviewEvaluationEntity.builder().sessionId(sessionId).build());

            entity.setOverallScore(0);
            entity.setOverallFeedback("本次语音面试未形成有效对话记录，暂无可评估内容。");
            entity.setQuestionEvaluationsJson("[]");
            entity.setStrengthsJson("[]");
            entity.setImprovementsJson("[\"请先完成至少一轮有效问答后再生成评估。\"]");
            entity.setReferenceAnswersJson("[]");
            entity.setInterviewerRole(session.getRoleType());
            entity.setInterviewDate(session.getStartTime());

            evaluationRepository.save(entity);
            log.info("空评估结果已保存: sessionId={}", sessionId);
        } catch (Exception e) {
            log.error("保存空评估结果失败: sessionId={}", sessionId, e);
            throw new BusinessException(ErrorCode.VOICE_EVALUATION_FAILED,
                "保存空评估失败: " + e.getMessage());
        }
    }

    /**
     * 反序列化评估 JSON 列并组装详情 DTO。
     *
     * 由 {@link #getEvaluation(Long)} 调用。方法按问题索引合并逐题评价和参考答案，再返回前端需要的
     * 得分、反馈、优势和改进项；历史脏数据或 JSON 格式错误会转换为业务异常。
     *
     * @param entity 评估实体
     * @return 评估详情 DTO
     */
    private VoiceEvaluationDetailDTO buildDetailDTO(VoiceInterviewEvaluationEntity entity) {
        try {
            // 反序列化各项评估数据
            List<EvaluationReport.QuestionEvaluation> questionItems = objectMapper.readValue(
                entity.getQuestionEvaluationsJson(),
                new TypeReference<List<EvaluationReport.QuestionEvaluation>>() {});
            
            // 反序列化优势和改进
            // 因为优势和改进都是字符串列表，所以直接反序列化即可
            List<String> strengths = objectMapper.readValue(
                entity.getStrengthsJson(),
                new TypeReference<List<String>>() {});
            
            // 反序列化参考答案
            // 因为参考答案是字符串列表，所以直接反序列化即可
            List<String> improvements = objectMapper.readValue(
                entity.getImprovementsJson(),
                new TypeReference<List<String>>() {});
            
            
            List<EvaluationReport.ReferenceAnswer> refAnswers = objectMapper.readValue(
                entity.getReferenceAnswersJson(),
                new TypeReference<List<EvaluationReport.ReferenceAnswer>>() {});

            // 构建参考答案映射
            Map<Integer, EvaluationReport.ReferenceAnswer> refMap = refAnswers.stream()
                .collect(Collectors.toMap(
                    EvaluationReport.ReferenceAnswer::questionIndex, r -> r, (a, b) -> a));

            // 构建答案详情列表
            List<AnswerDetail> answers = new ArrayList<>();
            for (EvaluationReport.QuestionEvaluation q : questionItems) {
                EvaluationReport.ReferenceAnswer ref = refMap.get(q.questionIndex());
                answers.add(AnswerDetail.builder()
                    .questionIndex(q.questionIndex())
                    .question(q.question())
                    .category(q.category())
                    .userAnswer(q.userAnswer())
                    .score(q.score())
                    .feedback(q.feedback())
                    .referenceAnswer(ref != null ? ref.referenceAnswer() : null)
                    .keyPoints(ref != null ? ref.keyPoints() : null)
                    .build());
            }
            //返回评估详情 DTO
            return VoiceEvaluationDetailDTO.builder()
                .sessionId(entity.getSessionId())
                .totalQuestions(answers.size())
                .overallScore(entity.getOverallScore())
                .overallFeedback(entity.getOverallFeedback())
                .strengths(strengths)
                .improvements(improvements)
                .answers(answers)
                .build();

        } catch (Exception e) {
            log.error("构建评估详情失败: sessionId={}", entity.getSessionId(), e);
            throw new BusinessException(ErrorCode.VOICE_EVALUATION_FAILED,
                "构建评估结果失败: " + e.getMessage());
        }
    }

    /**
     * 为评估生成加载必需的会话元数据。
     *
     * 仅由 {@link #generateEvaluation(Long)} 调用；会话在任务执行前已被删除时抛出明确领域异常，
     * 由异步消费模板负责失败或取消处理。
     * 
     * @param sessionId 会话 ID
     * @return 会话实体
     */
    private VoiceInterviewSessionEntity getSession(Long sessionId) {
        return sessionRepository.findById(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND,
                "语音面试会话不存在: " + sessionId));
    }
}
