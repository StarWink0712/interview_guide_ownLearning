package interview.guide.testsupport;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Testcontainers 集成测试基类。
 *
 * <p>使用真实 PostgreSQL + pgvector、Redis、MinIO，同时通过 JUnit tag 与普通 H2
 * 单元测试隔离。若本机没有 Docker，Testcontainers 会跳过 tagged container 测试。
 */
@Tag("integration")
@Tag("container")
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@SpringBootTest(properties = {
    "spring.main.lazy-initialization=true",
    "spring.jpa.hibernate.ddl-auto=update"
})
public abstract class BaseIntegrationTest {

  private static final DockerImageName PGVECTOR_IMAGE = DockerImageName
      .parse("pgvector/pgvector:pg16")
      .asCompatibleSubstituteFor("postgres");

  private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7.2-alpine");

  private static final DockerImageName MINIO_IMAGE = DockerImageName
      .parse("minio/minio:RELEASE.2024-07-16T23-46-41Z");

  private static final String MINIO_ACCESS_KEY = "minioadmin";
  private static final String MINIO_SECRET_KEY = "minioadmin";
  private static final String TEST_BUCKET = "interview-guide-test";

  @Container
  protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PGVECTOR_IMAGE)
      .withDatabaseName("interview_guide_test")
      .withUsername("test")
      .withPassword("test");

  @Container
  protected static final GenericContainer<?> REDIS = new GenericContainer<>(REDIS_IMAGE)
      .withExposedPorts(6379);

  @Container
  protected static final GenericContainer<?> MINIO = new GenericContainer<>(MINIO_IMAGE)
      .withEnv("MINIO_ROOT_USER", MINIO_ACCESS_KEY)
      .withEnv("MINIO_ROOT_PASSWORD", MINIO_SECRET_KEY)
      .withCommand("server", "/data")
      .withExposedPorts(9000)
      .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000).forStatusCode(200));

  @DynamicPropertySource
  static void registerContainerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("spring.jpa.properties.hibernate.dialect",
        () -> "org.hibernate.dialect.PostgreSQLDialect");

    registry.add("spring.redis.redisson.config", BaseIntegrationTest::redissonConfig);

    registry.add("app.storage.endpoint", BaseIntegrationTest::minioEndpoint);
    registry.add("app.storage.access-key", () -> MINIO_ACCESS_KEY);
    registry.add("app.storage.secret-key", () -> MINIO_SECRET_KEY);
    registry.add("app.storage.bucket", () -> TEST_BUCKET);
    registry.add("app.storage.region", () -> "us-east-1");
  }

  private static String redissonConfig() {
    return """
        singleServerConfig:
          address: "redis://%s:%d"
          database: 0
        """.formatted(REDIS.getHost(), REDIS.getMappedPort(6379));
  }

  private static String minioEndpoint() {
    return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
  }
}
