package interview.guide.modules.interview.adaptive;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.adaptive.model.TurnEvaluationDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 自适应面试 - 单轮即时评估服务
 *
 * <p>对面试者单轮答题进行 LLM 驱动的即时评估，产出包含得分、能力分类、
 * 缺失知识点和追问建议的 TurnEvaluationDTO。职责单一，不涉及能力画像
 * 累积或下一步决策。
 *
 * <p>调用链：AdaptiveInterviewService.evaluateAndDecide() 第1步
 * → TurnEvaluationService.evaluateTurn()
 *
 * <p>处理流程：
 * <pre>
 *   1. 通过 LlmProviderRegistry 获取纯净 ChatClient（不含 SkillsTool）
 *   2. 渲染系统提示词 + 追加 JSON Schema 格式指令
 *   3. 将本轮题目和回答作为模板变量渲染用户提示词
 *   4. 通过 StructuredOutputInvoker 调用 LLM（内置重试 + JSON 修复）
 *   5. 对 LLM 产出的评估做标准化处理（得分钳位、空值兜底、缺失知识点截断）
 * </pre>
 *
 * <p><strong>联动关系：</strong>
 * <ul>
 *   <li>被 AdaptiveInterviewService 调用（evaluateAndDecide 编排第一步，
 *       产出 TurnEvaluationDTO 供后续画像更新和决策使用）</li>
 *   <li>AbilityProfileService 和 AdaptiveDecisionService 均不直接调用本服务，
 *       仅通过 AdaptiveInterviewService 间接传递 TurnEvaluationDTO</li>
 *   <li>依赖 LlmProviderRegistry（通过 getPlainChatClient 获取不含 SkillsTool
 *       的纯净 ChatClient，确保结构化 JSON 输出不被工具调用干扰）</li>
 *   <li>依赖 StructuredOutputInvoker 封装 LLM 调用、重试与 JSON 修复</li>
 *   <li>依赖 ResourceLoader 加载 classpath 下的 .st Prompt 模板文件</li>
 *   <li>依赖 BeanOutputConverter&lt;TurnEvaluationDTO&gt; 将 LLM 返回的 JSON
 *       反序列化为评估对象，并生成 JSON Schema 格式指令</li>
 *   <li>抛出 BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED，3005)
 *       当 LLM 全部重试失败时（由 StructuredOutputInvoker 抛出）</li>
 *   <li>使用 InterviewQuestionDTO 获取题目上下文，产出 TurnEvaluationDTO</li>
 * </ul>
 *
 * <p><strong>Prompt 模板：</strong>
 * 加载 classpath 下的 StringTemplate 模板文件：
 * <ul>
 *   <li>prompts/interview-turn-evaluation-system.st — 系统提示词（角色设定 + JSON Schema 格式约束）</li>
 *   <li>prompts/interview-turn-evaluation-user.st   — 用户提示词（题目+回答上下文注入）</li>
 * </ul>
 */
@Slf4j
@Service
public class TurnEvaluationService {

  /**
   * 结构化输出调用器
   *
   * <p>统一封装 LLM 结构化输出（JSON）的调用与重试策略。
   * 内置最多 N 次重试（从配置读取）、JSON 修复（自动修复未转义引号）、
   * 防注入指令追加、Metrics 指标记录。
   */
  private final StructuredOutputInvoker structuredOutputInvoker;

  /**
   * LLM 提供商注册表
   *
   * <p>管理多 LLM Provider 的注册与缓存，根据 providerId 路由到对应的 ChatClient。
   *
   * <p>使用 {@link LlmProviderRegistry#getPlainChatClient(String)} 而非
   * getChatClient()，因为即时评估需要结构化 JSON 输出，不能注册 SkillsTool
   * 和对话记忆 Advisor（可能触发工具调用混入响应，破坏 JSON 格式）。
   */
  private final LlmProviderRegistry llmProviderRegistry;

  /**
   * 系统提示词模板
   *
   * <p>从 classpath:prompts/interview-turn-evaluation-system.st 加载，
   * 定义即时评估助手的角色行为、评分维度和输出格式约束。
   * 运行时通过 {@link PromptTemplate#render()} 渲染后追加 JSON Schema 格式指令。
   */
  private final PromptTemplate systemPromptTemplate;

  /**
   * 用户提示词模板
   *
   * <p>从 classpath:prompts/interview-turn-evaluation-user.st 加载，
   * 接收本轮题目和回答上下文变量。
   * 运行时通过 {@link PromptTemplate#render(Map)} 注入具体变量值。
   */
  private final PromptTemplate userPromptTemplate;

  /**
   * JSON 到 TurnEvaluationDTO 的转换器
   *
   * <p>Spring AI 的 BeanOutputConverter，将 LLM 返回的 JSON 字符串
   * 反序列化为 TurnEvaluationDTO 对象。同时提供 {@link BeanOutputConverter#getFormat()}
   * 方法生成 JSON Schema 格式指令，追加到系统提示词末尾指导 LLM 按约定格式输出。
   */
  private final BeanOutputConverter<TurnEvaluationDTO> outputConverter;

  /**
   * 构造器，手动注入依赖并加载 Prompt 模板
   *
   * <p>由 Spring 创建 Bean 时调用，构造过程读取模板并初始化转换器，
   * 不执行 LLM 调用。
   *
   * <p>不使用 @RequiredArgsConstructor 而采用手动构造器，因为需要在构造阶段
   * 从 classpath 加载 .st 模板文件并初始化 PromptTemplate 和 BeanOutputConverter。
   *
   * @param structuredOutputInvoker 结构化输出调用器（含重试和 JSON 修复）
   * @param llmProviderRegistry LLM 提供商注册表
   * @param resourceLoader Spring 资源加载器，用于读取 classpath 下的模板文件
   * @throws IOException 模板文件读取失败时抛出
   */
  public TurnEvaluationService(
      StructuredOutputInvoker structuredOutputInvoker,
      LlmProviderRegistry llmProviderRegistry,
      ResourceLoader resourceLoader
  ) throws IOException {
    this.structuredOutputInvoker = structuredOutputInvoker;
    this.llmProviderRegistry = llmProviderRegistry;
    this.systemPromptTemplate = new PromptTemplate(resourceLoader
        .getResource("classpath:prompts/interview-turn-evaluation-system.st")
        .getContentAsString(StandardCharsets.UTF_8));
    this.userPromptTemplate = new PromptTemplate(resourceLoader
        .getResource("classpath:prompts/interview-turn-evaluation-user.st")
        .getContentAsString(StandardCharsets.UTF_8));
    this.outputConverter = new BeanOutputConverter<>(TurnEvaluationDTO.class);
  }

  /**
   * 对单轮答题进行 LLM 驱动的即时评估
   *
   * <p>将题目和回答上下文注入 Prompt 模板，调用 LLM 获取结构化的单轮评估结果。
   * 评估结果经过标准化处理：得分钳位到 0-100、深度等级钳位到 1-5、
   * 缺失知识点过滤空值并截断到 10 个、空分类兜底为原题分类。
   *
   * <p>调用位置：AdaptiveInterviewService.evaluateAndDecide() — 核心编排第1步
   *
   * @param llmProvider LLM 提供商标识（如 "dashscope"），用于路由 ChatClient
   * @param question 本轮题目，包含题号、题干、能力分类、知识点摘要等
   * @param answer 面试者的回答文本
   * @return 标准化后的单轮评估结果，含得分、深度等级、缺失知识点和追问建议
   * @throws BusinessException LLM 全部重试失败时抛出
   */
  public TurnEvaluationDTO evaluateTurn(
      String llmProvider,
      InterviewQuestionDTO question,
      String answer
  ) {
    ChatClient chatClient = llmProviderRegistry.getPlainChatClient(llmProvider);
    // 系统提示词 = 角色设定模板 + JSON Schema 格式指令
    String systemPrompt = systemPromptTemplate.render() + "\n\n" + outputConverter.getFormat();
    // 用户提示词 = 模板变量注入后的本轮题目+回答上下文
    String userPrompt = userPromptTemplate.render(buildVariables(question, answer));

    TurnEvaluationDTO evaluation = structuredOutputInvoker.invoke(
        chatClient,
        systemPrompt,
        userPrompt,
        outputConverter,
        ErrorCode.INTERVIEW_EVALUATION_FAILED,
        "即时评估失败：",
        "面试即时评估",
        log
    );
    // 标准化处理：得分钳位、空值兜底、缺失知识点过滤截断
    return normalizeEvaluation(question, evaluation);
  }

  /**
   * 标准化 LLM 产出的评估结果
   *
   * <p>仅由 {@link #evaluateTurn} 在 LLM 返回后调用，
   * 内部使用 {@link #safe(String)} 处理可选文本字段。
   *
   * <p>LLM 可能返回超出范围的值、null 字段或不规范格式，本方法对各字段做安全处理：
   * <ul>
   *   <li>得分（score）：钳位到 [0, 100] 区间</li>
   *   <li>深度等级（depthLevel）：钳位到 [1, 5] 区间</li>
   *   <li>缺失知识点（missingKeyPoints）：过滤 null/空白项，截断到最多 10 个</li>
   *   <li>追问建议（followUpSuggestion）：null 转为空字符串</li>
   *   <li>能力分类（category）：空时使用原题分类兜底</li>
   *   <li>题号（questionIndex）：强制使用原题的 questionIndex，不从 LLM 取值</li>
   * </ul>
   *
   * @param question 原题信息，用于题号和分类兜底
   * @param evaluation LLM 返回的原始评估结果
   * @return 经过标准化处理的安全评估对象
   */
  private TurnEvaluationDTO normalizeEvaluation(
      InterviewQuestionDTO question,
      TurnEvaluationDTO evaluation
  ) {
    // 得分钳位到 0-100 区间
    int score = Math.max(0, Math.min(100, evaluation.score()));
    // 深度等级钳位到 1-5 区间
    int depthLevel = Math.max(1, Math.min(5, evaluation.depthLevel()));
    // 缺失知识点过滤空值并截断到 10 个
    List<String> missingKeyPoints = evaluation.missingKeyPoints() == null
        ? List.of()
        : evaluation.missingKeyPoints().stream()
            .filter(item -> item != null && !item.isBlank())
            .limit(10)
            .toList();
    String followUpSuggestion = safe(evaluation.followUpSuggestion());
    // 能力分类为空时使用原题分类兜底
    String category = evaluation.category() == null || evaluation.category().isBlank()
        ? safe(question.category())
        : evaluation.category().trim();
    return new TurnEvaluationDTO(
        question.questionIndex(),
        score,
        depthLevel,
        missingKeyPoints,
        followUpSuggestion,
        category
    );
  }

  /**
   * 构建 Prompt 模板的变量映射
   *
   * <p>仅由 {@link #evaluateTurn} 在渲染用户 Prompt 前调用，
   * 通过 {@link #safe(String)} 避免模板变量出现 null。
   *
   * <p>将题目的各个字段和回答文本组装为 key-value 映射，注入到用户提示词模板中。
   * 空值统一转为空字符串，避免 LLM 收到 "null" 字面量。
   *
   * @param question 本轮题目
   * @param answer 面试者的回答文本
   * @return 包含题目所有字段和回答的变量映射
   */
  private Map<String, Object> buildVariables(InterviewQuestionDTO question, String answer) {
    Map<String, Object> variables = new HashMap<>();
    variables.put("questionIndex", question.questionIndex());
    variables.put("category", safe(question.category()));
    variables.put("type", safe(question.type()));
    variables.put("question", safe(question.question()));
    variables.put("topicSummary", safe(question.topicSummary()));
    variables.put("answer", safe(answer));
    return variables;
  }

  /**
   * 空安全的字符串处理
   *
   * <p>由评估标准化和 Prompt 变量构建共同调用。
   *
   * <p>将 null 转为空字符串，确保 Prompt 模板变量不会出现 "null" 字面量。
   *
   * @param value 可能为 null 的字符串
   * @return 非 null 的字符串，null 时返回 ""
   */
  private String safe(String value) {
    return value == null ? "" : value;
  }
}
