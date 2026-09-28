package interview.guide;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
class AppTest {

  @Test
  @DisplayName("应用上下文可以正常启动")
  void contextLoads() {
  }
}
