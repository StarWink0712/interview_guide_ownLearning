/**
 * RAG 检索日志服务
 *
 * 负责记录和查询每次 RAG（检索增强生成）向量检索的完整过程日志。
 * 与子系统 1（AiInvocationLogService 记录"调用了哪个模型"）不同，
 * 本服务记录的是“RAG 检索时发了什么查询、改写成了什么、
 * 检出了哪些文档块”。
 *
 * 核心功能：
 * <ul>
 *   <li>记录 RAG 检索日志：将一次 RAG 检索的查询、改写、
 *       命中结果完整记录到数据库</li>
 *   <li>保存命中文档详情：将每次检出的文档块按 rank 排序存入子表</li>
 *   <li>按 traceId 查询链路：通过 traceId 关联 AI 调用日志，
 *       形成完整的 RAG 调用全链路</li>
 *   <li>查询最近记录：查看最近的 20 条 RAG 检索记录</li>
 * </ul>
 *
 * 数据模型（一对多）：
 * <pre>
 *   rag_retrieval_logs（1 条主记录）
 *     ├── traceId, sessionId, question, rewrittenQuestion, topK, hitCount, latencyMs
 *     └── rag_retrieved_chunk_logs（N 条命中文档块）
 *           ├── knowledgeBaseId, rank, score, chunkTextPreview
 *           └── metadataJson（文档元数据）
 * </pre>
 *
 * 设计要点：
 * <ul>
 *   <li>不存完整文档内容：只存储 chunkTextPreview（前 500 字符），
 *       避免数据库体积过度膨胀</li>
 *   <li>JSON 存储结构化数据：将 knowledgeBaseIds、candidateQueries
 *       等数组序列化为 JSON
 *       存入 TEXT 字段，避免引入额外的关联表复杂度</li>
 *   <li>降级写入：写入失败时返回 Optional.empty()，不抛异常</li>
 * </ul>
 *
 * 联动关系：
 * <ul>
 *   <li>数据来源：被 KnowledgeBaseQueryService 调用 recordRetrieval()，
 *       在每次 RAG 向量检索完成后写入日志</li>
 *   <li>数据查询：被 AiObservabilityController 调用，
 *       通过 REST API 暴露 RAG Trace 详情给前端</li>
 *   <li>配合 AiInvocationLogService：两者通过 traceId 关联，
 *       可在前端完整追溯一次 RAG 查询的"检索阶段日志"和"LLM 调用日志"</li>
 *   <li>依赖 ObjectMapper：用于将数组、Map 等结构化数据
 *       序列化为 JSON 字符串</li>
 * </ul>
 */
package interview.guide.modules.aiobservability.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.aiobservability.model.RagRetrievalLogEntity;
import interview.guide.modules.aiobservability.model.RagRetrievalLogRequest;
import interview.guide.modules.aiobservability.model.RagRetrievedChunkDTO;
import interview.guide.modules.aiobservability.model.RagRetrievedChunkLogEntity;
import interview.guide.modules.aiobservability.model.RagTraceDTO;
import interview.guide.modules.aiobservability.repository.RagRetrievalLogRepository;
import interview.guide.modules.aiobservability.repository.RagRetrievedChunkLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagRetrievalLogService {

  /** 命中文档块内容预览的最大字符数，避免存储完整的文档原文 */
  private static final int MAX_CHUNK_PREVIEW_LENGTH = 500;

  /** RAG 检索主日志的 JPA 仓库 */
  private final RagRetrievalLogRepository retrievalLogRepository;

  /** 命中文档块日志的 JPA 仓库 */
  private final RagRetrievedChunkLogRepository chunkLogRepository;

  /** Jackson JSON 序列化器，用于将数组/Map 等结构化数据转为 JSON 字符串 */
  private final ObjectMapper objectMapper;

  /**
   * 生成全局唯一的追踪 ID
   *
   * <p>调用入口：{@code KnowledgeBaseQueryService}
   * 在开始一次普通查询或流式查询时调用，
   * 生成的 ID 会继续传给检索日志和 AI 调用日志，用于串联完整链路。
   * 本方法只生成 UUID，不访问数据库。
   *
   * 如果调用方没有传入 traceId，
   * 则自动生成一个新的 UUID 作为 RAG 检索的追踪标识。
   *
   * @return UUID 格式的 36 位字符串
   */
  public String createTraceId() {
    return UUID.randomUUID().toString();
  }

  /**
   * 记录一次 RAG 检索的完整日志
   *
   * <p>调用入口：{@code KnowledgeBaseQueryService} 完成向量检索后调用，
   * 作为可观测性旁路记录检索参数和结果，不应影响 RAG 查询主流程。
   *
   * 将一次 RAG 向量检索的查询信息、命中结果和命中文档块
   * 全部持久化到数据库。写入失败时降级为警告日志并返回
   * Optional.empty()，不影响 RAG 检索业务主流程。
   *
   * 执行流程：
   * <ol>
   *   <li>将 RagRetrievalLogRequest 中的查询信息组装为 RagRetrievalLogEntity</li>
   *   <li>将 retrievedDocs 列表中的每个 Document 转换为 RagRetrievedChunkLogEntity，
   *       包含文档预览、得分、排序位置和元数据</li>
   *   <li>先保存主日志记录，再批量保存命中文档块记录</li>
   *   <li>组装 RagTraceDTO 返回</li>
   * </ol>
   *
   * <p>主要调用：使用 {@link #toJson(Object, String)} 序列化结构化字段，
   * 调用 {@code retrievalLogRepository.save(...)} 保存主记录，调用
   * {@link #buildChunkLogs(Long, List)} 构造命中块并批量保存，最后由
   * {@link #toTraceDTO(RagRetrievalLogEntity, List)} 组装返回值。
   * 任一步失败都会降级为 {@link Optional#empty()}。
   *
   * @param request       RAG 检索日志请求体，包含查询参数和命中统计信息
   * @param retrievedDocs 实际从向量数据库检出的文档列表
   *                      （Spring AI 的 Document 对象）
   * @return 包含 RAG Trace 完整信息的 Optional；写入失败时返回 Optional.empty()
   */
  public Optional<RagTraceDTO> recordRetrieval(
      RagRetrievalLogRequest request,
      List<Document> retrievedDocs
  ) {
    try {
      // 构建检索主日志实体
      RagRetrievalLogEntity logEntity = RagRetrievalLogEntity.builder()
          .traceId(request.traceId())
          .sessionId(request.sessionId())
          .question(request.question())
          .rewrittenQuestion(request.rewrittenQuestion())
          // 候选查询语句列表序列化为 JSON，方便在 UI 上展示多条改写结果
          .candidateQueriesJson(toJson(request.candidateQueries(), "[]"))
          // 关联的知识库 ID 列表序列化为 JSON
          .knowledgeBaseIdsJson(toJson(request.knowledgeBaseIds(), "[]"))
          .topK(request.topK())
          .minScore(request.minScore())
          .hitCount(request.hitCount())
          .latencyMs(request.latencyMs())
          .build();
      RagRetrievalLogEntity saved = retrievalLogRepository.save(logEntity);

      // 将每个命中文档转换为子表实体并批量保存
      List<RagRetrievedChunkLogEntity> chunks = buildChunkLogs(saved.getId(), retrievedDocs);
      if (!chunks.isEmpty()) {
        chunkLogRepository.saveAll(chunks);
      }
      return Optional.of(toTraceDTO(saved, chunks));
    } catch (Exception e) {
      log.warn("RAG 检索日志写入失败: traceId={}, question={}",
          request.traceId(), request.question(), e);
      return Optional.empty();
    }
  }

  /**
   * 根据 traceId 查询一次 RAG 检索的完整链路
   *
   * <p>调用入口：{@code AiObservabilityController.getRagTrace}。
   * 先通过 {@link RagRetrievalLogRepository#findByTraceId(String)} 查询主记录，
   * 再通过 {@link RagRetrievedChunkLogRepository#findByRetrievalLogIdOrderByRankAsc(Long)}
   * 查询按排名排序的命中块，最终交给
   * {@link #toTraceDTO(RagRetrievalLogEntity, List)}。
   *
   * 返回主记录 + 所有命中文档块的详情。同一个 traceId 可以关联到
   * AiInvocationLogService 中的 AI 调用日志，形成完整的 RAG 调用链路。
   *
   * @param traceId 链路追踪 ID
   * @return RAG Trace DTO，包含检索详情和命中文档块列表
   * @throws BusinessException 当 traceId 对应的记录不存在时抛出
   */
  @Transactional(readOnly = true)
  public RagTraceDTO getTrace(String traceId) {
    RagRetrievalLogEntity retrievalLog = retrievalLogRepository.findByTraceId(traceId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "RAG Trace 不存在"));
    List<RagRetrievedChunkLogEntity> chunks = chunkLogRepository
        .findByRetrievalLogIdOrderByRankAsc(retrievalLog.getId());
    return toTraceDTO(retrievalLog, chunks);
  }

  /**
   * 查询最近的 20 条 RAG 检索记录
   *
   * <p>调用入口：{@code AiObservabilityController.listRecentRagTraces}。
   * 主要调用 {@link RagRetrievalLogRepository#findTop20ByOrderByCreatedAtDesc()}
   * 查询主记录，
   * 并为每条记录补充命中文档块后组装 DTO。
   *
   * 用于在管理后台展示 RAG 检索活动概览，按创建时间降序排列。
   * 每条记录包含对应的命中文档块信息。
   *
   * @return 最近 20 条 RAG Trace DTO 列表
   */
  @Transactional(readOnly = true)
  public List<RagTraceDTO> listRecentTraces() {
    return retrievalLogRepository.findTop20ByOrderByCreatedAtDesc().stream()
        .map(logEntity -> toTraceDTO(
            logEntity,
            chunkLogRepository.findByRetrievalLogIdOrderByRankAsc(logEntity.getId())
        ))
        .toList();
  }

  /**
   * 构建命中文档块的日志实体列表
   *
   * <p>仅由 {@link #recordRetrieval(RagRetrievalLogRequest, List)} 调用。
   * 处理过程中会调用 {@link #extractKnowledgeBaseId(Document)}、
   * {@link #preview(String)} 和 {@link #toJson(Object, String)}，但不直接写数据库。
   *
   * 将 Spring AI 的 Document 列表转换为 RagRetrievedChunkLogEntity 列表。
   * 每个 Document 对应一个命中的文档块，
   * 包含内容预览、相似度得分、排序位置和元数据。
   *
   * 不存储完整的文档正文，只存储 chunkTextPreview（前 500 字符），
   * 在保证可读性的前提下控制数据库体积。
   *
   * @param retrievalLogId 所属的检索主日志 ID
   * @param docs           命中的文档列表（按相似度降序排列）
   * @return 命中文档块日志实体列表；docs 为 null 或空时返回空列表
   */
  private List<RagRetrievedChunkLogEntity> buildChunkLogs(
      Long retrievalLogId,
      List<Document> docs
  ) {
    if (docs == null || docs.isEmpty()) {
      return List.of();
    }
    List<RagRetrievedChunkLogEntity> chunks = new ArrayList<>();
    for (int i = 0; i < docs.size(); i++) {
      Document doc = docs.get(i);
      chunks.add(RagRetrievedChunkLogEntity.builder()
          .retrievalLogId(retrievalLogId)
          // 从文档元数据中提取知识库 ID（metadata 中的 kb_id 字段）
          .knowledgeBaseId(extractKnowledgeBaseId(doc))
          // 文档内容预览（截断到 500 字符），不存完整原文
          .chunkTextPreview(preview(doc.getText()))
          // 向量相似度得分（0~1 之间，越高越相似）
          .score(doc.getScore())
          // 按相似度从高到低的排序位置（1-based）
          .rank(i + 1)
          // 文档元数据序列化为 JSON（如来源文件、页码等）
          .metadataJson(toJson(doc.getMetadata(), "{}"))
          .build());
    }
    return chunks;
  }

  /**
   * 将主日志实体和命中文档块列表组装为 RagTraceDTO
   *
   * <p>由日志写入、traceId 详情查询和最近记录查询共同调用；
   * 内部通过 {@link #toChunkDTO(RagRetrievedChunkLogEntity)} 转换每个命中块，
   * 是本服务对外 DTO 的统一组装入口。
   *
   * DTO 是最终对外暴露的数据格式，禁止直接返回 Entity。
   *
   * @param retrievalLog 检索主日志实体
   * @param chunks       命中文档块日志实体列表
   * @return RAG Trace DTO
   */
  private RagTraceDTO toTraceDTO(
      RagRetrievalLogEntity retrievalLog,
      List<RagRetrievedChunkLogEntity> chunks
  ) {
    return new RagTraceDTO(
        retrievalLog.getId(),
        retrievalLog.getTraceId(),
        retrievalLog.getSessionId(),
        retrievalLog.getQuestion(),
        retrievalLog.getRewrittenQuestion(),
        retrievalLog.getCandidateQueriesJson(),
        retrievalLog.getKnowledgeBaseIdsJson(),
        retrievalLog.getTopK(),
        retrievalLog.getMinScore(),
        retrievalLog.getHitCount(),
        retrievalLog.getLatencyMs(),
        retrievalLog.getCreatedAt(),
        chunks.stream().map(this::toChunkDTO).toList()
    );
  }

  /**
   * 将命中文档块实体转换为 DTO
   *
   * <p>仅由 {@link #toTraceDTO(RagRetrievalLogEntity, List)} 在组装命中块列表时调用。
   *
   * @param entity 命中文档块日志实体
   * @return 命中文档块 DTO
   */
  private RagRetrievedChunkDTO toChunkDTO(RagRetrievedChunkLogEntity entity) {
    return new RagRetrievedChunkDTO(
        entity.getId(),
        entity.getKnowledgeBaseId(),
        entity.getChunkTextPreview(),
        entity.getScore(),
        entity.getRank(),
        entity.getMetadataJson()
    );
  }

  /**
   * 从 Document 的 metadata 中提取知识库 ID
   *
   * <p>由 {@link #buildChunkLogs(Long, List)} 调用，
   * 用于把向量检索结果关联回知识库。
   * 本方法兼容 metadata 中的数值和字符串形式，无法解析时返回 {@code null}。
   *
   * 文档在向量化入库时将 kb_id 写入 metadata。检索返回的 Document 对象
   * 携带该 metadata，通过 {@code doc.getMetadata().get("kb_id")} 提取。
   *
   * @param doc Spring AI 文档对象
   * @return 知识库 ID；解析失败或不存在时返回 null
   */
  private Long extractKnowledgeBaseId(Document doc) {
    Object value = doc.getMetadata().get("kb_id");
    if (value == null) {
      return null;
    }
    try {
      return value instanceof Long id ? id : Long.parseLong(value.toString());
    } catch (NumberFormatException e) {
      log.warn("RAG 命中文档 kb_id 无法解析: kbId={}", value);
      return null;
    }
  }

  /**
   * 将对象序列化为 JSON 字符串
   *
   * <p>由 {@link #recordRetrieval(RagRetrievalLogRequest, List)}
   * 序列化候选查询和知识库 ID，
   * 也由 {@link #buildChunkLogs(Long, List)} 序列化文档 metadata。
   * 序列化失败时返回调用方提供的回退 JSON，避免日志功能中断主业务。
   *
   * 用于将 List/Map 等结构化数据转为 JSON 存入 TEXT 字段，
   * 避免引入额外的关联表。
   *
   * @param value    待序列化的对象
   * @param fallback 序列化失败时的备用值
   * @return JSON 字符串；序列化失败时返回 fallback
   */
  private String toJson(Object value, String fallback) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      log.warn("RAG 观测字段序列化失败: valueType={}",
          value == null ? "null" : value.getClass().getName(), e);
      return fallback;
    }
  }

  /**
   * 截取文本预览（前 N 字符）
   *
   * <p>仅由 {@link #buildChunkLogs(Long, List)} 调用，
   * 确保日志只保存有限长度的内容，
   * 避免把完整知识库文档复制到可观测性表中。
   *
   * @param text 原始文本，可能为 null
   * @return 截断后的预览文本；text 为 null 时返回空字符串
   */
  private String preview(String text) {
    if (text == null) {
      return "";
    }
    String compact = text.strip();
    if (compact.length() <= MAX_CHUNK_PREVIEW_LENGTH) {
      return compact;
    }
    return compact.substring(0, MAX_CHUNK_PREVIEW_LENGTH);
  }
}
