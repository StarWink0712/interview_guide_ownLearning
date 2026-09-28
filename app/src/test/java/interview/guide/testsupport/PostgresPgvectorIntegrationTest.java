package interview.guide.testsupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PostgreSQL pgvector 容器集成测试")
class PostgresPgvectorIntegrationTest extends BaseIntegrationTest {

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("pgvector 扩展可创建并执行向量距离计算")
  void pgvectorExtensionCanRunVectorDistanceQuery() {
    jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");

    Integer extensionCount = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM pg_extension WHERE extname = 'vector'",
        Integer.class
    );
    Double distance = jdbcTemplate.queryForObject(
        "SELECT ('[1,2,3]'::vector <=> '[1,2,4]'::vector)::float8",
        Double.class
    );

    assertThat(extensionCount).isEqualTo(1);
    assertThat(distance).isNotNull().isGreaterThanOrEqualTo(0.0);
  }
}
