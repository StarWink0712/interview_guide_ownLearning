/**
 * 文件哈希服务
 *
 * 统一提供 SHA-256 哈希计算功能，用于文件去重判断。
 * 支持三种输入形式：MultipartFile、字节数组、InputStream（流式计算，适用于大文件）。
 *
 * 联动关系：
 * - 被 ResumePersistenceService 调用（计算简历文件哈希，用于去重校验）
 * - 被 KnowledgeBaseUploadService 调用（计算知识库文件哈希，用于去重校验）
 */
package interview.guide.infrastructure.file;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 文件哈希服务
 * 统一提供 SHA-256 哈希计算功能，用于文件去重判断
 */
@Slf4j
@Service
public class FileHashService {

    /** 哈希算法名称 */
    private static final String HASH_ALGORITHM = "SHA-256";
    /** 流式计算缓冲区大小 */
    private static final int BUFFER_SIZE = 8192;

    /**
     * 计算文件的 SHA-256 哈希值
     *
     * @param file MultipartFile 文件
     * @return 十六进制哈希字符串
     */
    public String calculateHash(MultipartFile file) {
        try {
            //这里是先将MultipartFile转换为字节数组，再计算哈希值
            return calculateHash(file.getBytes());
        } catch (IOException e) {
            log.error("读取文件内容失败", e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "计算文件哈希失败");
        }
    }

    /**
     * 计算字节数组的 SHA-256 哈希值
     *
     * @param data 字节数组
     * @return 十六进制哈希字符串
     */
    public String calculateHash(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hashBytes = digest.digest(data);
            return bytesToHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            log.error("哈希算法不支持: {}", HASH_ALGORITHM, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "计算文件哈希失败");
        }
    }

    /**
     * 流式计算文件的 SHA-256 哈希值（适用于大文件）
     *
     * @param inputStream 输入流
     * @return 十六进制哈希字符串
     */
    public String calculateHash(InputStream inputStream) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
            return bytesToHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            log.error("计算文件哈希失败", e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "计算文件哈希失败");
        }
    }

    /**
     * 将字节数组转换为十六进制字符串
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }
}
