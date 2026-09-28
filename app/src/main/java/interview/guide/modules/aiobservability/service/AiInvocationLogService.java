/**
 * AI 调用日志服务
 *
 * 负责记录和查询每次 LLM（大语言模型）调用的详细信息，
 * 为 AI 可观测性提供数据支撑。
 * 每次调用大模型时由 StructuredOutputInvoker 或类似组件调用 recordInvocation()，
 * 将调用的场景、模型、耗时、成功与否等关键信息持久化到数据库。
 *
 * 核心功能：
 * <ul>
 *   <li>记录 AI 调用：将一次 LLM 调用的收据
 *       （模型、耗时、成功/失败等）存入数据库</li>
 *   <li>按条件查询：支持按 ID 查询单条、按 traceId 查询全链路、
 *       查询最近 50 条记录</li>
 *   <li>生成 traceId：为每次 AI 调用生成全局唯一追踪 ID，
 *       便于关联上下游日志</li>
 * </ul>
 *
 * 设计要点：
 * <ul>
 *   <li>降级写入：日志写入失败时返回 Optional.empty() 而非抛异常，
 *       确保"记录日志"这件事本身不影响"被记录的业务"</li>
 *   <li>Prompt 哈希化：对 systemPrompt + userPrompt 做 SHA-256 哈希，
 *       存储 hash 而非原文，既能做去重统计又节省存储空间</li>
 *   <li>错误消息截断：LLM 返回的错误消息可能很长，
 *       截断到 1000 字符防止撑爆 TEXT 字段</li>
 * </ul>
 *
 * 联动关系：
 * <ul>
 *   <li>数据来源：被 StructuredOutputInvoker 调用 recordInvocation() 写入日志</li>
 *   <li>数据查询：被 AiObservabilityController 调用，通过 REST API 暴露给前端</li>
 *   <li>依赖 Repository：AiInvocationLogRepository 负责 JPA 持久化</li>
 *   <li>配合 TraceIdFilter：traceId 字段与 HTTP 请求的 X-Trace-Id 关联，
 *       可在前端页面看到一次 HTTP 请求触发了哪些 AI 调用</li>
 * </ul>
 */
package interview.guide.modules.aiobservability.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.aiobservability.model.AiInvocationLogCreateRequest;
import interview.guide.modules.aiobservability.model.AiInvocationLogDTO;
import interview.guide.modules.aiobservability.model.AiInvocationLogEntity;
import interview.guide.modules.aiobservability.repository.AiInvocationLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiInvocationLogService {

  /** 错误消息最大长度限制，防止异常堆栈过长导致数据库字段溢出 */
  private static final int MAX_ERROR_MESSAGE_LENGTH = 1000;

  /** JPA 仓库，负责 ai_invocation_logs 表的 CRUD 操作 */
  private final AiInvocationLogRepository logRepository;

  /**
   * 生成全局唯一的追踪 ID
   *
   * <p>调用入口：{@code StructuredOutputInvoker} 在当前调用链没有 traceId 时调用；
   * {@link #recordInvocation(AiInvocationLogCreateRequest)} 也会用它补齐空 traceId。
   * 本方法只生成 UUID，不读写数据库。
   *
   * 用于关联一次 HTTP 请求中触发的所有 AI 调用。
   * 如果调用方没有传入 traceId（如后台异步任务），
   * 则使用此方法生成新的 ID。
   *
   * @return UUID 格式的 36 位字符串，如 "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
   */
  public String createTraceId() {
    return UUID.randomUUID().toString();
  }

  /**
   * 记录一次 AI 调用日志
   *
   * <p>调用入口：{@code StructuredOutputInvoker}
   * 在结构化 LLM 调用成功或最终失败后调用，
   * 属于可观测性旁路，不应反向影响 AI 业务结果。
   *
   * 将一次 LLM 调用的完整信息持久化到数据库。
   * 如果写入失败（如数据库异常），
   * 只记录警告日志并返回 Optional.empty()，不影响业务主流程。
   *
   * 执行流程：
   * <ol>
   *   <li>拼接 systemPrompt + userPrompt 作为哈希源文本</li>
   *   <li>计算 prompt 的 SHA-256 哈希值（用于去重和统计）</li>
   *   <li>组装 AiInvocationLogEntity 并调用 Repository.save()</li>
   *   <li>将持久化后的 Entity 转换为 DTO 返回</li>
   * </ol>
   *
   * <p>主要调用：使用 {@link #hash(String)} 生成 Prompt 摘要，使用
   * {@link #truncate(String, int)} 限制错误长度，最后调用
   * {@code logRepository.save(...)} 写入数据库并通过
   * {@link #toDTO(AiInvocationLogEntity)} 返回安全的数据传输对象。
   *
   * 异常处理：整个方法被 try-catch 包裹，
   * 任何异常都降级为警告日志 + Optional.empty()。
   * 这是有意为之——日志功能不应因为自身故障
   * 而影响业务逻辑的正常执行。
   *
   * @param request 调用日志请求体，包含 traceId、scenario、providerId、
   *                latencyMs 等字段
   * @return 包含持久化后 DTO 的 Optional；写入失败时返回 Optional.empty()
   */
  public Optional<AiInvocationLogDTO> recordInvocation(AiInvocationLogCreateRequest request) {
    try {
      // 将 systemPrompt 和 userPrompt 拼接，用于计算哈希值
      String promptSource = safeText(request.systemPrompt())
          + "\n"
          + safeText(request.userPrompt());
      AiInvocationLogEntity entity = AiInvocationLogEntity.builder()
          // traceId：优先使用调用方传入的，为空则自动生成
          .traceId(nonBlankOr(request.traceId(), createTraceId()))
          .scenario(request.scenario())
          .providerId(request.providerId())
          .model(request.model())
          .promptVersion(request.promptVersion())
          // Prompt 内容的 SHA-256 哈希，用于去重统计
          .promptHash(hash(promptSource))
          // Prompt 内容的总字符数
          .promptLength(promptSource.length())
          .streaming(request.streaming())
          .structuredOutput(request.structuredOutput())
          // 总尝试次数（含首次+重试），由 StructuredOutputInvoker 提供
          .attemptCount(request.attemptCount())
          // 最终是否成功（true=最终重试后成功，false=所有重试后仍失败）
          .success(request.success())
          // 总耗时，单位毫秒
          .latencyMs(request.latencyMs())
          .errorCode(request.errorCode())
          // 错误消息截断到最大长度，防止超长堆栈撑爆 TEXT 字段
          .errorMessage(truncate(request.errorMessage(), MAX_ERROR_MESSAGE_LENGTH))
          .build();
      return Optional.of(toDTO(logRepository.save(entity)));
    } catch (Exception e) {
      log.warn("AI 调用日志写入失败: traceId={}, scenario={}",
          request.traceId(), request.scenario(), e);
      return Optional.empty();
    }
  }

  /**
   * 查询最近 50 条 AI 调用日志
   *
   * <p>调用入口：{@code AiObservabilityController.listInvocations}
   * 在未指定 traceId 时调用。
   * 主要调用 {@link AiInvocationLogRepository#findTop50ByOrderByCreatedAtDesc()}，
   * 并通过 {@link #toDTO(AiInvocationLogEntity)} 转换查询结果。
   *
   * 用于在管理后台展示"最近的 AI 调用"列表，按创建时间降序排列。
   * 只查询最近 50 条记录，避免一次查询过多数据。
   *
   * @return 最多 50 条 AiInvocationLogDTO 的列表
   */
  @Transactional(readOnly = true)
  public List<AiInvocationLogDTO> listRecentInvocations() {
    return logRepository.findTop50ByOrderByCreatedAtDesc().stream()
        .map(this::toDTO)
        .toList();
  }

  /**
   * 根据主键查询单条 AI 调用日志详情
   *
   * <p>调用入口：{@code AiObservabilityController.getInvocation}。
   * 主要调用 {@code logRepository.findById(...)}；记录不存在时转换为业务异常。
   *
   * @param id 日志主键 ID
   * @return AI 调用日志 DTO
   * @throws BusinessException 当 ID 对应的日志不存在时抛出 NOT_FOUND 异常
   */
  @Transactional(readOnly = true)
  public AiInvocationLogDTO getInvocation(Long id) {
    return logRepository.findById(id)
        .map(this::toDTO)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "AI 调用日志不存在"));
  }

  /**
   * 根据 traceId 查询关联的所有 AI 调用日志
   *
   * <p>调用入口：{@code AiObservabilityController.listInvocations}
   * 在指定 traceId 时调用。
   * 主要调用 {@link AiInvocationLogRepository#findByTraceIdOrderByCreatedAtAsc(String)}，
   * 再通过 {@link #toDTO(AiInvocationLogEntity)} 转换结果。
   *
   * 一次 HTTP 请求可能触发多次 AI 调用（如面试评估的分批评估阶段），
   * 这些调用共享同一个 traceId。通过此方法可以查看一次请求触发的所有
   * LLM 调用的完整链路。
   *
   * @param traceId 链路追踪 ID
   * @return 按创建时间升序排列的 AI 调用日志列表
   */
  @Transactional(readOnly = true)
  public List<AiInvocationLogDTO> listByTraceId(String traceId) {
    // 按 traceId 精确查询
    return logRepository.findByTraceIdOrderByCreatedAtAsc(traceId).stream()
        .map(this::toDTO)
        .toList();
  }

  /**
   * 将持久化实体转换为 DTO（用于对外暴露）
   *
   * <p>由日志写入、最近记录查询、详情查询和 traceId 查询共同调用，
   * 是本服务所有 Entity → DTO 转换的统一出口。
   *
   * 禁止直接返回 Entity 给前端，避免暴露内部持久化细节。
   * DTO 中只包含调用方需要的字段，不包含 JPA 代理相关属性。
   *
   * @param entity AI 调用日志实体
   * @return AI 调用日志 DTO
   */
  private AiInvocationLogDTO toDTO(AiInvocationLogEntity entity) {
    return new AiInvocationLogDTO(
        entity.getId(),
        entity.getTraceId(),
        entity.getScenario(),
        entity.getProviderId(),
        entity.getModel(),
        entity.getPromptVersion(),
        entity.getPromptHash(),
        entity.getPromptLength(),
        entity.getStreaming(),
        entity.getStructuredOutput(),
        entity.getAttemptCount(),
        entity.getSuccess(),
        entity.getLatencyMs(),
        entity.getErrorCode(),
        entity.getErrorMessage(),
        entity.getCreatedAt()
    );
  }

  /**
   * 计算文本的 SHA-256 哈希值
   *
   * <p>仅由 {@link #recordInvocation(AiInvocationLogCreateRequest)} 调用，
   * 计算结果写入 {@code promptHash}，不会保存 Prompt 原文。
   *
   * 用于对 systemPrompt + userPrompt 做摘要，生成固定长度的哈希标识。
   * 通过哈希值可以统计相同 Prompt 被调用的频次，
   * 无需存储完整的 Prompt 原文。
   *
   * @param text 需要计算哈希的文本内容（拼接后的 system + user prompt）
   * @return 64 字符的十六进制哈希字符串；
   *         计算失败（如算法不可用）时返回 null
   */
  private String hash(String text) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(bytes);
    } catch (NoSuchAlgorithmException e) {
      log.warn("计算 prompt hash 失败", e);
      return null;
    }
  }

  /**
   * 字符串空值保护：null 转为空字符串
   *
   * <p>由 {@link #recordInvocation(AiInvocationLogCreateRequest)} 在拼接
   * system/user Prompt 前调用，避免空值影响摘要计算。
   *
   * @param value 原始字符串，可能为 null
   * @return value 为 null 时返回空字符串，否则原样返回
   */
  private String safeText(String value) {
    return value == null ? "" : value;
  }

  /**
   * 字符串空值回退：如果目标值为 null 或空白，返回备用值
   *
   * <p>由 {@link #recordInvocation(AiInvocationLogCreateRequest)} 调用，
   * 用自动生成的 UUID 补齐缺失的 traceId。
   *
   * @param value    目标值，可能为 null 或空白
   * @param fallback 备用值
   * @return value 非空白时返回 value.trim()；否则返回 fallback
   */
  private String nonBlankOr(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  /**
   * 字符串截断：将超长字符串截断为指定长度
   *
   * <p>由 {@link #recordInvocation(AiInvocationLogCreateRequest)} 在构建实体时调用，
   * 当前用于限制持久化错误消息的长度。
   *
   * 用于限制错误消息等可能非常长的字段，防止数据库 TEXT 字段被撑爆。
   *
   * @param value     原始字符串，可能为 null
   * @param maxLength 最大允许的字符数
   * @return 截断后的字符串；value 为 null 时返回 null
   */
  private String truncate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, maxLength);
  }
}
