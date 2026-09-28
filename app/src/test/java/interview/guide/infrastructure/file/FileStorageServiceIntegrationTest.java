package interview.guide.infrastructure.file;

import interview.guide.testsupport.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("对象存储容器集成测试")
class FileStorageServiceIntegrationTest extends BaseIntegrationTest {

  @Autowired
  private FileStorageService fileStorageService;

  @BeforeEach
  void ensureBucket() {
    fileStorageService.ensureBucketExists();
  }

  @Test
  @DisplayName("简历文件可上传、生成 URL、下载并删除")
  void uploadDownloadAndDeleteResumeFile() {
    byte[] content = "Java 后端候选人简历\nSpring Boot, Redis, PostgreSQL".getBytes(
        StandardCharsets.UTF_8
    );
    MockMultipartFile file = new MockMultipartFile(
        "file",
        "张三简历.txt",
        "text/plain",
        content
    );
    String fileKey = null;

    try {
      fileKey = fileStorageService.uploadResume(file);
      String url = fileStorageService.getFileUrl(fileKey);
      byte[] downloaded = fileStorageService.downloadFile(fileKey);

      assertThat(fileKey).startsWith("resumes/");
      assertThat(url).contains("/interview-guide-test/").contains(fileKey);
      assertThat(downloaded).isEqualTo(content);
    } finally {
      fileStorageService.deleteResume(fileKey);
    }

    assertThat(fileStorageService.fileExists(fileKey)).isFalse();
  }
}
