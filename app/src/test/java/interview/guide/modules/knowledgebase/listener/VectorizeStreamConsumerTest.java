package interview.guide.modules.knowledgebase.listener;

import interview.guide.common.async.AsyncTaskCancelledException;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.asynctask.service.AsyncTaskService;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseParseService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("知识库向量化 Stream 消费者测试")
class VectorizeStreamConsumerTest {

  @Mock
  private RedisService redisService;

  @Mock
  private KnowledgeBaseVectorService vectorService;

  @Mock
  private KnowledgeBaseRepository knowledgeBaseRepository;

  @Mock
  private KnowledgeBaseParseService parseService;

  @Mock
  private AsyncTaskService asyncTaskService;

  @Test
  @DisplayName("消费者按 kbId 从对象存储解析内容后执行向量化")
  void processBusinessLoadsContentByKnowledgeBaseId() {
    VectorizeStreamConsumer consumer = createConsumer();
    KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
    kb.setId(5L);
    kb.setStorageKey("kb/file.pdf");
    kb.setOriginalFilename("file.pdf");
    when(knowledgeBaseRepository.findById(5L)).thenReturn(Optional.of(kb));
    when(parseService.downloadAndParseContent("kb/file.pdf", "file.pdf"))
        .thenReturn("知识库文本");

    consumer.processBusiness(new VectorizeStreamConsumer.VectorizePayload(1L, 5L));

    verify(vectorService).vectorizeAndStore(5L, "知识库文本");
  }

  @Test
  @DisplayName("知识库已删除时取消任务")
  void deletedKnowledgeBaseCancelsTask() {
    VectorizeStreamConsumer consumer = createConsumer();
    when(knowledgeBaseRepository.findById(5L)).thenReturn(Optional.empty());

    assertThatThrownBy(() ->
        consumer.processBusiness(new VectorizeStreamConsumer.VectorizePayload(1L, 5L)))
        .isInstanceOf(AsyncTaskCancelledException.class);
  }

  private VectorizeStreamConsumer createConsumer() {
    return new VectorizeStreamConsumer(
        redisService,
        vectorService,
        knowledgeBaseRepository,
        parseService,
        asyncTaskService,
        null
    );
  }
}
