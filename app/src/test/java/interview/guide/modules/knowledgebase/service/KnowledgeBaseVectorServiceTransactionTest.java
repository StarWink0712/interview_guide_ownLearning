package interview.guide.modules.knowledgebase.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("知识库向量化事务边界测试")
class KnowledgeBaseVectorServiceTransactionTest {

  @Test
  @DisplayName("vectorizeAndStore 不持有外层大事务")
  void vectorizeAndStoreIsNotTransactional() throws Exception {
    Transactional transactional = KnowledgeBaseVectorService.class
        .getMethod("vectorizeAndStore", Long.class, String.class)
        .getAnnotation(Transactional.class);

    assertThat(transactional).isNull();
  }

  @Test
  @DisplayName("deleteByKnowledgeBaseId 保留短事务")
  void deleteByKnowledgeBaseIdKeepsShortTransaction() throws Exception {
    Transactional transactional = KnowledgeBaseVectorService.class
        .getMethod("deleteByKnowledgeBaseId", Long.class)
        .getAnnotation(Transactional.class);

    assertThat(transactional).isNotNull();
  }
}
