package interview.guide.modules.aiobservability.service;

import interview.guide.modules.aiobservability.model.AiProviderAttemptLogCreateRequest;
import interview.guide.modules.aiobservability.model.AiProviderAttemptLogDTO;
import interview.guide.modules.aiobservability.model.AiProviderAttemptLogEntity;
import interview.guide.modules.aiobservability.model.AiProviderInvocationLogCreateRequest;
import interview.guide.modules.aiobservability.model.AiProviderInvocationLogDTO;
import interview.guide.modules.aiobservability.model.AiProviderInvocationLogEntity;
import interview.guide.modules.aiobservability.repository.AiProviderAttemptLogRepository;
import interview.guide.modules.aiobservability.repository.AiProviderInvocationLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

/**
 * Provider 级 AI 调用日志服务。
 *
 * <p>功能概述：
 * 持久化真实 LLM Provider 调用日志，分为两个粒度：
 * <ul>
 *   <li>Invocation：一次 ChatClient 逻辑调用的最终结果，写入 ai_provider_invocation_logs</li>
 *   <li>Attempt：一次底层 HTTP 请求，写入 ai_provider_attempt_logs</li>
 * </ul>
 *
 * <p>为什么拆成两张表：
 * 一次业务调用可能因为 RetryTemplate 产生多次 HTTP 请求。如果只存最终结果，看不到
 * 中间第几次 500、哪一次成功；如果只存 HTTP attempt，又很难直接统计业务调用成功率。
 * 因此使用 invocationId 将两张表关联：
 * <pre>
 * ai_provider_invocation_logs.invocation_id
 *   -> ai_provider_attempt_logs.invocation_id
 * </pre>
 *
 * <p>故障隔离：
 * 观测日志不能影响主业务调用，所以写库使用 TransactionTemplate + REQUIRES_NEW，
 * 并且方法内部 catch 所有异常，写入失败只打 warn，返回 Optional.empty()。
 *
 * <p>联动关系：
 * <ul>
 *   <li>AiProviderObservationAdvisor 调用 recordInvocation()</li>
 *   <li>AiProviderHttpObservationInterceptor 调用 recordAttempt()</li>
 *   <li>AiProviderWebClientObservationFilter 调用 recordAttempt()</li>
 *   <li>AiObservabilityController 调用查询方法给前端展示</li>
 * </ul>
 */
@Slf4j
@Service
public class AiProviderObservationLogService {

  private static final int MAX_ERROR_MESSAGE_LENGTH = 1000;

  private final AiProviderInvocationLogRepository invocationRepository;

  private final AiProviderAttemptLogRepository attemptRepository;

  private final TransactionTemplate transactionTemplate;

  /**
   * 构造 Provider 观测日志服务。
   *
   * <p>TransactionTemplate 使用 REQUIRES_NEW，是为了让日志写入和主业务事务隔离。
   * 即使业务 Service 外层没有事务，或者日志写入失败，也不会污染主调用链路。
   */
  public AiProviderObservationLogService(
      AiProviderInvocationLogRepository invocationRepository,
      AiProviderAttemptLogRepository attemptRepository,
      PlatformTransactionManager transactionManager
  ) {
    this.invocationRepository = invocationRepository;
    this.attemptRepository = attemptRepository;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /**
   * 记录一次完整 Provider invocation。
   *
   * <p>调用时机：
   * <ul>
   *   <li>普通 call()：AiProviderObservationAdvisor.finally 中记录</li>
   *   <li>流式 stream()：Flux complete/error 时记录</li>
   *   <li>兜底场景：直接绕过 ChatClient 调用 OpenAiApi 时由 HTTP interceptor 记录</li>
   * </ul>
   *
   * <p>记录含义：
   * success 表示这次逻辑调用最终是否成功，attemptCount 表示底层真实 HTTP 请求次数，
   * retryCount 表示 RetryTemplate 触发了几次 retry。
   */
  public Optional<AiProviderInvocationLogDTO> recordInvocation(
      AiProviderInvocationLogCreateRequest request) {
    try {
      return transactionTemplate.execute(status -> {
        AiProviderInvocationLogEntity entity = AiProviderInvocationLogEntity.builder()
            .traceId(request.traceId())
            .invocationId(request.invocationId())
            .providerId(request.providerId())
            .model(request.model())
            .clientVariant(request.clientVariant())
            .operation(request.operation())
            .streaming(request.streaming())
            .startedAt(request.startedAt())
            .completedAt(request.completedAt())
            .latencyMs(request.latencyMs())
            .success(request.success())
            .attemptCount(request.attemptCount())
            .retryCount(request.retryCount())
            .errorCategory(request.errorCategory())
            .exceptionType(request.exceptionType())
            .errorMessage(truncate(request.errorMessage(), MAX_ERROR_MESSAGE_LENGTH))
            .build();
        return Optional.of(toDTO(invocationRepository.save(entity)));
      });
    } catch (Exception e) {
      log.warn("Provider 调用日志写入失败: invocationId={}, provider={}",
          request.invocationId(), request.providerId(), e);
      return Optional.empty();
    }
  }

  /**
   * 记录一次底层 HTTP attempt。
   *
   * <p>调用时机：
   * 每一次 RestClient/WebClient 真正向 LLM Provider 发请求时都会记录。
   * 如果一次 ChatClient 调用因为 5xx 重试了 2 次，则会产生 3 条 attempt 记录。
   */
  public Optional<AiProviderAttemptLogDTO> recordAttempt(AiProviderAttemptLogCreateRequest request) {
    try {
      return transactionTemplate.execute(status -> {
        AiProviderAttemptLogEntity entity = AiProviderAttemptLogEntity.builder()
            .invocationId(request.invocationId())
            .providerId(request.providerId())
            .model(request.model())
            .clientVariant(request.clientVariant())
            .operation(request.operation())
            .attemptNo(request.attemptNo())
            .httpMethod(request.httpMethod())
            .uri(truncate(request.uri(), 500))
            .httpStatus(request.httpStatus())
            .success(request.success())
            .retryable(request.retryable())
            .latencyMs(request.latencyMs())
            .errorCategory(request.errorCategory())
            .exceptionType(request.exceptionType())
            .errorMessage(truncate(request.errorMessage(), MAX_ERROR_MESSAGE_LENGTH))
            .build();
        return Optional.of(toDTO(attemptRepository.save(entity)));
      });
    } catch (Exception e) {
      log.warn("Provider attempt 日志写入失败: invocationId={}, provider={}, attempt={}",
          request.invocationId(), request.providerId(), request.attemptNo(), e);
      return Optional.empty();
    }
  }

  /** 查询最近 50 条 Provider invocation，用于观测页面默认列表。 */
  @Transactional(readOnly = true)
  public List<AiProviderInvocationLogDTO> listRecentInvocations() {
    return invocationRepository.findTop50ByOrderByCreatedAtDesc().stream()
        .map(this::toDTO)
        .toList();
  }

  /** 根据 traceId 查询一次请求链路下触发的所有 Provider invocation。 */
  @Transactional(readOnly = true)
  public List<AiProviderInvocationLogDTO> listByTraceId(String traceId) {
    return invocationRepository.findByTraceIdOrderByCreatedAtAsc(traceId).stream()
        .map(this::toDTO)
        .toList();
  }

  /** 根据 invocationId 查询这次逻辑调用下面的所有 HTTP attempt。 */
  @Transactional(readOnly = true)
  public List<AiProviderAttemptLogDTO> listAttempts(String invocationId) {
    return attemptRepository.findByInvocationIdOrderByAttemptNoAsc(invocationId).stream()
        .map(this::toDTO)
        .toList();
  }

  /** Entity 转 DTO，避免 Controller 直接暴露 JPA Entity。 */
  private AiProviderInvocationLogDTO toDTO(AiProviderInvocationLogEntity entity) {
    return new AiProviderInvocationLogDTO(
        entity.getId(),
        entity.getTraceId(),
        entity.getInvocationId(),
        entity.getProviderId(),
        entity.getModel(),
        entity.getClientVariant(),
        entity.getOperation(),
        entity.getStreaming(),
        entity.getStartedAt(),
        entity.getCompletedAt(),
        entity.getLatencyMs(),
        entity.getSuccess(),
        entity.getAttemptCount(),
        entity.getRetryCount(),
        entity.getErrorCategory(),
        entity.getExceptionType(),
        entity.getErrorMessage(),
        entity.getCreatedAt()
    );
  }

  /** Entity 转 DTO，保留前端排查 attempt 需要的状态码、耗时和错误分类。 */
  private AiProviderAttemptLogDTO toDTO(AiProviderAttemptLogEntity entity) {
    return new AiProviderAttemptLogDTO(
        entity.getId(),
        entity.getInvocationId(),
        entity.getProviderId(),
        entity.getModel(),
        entity.getClientVariant(),
        entity.getOperation(),
        entity.getAttemptNo(),
        entity.getHttpMethod(),
        entity.getUri(),
        entity.getHttpStatus(),
        entity.getSuccess(),
        entity.getRetryable(),
        entity.getLatencyMs(),
        entity.getErrorCategory(),
        entity.getExceptionType(),
        entity.getErrorMessage(),
        entity.getCreatedAt()
    );
  }

  /** 截断超长字段，防止异常消息或 URL 过长影响日志表存储。 */
  private String truncate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, maxLength);
  }
}
