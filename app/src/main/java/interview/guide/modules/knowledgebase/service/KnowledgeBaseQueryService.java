/**
 * 知识库 RAG 查询服务
 *
 * 核心的 RAG 问答引擎，实现"检索增强生成"的完整流程：
 * 1. 问题归一化与可选的 Query Rewrite（利用 LLM 改写用户问题以提升检索效果）
 * 2. 基于向量相似度搜索检索相关文档片段（支持多候选查询策略和动态 topK/阈值参数）
 * 3. 构建 System/User Prompt 并调用 LLM 生成回答
 * 支持同步和流式（SSE）两种响应模式，流式输出带有探测窗口归一化机制，
 * 可快速识别"无信息"模板并提前终止，避免长篇拒答。
 *
 * 联动关系：
 * - 被 KnowledgeBaseController 委托处理同步和流式问答请求
 * - 被 RagChatSessionService.getStreamAnswer() 委托处理 RAG 聊天的流式回答
 * - 依赖 LlmProviderRegistry 获取 ChatClient（支持多 LLM Provider）
 * - 依赖 KnowledgeBaseVectorService 执行向量相似度搜索
 * - 依赖 KnowledgeBaseListService 获取知识库名称
 * - 依赖 KnowledgeBaseCountService 更新提问计数
 * - 依赖 KnowledgeBaseQueryProperties 读取搜索参数和 Prompt 路径
 * - 使用 PromptSecurityConstants 添加防注入指令
 */
package interview.guide.modules.knowledgebase.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.observability.RagMetricsRecorder;
import interview.guide.modules.aiobservability.model.RagRetrievalLogRequest;
import interview.guide.modules.aiobservability.service.RagRetrievalLogService;
import interview.guide.modules.knowledgebase.model.QueryRequest;
import interview.guide.modules.knowledgebase.model.QueryResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 知识库查询服务
 * 基于向量搜索的RAG问答
 */
@Slf4j
@Service
public class KnowledgeBaseQueryService {
    private static final String NO_RESULT_RESPONSE = "抱歉，在选定的知识库中未检索到相关信息。请换一个更具体的关键词或补充上下文后再试。";
    private static final int STREAM_PROBE_CHARS = 120;
    private static final int MAX_REWRITE_HISTORY_CHAR = 200;

    // LLM 提供器注册器
    private final LlmProviderRegistry llmProviderRegistry;
    // 向量服务
    private final KnowledgeBaseVectorService vectorService;
    // 知识库列表服务
    private final KnowledgeBaseListService listService;
    // 知识库计数服务
    private final KnowledgeBaseCountService countService;
    // RAG 检索日志服务
    private final RagRetrievalLogService retrievalLogService;
    // RAG 挀测指标记录器
    private final RagMetricsRecorder ragMetricsRecorder;
    // 系统提示模板
    private final PromptTemplate systemPromptTemplate;
    // 用户提示模板
    private final PromptTemplate userPromptTemplate;
    // 重写提示模板
    private final PromptTemplate rewritePromptTemplate;
    // 是否启用重写
    private final boolean rewriteEnabled;
    // 短查询长度
    private final int shortQueryLength;
    // 短查询 topK
    private final int topkShort;

    private final int topkMedium;
    private final int topkLong;
    // 短查询最小分数
    private final double minScoreShort;
    // 默认最小分数 
    private final double minScoreDefault;

    public KnowledgeBaseQueryService(
            LlmProviderRegistry llmProviderRegistry,
            KnowledgeBaseVectorService vectorService,
            KnowledgeBaseListService listService,
            KnowledgeBaseCountService countService,
            RagRetrievalLogService retrievalLogService,
            RagMetricsRecorder ragMetricsRecorder,
            KnowledgeBaseQueryProperties queryProperties,
            ResourceLoader resourceLoader) throws IOException {
        
        this.llmProviderRegistry = llmProviderRegistry;
        this.vectorService = vectorService;
        this.listService = listService;
        this.countService = countService;
        this.retrievalLogService = retrievalLogService;
        this.ragMetricsRecorder = ragMetricsRecorder;
        this.systemPromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getSystemPromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.userPromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getUserPromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.rewritePromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getRewritePromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.rewriteEnabled = queryProperties.getRewrite().isEnabled();
        this.shortQueryLength = queryProperties.getSearch().getShortQueryLength();
        this.topkShort = queryProperties.getSearch().getTopkShort();
        this.topkMedium = queryProperties.getSearch().getTopkMedium();
        this.topkLong = queryProperties.getSearch().getTopkLong();
        this.minScoreShort = queryProperties.getSearch().getMinScoreShort();
        this.minScoreDefault = queryProperties.getSearch().getMinScoreDefault();
    }

    private ChatClient getChatClient() {
        return llmProviderRegistry.getDefaultChatClient();
    }

    /**
     * 基于单个知识库回答用户问题
     *
     * @param knowledgeBaseId 知识库ID
     * @param question 用户问题
     * @return AI回答
     */
    public String answerQuestion(Long knowledgeBaseId, String question) {
        return answerQuestion(List.of(knowledgeBaseId), question);
    }

    /**
     * 基于多个知识库回答用户问题（RAG）
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @return AI回答
     */
    public String answerQuestion(List<Long> knowledgeBaseIds, String question) {
        return answerQuestionInternal(knowledgeBaseIds, question, null).answer();
    }
    // 内部方法，负责回答用户问题
    private RagAnswerResult answerQuestionInternal(
            List<Long> knowledgeBaseIds,
            String question,
            Long sessionId) {
        
        // 记录回答开始时间
        long answerStartNanos = System.nanoTime();
        // 创建 RAG 跟踪上下文
        // 包含会话ID和跟踪ID
        // 用于记录查询日志和统计指标
        RagTraceContext traceContext = new RagTraceContext(retrievalLogService.createTraceId(), sessionId);
        log.info("收到知识库提问: kbIds={}, question={}", knowledgeBaseIds, question);
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalizeQuestion(question).isBlank()) {
            recordRagAnswer(false, true, answerStartNanos);
            return new RagAnswerResult(NO_RESULT_RESPONSE, traceContext.traceId());
        }
        // 更新知识库计数
        // 每次查询都增加计数，避免重复计数
        // 计数器在查询完成后重置，避免对后续查询产生影响
        countService.updateQuestionCounts(knowledgeBaseIds);

        // 构建查询上下文
        // 包含用户问题和历史消息列表
        QueryContext queryContext = buildQueryContext(question, List.of());
        // 从向量数据库中检索相关文档
        List<Document> relevantDocs = retrieveRelevantDocs(queryContext, knowledgeBaseIds, traceContext);

        if (!hasEffectiveHit(relevantDocs)) {
            recordRagAnswer(false, true, answerStartNanos);
            return new RagAnswerResult(NO_RESULT_RESPONSE, traceContext.traceId());
        }

        // 构建上下文字符串
        // 每个文档之间用 "---\n\n" 分隔
        // 用于后续的 LLM 调用
        // 包含用户问题和相关文档内容
        String context = relevantDocs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));

        // 构建系统提示词
        // 包含知识库名称和上下文
        String systemPrompt = buildSystemPrompt();
        // 构建用户提示词
        // 包含上下文和用户问题 ，用于引导 LLM 生成回答
        String userPrompt = buildUserPrompt(context, question);

        try {
            // 调用 LLM 生成回答
            // 包含系统提示词和用户提示词
            // 返回 LLM 生成的文本内容
            String answer = getChatClient().prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
            answer = normalizeAnswer(answer);

            log.info("知识库问答完成: kbIds={}, traceId={}", knowledgeBaseIds, traceContext.traceId());
            // 记录回答成功事件
            recordRagAnswer(false, true, answerStartNanos);
            return new RagAnswerResult(answer, traceContext.traceId());

        } catch (Exception e) {
            recordRagAnswer(false, false, answerStartNanos);
            log.error("知识库问答失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED, "知识库查询失败：" + e.getMessage());
        }
    }

    /** 
     * 构建系统提示词
     */
    private String buildSystemPrompt() {
        return systemPromptTemplate.render()
            + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION;
    }

    /**
     * 构建用户提示词
     */
    private String buildUserPrompt(String context, String question) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("context", context);
        variables.put("question", question);
        return userPromptTemplate.render(variables);
    }

    /**
     * 查询知识库并返回完整响应
     */
    public QueryResponse queryKnowledgeBase(QueryRequest request) {
        RagAnswerResult result = answerQuestionInternal(
            request.knowledgeBaseIds(),
            request.question(),
            null
        );

        // 获取知识库名称（多个知识库用逗号分隔）
        List<String> kbNames = listService.getKnowledgeBaseNames(request.knowledgeBaseIds());
        String kbNamesStr = String.join("、", kbNames);

        // 使用第一个知识库ID作为主要标识（兼容前端）
        Long primaryKbId = request.knowledgeBaseIds().getFirst();

        return new QueryResponse(result.answer(), primaryKbId, kbNamesStr, result.traceId());
    }

    /**
     * 流式查询知识库（SSE，无上下文）
     * 这是知识库 直接问答 的流式接口（不带会话上下文），
     * 和 RAG 聊天模式走的是两条路径——聊天模式调的是带 history 参数的 answerQuestionStream(sessionId, kbIds, question, history) 。
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @return 流式响应
     */
    public Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question) {
        return answerQuestionStream(null, knowledgeBaseIds, question, List.of());
    }

    /**
     * 流式查询知识库（SSE，支持多轮上下文）
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @param history 历史对话消息（可选）
     * @return 流式响应
     */
    public Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question, List<Message> history) {
        return answerQuestionStream(null, knowledgeBaseIds, question, history);
    }

    /**
     * 流式查询知识库（SSE，支持多轮上下文）
     */
    public Flux<String> answerQuestionStream(
            Long sessionId,
            List<Long> knowledgeBaseIds,
            String question,
            List<Message> history) {
        return answerQuestionStreamWithTrace(sessionId, knowledgeBaseIds, question, history).content();
    }

    /**
     * 流式查询知识库（SSE，支持多轮上下文）
     *
     * @param sessionId 会话ID（可选）
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @param history 历史对话消息（可选）
     * @return 流式响应结果
     */
    public RagStreamResult answerQuestionStreamWithTrace(
            Long sessionId,
            List<Long> knowledgeBaseIds,
            String question,
            List<Message> history) {
        long answerStartNanos = System.nanoTime();
        RagTraceContext traceContext = new RagTraceContext(retrievalLogService.createTraceId(), sessionId);
        log.info("收到知识库流式提问: kbIds={}, question={}, historySize={}", knowledgeBaseIds, question,
                history != null ? history.size() : 0);
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalizeQuestion(question).isBlank()) {
            recordRagAnswer(true, true, answerStartNanos);
            return new RagStreamResult(traceContext.traceId(), Flux.just(NO_RESULT_RESPONSE));
        }

        try {
            // 1. 验证知识库是否存在并更新问题计数
            countService.updateQuestionCounts(knowledgeBaseIds);

            // 2. Query rewrite + 动态参数检索
            List<Message> effectiveHistory = sanitizeHistory(history);
            QueryContext queryContext = buildQueryContext(question, effectiveHistory);
            List<Document> relevantDocs = retrieveRelevantDocs(
                queryContext,
                knowledgeBaseIds,
                traceContext
            );

            if (!hasEffectiveHit(relevantDocs)) {
                recordRagAnswer(true, true, answerStartNanos);
                return new RagStreamResult(traceContext.traceId(), Flux.just(NO_RESULT_RESPONSE));
            }

            // 3. 构建上下文
            String context = relevantDocs.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n\n---\n\n"));

            log.debug("检索到 {} 个相关文档片段", relevantDocs.size());

            // 4. 构建提示词
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(context, question);

            // 5. 流式调用（带历史上下文）+ 探测窗口归一化
            var promptSpec = getChatClient().prompt().system(systemPrompt);
            if (!effectiveHistory.isEmpty()) {
                promptSpec = promptSpec.messages(effectiveHistory);
            }
            Flux<String> responseFlux = promptSpec
                    .user(userPrompt)
                    .stream()
                    .content();

            log.info("开始流式输出知识库回答(探测窗口): kbIds={}, traceId={}",
                knowledgeBaseIds, traceContext.traceId());
            Flux<String> content = normalizeStreamOutput(responseFlux)
                .doOnComplete(() -> {
                    recordRagAnswer(true, true, answerStartNanos);
                    log.info("流式输出完成: kbIds={}, traceId={}",
                        knowledgeBaseIds, traceContext.traceId());
                })
                .onErrorResume(e -> {
                    recordRagAnswer(true, false, answerStartNanos);
                    log.error("流式输出失败: kbIds={}, error={}", knowledgeBaseIds, e.getMessage(), e);
                    return Flux.just("【错误】知识库查询失败：AI服务暂时不可用，请稍后重试。");
                });
            return new RagStreamResult(traceContext.traceId(), content);

        } catch (Exception e) {
            recordRagAnswer(true, false, answerStartNanos);
            log.error("知识库流式问答失败: {}", e.getMessage(), e);
            return new RagStreamResult(
                traceContext.traceId(),
                Flux.just("【错误】知识库查询失败：" + e.getMessage())
            );
        }
    }

    /**
     * 构建查询上下文
     * 对原始问题进行归一化、结合历史对话改写，生成多个候选查询词，
     * 并根据问题长度动态解析检索参数（topK、minScore），最终封装为 QueryContext 供向量检索使用。
     *
     * @param originalQuestion 原始用户问题
     * @param history 历史对话消息列表
     * @return 包含归一化问题、改写问题和检索参数的查询上下文
     */
    private QueryContext buildQueryContext(String originalQuestion, List<Message> history) {
        // 归一化问题：去除多余空白、统一格式
        String normalizedQuestion = normalizeQuestion(originalQuestion);
        // 结合历史上下文改写问题，提升检索召回率
        String rewrittenQuestion = rewriteQuestion(normalizedQuestion, history);
        // 候选查询词集合：改写问题优先，归一化原问题作为备选
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(rewrittenQuestion);
        candidates.add(normalizedQuestion);

        // 根据问题长度动态确定 topK 和 minScore 阈值
        SearchParams searchParams = resolveSearchParams(normalizedQuestion);
        return new QueryContext(
            normalizedQuestion,
            rewrittenQuestion,
            new ArrayList<>(candidates),
            searchParams
        );
    }
    /**
     * 清洗历史上下文：移除空消息、重复消息等无效内容，确保检索时仅包含有效对话。
     */
    private List<Message> sanitizeHistory(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        return history;
    }

//       清洗
    private String normalizeQuestion(String question) {
        return question == null ? "" : question.trim();
    }

    /**
     * 向量检索：遍历候选查询词，依次在 pgvector 中执行相似度检索，
     * 返回首个有命中结果的文档列表。检索完成后通过 finally 块记录指标和日志。
     *
     * @param queryContext 查询上下文（候选查询词、检索参数等）
     * @param knowledgeBaseIds 目标知识库 ID 列表
     * @param traceContext RAG 追踪上下文（traceId + sessionId）
     * @return 相似度最高的文档片段列表，无命中时返回空列表
     */
    private List<Document> retrieveRelevantDocs(
            QueryContext queryContext,
            List<Long> knowledgeBaseIds,
            RagTraceContext traceContext) {
        long startNanos = System.nanoTime();
        List<Document> selectedDocs = List.of();
        boolean success = false;
        try {
            // 按优先级遍历候选查询词，改写词优先、原词备选
            for (String candidateQuery : queryContext.candidateQueries()) {
                if (candidateQuery.isBlank()) {
                    continue;
                }
                // 调用 pgvector 向量相似度检索，返回 topK 条且 minScore 以上的文档片段
                List<Document> docs = vectorService.similaritySearch(
                    candidateQuery,
                    knowledgeBaseIds,
                    queryContext.searchParams().topK(),
                    queryContext.searchParams().minScore()
                );
                log.info("检索候选 query='{}'，命中 {} 条", candidateQuery, docs.size());
                // 首个候选词命中即返回，不再尝试后续候选词
                if (hasEffectiveHit(docs)) {
                    selectedDocs = docs;
                    success = true;
                    return selectedDocs;
                }
            }
            success = true;
            return selectedDocs;
        } finally {
            // 无论是否命中，都记录检索指标（topK、命中数、耗时等）
            recordRagRetrieval(
                queryContext,
                selectedDocs.size(),
                success,
                startNanos
            );
            // 记录完整的检索日志（含候选词、检索参数、文档列表），用于可观测性
            recordRetrievalLog(
                traceContext,
                queryContext,
                knowledgeBaseIds,
                selectedDocs,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)
            );
        }
    }

    /**
     * 记录检索日志：将本次 RAG 检索的完整上下文（追踪信息、候选查询词、
     * 检索参数、命中数量和耗时）写入检索日志表，供后续可观测性分析使用。
     */
    private void recordRetrievalLog(
            RagTraceContext traceContext,
            QueryContext queryContext,
            List<Long> knowledgeBaseIds,
            List<Document> docs,
            long latencyMs) {
        retrievalLogService.recordRetrieval(
            new RagRetrievalLogRequest(
                traceContext.traceId(),
                traceContext.sessionId(),
                queryContext.originalQuestion(),
                queryContext.rewrittenQuestion(),
                queryContext.candidateQueries(),
                knowledgeBaseIds,
                queryContext.searchParams().topK(),
                queryContext.searchParams().minScore(),
                docs.size(),
                latencyMs
            ),
            docs
        );
    }

    /**
     * 根据问题长度动态解析检索参数（topK、minScore）
     * 短问题使用宽松阈值提高召回率，长问题使用严格阈值保证精度。
     *
     * @param question 用户问题
     * @return 与问题长度匹配的检索参数
     */
    private SearchParams resolveSearchParams(String question) {
        // 去除空白字符后计算实际问题长度
        int compactLength = question.replaceAll("\\s+", "").length();
        // 极短问题（≤shortQueryLength 字符）：放宽 minScore，提高召回
        if (compactLength <= shortQueryLength) {
            return new SearchParams(topkShort, minScoreShort);
        }
        // 中等长度问题：适中 topK
        if (compactLength <= 12) {
            return new SearchParams(topkMedium, minScoreDefault);
        }
        // 长问题：最大 topK，严格 minScore
        return new SearchParams(topkLong, minScoreDefault);
    }

    /**
     * 问题改写：结合历史对话上下文，通过 LLM 将用户原始问题改写为更适合
     * 向量检索的关键词/短语形式，提升检索召回率。改写失败时降级使用原问题。
     *
     * @param question 归一化后的用户问题
     * @param history 历史对话消息列表
     * @return 改写后的查询词，改写失败或未启用时返回原问题
     */
    private String rewriteQuestion(String question, List<Message> history) {
        long startNanos = System.nanoTime();
        // 未启用改写或问题为空时直接返回原问题
        if (!rewriteEnabled || question.isBlank()) {
            recordRagRewrite(true, startNanos);
            return question;
        }
        try {
            Map<String, Object> variables = new HashMap<>();
            variables.put("question", question);
            variables.put("history", formatHistoryForRewrite(history));
            // 渲染 rewrite 提示词模板，调用 LLM 生成改写结果
            String rewritePrompt = rewritePromptTemplate.render(variables);
            String rewritten = getChatClient().prompt()
                .user(rewritePrompt)
                .call()
                .content();
            // LLM 返回空结果时降级使用原问题
            if (rewritten == null || rewritten.isBlank()) {
                return question;
            }
            String normalized = rewritten.trim();
            log.info("Query rewrite: origin='{}', rewritten='{}', historySize={}", question, normalized, history.size());
            recordRagRewrite(true, startNanos);
            return normalized;
        } catch (Exception e) {
            recordRagRewrite(false, startNanos);
            log.warn("Query rewrite 失败，使用原问题继续检索: {}", e.getMessage());
            return question;
        }
    }

    /**
     * 记录问题改写指标：将改写成功/失败和耗时上报到 RAG 指标采集器
     */
    private void recordRagRewrite(boolean success, long startNanos) {
        if (ragMetricsRecorder != null) {
            ragMetricsRecorder.recordRewrite(rewriteEnabled, success, startNanos);
        }
    }

    /**
     * 记录向量检索指标：将检索参数、命中数和耗时上报到 RAG 指标采集器
     */
    private void recordRagRetrieval(
            QueryContext queryContext,
            int hitCount,
            boolean success,
            long startNanos) {
        if (ragMetricsRecorder != null) {
            ragMetricsRecorder.recordRetrieval(
                rewriteEnabled,
                queryContext.searchParams().topK(),
                hitCount,
                success,
                startNanos
            );
        }
    }

    /**
     * 记录 RAG 回答指标：按流式/非流式维度上报回答耗时与成功状态
     */
    private void recordRagAnswer(boolean streaming, boolean success, long startNanos) {
        if (ragMetricsRecorder != null) {
            ragMetricsRecorder.recordAnswer(streaming, success, startNanos);
        }
    }

    /**
     * 将历史消息格式化为重写 prompt 中的文本摘要。
     * 每条消息格式：用户: xxx / 助手: xxx
     */
    private String formatHistoryForRewrite(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Message msg : history) {
            if (msg instanceof UserMessage) {
                sb.append("用户: ").append(msg.getText()).append("\n");
            } else if (msg instanceof AssistantMessage) {
                // 截断过长的助手回复，避免 rewrite prompt 过长
                String text = msg.getText();
                if (text.length() > MAX_REWRITE_HISTORY_CHAR) {
                    text = text.substring(0, MAX_REWRITE_HISTORY_CHAR) + "...";
                }
                sb.append("助手: ").append(text).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * 判断文档列表是否有有效命中（非空且非空列表）
     */
    private boolean hasEffectiveHit(List<Document> docs) {
        return docs != null && !docs.isEmpty();
    }

    /**
     * 归一化 AI 回答：截断空白，识别并替换无信息类的拒答文本为统一模板
     */
    private String normalizeAnswer(String answer) {
        if (answer == null || answer.isBlank()) {
            return NO_RESULT_RESPONSE;
        }
        String normalized = answer.trim();
        if (isNoResultLike(normalized)) {
            return NO_RESULT_RESPONSE;
        }
        return normalized;
    }

    /**
     * 判断文本是否属于"知识库无相关信息"类拒答复语
     */
    private boolean isNoResultLike(String text) {
        return text.contains("没有找到相关信息")
            || text.contains("未检索到相关信息")
            || text.contains("信息不足")
            || text.contains("超出知识库范围")
            || text.contains("无法根据提供内容回答");
    }

    /**
     * 先观察前一小段流式内容，快速识别“无信息”模板。
     * - 命中无信息：立即输出固定模板并结束，防止长篇拒答
     * - 非无信息：尽快释放缓冲并继续实时透传
     */
    private Flux<String> normalizeStreamOutput(Flux<String> rawFlux) {
        return Flux.create(sink -> {
            // 探测阶段暂存前几个 chunk 的缓冲区，用于拒答关键词检测
            StringBuilder probeBuffer = new StringBuilder();
            // 是否已进入透传模式（直接转发，不做缓冲和检测）
            AtomicBoolean passthrough = new AtomicBoolean(false);
            // 是否已结束（防止拒答命中后重复触发 complete）
            AtomicBoolean completed = new AtomicBoolean(false);
            // 持有原始流的订阅引用，用于命中拒答时主动取消订阅、中断 LLM 输出
            final Disposable[] disposableRef = new Disposable[1];

            disposableRef[0] = rawFlux.subscribe(
                chunk -> {
                    // 已结束或下游已取消，忽略后续到达的 chunk
                    if (completed.get() || sink.isCancelled()) {
                        return;
                    }
                    // 透传模式：直接转发 chunk，零缓冲零延迟
                    if (passthrough.get()) {
                        sink.next(chunk);
                        return;
                    }

                    // 探测阶段：累积 chunk 到缓冲区
                    probeBuffer.append(chunk);
                    String probeText = probeBuffer.toString();
                    // 检测到拒答关键词，立即中止原始流并用简短模板替代
                    if (isNoResultLike(probeText)) {
                        completed.set(true);
                        sink.next(NO_RESULT_RESPONSE);
                        sink.complete();
                        // 取消原始订阅，避免 LLM 继续生成长篇无用拒答文本
                        if (disposableRef[0] != null) {
                            disposableRef[0].dispose();
                        }
                        return;
                    }

                    // 缓冲区达到探测窗口上限且未命中拒答，认为不是拒答内容，
                    // 释放缓冲进入透传模式，后续所有 chunk 直接转发
                    if (probeBuffer.length() >= STREAM_PROBE_CHARS) {
                        passthrough.set(true);
                        sink.next(probeText);
                        probeBuffer.setLength(0);
                    }
                },
                // 原始流出错时，直接将错误传播给下游
                sink::error,
                // 原始流正常结束的回调
                () -> {
                    if (completed.get() || sink.isCancelled()) {
                        return;
                    }
                    // 仍在探测阶段就结束了（总内容不足 120 字符），
                    // 对剩余内容做归一化处理后输出
                    if (!passthrough.get()) {
                        sink.next(normalizeAnswer(probeBuffer.toString()));
                    }
                    sink.complete();
                }
            );

            // 下游取消订阅时（如前端关闭页面），同步取消原始流订阅，释放资源
            sink.onCancel(() -> {
                if (disposableRef[0] != null) {
                    disposableRef[0].dispose();
                }
            });
        });
    }

    /**
     * 向量检索参数：topK 返回数量，minScore 最低相似度阈值
     */
    private record SearchParams(int topK, double minScore) {
    }

    /**
     * 向量检索查询上下文：包含原始问题、改写后问题、候选查询词列表和检索参数
     */
    private record QueryContext(
        String originalQuestion,
        String rewrittenQuestion,
        List<String> candidateQueries,
        SearchParams searchParams
    ) {
    }

    /**
     * RAG 追踪上下文：关联追踪 ID 与会话 ID，用于检索日志和可观测性
     */
    private record RagTraceContext(String traceId, Long sessionId) {
    }

    /**
     * RAG 非流式回答结果：包含 AI 回答文本和追踪 ID
     */
    private record RagAnswerResult(String answer, String traceId) {
    }

    /**
     * RAG 流式回答结果：包含追踪 ID 和 SSE 流式响应 Flux
     */
    public record RagStreamResult(String traceId, Flux<String> content) {
    }
}
