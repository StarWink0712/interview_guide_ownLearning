/**
 * 异步任务管理 Controller
 *
 * 提供异步任务的查询、重试和取消接口，作为前端管理后台的操作入口。
 * 所有请求路径遵循 RESTful 风格：/api/async-tasks。
 * 支持按任务类型（RESUME_ANALYZE / KB_VECTORIZE / INTERVIEW_EVALUATE /
 * VOICE_INTERVIEW_EVALUATE）和状态（PENDING / PROCESSING / COMPLETED /
 * FAILED / CANCELLED / DEAD_LETTER）组合过滤查询，
 * 以及单个任务的详情查看、手动重试和手动取消操作。
 *
 * 联动关系：
 * - 依赖 AsyncTaskService 处理任务查询、重试和取消
 * - 前端管理页面通过此 Controller 查看任务状态并触发人工操作
 * - 异步任务的实际执行由各个 Stream Consumer 负责
 * - 返回体统一使用 {@code Result<T>} 包装，异常由全局异常处理器转换
 */
package interview.guide.modules.asynctask;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.result.Result;
import interview.guide.modules.asynctask.model.AsyncTaskDTO;
import interview.guide.modules.asynctask.model.AsyncTaskStatusDTO;
import interview.guide.modules.asynctask.model.AsyncTaskType;
import interview.guide.modules.asynctask.service.AsyncTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 异步任务管理 Controller
 * 为前端管理后台提供异步任务的查询、重试和取消入口
 */
@RestController
@RequiredArgsConstructor
public class AsyncTaskController {

  /** 异步任务管理服务，提供任务的全生命周期管理能力 */
  private final AsyncTaskService asyncTaskService;

  /**
   * 查询异步任务列表
   *
   * <p>调用入口：前端异步任务总览页通过 {@code GET /api/async-tasks} 调用。
   * {@code taskType} 和 {@code status} 均为可选参数，可单独使用或组合过滤。
   *
   * <p>主要调用：委托
   * {@link AsyncTaskService#listTasks(AsyncTaskType, AsyncTaskStatusDTO)}
   * 选择对应的 Repository 查询，结果按创建时间倒序返回。
   * 本方法不修改任务状态。
   *
   * @param taskType 异步任务类型，可选
   * @param status 任务状态，可选
   * @return 包含过滤后任务 DTO 列表的统一响应
   */
  @GetMapping("/api/async-tasks")
  public Result<List<AsyncTaskDTO>> listTasks(
      @RequestParam(value = "taskType", required = false) AsyncTaskType taskType,
      @RequestParam(value = "status", required = false) AsyncTaskStatusDTO status
  ) {
    return Result.success(asyncTaskService.listTasks(taskType, status));
  }

  /**
   * 根据 ID 查询单个异步任务详情
   *
   * <p>调用入口：前端打开任务详情面板时，通过
   * {@code GET /api/async-tasks/{id}} 调用。
   *
   * <p>主要调用：委托 {@link AsyncTaskService#getTask(Long)} 查询并转换 DTO；
   * 任务不存在时，Service 抛出的业务异常
   * 交由全局异常处理器转换为统一响应。
   *
   * @param id 异步任务数据库主键
   * @return 包含任务详情 DTO 的统一响应
   * @throws BusinessException 任务不存在时抛出
   */
  @GetMapping("/api/async-tasks/{id}")
  public Result<AsyncTaskDTO> getTask(@PathVariable Long id) {
    return Result.success(asyncTaskService.getTask(id));
  }

  /**
   * 手动重试异步任务
   *
   * <p>调用入口：运维人员通过
   * {@code POST /api/async-tasks/{id}/retry} 重新执行失败、取消或死信任务。
   *
   * <p>主要调用：委托 {@link AsyncTaskService#retryTask(Long)} 校验状态，
   * 重置重试信息并向原 Redis Stream 重新写入消息。
   * 该接口会同时修改数据库和 Redis。
   *
   * @param id 异步任务数据库主键
   * @return 包含重试后任务 DTO 的统一响应
   * @throws BusinessException 任务不存在或当前状态不允许重试时抛出
   */
  @PostMapping("/api/async-tasks/{id}/retry")
  public Result<AsyncTaskDTO> retryTask(@PathVariable Long id) {
    return Result.success(asyncTaskService.retryTask(id));
  }

  /**
   * 手动取消异步任务
   *
   * <p>调用入口：运维人员通过
   * {@code POST /api/async-tasks/{id}/cancel} 取消等待中或处理中的任务。
   *
   * <p>主要调用：委托 {@link AsyncTaskService#cancelTask(Long)} 校验状态，
   * 将任务标记为 {@code CANCELLED} 并清除活跃键。该操作只更新持久化状态，
   * 不会强制中断已经开始执行的消费者线程，
   * 也不会直接 ACK 或删除 Stream 消息。
   *
   * @param id 异步任务数据库主键
   * @return 包含取消后任务 DTO 的统一响应
   * @throws BusinessException 任务不存在或当前状态不允许取消时抛出
   */
  @PostMapping("/api/async-tasks/{id}/cancel")
  public Result<AsyncTaskDTO> cancelTask(@PathVariable Long id) {
    return Result.success(asyncTaskService.cancelTask(id));
  }
}
