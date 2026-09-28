package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.FileHashService;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.file.FileValidationService;
import interview.guide.modules.knowledgebase.listener.VectorizeStreamProducer;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("知识库上传事务边界测试")
class KnowledgeBaseUploadServiceTest {

  @Mock
  private KnowledgeBaseParseService parseService;

  @Mock
  private KnowledgeBasePersistenceService persistenceService;

  @Mock
  private FileStorageService storageService;

  @Mock
  private KnowledgeBaseRepository knowledgeBaseRepository;

  @Mock
  private FileValidationService fileValidationService;

  @Mock
  private FileHashService fileHashService;

  @Mock
  private VectorizeStreamProducer vectorizeStreamProducer;

  @Test
  @DisplayName("对象存储上传成功后 DB 保存失败会补偿删除文件且不入队")
  void uploadCompensatesStorageWhenDbSaveFails() {
    KnowledgeBaseUploadService service = new KnowledgeBaseUploadService(
        parseService,
        persistenceService,
        storageService,
        knowledgeBaseRepository,
        fileValidationService,
        fileHashService,
        vectorizeStreamProducer
    );
    MockMultipartFile file = new MockMultipartFile(
        "file",
        "knowledge.pdf",
        "application/pdf",
        "knowledge text".getBytes()
    );

    when(parseService.detectContentType(file)).thenReturn("application/pdf");
    when(fileHashService.calculateHash(file)).thenReturn("hash-1");
    when(knowledgeBaseRepository.findByFileHash("hash-1")).thenReturn(Optional.empty());
    when(parseService.parseContent(file)).thenReturn("parsed content");
    when(storageService.uploadKnowledgeBase(file)).thenReturn("knowledgebases/2026/kb.pdf");
    when(storageService.getFileUrl("knowledgebases/2026/kb.pdf")).thenReturn("http://files/kb.pdf");
    when(persistenceService.saveKnowledgeBase(
        eq(file),
        eq("Java 面试"),
        eq("interview"),
        eq("knowledgebases/2026/kb.pdf"),
        eq("http://files/kb.pdf"),
        eq("hash-1")
    )).thenThrow(new BusinessException(ErrorCode.INTERNAL_ERROR, "保存失败"));

    assertThatThrownBy(() -> service.uploadKnowledgeBase(file, "Java 面试", "interview"))
        .isInstanceOf(BusinessException.class);

    verify(storageService).deleteKnowledgeBase("knowledgebases/2026/kb.pdf");
    verify(vectorizeStreamProducer, never()).sendVectorizeTask(anyLong(), anyString());
    verify(fileValidationService).validateFile(any(), anyLong(), eq("知识库"));
  }
}
