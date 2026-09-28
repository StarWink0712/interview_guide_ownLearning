package interview.guide.common.observability;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TraceId 过滤器测试")
class TraceIdFilterTest {

  private final TraceIdFilter filter = new TraceIdFilter();

  @Test
  @DisplayName("请求无 traceId 时自动生成并写入响应头和 MDC")
  void generatesTraceIdWhenHeaderMissing() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");
    MockHttpServletResponse response = new MockHttpServletResponse();

    FilterChain chain = (servletRequest, servletResponse) -> {
      assertThat(MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY)).isNotBlank();
      assertThat(((MockHttpServletResponse) servletResponse)
          .getHeader(TraceIdFilter.TRACE_ID_HEADER)).isNotBlank();
    };

    filter.doFilter(request, response, chain);

    assertThat(response.getHeader(TraceIdFilter.TRACE_ID_HEADER)).isNotBlank();
    assertThat(MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY)).isNull();
  }

  @Test
  @DisplayName("请求已有 traceId 时沿用原值")
  void reusesIncomingTraceId() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");
    MockHttpServletResponse response = new MockHttpServletResponse();
    request.addHeader(TraceIdFilter.TRACE_ID_HEADER, "trace-from-client");

    filter.doFilter(request, response, (servletRequest, servletResponse) ->
        assertThat(MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY)).isEqualTo("trace-from-client"));

    assertThat(response.getHeader(TraceIdFilter.TRACE_ID_HEADER)).isEqualTo("trace-from-client");
    assertThat(MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY)).isNull();
  }
}
