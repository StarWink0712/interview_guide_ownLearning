package interview.guide.modules.aiobservability.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.modules.aiobservability.model.RagRetrievalLogEntity;
import interview.guide.modules.aiobservability.model.RagRetrievalLogRequest;
import interview.guide.modules.aiobservability.model.RagRetrievedChunkLogEntity;
import interview.guide.modules.aiobservability.model.RagTraceDTO;
import interview.guide.modules.aiobservability.repository.RagRetrievalLogRepository;
import interview.guide.modules.aiobservability.repository.RagRetrievedChunkLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RAG 检索日志服务测试")
class RagRetrievalLogServiceTest {

  @Mock
  private RagRetrievalLogRepository retrievalLogRepository;

  @Mock
  private RagRetrievedChunkLogRepository chunkLogRepository;

  @Test
  @DisplayName("recordRetrieval 保存检索参数和命中 chunk 摘要")
  @SuppressWarnings("unchecked")
  void recordRetrievalSavesTraceAndChunks() {
    RagRetrievalLogService service = new RagRetrievalLogService(
        retrievalLogRepository,
        chunkLogRepository,
        new ObjectMapper()
    );
    when(retrievalLogRepository.save(any(RagRetrievalLogEntity.class))).thenAnswer(invocation -> {
      RagRetrievalLogEntity entity = invocation.getArgument(0);
      entity.setId(9L);
      entity.setCreatedAt(LocalDateTime.now());
      return entity;
    });
    when(chunkLogRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

    Document doc = Document.builder()
        .text("Spring Boot 自动配置让应用开发更轻量")
        .metadata(Map.of("kb_id", "12", "source", "java.md"))
        .score(0.91)
        .build();
    RagRetrievalLogRequest request = new RagRetrievalLogRequest(
        "trace-rag",
        100L,
        "Spring Boot 是什么？",
        "Spring Boot 自动配置是什么？",
        List.of("Spring Boot 自动配置是什么？", "Spring Boot 是什么？"),
        List.of(12L),
        8,
        0.28,
        1,
        35L
    );

    Optional<RagTraceDTO> result = service.recordRetrieval(request, List.of(doc));

    assertThat(result).isPresent();
    assertThat(result.get().traceId()).isEqualTo("trace-rag");
    assertThat(result.get().chunks()).hasSize(1);
    assertThat(result.get().chunks().getFirst().knowledgeBaseId()).isEqualTo(12L);

    ArgumentCaptor<List<RagRetrievedChunkLogEntity>> captor = ArgumentCaptor.forClass(List.class);
    verify(chunkLogRepository).saveAll(captor.capture());
    assertThat(captor.getValue().getFirst().getChunkTextPreview()).contains("Spring Boot");
    assertThat(captor.getValue().getFirst().getMetadataJson()).contains("java.md");
  }

  @Test
  @DisplayName("recordRetrieval 写入失败时不影响主 RAG 流程")
  void recordRetrievalDoesNotThrowWhenRepositoryFails() {
    RagRetrievalLogService service = new RagRetrievalLogService(
        retrievalLogRepository,
        chunkLogRepository,
        new ObjectMapper()
    );
    when(retrievalLogRepository.save(any(RagRetrievalLogEntity.class)))
        .thenThrow(new IllegalStateException("database unavailable"));

    RagRetrievalLogRequest request = new RagRetrievalLogRequest(
        "trace-fail",
        null,
        "问题",
        "问题",
        List.of("问题"),
        List.of(1L),
        8,
        0.28,
        0,
        10L
    );

    assertThatCode(() -> service.recordRetrieval(request, List.of())).doesNotThrowAnyException();
    assertThat(service.recordRetrieval(request, List.of())).isEmpty();
    verify(chunkLogRepository, never()).saveAll(any());
  }
}
