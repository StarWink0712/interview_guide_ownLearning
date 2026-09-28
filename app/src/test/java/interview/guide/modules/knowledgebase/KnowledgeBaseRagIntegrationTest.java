package interview.guide.modules.knowledgebase;

import interview.guide.modules.knowledgebase.repository.VectorRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("知识库 RAG 最小集成测试")
class KnowledgeBaseRagIntegrationTest {

  @Mock
  private VectorStore vectorStore;

  @Mock
  private VectorRepository vectorRepository;

  private KnowledgeBaseVectorService vectorService;

  @BeforeEach
  void setUp() {
    vectorService = new KnowledgeBaseVectorService(vectorStore, vectorRepository);
  }

  @Test
  @DisplayName("文档分块写入向量库时 metadata 包含 kb_id")
  void vectorizedChunksCarryKnowledgeBaseIdMetadata() {
    ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);

    vectorService.vectorizeAndStore(42L, repeatedKnowledgeBaseContent());

    verify(vectorStore, atLeastOnce()).add(captor.capture());
    List<Document> addedDocuments = captor.getAllValues().stream()
        .flatMap(List::stream)
        .toList();

    assertThat(addedDocuments).isNotEmpty();
    assertThat(addedDocuments).allSatisfy(document ->
        assertThat(document.getMetadata()).containsEntry("kb_id", "42")
    );
  }

  @Test
  @DisplayName("过滤表达式失败时回退检索并按 kbId 本地过滤")
  void fallbackSearchFiltersDocumentsByKnowledgeBaseId() {
    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenThrow(new IllegalArgumentException("filter expression unsupported"))
        .thenReturn(List.of(
            new Document("Spring Boot Redis 命中文档", Map.of("kb_id", "42")),
            new Document("其他知识库噪声", Map.of("kb_id", "99"))
        ));

    List<Document> results = vectorService.similaritySearch("Redis Stream", List.of(42L), 5, 0.1);

    assertThat(results).hasSize(1);
    assertThat(results.getFirst().getText()).contains("Redis");
    assertThat(results.getFirst().getMetadata()).containsEntry("kb_id", "42");
    verify(vectorStore, times(2)).similaritySearch(any(SearchRequest.class));
  }

  @Test
  @DisplayName("无命中时返回空列表")
  void noHitReturnsEmptyList() {
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

    List<Document> results = vectorService.similaritySearch("完全无关的问题", List.of(42L), 3, 0.0);

    assertThat(results).isEmpty();
  }

  private String repeatedKnowledgeBaseContent() {
    return """
        Spring Boot 应用通过 Redis Stream 承载异步任务，消费者组负责 ACK 与重试。
        PostgreSQL pgvector 用于知识库向量检索，metadata 中的 kb_id 用来隔离不同知识库。
        RAG 查询会根据 topK 和 minScore 控制召回数量与相似度阈值。
        """.repeat(20);
  }
}
