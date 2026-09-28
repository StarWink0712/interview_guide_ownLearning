/**
 * 结构化输出重试策略配置属性
 *
 * 映射 app.ai 前缀下与结构化输出相关的配置项，包括最大重试次数、是否附带错误信息、
 * 是否使用修复提示词、是否追加严格 JSON 指令、错误信息最大长度、指标采集开关等。
 *
 * 联动关系：
 * - 被 StructuredOutputInvoker 注入，控制重试行为和指标采集
 */
package interview.guide.common.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.ai")
public class StructuredOutputProperties {

    /** 最大重试次数（含首次调用），至少为 1，默认 2 次 */
    private int structuredMaxAttempts = 2;
    /** 重试时是否将上次错误信息附入提示词，帮助 AI 修正输出，默认开启 */
    private boolean structuredIncludeLastError = true;
    /** 重试时是否使用修复提示词（追加修复指令），默认开启 */
    private boolean structuredRetryUseRepairPrompt = true;
    /** 重试时是否追加 STRICT_JSON_INSTRUCTION 强制纯 JSON 输出，默认开启 */
    private boolean structuredRetryAppendStrictJsonInstruction = true;
    /** 附带错误信息最大长度（字符），超长截断，默认 200 */
    private int structuredErrorMessageMaxLength = 200;
    /** 是否启用 Micrometer 指标采集（调用次数、延迟、重试次数），默认开启 */
    private boolean structuredMetricsEnabled = true;
}
