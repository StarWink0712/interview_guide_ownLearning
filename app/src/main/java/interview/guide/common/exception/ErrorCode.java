/**
 * 错误码枚举
 *
 * 按 10 个业务域分段定义错误码：通用(1xxx)、简历(2xxx)、面试(3xxx)、存储(4xxx)、
 * 导出(5xxx)、知识库(6xxx)、AI 服务(7xxx)、限流(8xxx)、面试日程(9xxx)、
 * 语音面试(10xxx)、Provider 管理(11xxx)。每个错误码包含数字 code 和默认 message。
 *
 * 联动关系：
 * - 被 BusinessException 引用，构造业务异常
 * - 被 GlobalExceptionHandler 引用，构造错误响应
 * - 被 StructuredOutputInvoker 引用，构造结构化输出失败异常
 * - 被各业务模块 Service 层广泛引用
 */
package interview.guide.common.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ErrorCode {

    // ========== 通用错误 1xxx ==========
    // 适用于所有模块的基础错误码，对应标准 HTTP 状态码
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未授权"),
    FORBIDDEN(403, "禁止访问"),
    NOT_FOUND(404, "资源不存在"),
    METHOD_NOT_ALLOWED(405, "请求方法不支持"),
    INTERNAL_ERROR(500, "服务器内部错误"),

    // ========== 简历模块错误 2xxx ==========
    // 简历上传、解析、分析、去重相关的业务错误码
    RESUME_NOT_FOUND(2001, "简历不存在"),
    RESUME_PARSE_FAILED(2002, "简历解析失败"),
    RESUME_UPLOAD_FAILED(2003, "简历上传失败"),
    RESUME_DUPLICATE(2004, "简历已存在"),
    RESUME_FILE_TYPE_NOT_SUPPORTED(2006, "不支持的文件类型"),
    RESUME_ANALYSIS_FAILED(2007, "简历分析失败"),
    RESUME_ANALYSIS_NOT_FOUND(2008, "简历分析结果不存在"),

    // ========== 面试模块错误 3xxx ==========
    // 面试会话创建、答题、评估相关的业务错误码
    INTERVIEW_SESSION_NOT_FOUND(3001, "面试会话不存在"),
    INTERVIEW_SESSION_EXPIRED(3002, "面试会话已过期"),
    INTERVIEW_QUESTION_NOT_FOUND(3003, "面试问题不存在"),
    INTERVIEW_ALREADY_COMPLETED(3004, "面试已完成"),
    INTERVIEW_EVALUATION_FAILED(3005, "面试评估失败"),
    INTERVIEW_QUESTION_GENERATION_FAILED(3006, "面试问题生成失败"),
    INTERVIEW_NOT_COMPLETED(3007, "面试尚未完成"),

    // ========== 存储模块错误 4xxx ==========
    // RustFS/S3 文件上传、下载、删除相关的错误码
    STORAGE_UPLOAD_FAILED(4001, "文件上传失败"),
    STORAGE_DOWNLOAD_FAILED(4002, "文件下载失败"),
    STORAGE_DELETE_FAILED(4003, "文件删除失败"),

    // ========== 导出模块错误 5xxx ==========
    // PDF 报告导出相关的错误码（iText 8 渲染失败、字体缺失等）
    EXPORT_PDF_FAILED(5001, "PDF导出失败"),

    // ========== 知识库模块错误 6xxx ==========
    // 知识库文档上传、解析、向量化、RAG 查询相关的错误码
    KNOWLEDGE_BASE_NOT_FOUND(6001, "知识库不存在"),
    KNOWLEDGE_BASE_PARSE_FAILED(6002, "知识库文件解析失败"),
    KNOWLEDGE_BASE_QUERY_FAILED(6004, "知识库查询失败"),
    KNOWLEDGE_BASE_DELETE_FAILED(6005, "知识库删除失败"),
    KNOWLEDGE_BASE_VECTORIZATION_FAILED(6006, "知识库向量化失败"),

    // ========== AI服务错误 7xxx ==========
    // LLM 调用（已按状态码细分：不可用/超时/错误/密钥无效/限流）
    AI_SERVICE_UNAVAILABLE(7001, "AI服务暂时不可用，请稍后重试"),
    AI_SERVICE_TIMEOUT(7002, "AI服务响应超时"),
    AI_SERVICE_ERROR(7003, "AI服务调用失败"),
    AI_API_KEY_INVALID(7004, "AI服务密钥无效"),
    AI_RATE_LIMIT_EXCEEDED(7005, "AI服务调用频率超限"),

    // ========== 限流模块错误 8xxx ==========
    // 系统级限流触发时的错误码（由 RateLimitAspect 抛出）
    RATE_LIMIT_EXCEEDED(8001, "请求过于频繁，请稍后再试"),

    // ========== 面试日程模块错误 9xxx ==========
    INTERVIEW_SCHEDULE_NOT_FOUND(9001, "面试日程不存在"),
    INTERVIEW_REMINDER_NOT_FOUND(9002, "面试提醒不存在"),
    INTERVIEW_REMINDER_SEND_FAILED(9003, "面试提醒发送失败"),

    // ========== 语音面试模块错误 10xxx ==========
    // WebSocket 实时语音面试（ASR/TTS/评估）相关的错误码
    VOICE_SESSION_NOT_FOUND(10001, "语音面试会话不存在"),
    VOICE_EVALUATION_FAILED(10004, "语音面试评估失败"),
    VOICE_EVALUATION_NOT_FOUND(10006, "语音面试评估结果不存在"),

    // ========== Provider管理模块错误 11xxx ==========
    // LLM Provider 动态配置管理（增删改查、连通性测试）相关的错误码
    PROVIDER_NOT_FOUND(11001, "LLM Provider 不存在"),
    PROVIDER_ALREADY_EXISTS(11002, "LLM Provider 已存在"),
    PROVIDER_CONFIG_READ_FAILED(11004, "读取 Provider 配置失败"),
    PROVIDER_CONFIG_WRITE_FAILED(11005, "写入 Provider 配置失败"),
    PROVIDER_TEST_FAILED(11006, "Provider 连通性测试失败"),
    PROVIDER_DEFAULT_CANNOT_DELETE(11007, "默认 Provider 不可删除"),
    MODULE_NOT_FOUND(11008, "模块不存在"),
    VOICE_CONFIG_READ_FAILED(11009, "读取语音服务配置失败"),
    VOICE_CONFIG_WRITE_FAILED(11010, "写入语音服务配置失败"),
    VOICE_CONFIG_TEST_FAILED(11011, "语音服务连通性测试失败");

    /** 业务错误码数字标识（非 HTTP 状态码，仅在响应 body 中使用） */
    private final Integer code;
    /** 默认错误描述消息 */
    private final String message;
}
