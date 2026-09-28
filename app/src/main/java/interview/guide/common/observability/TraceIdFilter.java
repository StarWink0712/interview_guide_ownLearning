/**
 * 全链路追踪 ID 过滤器
 *
 * 为每个 HTTP 请求生成或传递唯一的 Trace ID，注入到 SLF4J 的 MDC（Mapped Diagnostic Context）
 * 中，使得同一次请求的所有日志自动携带相同的 traceId 字段，方便在分布式日志系统中关联
 * 和追踪完整的调用链路。
 *
 * 设计要点：
 * <ul>
 *   <li>继承 OncePerRequestFilter：保证每个请求只执行一次过滤逻辑，避免 Forward/Include 重复执行</li>
 *   <li>传递已有 Trace ID：如果请求头中已携带 X-Trace-Id（由上游服务或前端传入），则复用该值，
 *       确保跨服务的全链路追踪连续性</li>
 *   <li>自动生成 Trace ID：如果请求头中不存在，则使用 UUID 生成新的唯一标识</li>
 *   <li>响应头透传：将 Trace ID 写入响应头 X-Trace-Id，方便前端或调用方获取本次请求的追踪 ID</li>
 *   <li>Finally 清理：无论请求处理成功还是失败，都在 finally 块中清除 MDC，防止线程池复用时
 *       Trace ID 泄露到其他请求</li>
 * </ul>
 *
 * 联动关系：
 * <ul>
 *   <li>注入 SLF4J MDC：所有使用 SLF4J 的日志输出将自动包含 traceId 字段（需配置日志格式包含 %X{traceId}）</li>
 *   <li>被 Spring Boot 自动发现：@Component 注解使其作为 Filter Bean 自动注册到过滤器链</li>
 *   <li>被其他组件静态调用：通过 TraceIdFilter.currentTraceId() 可在任意位置获取当前请求的 Trace ID</li>
 * </ul>
 *
 * 使用示例：
 * <pre>
 *   // 在任意 Service 或工具类中获取当前 Trace ID
 *   String traceId = TraceIdFilter.currentTraceId().orElse("N/A");
 *   log.info("处理请求: traceId={}, userId={}", traceId, userId);
 * </pre>
 */
package interview.guide.common.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

@Component
public class TraceIdFilter extends OncePerRequestFilter {

  /** HTTP 请求头名称，用于在上下游服务之间传递 Trace ID */
  public static final String TRACE_ID_HEADER = "X-Trace-Id";

  /** SLF4J MDC 中的键名，日志格式中通过 %X{traceId} 引用该值 */
  public static final String TRACE_ID_MDC_KEY = "traceId";

  /**
   * 获取当前线程关联的 Trace ID（静态便捷方法）
   *
   * 可在任意位置（Service、Controller、工具类等）调用，获取当前请求的追踪 ID。
   * 如果当前线程未经过 TraceIdFilter（如异步任务、定时任务），则返回 Optional.empty()。
   *
   * @return 包含 Trace ID 的 Optional，当前线程无 Trace ID 时为 Optional.empty()
   */
  public static Optional<String> currentTraceId() {
    return Optional.ofNullable(MDC.get(TRACE_ID_MDC_KEY))
        .filter(traceId -> !traceId.isBlank());
  }

  /**
   * 过滤器核心逻辑：解析/生成 Trace ID → 注入 MDC → 执行请求 → 清理 MDC
   *
   * 执行流程：
   * <ol>
   *   <li>从请求头解析 Trace ID（有则复用，无则生成 UUID）</li>
   *   <li>将 Trace ID 写入 SLF4J MDC，后续日志自动携带</li>
   *   <li>将 Trace ID 写入响应头，方便前端/调用方获取</li>
   *   <li>调用 filterChain.doFilter() 继续执行后续过滤器和 Controller</li>
   *   <li>在 finally 块中清除 MDC，防止线程池复用导致数据泄露</li>
   * </ol>
   *
   * @param request     HTTP 请求对象
   * @param response    HTTP 响应对象
   * @param filterChain 过滤器链，用于将请求传递给下一个过滤器或目标 Servlet
   * @throws ServletException Servlet 处理异常
   * @throws IOException      I/O 异常
   */
  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain
  ) throws ServletException, IOException {
    String traceId = resolveTraceId(request);
    MDC.put(TRACE_ID_MDC_KEY, traceId);
    response.setHeader(TRACE_ID_HEADER, traceId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(TRACE_ID_MDC_KEY);
    }
  }

  /**
   * 解析请求中的 Trace ID
   *
   * 解析策略：
   * <ol>
   *   <li>优先从请求头 X-Trace-Id 中获取（支持上游服务或前端传入）</li>
   *   <li>如果请求头不存在或为空，使用 UUID.randomUUID() 生成新的 Trace ID</li>
   *   <li>对从请求头获取的值执行 trim() 去除首尾空白字符</li>
   * </ol>
   *
   * @param request HTTP 请求对象
   * @return 有效的 Trace ID 字符串，保证非空
   */
  private String resolveTraceId(HttpServletRequest request) {
    String incoming = request.getHeader(TRACE_ID_HEADER);
    if (incoming != null && !incoming.isBlank()) {
      return incoming.trim();
    }
    return UUID.randomUUID().toString();
  }
}
