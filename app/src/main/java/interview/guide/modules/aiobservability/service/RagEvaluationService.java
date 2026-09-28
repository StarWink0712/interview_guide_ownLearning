/**
 * RAG 评测服务
 *
 * 这是 RAG（检索增强生成）检索质量的自动化测试引擎。它允许开发者：
 * <ol>
 *   <li>创建评测集（Dataset）：一个名命名的测试集合</li>
 *   <li>添加评测用例（Case）：每个用例包含
 *       "问题 + 期望的关键词 + 关联的知识库 ID"</li>
 *   <li>执行评测（Run）：系统自动对每个用例执行向量检索，
 *       检验检出的文档是否命中期望关键词</li>
 *   <li>查看评测结果：报告每个用例的命中情况、耗时，
 *       以及整体平均命中率和平均耗时</li>
 * </ol>
 *
 * 评测原理：
 * <pre>
 *   评测用例：Q = "Spring 的 IoC 和 DI 有什么区别？"
 *            + expectedKeywords = ["控制反转", "依赖注入", "容器"]
 *            + knowledgeBaseIds = [1, 2]
 *
 *   评测执行：
 *     1. 在知识库 1 和 2 中对 Q 执行向量检索
 *     2. 将检出的所有文档块文本拼接为语料
 *     3. 检查每个期望关键词是否出现在语料中
 *     4. 记录匹配数、是否命中、耗时
 *
 *   评测结果：
 *     matchedCases / totalCases = 检索召回率
 *     avgLatencyMs = 平均检索延迟
 * </pre>
 *
 * 数据库表结构（4 张表）：
 * <pre>
 *   rag_eval_datasets（评测集）
 *     └── rag_eval_cases（评测用例）N 条
 *           └── rag_eval_runs（评测执行）N 条
 *               （每次执行产生一条运行记录）
 *                 └── rag_eval_run_case_results（每用例的执行结果）N 条
 * </pre>
 *
 * 设计要点：
 * <ul>
 *   <li>运行时同步执行：runDataset() 在当前线程同步执行所有用例的检索，
 *       不涉及异步。适用于用例较少（几十到几百个）的场景</li>
 *   <li>关键词匹配基于文本包含：使用 String.contains()
 *       进行大小写不敏感匹配，
 *       简单有效，不依赖额外的 LLM 判断</li>
 *   <li>失败不中断：单个用例执行失败时捕获异常并写入错误信息，
 *       继续执行后续用例</li>
 *   <li>JSON 字段存储：knowledgeBaseIds、expectedKeywords、retrievedPreview 等
 *       结构化数据以 JSON 字符串形式存入 TEXT 字段</li>
 * </ul>
 *
 * 联动关系：
 * <ul>
 *   <li>依赖 KnowledgeBaseVectorService：调用 similaritySearch()
 *       执行实际的向量检索</li>
 *   <li>依赖 KnowledgeBaseQueryProperties：读取 topK、minScore
 *       等检索参数的默认值</li>
 *   <li>被 AiObservabilityController 调用：
 *       通过 REST API 暴露创建评测集、添加用例、
 *       执行评测、查看结果等操作</li>
 * </ul>
 */
package interview.guide.modules.aiobservability.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.aiobservability.model.AddRagEvalCaseRequest;
import interview.guide.modules.aiobservability.model.CreateRagEvalDatasetRequest;
import interview.guide.modules.aiobservability.model.RagEvalCaseDTO;
import interview.guide.modules.aiobservability.model.RagEvalCaseEntity;
import interview.guide.modules.aiobservability.model.RagEvalDatasetDTO;
import interview.guide.modules.aiobservability.model.RagEvalDatasetEntity;
import interview.guide.modules.aiobservability.model.RagEvalRunCaseResultDTO;
import interview.guide.modules.aiobservability.model.RagEvalRunCaseResultEntity;
import interview.guide.modules.aiobservability.model.RagEvalRunDTO;
import interview.guide.modules.aiobservability.model.RagEvalRunEntity;
import interview.guide.modules.aiobservability.model.RagEvalRunStatus;
import interview.guide.modules.aiobservability.model.RunRagEvalRequest;
import interview.guide.modules.aiobservability.repository.RagEvalCaseRepository;
import interview.guide.modules.aiobservability.repository.RagEvalDatasetRepository;
import interview.guide.modules.aiobservability.repository.RagEvalRunCaseResultRepository;
import interview.guide.modules.aiobservability.repository.RagEvalRunRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryProperties;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagEvaluationService {

  /** 检索结果预览文本的最大字符数 */
  private static final int MAX_RESULT_PREVIEW_LENGTH = 220;

  /** 评测集 JPA 仓库 */
  private final RagEvalDatasetRepository datasetRepository;

  /** 评测用例 JPA 仓库 */
  private final RagEvalCaseRepository caseRepository;

  /** 评测运行记录 JPA 仓库 */
  private final RagEvalRunRepository runRepository;

  /** 评测运行各用例结果 JPA 仓库 */
  private final RagEvalRunCaseResultRepository resultRepository;

  /** 知识库向量检索服务，执行实际的 pgvector 相似度搜索 */
  private final KnowledgeBaseVectorService vectorService;

  /** 知识库查询配置属性，用于读取 topK、minScore 等参数的默认值 */
  private final KnowledgeBaseQueryProperties queryProperties;

  /** Jackson JSON 序列化器 */
  private final ObjectMapper objectMapper;

  // ==================== 评测集管理 ====================

  /**
   * 创建新的 RAG 评测集
   *
   * <p>调用入口：{@code AiObservabilityController.createDataset}。
   * 该方法规范化名称后调用 {@code datasetRepository.save(...)}，
   * 再通过 {@link #toDatasetDTO(RagEvalDatasetEntity)} 返回 DTO。
   *
   * 评测集是一个命名的测试集合，用于组织一组评测用例。
   *
   * @param request 创建请求，包含名称和描述
   * @return 创建后的评测集 DTO
   */
  @Transactional
  public RagEvalDatasetDTO createDataset(CreateRagEvalDatasetRequest request) {
    RagEvalDatasetEntity entity = RagEvalDatasetEntity.builder()
        .name(request.name().trim())
        .description(request.description())
        .build();
    return toDatasetDTO(datasetRepository.save(entity));
  }

  /**
   * 查询所有评测集列表
   *
   * <p>调用入口：{@code AiObservabilityController.listDatasets}。
   * 主要调用 {@code datasetRepository.findAll()}，在内存中按更新时间倒序排列，
   * 再通过 {@link #toDatasetDTO(RagEvalDatasetEntity)} 转换结果。
   *
   * @return 按更新时间降序排列的评测集 DTO 列表
   */
  @Transactional(readOnly = true)
  public List<RagEvalDatasetDTO> listDatasets() {
    return datasetRepository.findAll().stream()
        .sorted(Comparator.comparing(RagEvalDatasetEntity::getUpdatedAt).reversed())
        .map(this::toDatasetDTO)
        .toList();
  }

  /**
   * 查询评测集详情
   *
   * <p>调用入口：{@code AiObservabilityController.getDataset}。
   * 主要调用 {@code datasetRepository.findById(...)}；
   * 记录不存在时转换为业务异常。
   *
   * @param datasetId 评测集 ID
   * @return 评测集 DTO
   * @throws BusinessException 评测集不存在时抛出
   */
  @Transactional(readOnly = true)
  public RagEvalDatasetDTO getDataset(Long datasetId) {
    RagEvalDatasetEntity entity = datasetRepository.findById(datasetId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "RAG 评测集不存在"));
    return toDatasetDTO(entity);
  }

  // ==================== 评测用例管理 ====================

  /**
   * 向评测集添加一个评测用例
   *
   * <p>调用入口：{@code AiObservabilityController.addCase}。
   * 先调用 {@link #ensureDatasetExists(Long)} 校验归属评测集，随后清洗关键词、
   * 序列化列表并调用 {@code caseRepository.save(...)} 持久化用例。
   *
   * 每个用例包含：
   * <ul>
   *   <li>question：测试问题，RAG 系统将对其执行向量检索</li>
   *   <li>knowledgeBaseIds：限定在哪些知识库中检索</li>
   *   <li>expectedKeywords：期望出现在检索结果文档中的关键词列表，
   *       用于判断检索是否命中正确答案</li>
   * </ul>
   *
   * knowledgeBaseIds 和 expectedKeywords 会被序列化为 JSON 字符串存入数据库。
   *
   * @param datasetId 所属评测集 ID
   * @param request   添加用例请求
   * @return 创建后的评测用例 DTO
   * @throws BusinessException 评测集不存在时抛出
   */
  @Transactional
  public RagEvalCaseDTO addCase(Long datasetId, AddRagEvalCaseRequest request) {
    ensureDatasetExists(datasetId);
    RagEvalCaseEntity entity = RagEvalCaseEntity.builder()
        .datasetId(datasetId)
        .question(request.question().trim())
        // 知识库 ID 列表序列化为 JSON
        .knowledgeBaseIdsJson(toJsonOrThrow(request.knowledgeBaseIds()))
        // 期望关键词列表清洗后序列化为 JSON（去重、去除空白）
        .expectedKeywordsJson(toJsonOrThrow(cleanKeywords(request.expectedKeywords())))
        .build();
    return toCaseDTO(caseRepository.save(entity));
  }

  // ==================== 评测执行 ====================

  /**
   * 执行一次评测运行
   *
   * <p>调用入口：{@code AiObservabilityController.runDataset}。
   * 本方法在 HTTP 请求线程中同步编排整个评测：
   * 读取参数和用例、创建运行记录、
   * 循环调用 {@link #evaluateCase(Long, RagEvalCaseEntity, int, double)}，
   * 汇总后再调用 {@link #getRun(Long)} 返回完整结果。
   *
   * 对评测集下的所有用例逐个执行向量检索，
   * 并使用关键词匹配判断检索质量。
   * 执行过程是同步的，所有用例执行完后更新运行状态为 COMPLETED。
   *
   * 执行流程：
   * <ol>
   *   <li>创建 RagEvalRunEntity，初始化状态为 RUNNING</li>
   *   <li>遍历评测集的每个用例：
   *     <ul>
   *       <li>调用 KnowledgeBaseVectorService.similaritySearch() 执行向量检索</li>
   *       <li>计算检索耗时</li>
   *       <li>将检出的文档文本拼接，用 String.contains() 匹配期望关键词</li>
   *       <li>记录命中文档预览、命中数、匹配关键词数</li>
   *       <li>单个用例执行失败时，记录错误信息并继续执行后续用例</li>
   *     </ul>
   *   </li>
   *   <li>更新运行状态为 COMPLETED，记录总匹配数和平均耗时</li>
   * </ol>
   *
   * 评测指标：
   * <ul>
   *   <li>matchedCases / totalCases：关键词命中率，反映检索召回质量</li>
   *   <li>avgLatencyMs：平均检索延迟，反映检索性能</li>
   * </ul>
   *
   * @param datasetId 评测集 ID
   * @param request   评测参数（可指定 topK 和 minScore，为空则使用默认配置）
   * @return 评测运行结果 DTO，包含每个用例的详细结果
   * @throws BusinessException 评测集不存在时抛出
   */
  public RagEvalRunDTO runDataset(Long datasetId, RunRagEvalRequest request) {
    ensureDatasetExists(datasetId);
    // 解析检索引擎参数：优先使用请求中指定的，否则使用系统默认配置
    int topK = resolveTopK(request);
    double minScore = resolveMinScore(request);
    List<RagEvalCaseEntity> cases = caseRepository.findByDatasetIdOrderByIdAsc(datasetId);

    // 初始化评测运行记录
    RagEvalRunEntity run = RagEvalRunEntity.builder()
        .datasetId(datasetId)
        .status(RagEvalRunStatus.RUNNING)
        // 将检索引擎参数序列化为 JSON 存档
        .strategyJson(toJsonOrThrow(Map.of("topK", topK, "minScore", minScore)))
        .totalCases(cases.size())
        .matchedCases(0)
        .avgLatencyMs(0L)
        .build();
    run = runRepository.save(run);

    // 逐个执行评测用例
    long totalLatency = 0L;
    int matchedCases = 0;
    for (RagEvalCaseEntity evalCase : cases) {
      RagEvalRunCaseResultEntity result = evaluateCase(run.getId(), evalCase, topK, minScore);
      totalLatency += result.getLatencyMs() == null ? 0L : result.getLatencyMs();
      if (Boolean.TRUE.equals(result.getExpectedKeywordHit())) {
        matchedCases++;
      }
    }

    // 更新运行状态为完成
    run.setStatus(RagEvalRunStatus.COMPLETED);
    run.setMatchedCases(matchedCases);
    run.setAvgLatencyMs(cases.isEmpty() ? 0L : totalLatency / cases.size());
    run.setCompletedAt(LocalDateTime.now());
    run = runRepository.save(run);
    return getRun(run.getId());
  }

  /**
   * 查询一次评测运行的完整结果
   *
   * <p>调用入口：{@code AiObservabilityController.getRun}，也由
   * {@link #runDataset(Long, RunRagEvalRequest)} 在评测结束后调用。
   * 该方法分别查询运行主记录和逐用例结果，
   * 再通过 {@link #toRunDTO(RagEvalRunEntity, List)}
   * 组装完整 DTO。
   *
   * @param runId 评测运行 ID
   * @return 评测运行 DTO，包含每个用例的执行结果
   * @throws BusinessException 评测运行不存在时抛出
   */
  @Transactional(readOnly = true)
  public RagEvalRunDTO getRun(Long runId) {
    RagEvalRunEntity run = runRepository.findById(runId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "RAG 评测运行不存在"));
    List<RagEvalRunCaseResultDTO> results = resultRepository.findByRunIdOrderByIdAsc(runId)
        .stream()
        .map(this::toRunCaseResultDTO)
        .toList();
    return toRunDTO(run, results);
  }

  // ==================== 单个用例评估 ====================

  /**
   * 执行单个评测用例的评估
   *
   * <p>仅由 {@link #runDataset(Long, RunRagEvalRequest)} 在遍历评测用例时调用。
   * 主要调用 {@code KnowledgeBaseVectorService.similaritySearch(...)}
   * 执行外部向量检索，再调用关键词统计和预览构建方法，
   * 最后把成功或失败结果写入结果仓库。
   *
   * 核心评估逻辑：
   * <ol>
   *   <li>调用向量检索服务，在指定知识库中检索与问题相似的文档</li>
   *   <li>将检出的文档文本拼接为语料</li>
   *   <li>检查每个期望关键词是否出现在语料中（大小写不敏感）</li>
   *   <li>记录匹配数、是否匹配、检索数量、耗时</li>
   * </ol>
   *
   * 异常处理：单个用例执行失败时不会中断整体评测流程。
   * 错误信息会被记录在结果中，方便后续排查。
   *
   * @param runId     所属评测运行 ID
   * @param evalCase  评测用例
   * @param topK      检索返回的最大文档数
   * @param minScore  检索相似度最低阈值
   * @return 该用例的评估结果实体
   */
  private RagEvalRunCaseResultEntity evaluateCase(
      Long runId,
      RagEvalCaseEntity evalCase,
      int topK,
      double minScore
  ) {
    long startNanos = System.nanoTime();
    List<String> expectedKeywords = parseStringList(evalCase.getExpectedKeywordsJson());
    try {
      // 从 JSON 中解析知识库 ID 列表
      List<Long> knowledgeBaseIds = parseLongList(evalCase.getKnowledgeBaseIdsJson());
      // 执行向量检索
      List<Document> docs = vectorService.similaritySearch(
          evalCase.getQuestion(),
          knowledgeBaseIds,
          topK,
          minScore
      );
      long latencyMs = elapsedMs(startNanos);
      // 检查关键词是否命中
      int matchedKeywordCount = countMatchedKeywords(expectedKeywords, docs);
      boolean keywordHit = expectedKeywords.isEmpty()
          ? !docs.isEmpty()
          : matchedKeywordCount > 0;

      RagEvalRunCaseResultEntity result = RagEvalRunCaseResultEntity.builder()
          .runId(runId)
          .caseId(evalCase.getId())
          .question(evalCase.getQuestion())
          .expectedKeywordsJson(evalCase.getExpectedKeywordsJson())
          .matchedKeywordCount(matchedKeywordCount)
          .expectedKeywordHit(keywordHit)
          .hitCount(docs.size())
          .latencyMs(latencyMs)
          // 检出的文档预览列表（JSON）
          .retrievedPreviewJson(toJsonOrThrow(buildPreviews(docs)))
          .build();
      return resultRepository.save(result);
    } catch (Exception e) {
      // 单个用例失败不中断整体流程，记录错误继续
      log.warn("RAG 评测用例执行失败: caseId={}, question={}",
          evalCase.getId(), evalCase.getQuestion(), e);
      RagEvalRunCaseResultEntity result = RagEvalRunCaseResultEntity.builder()
          .runId(runId)
          .caseId(evalCase.getId())
          .question(evalCase.getQuestion())
          .expectedKeywordsJson(evalCase.getExpectedKeywordsJson())
          .matchedKeywordCount(0)
          .expectedKeywordHit(false)
          .hitCount(0)
          .latencyMs(elapsedMs(startNanos))
          .retrievedPreviewJson("[]")
          .errorMessage(e.getMessage())
          .build();
      return resultRepository.save(result);
    }
  }

  // ==================== 参数解析 ====================

  /**
   * 确认评测集存在
   *
   * <p>由 {@link #addCase(Long, AddRagEvalCaseRequest)} 和
   * {@link #runDataset(Long, RunRagEvalRequest)} 在执行写入或评测前调用。
   * 只进行存在性查询，不加载完整实体。
   *
   * @param datasetId 评测集 ID
   * @throws BusinessException 评测集不存在时抛出
   */
  private void ensureDatasetExists(Long datasetId) {
    if (!datasetRepository.existsById(datasetId)) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "RAG 评测集不存在");
    }
  }

  /**
   * 解析 topK 参数：优先使用请求中指定的，否则使用系统默认配置
   *
   * <p>仅由 {@link #runDataset(Long, RunRagEvalRequest)} 调用；
   * 请求值无效或缺失时读取 {@code KnowledgeBaseQueryProperties} 的默认值。
   *
   * @param request 评测请求体，可能为 null
   * @return topK 值（至少为 1）
   */
  private int resolveTopK(RunRagEvalRequest request) {
    if (request != null && request.topK() != null && request.topK() > 0) {
      return request.topK();
    }
    return queryProperties.getSearch().getTopkLong();
  }

  /**
   * 解析 minScore 参数：优先使用请求中指定的，否则使用系统默认配置
   *
   * <p>仅由 {@link #runDataset(Long, RunRagEvalRequest)} 调用；
   * 请求值无效或缺失时读取 {@code KnowledgeBaseQueryProperties} 的默认值。
   *
   * @param request 评测请求体，可能为 null
   * @return minScore 值（至少为 0）
   */
  private double resolveMinScore(RunRagEvalRequest request) {
    if (request != null && request.minScore() != null && request.minScore() >= 0) {
      return request.minScore();
    }
    return queryProperties.getSearch().getMinScoreDefault();
  }

  // ==================== 关键词匹配 ====================

  /**
   * 统计期望关键词在检索结果文档中的匹配数量
   *
   * <p>仅由 {@link #evaluateCase(Long, RagEvalCaseEntity, int, double)} 调用，
   * 作为离线评测的轻量级召回判断，不会再次调用 LLM 或数据库。
   *
   * 匹配策略：
   * <ul>
   *   <li>将所有检出的文档文本按换行符拼接成完整语料</li>
   *   <li>语料和关键词都转为小写，实现大小写不敏感匹配</li>
   *   <li>使用 String.contains() 判断关键词是否出现在语料中</li>
   *   <li>去重统计（同一关键词多次出现只计一次）</li>
   * </ul>
   *
   * 这种策略的优点是简单高效，
   * 缺点是无法理解语义（关键词近义词无法匹配）。
   * 适合作为快速的回归测试指标，高层语义评估可结合 LLM 做判断。
   *
   * @param expectedKeywords 期望关键词列表
   * @param docs             检出的文档列表
   * @return 匹配的关键词数量（去重后）
   */
  private int countMatchedKeywords(List<String> expectedKeywords, List<Document> docs) {
    if (expectedKeywords.isEmpty() || docs.isEmpty()) {
      return 0;
    }
    // 将所有文档文本拼成一个大语料
    String corpus = docs.stream()
        .map(Document::getText)
        .filter(text -> text != null && !text.isBlank())
        .collect(java.util.stream.Collectors.joining("\n"))
        .toLowerCase(Locale.ROOT);
    // 检查每个关键词是否出现在语料中（大小写不敏感）
    return (int) expectedKeywords.stream()
        .map(keyword -> keyword.toLowerCase(Locale.ROOT))
        .filter(corpus::contains)
        .distinct()
        .count();
  }

  // ==================== 数据转换辅助 ====================

  /**
   * 构建检索结果预览列表
   *
   * <p>由 {@link #evaluateCase(Long, RagEvalCaseEntity, int, double)} 在检索成功后调用。
   * 内部调用 {@link #extractKnowledgeBaseId(Document)} 和 {@link #preview(String)}，
   * 生成适合序列化入库的精简结果。
   *
   * 每个预览条目包含知识库 ID、相似度得分和文本预览。
   * 不存储完整文档文本，只存储前 MAX_RESULT_PREVIEW_LENGTH 字符的预览。
   *
   * @param docs 检出的文档列表
   * @return 预览数据列表（每个条目为 Map）
   */
  private List<Map<String, Object>> buildPreviews(List<Document> docs) {
    return docs.stream()
        .map(doc -> {
          Map<String, Object> preview = new LinkedHashMap<>();
          preview.put("knowledgeBaseId", extractKnowledgeBaseId(doc));
          preview.put("score", doc.getScore());
          preview.put("text", preview(doc.getText()));
          return preview;
        })
        .toList();
  }

  /**
   * 从 Document 的 metadata 中提取知识库 ID
   *
   * <p>仅由 {@link #buildPreviews(List)} 调用；兼容数值和字符串形式，
   * metadata 缺失或格式非法时返回 {@code null}。
   *
   * @param doc Spring AI 文档对象
   * @return 知识库 ID；解析失败时返回 null
   */
  private Long extractKnowledgeBaseId(Document doc) {
    Object value = doc.getMetadata().get("kb_id");
    if (value == null) {
      return null;
    }
    try {
      return value instanceof Long id ? id : Long.parseLong(value.toString());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * 清洗期望关键词列表
   *
   * <p>仅由 {@link #addCase(Long, AddRagEvalCaseRequest)} 在持久化用例前调用，
   * 避免空值、空白项和重复项进入评测数据。
   *
   * 去除 null、空白字符串，去除首尾空格，去重。
   *
   * @param keywords 原始关键词列表
   * @return 清洗后的关键词列表
   */
  private List<String> cleanKeywords(List<String> keywords) {
    if (keywords == null) {
      return List.of();
    }
    return keywords.stream()
        .filter(keyword -> keyword != null && !keyword.isBlank())
        .map(String::trim)
        .distinct()
        .toList();
  }

  /**
   * 将 JSON 字符串反序列化为字符串列表
   *
   * <p>由 {@link #evaluateCase(Long, RagEvalCaseEntity, int, double)}
   * 读取期望关键词时调用。解析失败会记录警告并返回空列表，
   * 使单个脏用例仍能形成可追踪的评测结果。
   *
   * @param json JSON 字符串
   * @return 字符串列表；解析失败时返回空列表
   */
  private List<String> parseStringList(String json) {
    try {
      return objectMapper.readValue(json, new TypeReference<List<String>>() {
      });
    } catch (Exception e) {
      log.warn("解析 RAG 评测关键词失败: json={}", json, e);
      return List.of();
    }
  }

  /**
   * 将 JSON 字符串反序列化为 Long 列表
   *
   * <p>由 {@link #evaluateCase(Long, RagEvalCaseEntity, int, double)}
   * 在调用向量检索前解析知识库范围；解析失败时返回空列表。
   *
   * @param json JSON 字符串
   * @return Long 列表；解析失败时返回空列表
   */
  private List<Long> parseLongList(String json) {
    try {
      return objectMapper.readValue(json, new TypeReference<List<Long>>() {
      });
    } catch (Exception e) {
      log.warn("解析 RAG 评测知识库列表失败: json={}", json, e);
      return List.of();
    }
  }

  /**
   * 将对象序列化为 JSON 字符串（序列化失败时抛异常）
   *
   * <p>由用例创建、评测运行创建和单用例结果记录共同调用。
   * 与观测日志的降级序列化不同，评测数据必须可复现，
   * 因此失败时抛出业务异常。
   *
   * @param value 待序列化的对象
   * @return JSON 字符串
   * @throws BusinessException 序列化失败时抛出 INTERNAL_ERROR
   */
  private String toJsonOrThrow(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "RAG 评测数据序列化失败");
    }
  }

  /**
   * 截取文本预览（前 N 字符）
   *
   * <p>仅由 {@link #buildPreviews(List)} 调用，
   * 用于控制单次评测结果的存储体积，
   * 不改变实际参与关键词匹配的完整文档内容。
   *
   * @param text 原始文本，可能为 null
   * @return 截断后的预览文本；text 为 null 时返回空字符串
   */
  private String preview(String text) {
    if (text == null) {
      return "";
    }
    String compact = text.strip();
    if (compact.length() <= MAX_RESULT_PREVIEW_LENGTH) {
      return compact;
    }
    return compact.substring(0, MAX_RESULT_PREVIEW_LENGTH);
  }

  /**
   * 计算从 startNanos 到现在经过的毫秒数
   *
   * <p>由 {@link #evaluateCase(Long, RagEvalCaseEntity, int, double)}
   * 在成功和失败路径共同调用，用于记录单用例检索耗时。
   *
   * @param startNanos 起始纳秒时间戳（System.nanoTime()）
   * @return 经过的毫秒数
   */
  private long elapsedMs(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000L;
  }

  // ==================== Entity → DTO 转换 ====================

  /**
   * 将评测集实体转换为 DTO
   *
   * <p>由评测集创建、列表查询和详情查询调用，
   * 是评测集 Entity → DTO 的统一出口。
   *
   * @param entity 评测集实体
   * @return 评测集 DTO
   */
  private RagEvalDatasetDTO toDatasetDTO(RagEvalDatasetEntity entity) {
    return new RagEvalDatasetDTO(
        entity.getId(),
        entity.getName(),
        entity.getDescription(),
        entity.getCreatedAt(),
        entity.getUpdatedAt()
    );
  }

  /**
   * 将评测用例实体转换为 DTO
   *
   * <p>由 {@link #addCase(Long, AddRagEvalCaseRequest)} 在用例持久化成功后调用。
   *
   * @param entity 评测用例实体
   * @return 评测用例 DTO
   */
  private RagEvalCaseDTO toCaseDTO(RagEvalCaseEntity entity) {
    return new RagEvalCaseDTO(
        entity.getId(),
        entity.getDatasetId(),
        entity.getQuestion(),
        entity.getKnowledgeBaseIdsJson(),
        entity.getExpectedKeywordsJson(),
        entity.getCreatedAt()
    );
  }

  /**
   * 将评测运行实体和用例结果列表组装为 DTO
   *
   * <p>仅由 {@link #getRun(Long)} 调用，
   * 把运行汇总和已转换的逐用例结果合并后返回前端。
   *
   * @param entity      评测运行实体
   * @param caseResults 各用例执行结果列表
   * @return 评测运行 DTO
   */
  private RagEvalRunDTO toRunDTO(
      RagEvalRunEntity entity,
      List<RagEvalRunCaseResultDTO> caseResults
  ) {
    return new RagEvalRunDTO(
        entity.getId(),
        entity.getDatasetId(),
        entity.getStatus(),
        entity.getStrategyJson(),
        entity.getTotalCases(),
        entity.getMatchedCases(),
        entity.getAvgLatencyMs(),
        entity.getCreatedAt(),
        entity.getCompletedAt(),
        caseResults
    );
  }

  /**
   * 将用例执行结果实体转换为 DTO
   *
   * <p>由 {@link #getRun(Long)} 在查询评测运行详情时逐条调用，
   * 避免将 JPA Entity 直接暴露到 Controller 层。
   *
   * @param entity 用例执行结果实体
   * @return 用例执行结果 DTO
   */
  private RagEvalRunCaseResultDTO toRunCaseResultDTO(RagEvalRunCaseResultEntity entity) {
    return new RagEvalRunCaseResultDTO(
        entity.getId(),
        entity.getRunId(),
        entity.getCaseId(),
        entity.getQuestion(),
        entity.getExpectedKeywordsJson(),
        entity.getMatchedKeywordCount(),
        entity.getExpectedKeywordHit(),
        entity.getHitCount(),
        entity.getLatencyMs(),
        entity.getRetrievedPreviewJson(),
        entity.getErrorMessage()
    );
  }
}
