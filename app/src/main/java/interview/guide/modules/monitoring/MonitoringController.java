/**
 * 业务监控控制层
 *
 * 提供系统核心业务指标的概览接口，用于管理后台的 Dashboard 监控面板展示。
 * 当前只包含一个端点，返回四大业务域（异步任务、AI 调用、RAG 检索、语音面试）
 * 在过去 24 小时的 7 个关键指标。
 *
 * 设计要点：
 * <ul>
 *   <li>Controller 层仅做路由和委托，不包含任何业务逻辑</li>
 *   <li>返回 Result&lt;T&gt; 统一响应包装</li>
 *   <li>所有指标在 BusinessMetricsQueryService 中一次性查询和计算完成</li>
 * </ul>
 *
 * 联动关系：
 * <ul>
 *   <li>依赖 BusinessMetricsQueryService：聚合多个数据源的业务指标</li>
 *   <li>前端页面：MonitoringPage（管理后台监控面板）</li>
 *   <li>数据来源：AsyncTaskRepository、AiInvocationLogRepository、
 *       RagRetrievalLogRepository、MeterRegistry 四个数据源</li>
 * </ul>
 */
package interview.guide.modules.monitoring;

import interview.guide.common.result.Result;
import interview.guide.modules.monitoring.model.BusinessMetricsSummaryDTO;
import interview.guide.modules.monitoring.service.BusinessMetricsQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class MonitoringController {

  /** 业务指标查询服务，聚合异步任务、AI 调用、RAG 检索、语音面试等数据源的统计 */
  private final BusinessMetricsQueryService businessMetricsQueryService;

  /**
   * 获取业务概览指标
   *
   * 返回系统最近 24 小时的核心业务健康指标，包括：
   * <ul>
   *   <li>异步任务：新增任务总数、失败任务数</li>
   *   <li>AI 调用：调用次数、成功率</li>
   *   <li>RAG 检索：查询次数、平均命中文档块数</li>
   *   <li>语音面试：平均轮次延迟</li>
   * </ul>
   *
   * 调用方可根据返回的指标判断系统整体运行状况。
   *
   * @return 包含业务概览指标的统一响应
   */
  @GetMapping("/api/monitoring/business-summary")
  public Result<BusinessMetricsSummaryDTO> getBusinessSummary() {
    return Result.success(businessMetricsQueryService.getBusinessSummary());
  }
}
