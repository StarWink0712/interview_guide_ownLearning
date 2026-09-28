package interview.guide.common.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("RAG 指标记录器测试")
class RagMetricsRecorderTest {

  @Test
  @DisplayName("MeterRegistry 缺失时不影响业务")
  void noopsWhenMeterRegistryMissing() {
    RagMetricsRecorder recorder = new RagMetricsRecorder(null);

    assertThatCode(() -> {
      recorder.recordRewrite(true, true, System.nanoTime());
      recorder.recordRetrieval(true, 8, 0, true, System.nanoTime());
      recorder.recordAnswer(true, false, System.nanoTime());
    }).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("记录检索命中数和无结果次数")
  void recordsRetrievalAndNoResultMetrics() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RagMetricsRecorder recorder = new RagMetricsRecorder(registry);

    recorder.recordRetrieval(true, 8, 0, true, System.nanoTime());

    assertThat(registry.get("app.rag.retrieval.latency").timer().count()).isEqualTo(1);
    assertThat(registry.get("app.rag.retrieval.hit.count").summary().count()).isEqualTo(1);
    assertThat(registry.get("app.rag.no_result.count").counter().count()).isEqualTo(1);
  }
}
