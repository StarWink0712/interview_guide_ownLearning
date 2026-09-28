/**
 * 业务异常
 *
 * 全局统一的业务异常类，携带错误码（ErrorCode）和自定义消息。
 * 所有业务层异常必须通过此类抛出，禁止直接使用 RuntimeException。
 * 全局异常处理器 GlobalExceptionHandler 统一捕获并返回 HTTP 200 + Result.error。
 *
 * 联动关系：
 * - 依赖 ErrorCode 枚举提供错误码
 * - 被 GlobalExceptionHandler 统一捕获处理
 * - 被所有业务模块 Service 层抛出使用
 */
package interview.guide.common.exception;

import lombok.Getter;

/**
 * 业务异常
 *
 * 全局统一的业务异常类，携带错误码（ErrorCode）和自定义消息。
 * 所有业务层异常必须通过此类抛出，禁止直接使用 RuntimeException。
 * 全局异常处理器 GlobalExceptionHandler 统一捕获并返回 HTTP 200 + Result.error。
 *
 * 联动关系：
 * - 依赖 ErrorCode 枚举提供错误码
 * - 被 GlobalExceptionHandler 统一捕获处理
 * - 被所有业务模块 Service 层抛出使用
 */
@Getter
public class BusinessException extends RuntimeException {

    /** HTTP 业务错误码 */
    private final Integer code;
    /** 错误描述信息 */
    private final String message;

    /**
     * 通过 ErrorCode 构造异常
     */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
        this.message = errorCode.getMessage();
    }

    /**
     * 通过 ErrorCode + 自定义消息构造异常
     */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
        this.message = message;
    }

    /**
     * 通过自定义 code + message 构造异常
     */
    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
        this.message = message;
    }

    /**
     * 仅通过消息构造异常（使用默认 INTERNAL_ERROR 错误码）
     */
    public BusinessException(String message) {
        super(message);
        this.code = ErrorCode.INTERNAL_ERROR.getCode();
        this.message = message;
    }

    /**
     * 通过消息 + 根因构造异常
     */
    public BusinessException(String message, Throwable cause) {
        super(message, cause);
        this.code = ErrorCode.INTERNAL_ERROR.getCode();
        this.message = message;
    }

    /**
     * 通过 ErrorCode + 消息 + 根因构造异常
     */
    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.code = errorCode.getCode();
        this.message = message;
    }
}
