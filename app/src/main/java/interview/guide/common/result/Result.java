/**
 * 统一响应结果包装
 *
 * 所有 API 接口的统一返回格式，包含 code（业务状态码）、message（描述信息）、data（业务数据）。
 * 提供成功/失败的静态工厂方法，支持泛型数据类型。
 * 全局异常处理器 GlobalExceptionHandler 统一通过此类返回错误响应。
 *
 * 联动关系：
 * - 依赖 CommonConstants.StatusCode 获取标准状态码
 * - 依赖 ErrorCode 构建错误响应
 * - 被所有 Controller 作为返回类型使用
 * - 被 GlobalExceptionHandler 用于构建异常响应
 */
package interview.guide.common.result;

import interview.guide.common.constant.CommonConstants;
import interview.guide.common.exception.ErrorCode;
import lombok.Getter;

@Getter
public class Result<T> {

    /** 业务状态码，0 表示成功，其他表示错误 */
    private final Integer code;
    /** 提示信息 */
    private final String message;
    /** 业务数据（泛型，可为 null） */
    private final T data;
    
    /**
     * 私有构造函数，通过静态工厂方法创建实例
     */
    private Result(Integer code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    // ========== 成功响应工厂方法 ==========

    /** 返回无数据成功响应 */
    public static <T> Result<T> success() {
        return new Result<>(CommonConstants.StatusCode.SUCCESS, "success", null);
    }

    /** 返回带数据成功响应 */
    public static <T> Result<T> success(T data) {
        return new Result<>(CommonConstants.StatusCode.SUCCESS, "success", data);
    }

    /** 返回带自定义消息和数据的成功响应 */
    public static <T> Result<T> success(String message, T data) {
        return new Result<>(CommonConstants.StatusCode.SUCCESS, message, data);
    }

    // ========== 失败响应工厂方法 ==========

    /** 返回仅消息的失败响应 */
    public static <T> Result<T> error(String message) {
        return new Result<>(CommonConstants.StatusCode.SERVER_ERROR, message, null);
    }

    /** 返回自定义状态码和消息的失败响应 */
    public static <T> Result<T> error(Integer code, String message) {
        return new Result<>(code, message, null);
    }

    /** 通过 ErrorCode 构建失败响应 */
    public static <T> Result<T> error(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    /** 通过 ErrorCode + 自定义消息构建失败响应 */
    public static <T> Result<T> error(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.getCode(), message, null);
    }

    // ========== 辅助方法 ==========

    /** 判断当前是否为成功响应 */
    public boolean isSuccess() {
        return CommonConstants.StatusCode.SUCCESS == this.code;
    }
}
