/**
 * 异步任务管理服务
 *
 * 提供异步任务的完整生命周期管理，
 * 是抽象异步任务（不限于 Redis Stream）的统一服务层。
 * 管理任务从 PENDING、PROCESSING 到各类终态的流转，
 * 支持任务创建、状态标记、重试、取消、查询等操作。
 * 使用 activeFlag + activeKey 机制约束同一业务对象的活跃任务数量。
 *
 * 任务状态流转：
 * <pre>
 * PENDING --markProcessing()--> PROCESSING
 * PROCESSING --> COMPLETED / CANCELLED / FAILED / DEAD_LETTER
 * FAILED / CANCELLED / DEAD_LETTER --retryTask()--> PENDING
 * </pre>
 *
 * 活跃任务唯一性约束（activeFlag + activeKey）：
 * - createOrReusePendingTask 在创建前查询同类型、同 businessId 的活跃任务
 * - 存在时复用已有任务并返回 created=false
 * - 任务进入终态时清除 activeFlag 和 activeKey
 * - activeKey 格式：taskType:businessId（如 RESUME_ANALYZE:123）
 *
 * 联动关系：
 * - 被 AnalyzeStreamProducer、VectorizeStreamProducer、EvaluateStreamProducer、
 *   VoiceEvaluateStreamProducer 调用（创建任务、标记入队、标记重试）
 * - 被 AnalyzeStreamConsumer、VectorizeStreamConsumer、EvaluateStreamConsumer、
 *   VoiceEvaluateStreamConsumer 调用（markProcessing、markCompleted、markCancelled、
 *   markFailed、markDeadLetter、markRetrying）
 * - 依赖 AsyncTaskRepository 进行数据库持久化操作
 * - 依赖 AsyncTaskStreamConstants 获取重试次数上限和消息字段常量
 * - 依赖 RedisService（仅在 retryTask 手动重试时 direct XADD 到 Stream）
 * - 抛出 AsyncTaskCancelledException 当 markProcessing 发现任务已被取消时
 * - 使用 AsyncTaskEntity 持久化任务状态，通过 AsyncTaskDTO 对外暴露只读视图
 */
package interview.guide.modules.asynctask.service;

import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.async.AsyncTaskCancelledException;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.asynctask.model.AsyncTaskDTO;
import interview.guide.modules.asynctask.model.AsyncTaskEnqueueResult;
import interview.guide.modules.asynctask.model.AsyncTaskEntity;
import interview.guide.modules.asynctask.model.AsyncTaskStatusDTO;
import interview.guide.modules.asynctask.model.AsyncTaskType;
import interview.guide.modules.asynctask.repository.AsyncTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 异步任务管理服务
 * 提供异步任务全生命周期管理：创建、状态流转、重试、取消、查询
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncTaskService {

  /**
   * 活跃状态集合：PENDING 和 PROCESSING
   * 用于 createOrReusePendingTask 查询是否存在同业务对象的活跃任务
   */
  private static final Set<AsyncTaskStatusDTO> ACTIVE_STATUSES = Set.of(
      AsyncTaskStatusDTO.PENDING,
      AsyncTaskStatusDTO.PROCESSING
  );

  /**
   * 可重试状态集合：FAILED、CANCELLED、DEAD_LETTER
   * retryTask() 只允许这些终态任务重新进入 PENDING 状态
   */
  private static final Set<AsyncTaskStatusDTO> RETRYABLE_STATUSES = Set.of(
      AsyncTaskStatusDTO.FAILED,
      AsyncTaskStatusDTO.CANCELLED,
      AsyncTaskStatusDTO.DEAD_LETTER
  );

  /**
   * 错误信息最大长度（1000 字符）
   * 超长错误信息会被截断，防止数据库字段溢出
   */
  private static final int MAX_ERROR_LENGTH = 1000;

  /**
   * 异步任务数据访问仓库
   */
  private final AsyncTaskRepository taskRepository;

  /**
   * Redis 通用服务
   * 仅在 retryTask() 手动重试时使用，将任务消息重新 XADD 到 Redis Stream
   */
  private final RedisService redisService;

  /**
   * 创建新任务或复用已有活跃任务
   *
   * <p>调用入口：四类 Redis Stream Producer 在发送业务消息前调用，
   * 用于先建立可查询的任务记录并取得 {@code taskId}。
   *
   * 先查询是否存在同类型、同业务对象且处于 PENDING 或 PROCESSING 的任务。
   * 存在则返回已有任务（created=false），避免重复创建任务记录；
   * 不存在则创建新任务（created=true），状态初始为 PENDING、retryCount=0、
   * maxRetryCount 从 AsyncTaskStreamConstants.MAX_RETRY_COUNT 获取。
   *
   * <p>主要调用：先通过
   * {@link AsyncTaskRepository#findFirstByTaskTypeAndBusinessIdAndStatusInOrderByCreatedAtDesc(
   * AsyncTaskType, String, java.util.Collection)} 查询活跃任务；存在时通过
   * {@link #toDTO(AsyncTaskEntity)} 返回复用结果，否则调用
   * {@link #createTask(AsyncTaskType, String, String, String)} 创建新记录。
   *
   * @param taskType 异步任务类型
   * @param businessId  业务对象 ID（如简历 ID、知识库 ID、面试会话 ID）
   * @param streamKey   目标 Redis Stream Key（用于 retryTask 时定位目标 Stream）
   * @param payloadJson 任务载荷 JSON，用于人工排查，不参与消息消费逻辑
   * @return AsyncTaskEnqueueResult 包含任务 DTO 和 created 标志的入队结果
   */
  @Transactional
  public AsyncTaskEnqueueResult createOrReusePendingTask(
      AsyncTaskType taskType,
      String businessId,
      String streamKey,
      String payloadJson
  ) {
    return taskRepository
        .findFirstByTaskTypeAndBusinessIdAndStatusInOrderByCreatedAtDesc(
            taskType,
            businessId,
            ACTIVE_STATUSES
        )
        .map(entity -> new AsyncTaskEnqueueResult(toDTO(entity), false))
        // 如果不存在活跃任务，创建新任务（created=true）
        .orElseGet(() -> createTask(taskType, businessId, streamKey, payloadJson));
  }

  /**
   * 标记任务消息已成功入队
   *
   * <p>调用入口：Producer 成功执行 Redis {@code XADD} 后调用。
   * 主要调用 {@link #getEntity(Long)} 取得任务，写入消息 ID 和活跃键后保存；
   * 本方法不负责发送 Redis 消息。
   *
   * 在 Producer 成功执行 XADD 后将 Redis 返回的 messageId 写入任务记录，
   * 状态设为 PENDING，activeFlag 置为 true，设置 activeKey。
   * activeFlag + activeKey 确保后续 createOrReusePendingTask 能检测到此活跃任务。
   *
   * @param taskId    异步任务 ID
   * @param messageId Redis Stream 消息 ID（XADD 返回值）
   */
  @Transactional
  public void markEnqueued(Long taskId, String messageId) {
    AsyncTaskEntity task = getEntity(taskId);
    task.setMessageId(messageId);
    task.setStatus(AsyncTaskStatusDTO.PENDING);
    task.setActiveFlag(true);
    task.setActiveKey(activeKey(task.getTaskType(), task.getBusinessId()));
    taskRepository.save(task);
  }

  /**
   * 标记任务开始处理
   *
   * <p>调用入口：各 Stream Consumer 从队列取得消息后、执行业务逻辑前调用。
   * 主要调用 {@link #getEntity(Long)} 查询任务并更新为 {@code PROCESSING}；
   * 若任务已在等待阶段被取消，则抛出取消异常，
   * 让消费模板 ACK 后停止处理。
   *
   * 消费者拉取消息后调用此方法，
   * 在执行业务逻辑前将任务状态从 PENDING 变为 PROCESSING。
   * 如果任务在等待处理期间被手动取消（状态为 CANCELLED），则抛出
   * AsyncTaskCancelledException，消费者收到后走取消路径并 ACK，不重试。
   *
   * @param taskId 异步任务 ID
   * @throws AsyncTaskCancelledException 如果任务已被取消
   */
  @Transactional
  public void markProcessing(Long taskId) {
    AsyncTaskEntity task = getEntity(taskId);
    if (task.getStatus() == AsyncTaskStatusDTO.CANCELLED) {
      throw new AsyncTaskCancelledException("任务已被取消: taskId=" + taskId);
    }
    task.setStatus(AsyncTaskStatusDTO.PROCESSING);
    task.setActiveFlag(true);
    task.setActiveKey(activeKey(task.getTaskType(), task.getBusinessId()));
    task.setLastError(null);
    taskRepository.save(task);
  }

  /**
   * 标记任务处理成功
   *
   * <p>调用入口：各 Stream Consumer 完成业务处理后调用。
   * 本方法委托 {@link #finishTask(Long, AsyncTaskStatusDTO, String)}
   * 写入 {@code COMPLETED} 并释放活跃键。
   *
   * 设置状态为 COMPLETED，清除 activeFlag 和 activeKey，
   * 记录错误信息（通常为 null）。
   *
   * @param taskId 异步任务 ID
   */
  @Transactional
  public void markCompleted(Long taskId) {
    finishTask(taskId, AsyncTaskStatusDTO.COMPLETED, null);
  }

  /**
   * 标记任务已取消
   *
   * <p>调用入口：消费模板捕获 {@code AsyncTaskCancelledException}，
   * 或消费者发现关联业务实体已删除时调用。
   * 本方法委托 {@link #finishTask(Long, AsyncTaskStatusDTO, String)}
   * 记录取消原因并释放活跃键，不会重新入队。
   *
   * 设置状态为 CANCELLED，清除 activeFlag 和 activeKey。
   * 取消原因（如实体已删除、无法获取内容）记录到 lastError。
   * 取消是正常的业务状态（不是系统故障），不触发重试。
   *
   * @param taskId 异步任务 ID
   * @param reason 取消原因
   */
  @Transactional
  public void markCancelled(Long taskId, String reason) {
    finishTask(taskId, AsyncTaskStatusDTO.CANCELLED, reason);
  }

  /**
   * 标记任务入队或处理失败
   *
   * <p>调用入口：当前由各 Stream Producer 在首次消息发送失败时调用。
   * 本方法委托 {@link #finishTask(Long, AsyncTaskStatusDTO, String)}
   * 写入 {@code FAILED}、保存截断后的错误并释放活跃键。
   *
   * 设置状态为 FAILED，清除 activeFlag 和 activeKey。
   * 错误信息记录到 lastError。
   * FAILED 表示任务没有进入可继续消费的状态，需要人工介入或手动重试。
   *
   * @param taskId 异步任务 ID
   * @param error  错误描述
   */
  @Transactional
  public void markFailed(Long taskId, String error) {
    finishTask(taskId, AsyncTaskStatusDTO.FAILED, error);
  }

  /**
   * 标记任务进入死信队列
   *
   * <p>调用入口：各 Stream Consumer 在自动重试耗尽，
   * 或重试消息无法再次写入 Stream 时调用。
   * 本方法委托 {@link #finishTask(Long, AsyncTaskStatusDTO, String)}
   * 写入 {@code DEAD_LETTER} 并释放活跃键。
   *
   * 设置状态为 DEAD_LETTER，清除 activeFlag 和 activeKey。
   * DEAD_LETTER 用于标记重试入队失败的极端情况（连重新 XADD 都失败了），
   * 此时消息无法再通过 Stream 流转，需要人工介入处理。
   *
   * @param taskId 异步任务 ID
   * @param error  错误描述
   */
  @Transactional
  public void markDeadLetter(Long taskId, String error) {
    finishTask(taskId, AsyncTaskStatusDTO.DEAD_LETTER, error);
  }

  /**
   * 标记任务正在重试
   *
   * <p>调用入口：消费者捕获业务异常，且
   * {@code retryCount < MAX_RETRY_COUNT}，并成功把重试消息写回 Stream 后调用。
   *
   * <p>主要调用：通过 {@link #getEntity(Long)} 查询任务，更新重试次数、
   * 新消息 ID 和活跃键后保存。本方法不负责发送重试消息。
   *
   * 将状态重置为 PENDING，更新 retryCount，
   * 记录新的 messageId，保持 activeFlag 为 true（任务仍处于活跃状态）。
   *
   * @param taskId     异步任务 ID
   * @param retryCount 当前重试次数（由消费者计算，每次 +1）
   * @param messageId  重新入队后 Redis 返回的新消息 ID
   */
  @Transactional
  public void markRetrying(Long taskId, int retryCount, String messageId) {
    AsyncTaskEntity task = getEntity(taskId);
    task.setStatus(AsyncTaskStatusDTO.PENDING);
    task.setRetryCount(retryCount);
    task.setMessageId(messageId);
    task.setActiveFlag(true);
    task.setActiveKey(activeKey(task.getTaskType(), task.getBusinessId()));
    taskRepository.save(task);
  }

  /**
   * 查询异步任务列表
   *
   * <p>调用入口：{@code AsyncTaskController.listTasks}。
   * 根据两个可选条件选择四个 Repository 派生查询之一，
   * 再通过 {@link #toDTO(AsyncTaskEntity)} 转换为只读视图。
   *
   * 支持按任务类型和状态组合过滤查询，结果按创建时间倒序排列。
   * 两个参数均为 null 时返回全部任务。
   * 只读事务，不修改数据库状态。
   *
   * @param taskType 异步任务类型，可为 null
   * @param status   任务状态，可为 null
   * @return 符合过滤条件的任务 DTO 列表
   */
  @Transactional(readOnly = true)
  public List<AsyncTaskDTO> listTasks(AsyncTaskType taskType, AsyncTaskStatusDTO status) {
    if (taskType != null && status != null) {
      return taskRepository.findByTaskTypeAndStatusOrderByCreatedAtDesc(taskType, status)
          .stream()
          .map(this::toDTO)
          .toList();
    }
    if (taskType != null) {
      return taskRepository.findByTaskTypeOrderByCreatedAtDesc(taskType).stream()
          .map(this::toDTO)
          .toList();
    }
    if (status != null) {
      return taskRepository.findByStatusOrderByCreatedAtDesc(status).stream()
          .map(this::toDTO)
          .toList();
    }
    return taskRepository.findAllByOrderByCreatedAtDesc().stream()
        .map(this::toDTO)
        .toList();
  }

  /**
   * 根据 ID 查询单个异步任务
   *
   * <p>调用入口：{@code AsyncTaskController.getTask}。
   * 主要调用 {@link #getEntity(Long)} 完成不存在校验，
   * 再通过 {@link #toDTO(AsyncTaskEntity)} 返回 DTO。
   *
   * 只读事务，任务不存在时抛出 BusinessException(ErrorCode.NOT_FOUND)。
   *
   * @param id 异步任务 ID
   * @return 任务 DTO
   * @throws BusinessException 任务不存在
   */
  @Transactional(readOnly = true)
  public AsyncTaskDTO getTask(Long id) {
    return toDTO(getEntity(id));
  }

  /**
   * 手动重试失败/取消/死信的任务
   *
   * <p>调用入口：{@code AsyncTaskController.retryTask}。
   * 该方法校验终态、重置数据库状态，
   * 调用 {@link #buildRetryMessage(AsyncTaskEntity)}
   * 构造消息，并通过 {@code RedisService.streamAdd(...)} 重新写入原 Stream。
   * 成功后保存新的 Redis messageId。
   *
   * 仅允许 FAILED、CANCELLED、DEAD_LETTER 三种终态任务重试。
   * 将 retryCount 重置为 0、状态变更为 PENDING、清除错误信息，
   * 然后通过 RedisService 将重试消息重新 XADD 到原 Stream。
   * 重试消息的字段名根据任务类型自动选择（RESUME_ANALYZE → resumeId、
   * KB_VECTORIZE → kbId、INTERVIEW_EVALUATE → sessionId、
   * VOICE_INTERVIEW_EVALUATE → voiceSessionId）。
   *
   * @param id 异步任务 ID
   * @return 更新后的任务 DTO
   * @throws BusinessException 任务不存在或当前状态不允许重试
   */
  @Transactional
  public AsyncTaskDTO retryTask(Long id) {
    AsyncTaskEntity task = getEntity(id);
    if (!RETRYABLE_STATUSES.contains(task.getStatus())) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "当前任务状态不允许重试");
    }

    task.setRetryCount(0);
    task.setStatus(AsyncTaskStatusDTO.PENDING);
    task.setLastError(null);
    task.setActiveFlag(true);
    task.setActiveKey(activeKey(task.getTaskType(), task.getBusinessId()));
    taskRepository.save(task);

    // 重新入队重试消息
    String messageId = redisService.streamAdd(
        task.getStreamKey(),
        buildRetryMessage(task),
        AsyncTaskStreamConstants.STREAM_MAX_LEN
    );
    task.setMessageId(messageId);
    return toDTO(taskRepository.save(task));
  }

  /**
   * 手动取消任务
   *
   * <p>调用入口：{@code AsyncTaskController.cancelTask}。
   * 主要调用 {@link #getEntity(Long)} 校验任务，
   * 随后更新数据库状态并释放活跃键。
   * 该方法不直接操作 Redis，也不会中断已经开始运行的消费者线程。
   *
   * 仅允许 PENDING 和 PROCESSING 两种活跃状态的任务被取消。
   * 设置状态为 CANCELLED、记录取消原因、清除 activeFlag 和 activeKey。
   * 取消后消费者在 markProcessing 时会检测到并抛出 AsyncTaskCancelledException。
   *
   * @param id 异步任务 ID
   * @return 更新后的任务 DTO
   * @throws BusinessException 任务不存在或当前状态不允许取消
   */
  @Transactional
  public AsyncTaskDTO cancelTask(Long id) {
    AsyncTaskEntity task = getEntity(id);
    if (!ACTIVE_STATUSES.contains(task.getStatus())) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "当前任务状态不允许取消");
    }
    task.setStatus(AsyncTaskStatusDTO.CANCELLED);
    task.setLastError("用户手动取消任务");
    task.setActiveFlag(false);
    task.setActiveKey(null);
    return toDTO(taskRepository.save(task));
  }

  /**
   * 将 AsyncTaskEntity 转换为 AsyncTaskDTO
   *
   * <p>由任务创建、查询、重试、取消等方法共同调用，
   * 是本服务所有 Entity → DTO 转换的统一出口。
   *
   * DTO 是对外暴露的只读数据视图，包含任务的全部字段。
   *
   * @param entity 持久化实体
   * @return 数据传输对象
   */
  public AsyncTaskDTO toDTO(AsyncTaskEntity entity) {
    return new AsyncTaskDTO(
        entity.getId(),
        entity.getTaskType(),
        entity.getBusinessId(),
        entity.getStreamKey(),
        entity.getMessageId(),
        entity.getStatus(),
        entity.getRetryCount(),
        entity.getMaxRetryCount(),
        entity.getNextRetryAt(),
        entity.getLastError(),
        entity.getPayloadJson(),
        entity.getParentTaskId(),
        entity.getActiveFlag(),
        entity.getCreatedAt(),
        entity.getUpdatedAt()
    );
  }

  /**
   * 创建新任务并返回入队结果
   *
   * <p>仅由
   * {@link #createOrReusePendingTask(AsyncTaskType, String, String, String)}
   * 在没有活跃任务时调用。内部生成 activeKey、保存实体并通过
   * {@link #toDTO(AsyncTaskEntity)} 返回 {@code created=true} 的结果。
   *
   * 构建 AsyncTaskEntity：taskType、businessId、streamKey、
   * 初始状态 PENDING、retryCount=0、maxRetryCount 从常量获取、
   * activeFlag=true、activeKey=taskType:businessId。
   * 持久化后包装为 AsyncTaskEnqueueResult（created=true）。
   *
   * @param taskType    异步任务类型
   * @param businessId  业务对象 ID
   * @param streamKey   Redis Stream Key
   * @param payloadJson 任务载荷 JSON
   * @return 入队结果（含新任务 DTO 和 created=true）
   */
  private AsyncTaskEnqueueResult createTask(
      AsyncTaskType taskType,
      String businessId,
      String streamKey,
      String payloadJson
  ) {
    AsyncTaskEntity task = AsyncTaskEntity.builder()
        .taskType(taskType)
        .businessId(businessId)
        .streamKey(streamKey)
        .status(AsyncTaskStatusDTO.PENDING)
        .retryCount(0)
        .maxRetryCount(AsyncTaskStreamConstants.MAX_RETRY_COUNT)
        .payloadJson(payloadJson)
        .activeFlag(true)
        .activeKey(activeKey(taskType, businessId))
        .build();
    return new AsyncTaskEnqueueResult(toDTO(taskRepository.save(task)), true);
  }

  /**
   * 将任务设置为终态并清除活跃标记
   *
   * <p>由 {@link #markCompleted(Long)}、{@link #markCancelled(Long, String)}、
   * {@link #markFailed(Long, String)} 和 {@link #markDeadLetter(Long, String)}
   * 共同调用。内部使用 {@link #truncate(String)} 限制错误长度后保存。
   *
   * 所有终态标记方法（markCompleted / markCancelled / markFailed / markDeadLetter）
   * 共享的实现。设置指定的 status，清除 activeFlag 和 activeKey，
   * 记录截断后的错误信息。
   *
   * @param taskId 异步任务 ID
   * @param status 终态状态
   * @param error  错误描述（可为 null，会截断至 MAX_ERROR_LENGTH）
   */
  private void finishTask(Long taskId, AsyncTaskStatusDTO status, String error) {
    AsyncTaskEntity task = getEntity(taskId);
    task.setStatus(status);
    task.setActiveFlag(false);
    task.setActiveKey(null);
    task.setLastError(truncate(error));
    taskRepository.save(task);
  }

  /**
   * 根据 ID 获取任务实体，不存在则抛出异常
   *
   * <p>由所有需要读取或更新单个任务的方法调用，
   * 统一把 Repository 的空结果转换为 {@link BusinessException}。
   *
   * @param id 异步任务 ID
   * @return AsyncTaskEntity
   * @throws BusinessException 任务不存在时抛出 {@code NOT_FOUND}
   */
  private AsyncTaskEntity getEntity(Long id) {
    return taskRepository.findById(id)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "异步任务不存在"));
  }

  /**
   * 构建手动重试时发送到 Redis Stream 的消息体
   *
   * <p>仅由 {@link #retryTask(Long)} 调用。
   * 根据任务类型选择业务 ID 字段，并调用
   * {@link #withBusinessField(Map, String, String)} 合并公共字段。
   * 本方法只构造消息，不访问 Redis。
   *
   * 根据 taskType 选择对应的业务字段名：
   * - RESUME_ANALYZE → resumeId
   * - KB_VECTORIZE → kbId
   * - INTERVIEW_EVALUATE → sessionId
   * - VOICE_INTERVIEW_EVALUATE → voiceSessionId
   *
   * 消息公共字段：taskId、businessId、retryCount（重置为 "0"）。
   *
   * @param task 异步任务实体
   * @return 消息键值对 Map（LinkedHashMap 保持插入顺序）
   */
  private Map<String, String> buildRetryMessage(AsyncTaskEntity task) {
    String businessId = task.getBusinessId();
    Map<String, String> common = Map.of(
        AsyncTaskStreamConstants.FIELD_TASK_ID, task.getId().toString(),
        AsyncTaskStreamConstants.FIELD_BUSINESS_ID, businessId,
        AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"
    );
    return switch (task.getTaskType()) {
      case RESUME_ANALYZE -> withBusinessField(
          common,
          AsyncTaskStreamConstants.FIELD_RESUME_ID,
          businessId
      );
      case KB_VECTORIZE -> withBusinessField(
          common,
          AsyncTaskStreamConstants.FIELD_KB_ID,
          businessId
      );
      case INTERVIEW_EVALUATE -> withBusinessField(
          common,
          AsyncTaskStreamConstants.FIELD_SESSION_ID,
          businessId
      );
      case VOICE_INTERVIEW_EVALUATE -> withBusinessField(
          common,
          AsyncTaskStreamConstants.FIELD_VOICE_SESSION_ID,
          businessId
      );
    };
  }

  /**
   * 将业务字段追加到公共消息 Map 中
   *
   * <p>仅由 {@link #buildRetryMessage(AsyncTaskEntity)} 的 switch 分支调用，
   * 用于在不修改不可变公共 Map 的前提下追加任务类型专属字段。
   *
   * 使用 LinkedHashMap 保持字段插入顺序，便于调试和日志查看。
   *
   * @param common      公共字段 Map
   * @param fieldName   业务字段名
   * @param businessId  业务对象 ID
   * @return 合并后的消息 Map（LinkedHashMap）
   */
  private Map<String, String> withBusinessField(
      Map<String, String> common,
      String fieldName,
      String businessId
  ) {
    LinkedHashMap<String, String> message = new LinkedHashMap<>(common);
    message.put(fieldName, businessId);
    return message;
  }

  /**
   * 生成活跃任务唯一标识键
   *
   * <p>由新任务创建、入队、开始处理、自动重试和手动重试调用，
   * 生成值写入数据库唯一索引字段，用于约束同一业务对象的活跃任务。
   *
   * 格式：taskType:businessId（如 RESUME_ANALYZE:123）
   * 用于 createOrReusePendingTask 的去重查询和活跃状态管理。
   *
   * @param taskType   异步任务类型
   * @param businessId 业务对象 ID
   * @return 活跃键字符串
   */
  private String activeKey(AsyncTaskType taskType, String businessId) {
    return taskType.name() + ":" + businessId;
  }

  /**
   * 截断过长的错误消息
   *
   * <p>仅由 {@link #finishTask(Long, AsyncTaskStatusDTO, String)} 调用，
   * 保证失败、取消和死信原因不会超过持久化长度上限。
   *
   * 防止错误信息超过数据库字段限制（MAX_ERROR_LENGTH=1000）。
   * null 输入返回 null，不超长时原样返回。
   *
   * @param error 原始错误消息
   * @return 截断后的错误消息
   */
  private String truncate(String error) {
    if (error == null || error.length() <= MAX_ERROR_LENGTH) {
      return error;
    }
    return error.substring(0, MAX_ERROR_LENGTH);
  }
}
