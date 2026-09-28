package interview.guide.modules.monitoring.model;

import java.time.LocalDateTime;

public record BusinessMetricsSummaryDTO(
    LocalDateTime since,
    long recentTaskCount,
    long failedTaskCount,
    long aiInvocationCount,
    double aiSuccessRate,
    long ragQueryCount,
    double ragAverageHitCount,
    double voiceAverageTurnLatencyMs
) {
}
