/**
 * AI 可观测性控制层
 *
 * 提供 AI 调用日志查询、RAG 检索链路追踪和 RAG 评测管理的 RESTful API。
 * 是 aiobservability 模块的全部接口入口，负责将前端请求路由到对应的
 * Service 层。
 *
 * 接口分组：
 * <ul>
 *   <li>AI 调用日志（/api/ai-observability/invocations）—
 *       查询 LLM 调用的历史记录</li>
 *   <li>RAG Trace（/api/ai-observability/rag-traces）—— 查询 RAG 检索的完整链路</li>
 *   <li>RAG 评测（/api/ai-observability/rag-eval）——
 *       管理评测集、添加用例、执行评测</li>
 * </ul>
 *
 * 设计要点：
 * <ul>
 *   <li>Controller 层仅做路由和委托，不包含任何业务逻辑
 *       （符合项目分层规范）</li>
 *   <li>所有接口返回 Result&lt;T&gt; 统一响应包装，HTTP 状态码统一为 200</li>
 *   <li>查询类接口使用 @GetMapping，写入类接口使用 @PostMapping</li>
 *   <li>提交评测用例时使用 @Valid 校验请求体参数合法性</li>
 * </ul>
 *
 * 联动关系：
 * <ul>
 *   <li>依赖 AiInvocationLogService：查询 AI 调用日志</li>
 *   <li>依赖 RagRetrievalLogService：查询 RAG 检索链路</li>
 *   <li>依赖 RagEvaluationService：管理评测集、执行评测</li>
 *   <li>前端页面：AiTracePage（AI 调用日志 + RAG Trace）、
 *       RagEvalPage（RAG 评测）</li>
 * </ul>
 */
package interview.guide.modules.aiobservability;

import interview.guide.common.result.Result;
import interview.guide.modules.aiobservability.model.AddRagEvalCaseRequest;
import interview.guide.modules.aiobservability.model.AiInvocationLogDTO;
import interview.guide.modules.aiobservability.model.AiProviderAttemptLogDTO;
import interview.guide.modules.aiobservability.model.AiProviderInvocationLogDTO;
import interview.guide.modules.aiobservability.model.CreateRagEvalDatasetRequest;
import interview.guide.modules.aiobservability.model.RagEvalCaseDTO;
import interview.guide.modules.aiobservability.model.RagEvalDatasetDTO;
import interview.guide.modules.aiobservability.model.RagEvalRunDTO;
import interview.guide.modules.aiobservability.model.RagTraceDTO;
import interview.guide.modules.aiobservability.model.RunRagEvalRequest;
import interview.guide.modules.aiobservability.service.AiInvocationLogService;
import interview.guide.modules.aiobservability.service.AiProviderObservationLogService;
import interview.guide.modules.aiobservability.service.RagEvaluationService;
import interview.guide.modules.aiobservability.service.RagRetrievalLogService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@Tag(name = "AI 调用观测", description = "AI 调用日志、RAG Trace 与 RAG 评测")
public class AiObservabilityController {

  /** AI 调用日志服务，用于记录和查询每次 LLM 调用的详细信息 */
  private final AiInvocationLogService invocationLogService;

  /** AI_PROVIDER_OBSERVABILITY: 查询 Provider 级真实 LLM 调用和 HTTP attempt 日志 */
  private final AiProviderObservationLogService providerObservationLogService;

  /** RAG 检索日志服务，用于记录和查询每次 RAG 向量检索的完整链路 */
  private final RagRetrievalLogService retrievalLogService;

  /** RAG 评测服务，用于管理评测集、执行离线检索质量评估 */
  private final RagEvaluationService evaluationService;

  // ==================== AI 调用日志 ====================

  /**
   * 查询 AI 调用日志列表
   *
   * <p>调用入口：前端链路追踪页面通过
   * {@code GET /api/ai-observability/invocations} 调用。
   *
   * 两个使用场景：
   * <ul>
   *   <li>未传入 traceId：返回最近 50 条 AI 调用日志，用于概览页面</li>
   *   <li>传入 traceId：返回该 traceId 关联的所有 AI 调用日志，
   *       用于在链路追踪页面查看一次 HTTP 请求触发的所有 LLM 调用</li>
   * </ul>
   *
   * <p>主要调用：根据 {@code traceId} 是否为空，分别委托
   * {@link AiInvocationLogService#listByTraceId(String)} 或
   * {@link AiInvocationLogService#listRecentInvocations()} 查询。
   *
   * @param traceId 可选参数，链路追踪 ID。传入时按 traceId 精确查询，
   *                不传时查询最近记录
   * @return 包含 AI 调用日志列表的统一响应
   */
  @GetMapping("/api/ai-observability/invocations")
  public Result<List<AiInvocationLogDTO>> listInvocations(
      @RequestParam(value = "traceId", required = false) String traceId
  ) {
    if (traceId != null && !traceId.isBlank()) {
      // 按 traceId 精确查询
      return Result.success(invocationLogService.listByTraceId(traceId));
    }
    // 不传 traceId 时查询最近记录
    return Result.success(invocationLogService.listRecentInvocations());
  }

  /**
   * 查询单条 AI 调用日志详情
   *
   * <p>调用入口：前端点击某条调用记录后，通过
   * {@code GET /api/ai-observability/invocations/{id}} 调用。
   * 该方法仅委托 {@link AiInvocationLogService#getInvocation(Long)}
   * 完成查询和不存在校验。
   *
   * @param id 日志主键 ID
   * @return 包含 AI 调用日志详情的统一响应
   */
  @GetMapping("/api/ai-observability/invocations/{id}")
  public Result<AiInvocationLogDTO> getInvocation(@PathVariable Long id) {
    return Result.success(invocationLogService.getInvocation(id));
  }

  // ==================== Provider 级 AI 调用日志 ====================

  @GetMapping("/api/ai-observability/provider-invocations")
  public Result<List<AiProviderInvocationLogDTO>> listProviderInvocations(
      @RequestParam(value = "traceId", required = false) String traceId
  ) {
    if (traceId != null && !traceId.isBlank()) {
      return Result.success(providerObservationLogService.listByTraceId(traceId));
    }
    return Result.success(providerObservationLogService.listRecentInvocations());
  }

  @GetMapping("/api/ai-observability/provider-invocations/{invocationId}/attempts")
  public Result<List<AiProviderAttemptLogDTO>> listProviderAttempts(
      @PathVariable String invocationId
  ) {
    return Result.success(providerObservationLogService.listAttempts(invocationId));
  }

  // ==================== RAG Trace ====================

  /**
   * 根据 traceId 查询一次 RAG 检索的完整链路
   *
   * <p>调用入口：前端查看某次 RAG 检索详情时，通过
   * {@code GET /api/ai-observability/rag-traces/{traceId}} 调用。
   *
   * 返回检索请求参数、查询改写结果、命中文档块列表等完整信息。
   * 同一个 traceId 可关联 AI 调用日志，在链路追踪页面上展示完整的
   * "RAG 检索 → LLM 答案生成"全链路。
   * 主要调用 {@link RagRetrievalLogService#getTrace(String)}
   * 查询主日志及命中文档块。
   *
   * @param traceId 链路追踪 ID（由 RagRetrievalLogService 或调用方生成）
   * @return 包含 RAG Trace 详情的统一响应
   */
  @GetMapping("/api/ai-observability/rag-traces/{traceId}")
  public Result<RagTraceDTO> getRagTrace(@PathVariable String traceId) {
    return Result.success(retrievalLogService.getTrace(traceId));
  }

  /**
   * 查询最近的 RAG 检索记录列表
   *
   * <p>调用入口：前端加载 RAG 检索概览时，通过
   * {@code GET /api/ai-observability/rag-traces/recent} 调用。
   * 该方法委托 {@link RagRetrievalLogService#listRecentTraces()}
   * 查询最近记录及其命中块。
   *
   * 返回最近 20 条 RAG 检索记录，用于在管理后台展示 RAG 检索活动概览。
   * 每条记录包含检索参数、命中统计和命中文档预览。
   *
   * @return 包含 RAG Trace 列表的统一响应
   */
  @GetMapping("/api/ai-observability/rag-traces/recent")
  public Result<List<RagTraceDTO>> listRecentRagTraces() {
    return Result.success(retrievalLogService.listRecentTraces());
  }

  // ==================== RAG 评测集管理 ====================

  /**
   * 创建新的 RAG 评测集
   *
   * <p>调用入口：前端提交“新建评测集”表单时，通过
   * {@code POST /api/ai-observability/rag-eval/datasets} 调用。
   * 请求体先由 Bean Validation 校验，再委托
   * {@link RagEvaluationService#createDataset(CreateRagEvalDatasetRequest)} 持久化。
   *
   * 评测集是一个命名的测试集合，用于组织一组评测用例。
   * 创建成功后可在前端页面上添加多个评测用例。
   *
   * @param request 创建请求体，包含名称和描述（名称不能为空）
   * @return 包含创建后评测集信息的统一响应
   */
  @PostMapping("/api/ai-observability/rag-eval/datasets")
  public Result<RagEvalDatasetDTO> createDataset(
      @Valid @RequestBody CreateRagEvalDatasetRequest request
  ) {
    return Result.success(evaluationService.createDataset(request));
  }

  /**
   * 查询所有 RAG 评测集列表
   *
   * <p>调用入口：前端进入 RAG 评测页面时，通过
   * {@code GET /api/ai-observability/rag-eval/datasets} 调用。
   * 该方法委托 {@link RagEvaluationService#listDatasets()} 查询并排序评测集。
   *
   * 按更新时间降序排列，返回系统中所有已创建的评测集。
   *
   * @return 包含评测集列表的统一响应
   */
  @GetMapping("/api/ai-observability/rag-eval/datasets")
  public Result<List<RagEvalDatasetDTO>> listDatasets() {
    return Result.success(evaluationService.listDatasets());
  }

  /**
   * 查询评测集详情
   *
   * <p>调用入口：前端打开指定评测集时，通过
   * {@code GET /api/ai-observability/rag-eval/datasets/{id}} 调用。
   * 该方法委托 {@link RagEvaluationService#getDataset(Long)} 完成查询和不存在校验。
   *
   * @param id 评测集 ID
   * @return 包含评测集详情的统一响应
   */
  @GetMapping("/api/ai-observability/rag-eval/datasets/{id}")
  public Result<RagEvalDatasetDTO> getDataset(@PathVariable Long id) {
    return Result.success(evaluationService.getDataset(id));
  }

  /**
   * 向评测集添加一个评测用例
   *
   * <p>调用入口：前端提交“新增评测用例”表单时，通过
   * {@code POST /api/ai-observability/rag-eval/datasets/{id}/cases} 调用。
   * 请求体先由 Bean Validation 校验，再委托
   * {@link RagEvaluationService#addCase(Long, AddRagEvalCaseRequest)} 清洗并持久化用例。
   *
   * 每个用例包含一个测试问题、期望关键词列表和关联的知识库 ID 列表。
   * 添加成功后可在评测集详情页看到该用例，并在执行评测时使用。
   *
   * @param id      评测集 ID
   * @param request 添加用例请求体，包含 question、expectedKeywords、knowledgeBaseIds
   * @return 包含创建后评测用例信息的统一响应
   */
  @PostMapping("/api/ai-observability/rag-eval/datasets/{id}/cases")
  public Result<RagEvalCaseDTO> addCase(
      @PathVariable Long id,
      @Valid @RequestBody AddRagEvalCaseRequest request
  ) {
    return Result.success(evaluationService.addCase(id, request));
  }

  // ==================== RAG 评测执行 ====================

  /**
   * 执行一次评测运行
   *
   * <p>调用入口：前端在评测集详情页点击“运行评测”时，通过
   * {@code POST /api/ai-observability/rag-eval/datasets/{id}/runs} 调用。
   * 该方法同步委托 {@link RagEvaluationService#runDataset(Long, RunRagEvalRequest)}；
   * 返回前连接会一直等待全部用例执行完成。
   *
   * 对指定评测集下的所有用例逐个执行向量检索，
   * 并使用关键词匹配判断检索质量。
   * 执行是同步的，评测集用例较多时可能需要一定时间。
   *
   * @param id      评测集 ID
   * @param request 可选参数请求体，可指定 topK 和 minScore 覆盖系统默认值；
   *                不传时使用 KnowledgeBaseQueryProperties 中的默认配置
   * @return 包含评测运行结果（含每个用例的详细信息）的统一响应
   */
  @PostMapping("/api/ai-observability/rag-eval/datasets/{id}/runs")
  public Result<RagEvalRunDTO> runDataset(
      @PathVariable Long id,
      @RequestBody(required = false) RunRagEvalRequest request
  ) {
    return Result.success(evaluationService.runDataset(id, request));
  }

  /**
   * 查询评测运行结果
   *
   * <p>调用入口：前端打开某次评测运行详情时，通过
   * {@code GET /api/ai-observability/rag-eval/runs/{runId}} 调用。
   * 该方法委托 {@link RagEvaluationService#getRun(Long)}
   * 组装运行汇总和逐用例结果。
   *
   * 返回单次评测运行的完整结果，包含每个评测用例的：
   * <ul>
   *   <li>向量检索命中数（hitCount）</li>
   *   <li>期望关键词匹配数（matchedKeywordCount）</li>
   *   <li>关键词是否命中（expectedKeywordHit）</li>
   *   <li>检索耗时（latencyMs）</li>
   *   <li>检索结果文档预览（retrievedPreviewJson）</li>
   * </ul>
   *
   * @param runId 评测运行 ID
   * @return 包含评测运行完整结果的统一响应
   */
  @GetMapping("/api/ai-observability/rag-eval/runs/{runId}")
  public Result<RagEvalRunDTO> getRun(@PathVariable Long runId) {
    return Result.success(evaluationService.getRun(runId));
  }
}
