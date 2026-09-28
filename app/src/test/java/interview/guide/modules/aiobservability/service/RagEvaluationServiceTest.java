package interview.guide.modules.aiobservability.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.modules.aiobservability.model.AddRagEvalCaseRequest;
import interview.guide.modules.aiobservability.model.CreateRagEvalDatasetRequest;
import interview.guide.modules.aiobservability.model.RagEvalCaseDTO;
import interview.guide.modules.aiobservability.model.RagEvalCaseEntity;
import interview.guide.modules.aiobservability.model.RagEvalDatasetDTO;
import interview.guide.modules.aiobservability.model.RagEvalDatasetEntity;
import interview.guide.modules.aiobservability.model.RagEvalRunCaseResultEntity;
import interview.guide.modules.aiobservability.model.RagEvalRunDTO;
import interview.guide.modules.aiobservability.model.RagEvalRunEntity;
import interview.guide.modules.aiobservability.model.RagEvalRunStatus;
import interview.guide.modules.aiobservability.model.RunRagEvalRequest;
import interview.guide.modules.aiobservability.repository.RagEvalCaseRepository;
import interview.guide.modules.aiobservability.repository.RagEvalDatasetRepository;
import interview.guide.modules.aiobservability.repository.RagEvalRunCaseResultRepository;
import interview.guide.modules.aiobservability.repository.RagEvalRunRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryProperties;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RAG 评测服务测试")
class RagEvaluationServiceTest {

  @Mock
  private RagEvalDatasetRepository datasetRepository;

  @Mock
  private RagEvalCaseRepository caseRepository;

  @Mock
  private RagEvalRunRepository runRepository;

  @Mock
  private RagEvalRunCaseResultRepository resultRepository;

  @Mock
  private KnowledgeBaseVectorService vectorService;

  private ObjectMapper objectMapper;
  private RagEvaluationService service;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    service = new RagEvaluationService(
        datasetRepository,
        caseRepository,
        runRepository,
        resultRepository,
        vectorService,
        new KnowledgeBaseQueryProperties(),
        objectMapper
    );
  }

  @Test
  @DisplayName("createDataset 创建评测集 DTO")
  void createDatasetReturnsDTO() {
    when(datasetRepository.save(any(RagEvalDatasetEntity.class))).thenAnswer(invocation -> {
      RagEvalDatasetEntity entity = invocation.getArgument(0);
      entity.setId(1L);
      return entity;
    });

    RagEvalDatasetDTO dto = service.createDataset(
        new CreateRagEvalDatasetRequest("Java 基础评测", "验证 RAG 检索")
    );

    assertThat(dto.id()).isEqualTo(1L);
    assertThat(dto.name()).isEqualTo("Java 基础评测");
  }

  @Test
  @DisplayName("addCase 保存知识库与期望关键词 JSON")
  void addCaseSavesCaseJson() {
    when(datasetRepository.existsById(1L)).thenReturn(true);
    when(caseRepository.save(any(RagEvalCaseEntity.class))).thenAnswer(invocation -> {
      RagEvalCaseEntity entity = invocation.getArgument(0);
      entity.setId(2L);
      return entity;
    });

    RagEvalCaseDTO dto = service.addCase(1L, new AddRagEvalCaseRequest(
        "Spring Boot 自动配置是什么？",
        List.of(10L, 11L),
        List.of("自动配置", "starter")
    ));

    assertThat(dto.id()).isEqualTo(2L);
    assertThat(dto.knowledgeBaseIdsJson()).contains("10");
    assertThat(dto.expectedKeywordsJson()).contains("自动配置");
  }

  @Test
  @DisplayName("runDataset 批量执行检索并计算关键词命中")
  void runDatasetEvaluatesKeywordHits() throws Exception {
    when(datasetRepository.existsById(1L)).thenReturn(true);
    RagEvalCaseEntity evalCase = RagEvalCaseEntity.builder()
        .id(5L)
        .datasetId(1L)
        .question("Spring Boot 自动配置是什么？")
        .knowledgeBaseIdsJson(objectMapper.writeValueAsString(List.of(10L)))
        .expectedKeywordsJson(objectMapper.writeValueAsString(List.of("自动配置")))
        .build();
    when(caseRepository.findByDatasetIdOrderByIdAsc(1L)).thenReturn(List.of(evalCase));

    AtomicReference<RagEvalRunEntity> runRef = new AtomicReference<>();
    when(runRepository.save(any(RagEvalRunEntity.class))).thenAnswer(invocation -> {
      RagEvalRunEntity entity = invocation.getArgument(0);
      if (entity.getId() == null) {
        entity.setId(7L);
      }
      runRef.set(entity);
      return entity;
    });
    when(runRepository.findById(7L)).thenAnswer(invocation -> Optional.of(runRef.get()));

    List<RagEvalRunCaseResultEntity> savedResults = new ArrayList<>();
    when(resultRepository.save(any(RagEvalRunCaseResultEntity.class))).thenAnswer(invocation -> {
      RagEvalRunCaseResultEntity entity = invocation.getArgument(0);
      entity.setId((long) savedResults.size() + 1);
      savedResults.add(entity);
      return entity;
    });
    when(resultRepository.findByRunIdOrderByIdAsc(7L)).thenReturn(savedResults);

    Document doc = Document.builder()
        .text("Spring Boot 会根据 classpath 和配置自动配置 Bean。")
        .metadata(Map.of("kb_id", "10"))
        .score(0.95)
        .build();
    when(vectorService.similaritySearch(
        eq("Spring Boot 自动配置是什么？"),
        eq(List.of(10L)),
        eq(3),
        eq(0.2)
    )).thenReturn(List.of(doc));

    RagEvalRunDTO run = service.runDataset(1L, new RunRagEvalRequest(3, 0.2));

    assertThat(run.status()).isEqualTo(RagEvalRunStatus.COMPLETED);
    assertThat(run.totalCases()).isEqualTo(1);
    assertThat(run.matchedCases()).isEqualTo(1);
    assertThat(run.caseResults()).hasSize(1);
    assertThat(run.caseResults().getFirst().expectedKeywordHit()).isTrue();
    assertThat(run.caseResults().getFirst().hitCount()).isEqualTo(1);
    verify(vectorService).similaritySearch(
        "Spring Boot 自动配置是什么？",
        List.of(10L),
        3,
        0.2
    );
  }
}
