package interview.guide.modules.interview.adaptive;

import interview.guide.modules.interview.adaptive.model.AbilityDimensionDTO;
import interview.guide.modules.interview.adaptive.model.AbilityProfileDTO;
import interview.guide.modules.interview.adaptive.model.TurnEvaluationDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("面试能力画像服务测试")
class AbilityProfileServiceTest {

  private final AbilityProfileService service = new AbilityProfileService(new ObjectMapper());

  @Test
  @DisplayName("首次回答会创建对应能力维度")
  void firstTurnCreatesAbilityDimension() {
    AbilityProfileDTO profile = service.updateProfile(null, new TurnEvaluationDTO(
        0,
        60,
        2,
        List.of("索引失效", "执行计划"),
        "追问索引优化",
        "MySQL"
    ));

    assertThat(profile.averageScore()).isEqualTo(60);
    assertThat(profile.dimensions()).hasSize(1);
    AbilityDimensionDTO dimension = profile.dimensions().getFirst();
    assertThat(dimension.category()).isEqualTo("MySQL");
    assertThat(dimension.questionCount()).isEqualTo(1);
    assertThat(dimension.recentPerformance()).isEqualTo("一般");
    assertThat(dimension.weakKeywords()).containsExactly("索引失效", "执行计划");
  }

  @Test
  @DisplayName("多轮回答后按维度滚动平均并合并薄弱关键词")
  void multipleTurnsUpdateRollingProfile() {
    AbilityProfileDTO firstProfile = service.updateProfile(null, new TurnEvaluationDTO(
        0,
        60,
        2,
        List.of("索引失效"),
        "追问索引",
        "MySQL"
    ));

    AbilityProfileDTO updatedProfile = service.updateProfile(
        service.toJson(firstProfile),
        new TurnEvaluationDTO(
            1,
            80,
            4,
            List.of("慢查询", "索引失效"),
            "追问 Explain",
            "MySQL"
        )
    );

    AbilityDimensionDTO mysql = updatedProfile.dimensions().getFirst();
    assertThat(mysql.score()).isEqualTo(70);
    assertThat(mysql.questionCount()).isEqualTo(2);
    assertThat(mysql.recentPerformance()).isEqualTo("稳定");
    assertThat(mysql.weakKeywords()).containsExactly("索引失效", "慢查询");
    assertThat(updatedProfile.summary()).contains("MySQL");
  }
}
