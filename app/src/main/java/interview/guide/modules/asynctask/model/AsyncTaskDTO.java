package interview.guide.modules.asynctask.model;

import java.time.LocalDateTime;
// 异步任务DTO
public record AsyncTaskDTO(
    Long id,
    AsyncTaskType taskType,
    String businessId,
    String streamKey,
    String messageId,
    AsyncTaskStatusDTO status,
    Integer retryCount,
    Integer maxRetryCount,
    LocalDateTime nextRetryAt,
    String lastError,
    String payloadJson,
    Long parentTaskId,
    Boolean activeFlag,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
}
