package interview.guide.modules.interview.adaptive;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.adaptive.model.AbilityProfileDTO;
import interview.guide.modules.interview.adaptive.model.AdaptiveDecisionDTO;
import interview.guide.modules.interview.adaptive.model.TurnEvaluationDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 自适应面试 - 策略决策服务
 *
 * 联动关系：
 * - 被 AdaptiveInterviewService 调用（evaluateAndDecide 中 LLM 驱动决策主路径，
 *   catch 块中兜底降级）
 * - AdaptiveInterviewService 是唯一调用方，其他自适应模块不直接调用本服务
 * - 依赖 LlmProviderRegistry（通过 getPlainChatClient 获取不含 SkillsTool 的纯净
 *   ChatClient，确保结构化 JSON 输出不被工具调用干扰）
 * - 依赖 StructuredOutputInvoker 封装 LLM 调用、重试与 JSON 修复
 * - 依赖 ObjectMapper 将 TurnerEvaluationDTO 和 AbilityProfileDTO 序列化为 JSON
 *   注入 Prompt 模板变量
 * - 依赖 ResourceLoader 加载 classpath 下的 .st Prompt 模板文件
 * - 依赖 {@code BeanOutputConverter<AdaptiveDecisionDTO>} 将 LLM 返回的 JSON 反序列化
 *   为决策对象，并生成 JSON Schema 格式指令追加到系统提示词
 * - 抛出 BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED，3005) 当 LLM
 *   全部重试失败时
 * - 使用 AdaptiveDecisionDTO 作为决策产出，TurnerEvaluationDTO 和 AbilityProfileDTO
 *   作为输入上下文
 *
 * <p><strong>核心职责：</strong>
 * 根据单轮答题评估结果和能力画像，决定下一道题的策略：
 * <ul>
 *   <li>主路径：将本轮上下文（题目、回答、评估、画像）注入 Prompt 模板，由 LLM 产出结构化决策</li>
 *   <li>兜底路径：LLM 调用失败时，基于得分和缺失知识点的本地规则直接计算决策</li>
 * </ul>
 *
 * <p><strong>决策动作说明：</strong>
 * <table>
 *   <caption>自适应决策动作</caption>
 *   <tr><th>动作</th><th>含义</th></tr>
 *   <tr><td>FOLLOW_UP</td><td>追问当前题目，确认理解深度</td></tr>
 *   <tr><td>NEXT_CATEGORY</td><td>切换能力分类，进入下一道新题</td></tr>
 *   <tr><td>RAISE_DIFFICULTY</td><td>提升难度等级（junior→mid→senior）</td></tr>
 *   <tr><td>LOWER_DIFFICULTY</td><td>降低难度等级</td></tr>
 *   <tr><td>DEEP_DIVE_PROJECT</td><td>深挖项目经验</td></tr>
 *   <tr><td>END_PHASE</td><td>结束当前阶段</td></tr>
 * </table>
 *
 * <p><strong>Prompt 模板：</strong>
 * 加载 classpath 下的 StringTemplate 模板文件：
 * <ul>
 *   <li>prompts/interview-adaptive-decision-system.st — 系统提示词（角色设定 + JSON Schema 格式约束）</li>
 *   <li>prompts/interview-adaptive-decision-user.st   — 用户提示词（本轮上下文变量注入）</li>
 * </ul>
 */
@Slf4j
@Service
public class AdaptiveDecisionService {

  /**
   * 结构化输出调用器
   *
   * <p>统一封装 LLM 结构化输出（JSON）的调用与重试策略。
   * 内置最多 N 次重试（从配置读取）、JSON 修复（自动修复未转义引号）、
   * 防注入指令追加、Metrics 指标记录。
   *
   * <p>使用场景：{@link #decideNextStep} 中通过 LLM 获取 AdaptiveDecisionDTO
   */
  private final StructuredOutputInvoker structuredOutputInvoker;

  /**
   * LLM 提供商注册表
   *
   * <p>管理多 LLM Provider 的注册与缓存，根据 providerId 路由到对应的 ChatClient。
   *
   * <p>这里使用 {@link LlmProviderRegistry#getPlainChatClient(String)} 而非
   * getChatClient()，因为自适应决策需要结构化 JSON 输出，不能注册 SkillsTool
   * 和对话记忆 Advisor（可能触发工具调用混入响应，破坏 JSON 格式）。
   */
  private final LlmProviderRegistry llmProviderRegistry;

  /**
   * Jackson ObjectMapper 实例
   *
   * <p>用于将 TurnEvaluationDTO 和 AbilityProfileDTO 序列化为 JSON 字符串，
   * 作为 Prompt 模板变量注入到 LLM 上下文中。
   */
  private final ObjectMapper objectMapper;

  /**
   * 系统提示词模板
   *
   * <p>从 classpath:prompts/interview-adaptive-decision-system.st 加载，
   * 定义自适应决策助手的角色行为、决策动作说明和输出格式约束。
   * 运行时通过 {@link PromptTemplate#render()} 渲染后追加 JSON Schema 格式指令。
   */
  private final PromptTemplate systemPromptTemplate;

  /**
   * 用户提示词模板
   *
   * <p>从 classpath:prompts/interview-adaptive-decision-user.st 加载，
   * 接收本轮上下文变量（难度、题目、回答、评估、画像）。
   * 运行时通过 {@link PromptTemplate#render(Map)} 注入具体变量值。
   */
  private final PromptTemplate userPromptTemplate;

  /**
   * JSON 到 AdaptiveDecisionDTO 的转换器
   *
   * <p>Spring AI 的 BeanOutputConverter，将 LLM 返回的 JSON 字符串
   * 反序列化为 AdaptiveDecisionDTO 对象。同时提供 {@link BeanOutputConverter#getFormat()}
   * 方法生成 JSON Schema 格式指令，追加到系统提示词末尾指导 LLM 按约定格式输出。
   */
  private final BeanOutputConverter<AdaptiveDecisionDTO> outputConverter;

  /**
   * 构造器，手动注入依赖并加载 Prompt 模板
   *
   * <p>由 Spring 创建 Bean 时调用，构造过程只读取 classpath 模板并初始化转换器，
   * 不调用 LLM。
   *
   * <p>不使用 @RequiredArgsConstructor 而采用手动构造器，因为需要在构造阶段
   * 从 classpath 加载 .st 模板文件并初始化 PromptTemplate 和 BeanOutputConverter。
   *
   * @param structuredOutputInvoker 结构化输出调用器（含重试和 JSON 修复）
   * @param llmProviderRegistry LLM 提供商注册表
   * @param objectMapper JSON 序列化工具
   * @param resourceLoader Spring 资源加载器，用于读取 classpath 下的模板文件
   * @throws IOException 模板文件读取失败时抛出
   */
  public AdaptiveDecisionService(
      StructuredOutputInvoker structuredOutputInvoker,
      LlmProviderRegistry llmProviderRegistry,
      ObjectMapper objectMapper,
      ResourceLoader resourceLoader
  ) throws IOException {
    this.structuredOutputInvoker = structuredOutputInvoker;
    this.llmProviderRegistry = llmProviderRegistry;
    this.objectMapper = objectMapper;
    this.systemPromptTemplate = new PromptTemplate(resourceLoader
        .getResource("classpath:prompts/interview-adaptive-decision-system.st")
        .getContentAsString(StandardCharsets.UTF_8));
    this.userPromptTemplate = new PromptTemplate(resourceLoader
        .getResource("classpath:prompts/interview-adaptive-decision-user.st")
        .getContentAsString(StandardCharsets.UTF_8));
    this.outputConverter = new BeanOutputConverter<>(AdaptiveDecisionDTO.class);
  }

  /**
   * LLM 驱动的自适应决策（主路径）
   *
   * <p>将本轮面试上下文（难度、题目、回答、评估结果、能力画像）注入 Prompt 模板，
   * 调用 LLM 获取结构化的下一步决策。决策结果会经过标准化处理，确保字段有效性。
   *
   * <p>调用位置：AdaptiveInterviewService.evaluateAndDecide() — 每轮答题后的决策步骤
   *
   * <p><strong>处理流程：</strong>
   * <ol>
   *   <li>通过 LlmProviderRegistry 获取纯净 ChatClient（不含 SkillsTool）</li>
   *   <li>渲染系统提示词并追加 JSON Schema 格式指令</li>
   *   <li>将本轮上下文序列化为 JSON 作为模板变量，渲染用户提示词</li>
   *   <li>通过 StructuredOutputInvoker 调用 LLM（内置重试）</li>
   *   <li>对 LLM 产出的决策做标准化处理（动作合法性校验、空值兜底）</li>
   * </ol>
   *
   * @param llmProvider LLM 提供商标识（如 "dashscope"），用于路由 ChatClient
   * @param currentDifficulty 当前难度等级：junior / mid / senior
   * @param question 本轮题目，包含题干文本和能力分类
   * @param answer 面试者的回答文本
   * @param evaluation 本轮答题评估结果，包含得分、缺失知识点、追问建议
   * @param profile 更新后的能力画像，包含各维度得分和置信度
   * @return 标准化后的策略决策，包含动作、目标分类、难度、理由和下一题文本
   * @throws BusinessException LLM 全部重试失败时抛出
   */
  public AdaptiveDecisionDTO decideNextStep(
      String llmProvider,
      String currentDifficulty,
      InterviewQuestionDTO question,
      String answer,
      TurnEvaluationDTO evaluation,
      AbilityProfileDTO profile
  ) {
    ChatClient chatClient = llmProviderRegistry.getPlainChatClient(llmProvider);
    // 系统提示词 = 角色设定模板 + JSON Schema 格式指令
    String systemPrompt = systemPromptTemplate.render() + "\n\n" + outputConverter.getFormat();
    // 用户提示词 = 模板变量注入后的本轮上下文
    String userPrompt = userPromptTemplate.render(buildVariables(
        currentDifficulty,
        question,
        answer,
        evaluation,
        profile
    ));

    AdaptiveDecisionDTO decision = structuredOutputInvoker.invoke(
        chatClient,
        systemPrompt,
        userPrompt,
        outputConverter,
        ErrorCode.INTERVIEW_EVALUATION_FAILED,
        "自适应决策失败：",
        "面试自适应决策",
        log
    );
    // 标准化处理：校验动作合法性，填补空字段
    return normalizeDecision(decision, question, currentDifficulty);
  }

  /**
   * 基于本地规则的兜底决策（LLM 失败时的降级路径）
   *
   * <p>完全基于确定性规则，不依赖外部 LLM 调用。根据本轮得分和缺失知识点
   * 快速给出下一步策略，保证面试流程不中断。
   *
   * <p>调用位置：AdaptiveInterviewService.evaluateAndDecide() 的 catch 块
   *
   * <p><strong>决策规则：</strong>
   * <table>
   *   <caption>LLM 失败时的本地决策规则</caption>
 *   <tr><th>条件</th><th>动作</th><th>说明</th></tr>
   *   <tr><td>score &gt;= 85</td><td>RAISE_DIFFICULTY</td><td>回答优秀，提升难度验证上限</td></tr>
   *   <tr><td>score &lt; 70 或存在缺失知识点</td><td>FOLLOW_UP</td><td>回答薄弱，追问确认理解深度</td></tr>
   *   <tr><td>其他情况</td><td>NEXT_CATEGORY</td><td>基本达标，切换分类保持节奏</td></tr>
   * </table>
   *
   * @param question 本轮题目
   * @param evaluation 本轮答题评估结果
   * @param currentDifficulty 当前难度等级
   * @return 本地规则产出的兜底决策
   */
  public AdaptiveDecisionDTO fallbackDecision(
      InterviewQuestionDTO question,
      TurnEvaluationDTO evaluation,
      String currentDifficulty
  ) {
    if (evaluation.score() >= 85) {
      return new AdaptiveDecisionDTO(
          "RAISE_DIFFICULTY",
          question.category(),
          raiseDifficulty(currentDifficulty),
          "本轮回答完整且有深度，建议提高难度验证上限。",
          "请进一步说明这个问题在高并发或复杂业务场景下的取舍。"
      );
    }
    if (evaluation.score() < 70 || !safeList(evaluation.missingKeyPoints()).isEmpty()) {
      return new AdaptiveDecisionDTO(
          "FOLLOW_UP",
          question.category(),
          currentDifficulty,
          "本轮回答存在关键点缺失，需要通过追问确认理解深度。",
          evaluation.followUpSuggestion()
      );
    }
    return new AdaptiveDecisionDTO(
        "NEXT_CATEGORY",
        question.category(),
        currentDifficulty,
        "本轮回答基本达标，可以切换到下一题继续覆盖能力面。",
        null
    );
  }

  /**
   * 构建 Prompt 模板的变量映射
   *
   * <p>仅由 {@link #decideNextStep} 调用，内部使用 {@link #safe(String)}
   * 处理文本空值，并使用 {@link #toJson(Object)} 序列化结构化上下文。
   *
   * <p>将本轮面试上下文的所有字段组装为 key-value 映射，其中评估结果和
   * 能力画像通过 ObjectMapper 序列化为 JSON 字符串传递给模板。
   *
   * @param currentDifficulty 当前难度等级
   * @param question 本轮题目
   * @param answer 面试者回答
   * @param evaluation 本轮评估结果
   * @param profile 能力画像
   * @return 包含所有模板变量名称到值的映射，空值统一转为空字符串
   */
  private Map<String, Object> buildVariables(
      String currentDifficulty,
      InterviewQuestionDTO question,
      String answer,
      TurnEvaluationDTO evaluation,
      AbilityProfileDTO profile
  ) {
    Map<String, Object> variables = new HashMap<>();
    variables.put("question", safe(question.question()));
    variables.put("category", safe(question.category()));
    variables.put("currentDifficulty", safe(currentDifficulty));
    variables.put("answer", safe(answer));
    // 评估结果和画像序列化为 JSON，便于 LLM 理解结构化数据
    variables.put("turnEvaluationJson", toJson(evaluation));
    variables.put("abilityProfileJson", toJson(profile));
    return variables;
  }

  /**
   * 将对象序列化为 JSON 字符串（带容错）
   *
   * <p>仅由 {@link #buildVariables} 调用；序列化失败记录警告并回退为空对象 JSON。
   *
   * <p>序列化失败时返回空 JSON 对象 "{}"，避免因序列化异常中断整个决策流程。
   *
   * @param value 待序列化的对象
   * @return JSON 字符串，序列化失败时返回 "{}"
   */
  private String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException e) {
      log.warn("自适应决策上下文序列化失败", e);
      return "{}";
    }
  }

  /**
   * 提升难度等级
   *
   * <p>仅由 {@link #fallbackDecision} 的高分分支调用，不修改会话持久化状态。
   *
   * <p>难度递进规则：junior → mid → senior，到达 senior 后保持不变。
   * 当前难度为 null 或空白时默认返回 mid。
   *
   * @param currentDifficulty 当前难度等级
   * @return 提升后的难度等级
   */
  private String raiseDifficulty(String currentDifficulty) {
    return switch (currentDifficulty == null ? "" : currentDifficulty) {
      case "junior" -> "mid";
      case "mid" -> "senior";
      default -> currentDifficulty == null || currentDifficulty.isBlank()
          ? "mid" : currentDifficulty;
    };
  }

  /**
   * 标准化 LLM 产出的决策结果
   *
   * <p>仅由 {@link #decideNextStep} 在 LLM 返回后调用，
   * 内部继续调用 {@link #normalizeAction(String)}、{@link #safe(String)}
   * 和 {@link #blankToNull(String)}。
   *
   * <p>LLM 可能返回不合法动作、空字段或不规范格式，本方法对各字段做兜底处理：
   * <ul>
   *   <li>动作（action）：非法值统一回退为 FOLLOW_UP</li>
   *   <li>目标分类（targetCategory）：空时使用原题分类</li>
   *   <li>下一题难度（nextDifficulty）：空时保持当前难度</li>
   *   <li>下一题文本（nextQuestion）：空白字符串转为 null</li>
   * </ul>
   *
   * @param decision LLM 返回的原始决策
   * @param question 本轮题目（用于空字段兜底）
   * @param currentDifficulty 当前难度（用于空字段兜底）
   * @return 经过标准化处理的安全决策对象
   */
  private AdaptiveDecisionDTO normalizeDecision(
      AdaptiveDecisionDTO decision,
      InterviewQuestionDTO question,
      String currentDifficulty
  ) {
    String action = normalizeAction(decision.action());
    String targetCategory = decision.targetCategory() == null || decision.targetCategory().isBlank()
        ? safe(question.category())
        : decision.targetCategory().trim();
    String nextDifficulty = decision.nextDifficulty() == null || decision.nextDifficulty().isBlank()
        ? safe(currentDifficulty)
        : decision.nextDifficulty().trim();
    return new AdaptiveDecisionDTO(
        action,
        targetCategory,
        nextDifficulty,
        safe(decision.reason()),
        blankToNull(decision.nextQuestion())
    );
  }

  /**
   * 校验并标准化决策动作
   *
   * <p>仅由 {@link #normalizeDecision} 调用，确保下游只接收六种受支持动作。
   *
   * <p>只允许 6 种预定义动作，非法值统一回退为 FOLLOW_UP，确保后续流程
   * 不会因未知动作而中断。
   *
   * <p>有效动作列表：FOLLOW_UP、NEXT_CATEGORY、RAISE_DIFFICULTY、
   * LOWER_DIFFICULTY、DEEP_DIVE_PROJECT、END_PHASE
   *
   * @param action LLM 返回的原始动作字符串
   * @return 标准化后的动作，null/非法值时返回 "FOLLOW_UP"
   */
  private String normalizeAction(String action) {
    if (action == null || action.isBlank()) {
      return "FOLLOW_UP";
    }
    return switch (action.trim()) {
      case "FOLLOW_UP", "NEXT_CATEGORY", "RAISE_DIFFICULTY", "LOWER_DIFFICULTY",
          "DEEP_DIVE_PROJECT", "END_PHASE" -> action.trim();
      default -> "FOLLOW_UP";
    };
  }

  /**
   * 空安全的列表处理
   *
   * <p>仅由 {@link #fallbackDecision} 检查缺失知识点时调用。
   *
   * <p>将 null 列表转为空不可变列表，避免下游的 NullPointerException。
   *
   * @param values 可能为 null 的字符串列表
   * @return 非 null 的列表，null 时返回空列表
   */
  private List<String> safeList(List<String> values) {
    return values == null ? List.of() : values;
  }

  /**
   * 空安全的字符串处理
   *
   * <p>由 Prompt 变量构建和决策标准化共同调用。
   *
   * <p>将 null 转为空字符串，确保 Prompt 模板变量不会出现 "null" 字面量。
   *
   * @param value 可能为 null 的字符串
   * @return 非 null 的字符串，null 时返回 ""
   */
  private String safe(String value) {
    return value == null ? "" : value;
  }

  /**
   * 空白字符串转 null
   *
   * <p>仅由 {@link #normalizeDecision} 规范化可选下一题时调用。
   *
   * <p>将 null 或空白字符串转为 null，用于 nextQuestion 字段，
   * 因为无追问时该字段应为 null 而非空字符串。
   *
   * @param value 可能为 null 或空白的字符串
   * @return 去除前后空格后的字符串，null 或空白时返回 null
   */
  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
