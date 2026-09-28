package interview.guide.common.ai;

import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;
import java.util.Locale;

/**
 * Provider 级 AI 观测通用工具类。
 *
 * <p>功能概述：
 * <ul>
 *   <li>统一处理指标标签里的空值，避免 Micrometer 出现 null tag</li>
 *   <li>统一截断错误信息，避免异常堆栈撑大数据库 TEXT 字段</li>
 *   <li>将 HTTP 状态码归类为 2xx/4xx/5xx/429 等低基数标签</li>
 *   <li>将底层异常归类为 timeout/network/transient_ai 等稳定错误分类</li>
 * </ul>
 *
 * <p>设计原因：
 * 指标系统不适合使用高基数字段，例如完整异常消息、完整 URL、动态业务 ID。
 * 所以这里把状态码和异常类型做成有限枚举风格的字符串，方便后续在
 * Prometheus/Grafana 中按 provider、model、errorCategory 做聚合和告警。
 *
 * <p>联动关系：
 * <ul>
 *   <li>被 AiProviderHttpObservationInterceptor 用于普通 call() 的 HTTP attempt 记录</li>
 *   <li>被 AiProviderWebClientObservationFilter 用于 stream() 的 HTTP attempt 记录</li>
 *   <li>被 AiProviderObservationAdvisor 用于最终 invocation 错误分类</li>
 *   <li>被 AiProviderRetryTemplateFactory 用于 RetryListener 异常类型记录</li>
 * </ul>
 */
final class AiProviderObservationSupport {

  private AiProviderObservationSupport() {}

  /** 将空 provider/model/variant 等标签统一归为 unknown，保证指标标签稳定。 */
  static String safe(String value) {
    return value == null || value.isBlank() ? "unknown" : value;
  }

  /** 截断长文本，主要用于错误消息和 URL，避免日志表单行过大。 */
  static String truncate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, maxLength);
  }

  /**
   * 将 HTTP 状态码转为低基数 bucket。
   *
   * <p>示例：
   * 200 -> 2xx，401 -> 4xx，500 -> 5xx，429 单独保留为 429。
   * status 为 null 说明请求在拿到 HTTP 响应前就失败了，例如连接超时。
   */
  static String httpStatusBucket(Integer status) {
    if (status == null) {
      return "exception";
    }
    if (status == 429) {
      return "429";
    }
    return (status / 100) + "xx";
  }

  /** HTTP 2xx/3xx 视为底层请求成功，4xx/5xx 视为 Provider 调用失败。 */
  static boolean isHttpSuccess(int statusCode) {
    return statusCode >= 200 && statusCode < 400;
  }

  /**
   * 判断当前 HTTP 状态在默认 Spring AI RetryTemplate 下是否属于可重试方向。
   *
   * <p>当前项目沿用 Spring AI 默认错误处理：5xx 会转成 TransientAiException，
   * 4xx 会转成 NonTransientAiException。因此这里将 5xx 标记为 retryable，
   * 429 虽然语义上常见于限流，但默认错误处理下不会自动重试，后续如果自定义
   * 429 重试策略，再同步调整这里即可。
   */
  static boolean isRetryableHttpStatus(int statusCode) {
    return statusCode >= 500;
  }

  /** 返回异常全限定类名，便于在数据库和指标中定位具体异常来源。 */
  static String exceptionType(Throwable error) {
    if (error == null) {
      return null;
    }
    return error.getClass().getName();
  }

  /** 提取并截断异常消息，避免把完整堆栈或超长响应体写入观测表。 */
  static String errorMessage(Throwable error) {
    if (error == null) {
      return null;
    }
    return truncate(error.getMessage(), 1000);
  }

  /**
   * 按异常类型归类错误。
   *
   * <p>这里不直接用异常类名做 errorCategory，是因为异常类名过细，不适合做监控大盘。
   * 例如 SocketTimeoutException 和某些 WebClient 超时异常都应该被归到 timeout。
   */
  static String errorCategory(Throwable error) {
    if (error == null) {
      return null;
    }
    Throwable root = rootCause(error);
    if (root instanceof SocketTimeoutException) {
      return "timeout";
    }
    if (root instanceof ResourceAccessException) {
      return "network";
    }
    if (root instanceof TransientAiException) {
      return "transient_ai";
    }
    if (root instanceof NonTransientAiException) {
      return "non_transient_ai";
    }
    String simpleName = root.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    if (simpleName.contains("timeout")) {
      return "timeout";
    }
    if (simpleName.contains("webclientresponse")) {
      return "http_error";
    }
    return "exception";
  }

  /**
   * 优先按 HTTP 状态码归类，拿不到状态码时再按异常类型归类。
   *
   * <p>普通 HTTP 失败可能同时有状态码和异常；状态码更能体现 Provider 返回了什么，
   * 所以优先用状态码判断 provider_5xx、client_4xx、rate_limited。
   */
  static String errorCategory(Integer httpStatus, Throwable error) {
    if (httpStatus != null) {
      if (httpStatus == 429) {
        return "rate_limited";
      }
      if (httpStatus >= 500) {
        return "provider_5xx";
      }
      if (httpStatus >= 400) {
        return "client_4xx";
      }
      return null;
    }
    return errorCategory(error);
  }

  /** 找到最底层异常，用于排除包装异常对分类的干扰。 */
  private static Throwable rootCause(Throwable error) {
    Throwable result = error;
    while (result.getCause() != null && result.getCause() != result) {
      result = result.getCause();
    }
    return result;
  }
}
