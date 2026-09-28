package interview.guide.modules.resume.service;

import interview.guide.common.config.AppConfigProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.file.FileValidationService;
import interview.guide.modules.resume.listener.AnalyzeStreamProducer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
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
@DisplayName("简历上传事务边界测试")
class ResumeUploadServiceTest {

  @Mock
  private ResumeParseService parseService;

  @Mock
  private FileStorageService storageService;

  @Mock
  private ResumePersistenceService persistenceService;

  @Mock
  private AppConfigProperties appConfig;

  @Mock
  private FileValidationService fileValidationService;

  @Mock
  private AnalyzeStreamProducer analyzeStreamProducer;

  @Test
  @DisplayName("对象存储上传成功后 DB 保存失败会补偿删除文件且不入队")
  void uploadCompensatesStorageWhenDbSaveFails() {
    ResumeUploadService service = new ResumeUploadService(
        parseService,
        storageService,
        persistenceService,
        appConfig,
        fileValidationService,
        analyzeStreamProducer
    );
    MockMultipartFile file = new MockMultipartFile(
        "file",
        "resume.pdf",
        "application/pdf",
        "resume text".getBytes()
    );

    when(parseService.detectContentType(file)).thenReturn("application/pdf");
    when(appConfig.getAllowedTypes()).thenReturn(List.of("pdf"));
    when(persistenceService.findExistingResume(file)).thenReturn(Optional.empty());
    when(parseService.parseResume(file)).thenReturn("parsed resume");
    when(storageService.uploadResume(file)).thenReturn("resumes/2026/resume.pdf");
    when(storageService.getFileUrl("resumes/2026/resume.pdf")).thenReturn("http://files/resume.pdf");
    when(persistenceService.saveResume(
        eq(file),
        eq("parsed resume"),
        eq("resumes/2026/resume.pdf"),
        eq("http://files/resume.pdf")
    )).thenThrow(new BusinessException(ErrorCode.RESUME_UPLOAD_FAILED, "保存失败"));

    assertThatThrownBy(() -> service.uploadAndAnalyze(file))
        .isInstanceOf(BusinessException.class);

    verify(storageService).deleteResume("resumes/2026/resume.pdf");
    verify(analyzeStreamProducer, never()).sendAnalyzeTask(anyLong(), anyString());
    verify(fileValidationService).validateContentTypeByList(
        eq("application/pdf"),
        eq(List.of("pdf")),
        anyString()
    );
    verify(fileValidationService).validateFile(any(), anyLong(), eq("简历"));
  }
}
