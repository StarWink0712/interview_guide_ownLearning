/**
 * 限流异常
 *
 * 当请求超过限流阈值时由 RateLimitAspect 抛出，继承 BusinessException，
 * 固定使用 ErrorCode.RATE_LIMIT_EXCEEDED 错误码。
 *
 * 联动关系：
 * - 被 RateLimitAspect 在限流拒绝时抛出
 * - 被 GlobalExceptionHandler 作为 BusinessException 子类统一捕获处理
 */
package interview.guide.common.exception;

/**
 * 限流异常
 *
 * 当请求超过限流阈值时由 RateLimitAspect 抛出，继承 BusinessException，
 * 固定使用 ErrorCode.RATE_LIMIT_EXCEEDED 错误码。
 *
 * 联动关系：
 * - 被 RateLimitAspect 在限流拒绝时抛出
 * - 被 GlobalExceptionHandler 作为 BusinessException 子类统一捕获处理
 */
public class RateLimitExceededException extends BusinessException {

    /**
     * 使用默认错误码和信息构造
     */
    public RateLimitExceededException() {
        super(ErrorCode.RATE_LIMIT_EXCEEDED, ErrorCode.RATE_LIMIT_EXCEEDED.getMessage());
    }

    /**
     * 使用自定义消息构造
     */
    public RateLimitExceededException(String message) {
        super(ErrorCode.RATE_LIMIT_EXCEEDED, message);
    }

    /**
     * 使用自定义消息和根因构造
     */
    public RateLimitExceededException(String message, Throwable cause) {
        super(ErrorCode.RATE_LIMIT_EXCEEDED.getCode(), message);
        this.initCause(cause);
    }
}
