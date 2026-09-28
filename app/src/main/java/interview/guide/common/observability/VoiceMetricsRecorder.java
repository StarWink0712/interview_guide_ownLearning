/**
 * 语音面试指标记录器
 *
 * 基于 Micrometer MeterRegistry 记录语音面试场景的通用性能指标。
 * 提供 Timer（耗时记录）和 Counter（计数递增）两种通用记录方法，
 * 调用方可灵活指定指标名称和标签键值对，适用于语音面试中 ASR 语音识别、
 * TTS 语音合成、WebSocket 连接管理、多轮评估等各类场景的指标采集。
 *
 * 设计要点：
 * <ul>
 *   <li>通用化设计：不绑定特定业务场景，通过 metricName 参数和 key=value 格式的
 *       tags 可变参数实现任意指标的自由组合记录</li>
 *   <li>双时间格式：recordTimerSinceNanos 使用纳秒精度的时间戳差值，适用于
 *       高精度耗时记录；recordTimerMillis 直接接收毫秒值，适用于外部已计算好
 *       耗时的场景</li>
 *   <li>负值保护：Timer 记录的耗时使用 Math.max(0, ...) 确保不会记录负数，
 *       防止因时钟异常或计算错误导致指标数据异常</li>
 *   <li>优雅降级：当 Micrometer 不可用时（meterRegistry 为 null）静默跳过，
 *       所有记录操作不抛异常</li>
 * </ul>
 *
 * 标签格式说明：
 * <p>tags 参数采用可变参数 String... tags，调用时按 key1, value1, key2, value2 的顺序
 * 传入。Micrometer 会将相邻两个字符串自动解析为 key=value 标签对。
 * 调用方需确保传入偶数个字符串，否则 Micrometer 会记录警告日志并用默认值填充。
 *
 * 典型应用场景：
 * <ul>
 *   <li>ASR 语音识别：recordTimerSinceNanos("app.voice.asr.latency", startNanos, "lang", "zh")</li>
 *   <li>TTS 语音合成：recordTimerMillis("app.voice.tts.latency", audioDuration, "voice", "female")</li>
 *   <li>WebSocket 连接：incrementCounter("app.voice.ws.connected", "session_id", sessionId)</li>
 *   <li>多轮对话评估：recordTimerSinceNanos("app.voice.evaluation.latency", startNanos, "round", "3")</li>
 *   <li>异常计数：incrementCounter("app.voice.error", "type", "asr_timeout")</li>
 * </ul>
 *
 * 联动关系：
 * <ul>
 *   <li>被 VoiceInterviewService 调用：记录 WebSocket 语音通话各环节的性能指标</li>
 *   <li>被 AsrService/TtsService 调用：记录语音识别和语音合成的延迟和成功率</li>
 *   <li>依赖 Micrometer MeterRegistry：与 Prometheus/Grafana 等监控系统集成</li>
 * </ul>
 */
package interview.guide.common.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class VoiceMetricsRecorder {

  /** Micrometer 指标注册中心，用于创建和管理 Timer/Counter */
  private final MeterRegistry meterRegistry;

  /**
   * 构造函数：通过可选注入获取 MeterRegistry
   *
   * 使用 required = false 实现优雅降级：在缺少 Micrometer 的运行环境中
   * 不会启动失败，所有记录操作静默跳过。
   *
   * @param meterRegistry Micrometer 指标注册中心，不可用时为 null
   */
  public VoiceMetricsRecorder(@Autowired(required = false) MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  /**
   * 基于纳秒时间戳记录 Timer 延迟指标
   *
   * 调用方在操作开始时记录 System.nanoTime()，操作完成后调用此方法。
   * 方法内部自动计算耗时（当前纳秒 - 起始纳秒）并记录到指定名称的 Timer 中。
   *
   * 优点：精确度高、不依赖外部计时、支持通过标签细粒度分类。
   *
   * @param metricName 指标名称（如 "app.voice.asr.latency"）
   * @param startNanos 操作开始时的 System.nanoTime() 值
   * @param tags       Micrometer 标签键值对，格式为 key1, value1, key2, value2, ...
   *                   数量必须为偶数，例如 "session_id", "abc123", "round", "3"
   */
  public void recordTimerSinceNanos(String metricName, long startNanos, String... tags) {
    if (meterRegistry == null) {
      return;
    }
    meterRegistry.timer(metricName, tags)
        .record(Math.max(0, System.nanoTime() - startNanos), TimeUnit.NANOSECONDS);
  }

  /**
   * 直接记录已知毫秒值的 Timer 延迟指标
   *
   * 适用于调用方已经计算出耗时的场景（例如从外部 API 响应中获取的耗时值、
   * 已通过其他方式计算的持续时间）。
   *
   * @param metricName 指标名称（如 "app.voice.tts.latency"）
   * @param millis     已计算好的耗时值，单位毫秒
   * @param tags       Micrometer 标签键值对，格式为 key1, value1, key2, value2, ...
   *                   数量必须为偶数
   */
  public void recordTimerMillis(String metricName, long millis, String... tags) {
    if (meterRegistry == null) {
      return;
    }
    meterRegistry.timer(metricName, tags).record(Math.max(0, millis), TimeUnit.MILLISECONDS);
  }

  /**
   * 递增指定名称的 Counter 计数器
   *
   * 用于记录事件发生次数，如 WebSocket 连接数、错误发生次数、API 调用次数等。
   * Counter 是单调递增的累加器，适用于累计计数而非瞬时值。
   *
   * @param metricName 指标名称（如 "app.voice.ws.connected"、"app.voice.error"）
   * @param tags       Micrometer 标签键值对，格式为 key1, value1, key2, value2, ...
   *                   数量必须为偶数
   */
  public void incrementCounter(String metricName, String... tags) {
    if (meterRegistry == null) {
      return;
    }
    meterRegistry.counter(metricName, tags).increment();
  }
}
