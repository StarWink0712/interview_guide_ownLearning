package interview.guide.modules.asynctask.model;
// 异步任务入队结果，包含任务DTO和是否创建成功
public record AsyncTaskEnqueueResult(
    AsyncTaskDTO task,
    boolean created
) {
}
