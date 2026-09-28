/**
 * 统一封装结构化输出调用与 JSON 解析重试策略。
 *
 * <p>职责边界：
 * <ul>
 *   <li>追加 Prompt 注入防护指令</li>
 *   <li>调用业务方传入的 Plain ChatClient</li>
 *   <li>将 LLM 返回内容转换为 Java 对象</li>
 *   <li>解析失败时本地修复常见 JSON 问题，并按配置重试</li>
 * </ul>
 *
 * <p>AI_PROVIDER_OBSERVABILITY: Provider 级网络/API 失败率、HTTP attempt、
 * Spring AI retry 次数已经下沉到 ChatClient/OpenAiApi/RetryTemplate 层记录。
 * 本类不再记录 AI 调用日志和 Micrometer 指标，避免把 JSON 解析失败误算为
 * Provider 调用失败。
 */
package interview.guide.common.ai;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

@Component
public class StructuredOutputInvoker {

  private static final String STRICT_JSON_INSTRUCTION = """
请仅返回可被 JSON 解析器直接解析的 JSON 对象，并严格满足字段结构要求：
1) 不要输出 Markdown 代码块（如 ```json）。
2) 不要输出任何解释文字、前后缀、注释。
3) 所有字符串内引号必须正确转义。
      """;

  private final int maxAttempts;
  private final boolean includeLastErrorInRetryPrompt;
  private final boolean retryUseRepairPrompt;
  private final boolean retryAppendStrictJsonInstruction;
  private final int errorMessageMaxLength;

  public StructuredOutputInvoker(StructuredOutputProperties properties) {
    this.maxAttempts = Math.max(1, properties.getStructuredMaxAttempts());
    this.includeLastErrorInRetryPrompt = properties.isStructuredIncludeLastError();
    this.retryUseRepairPrompt = properties.isStructuredRetryUseRepairPrompt();
    this.retryAppendStrictJsonInstruction = properties.isStructuredRetryAppendStrictJsonInstruction();
    this.errorMessageMaxLength = Math.max(20, properties.getStructuredErrorMessageMaxLength());
  }

  public <T> T invoke(
      ChatClient chatClient,
      String systemPromptWithFormat,
      String userPrompt,
      BeanOutputConverter<T> outputConverter,
      ErrorCode errorCode,
      String errorPrefix,
      String logContext,
      Logger log
  ) {
    String securedSystemPrompt = systemPromptWithFormat
        + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION;

    Exception lastError = null;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      String attemptSystemPrompt = attempt == 1
          ? securedSystemPrompt
          : buildRetrySystemPrompt(securedSystemPrompt, lastError);

      try {
        String content = chatClient.prompt()
            .system(attemptSystemPrompt)
            .user(userPrompt)
            .call()
            .content();
        return convertWithRepair(content, outputConverter, logContext, log);
      } catch (Exception e) {
        lastError = e;
        if (attempt < maxAttempts) {
          log.warn("{}结构化解析失败，准备重试: attempt={}/{}, error={}",
              logContext, attempt, maxAttempts, e.getMessage());
        } else {
          log.error("{}结构化解析失败，已达最大重试次数: attempts={}, error={}",
              logContext, maxAttempts, e.getMessage());
        }
      }
    }

    throw new BusinessException(
        errorCode,
        errorPrefix + (lastError != null ? lastError.getMessage() : "unknown")
    );
  }

  private <T> T convertWithRepair(
      String content,
      BeanOutputConverter<T> outputConverter,
      String logContext,
      Logger log
  ) {
    try {
      return outputConverter.convert(content);
    } catch (Exception firstError) {
      String repaired = repairUnescapedQuotesInJsonStrings(content);
      if (!repaired.equals(content)) {
        try {
          T result = outputConverter.convert(repaired);
          log.warn("{}结构化 JSON 存在未转义引号，已在本地修复后解析成功", logContext);
          return result;
        } catch (Exception repairError) {
          firstError.addSuppressed(repairError);
        }
      }
      throw firstError;
    }
  }

  private String repairUnescapedQuotesInJsonStrings(String content) {
    if (content == null || content.isBlank()) {
      return content;
    }

    StringBuilder repaired = new StringBuilder(content.length() + 16);
    boolean inString = false;
    boolean escaping = false;

    for (int i = 0; i < content.length(); i++) {
      char ch = content.charAt(i);

      if (!inString) {
        if (ch == '"') {
          inString = true;
        }
        repaired.append(ch);
        continue;
      }

      if (escaping) {
        repaired.append(ch);
        escaping = false;
        continue;
      }

      if (ch == '\\') {
        repaired.append(ch);
        escaping = true;
        continue;
      }

      if (ch == '"') {
        if (isLikelyJsonStringTerminator(content, i + 1)) {
          inString = false;
          repaired.append(ch);
        } else {
          repaired.append("\\\"");
        }
        continue;
      }

      repaired.append(ch);
    }

    return repaired.toString();
  }

  private boolean isLikelyJsonStringTerminator(String content, int start) {
    for (int i = start; i < content.length(); i++) {
      char next = content.charAt(i);
      if (Character.isWhitespace(next)) {
        continue;
      }
      return next == ',' || next == '}' || next == ']' || next == ':';
    }
    return true;
  }

  private String buildRetrySystemPrompt(String systemPromptWithFormat, Exception lastError) {
    if (!retryUseRepairPrompt) {
      return systemPromptWithFormat;
    }

    StringBuilder prompt = new StringBuilder(systemPromptWithFormat).append("\n\n");
    if (retryAppendStrictJsonInstruction) {
      prompt.append(STRICT_JSON_INSTRUCTION).append('\n');
    }
    prompt.append("上次输出解析失败，请仅返回合法 JSON。");

    if (includeLastErrorInRetryPrompt && lastError != null && lastError.getMessage() != null) {
      prompt.append("\n上次失败原因：")
          .append(sanitizeErrorMessage(lastError.getMessage()));
    }

    return prompt.toString();
  }

  private String sanitizeErrorMessage(String message) {
    String oneLine = message.replace('\n', ' ').replace('\r', ' ').trim();
    if (oneLine.length() > errorMessageMaxLength) {
      return oneLine.substring(0, errorMessageMaxLength) + "...";
    }
    return oneLine;
  }
}
