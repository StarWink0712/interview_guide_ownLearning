package interview.guide.common.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("AI 指标记录器测试")
class AiMetricsRecorderTest {

  @Test
  @DisplayName("MeterRegistry 缺失时不影响业务")
  void noopsWhenMeterRegistryMissing() {
    AiMetricsRecorder recorder = new AiMetricsRecorder(null);

    assertThatCode(() -> recorder.recordStructuredInvocation(
        "简历评分",
        "dashscope",
        "qwen",
        true,
        1,
        System.nanoTime()
    )).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("记录调用次数、延迟和重试次数")
  void recordsInvocationAndRetryMetrics() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    AiMetricsRecorder recorder = new AiMetricsRecorder(registry);

    recorder.recordStructuredInvocation(
        "简历评分",
        "dashscope",
        "qwen",
        true,
        3,
        System.nanoTime()
    );

    assertThat(registry.get("app.ai.invocation.count").counter().count()).isEqualTo(1);
    assertThat(registry.get("app.ai.structured_output.retry.count").counter().count())
        .isEqualTo(2);
    assertThat(registry.get("app.ai.invocation.latency").timer().count()).isEqualTo(1);
  }
}
