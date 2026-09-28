package interview.guide.modules.interview.adaptive;

import interview.guide.modules.interview.adaptive.model.AbilityDimensionDTO;
import interview.guide.modules.interview.adaptive.model.AbilityProfileDTO;
import interview.guide.modules.interview.adaptive.model.TurnEvaluationDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自适应面试 - 能力画像服务
 *
 * 联动关系：
 * - 被 AdaptiveInterviewService 调用（evaluateAndDecide 中更新画像、
 *   persistResult 中序列化、attachAdaptiveAnalysis 中反序列化）
 * - TurnerEvaluationService 不直接调用本服务，其产出的 TurnerEvaluationDTO 由
 *   AdaptiveInterviewService 传入 updateProfile() 间接协作
 * - AdaptiveDecisionService 不直接调用本服务，仅接收 AbilityProfileDTO 作为
 *   decideNextStep() 的方法参数，注入 LLM Prompt 模板变量
 * - 依赖 ObjectMapper 进行 AbilityProfileDTO 的 JSON 序列化与反序列化
 * - 使用 AbilityDimensionDTO 表示各能力维度的累积得分、置信度与薄弱关键词
 * - 使用 TurnerEvaluationDTO 获取本轮得分和缺失知识点，用于维度更新
 *
 * <p><strong>核心职责：</strong>
 * 负责面试者能力画像的累积更新、序列化/反序列化。每次答题评估后，
 * 将本轮得分按能力维度加权平均合并到历史画像中，动态反映面试者的强弱项变化。
 *
 * <p><strong>关键算法：</strong>
 * 各维度得分采用加权移动平均：
 * newScore = (previousScore × previousCount + currentScore) / newCount
 * 置信度随评估次数递增：confidence = min(1.0, 0.25 + count × 0.15)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AbilityProfileService {

  /**
   * Jackson ObjectMapper 实例
   *
   * <p>作用：用于 AbilityProfileDTO 的 JSON 序列化与反序列化。
   * Spring Boot 自动配置的 ObjectMapper，支持 Java 8 时间类型和 Jackson 注解。
   *
   * <p>使用场景：
   * <ul>
   *   <li>{@link #toJson(AbilityProfileDTO)} — 将画像对象序列化为 JSON 字符串，存入数据库会话记录</li>
   *   <li>{@link #parseProfile(String)} — 将数据库中的 JSON 字符串反序列化为画像对象</li>
   * </ul>
   */
  private final ObjectMapper objectMapper;

  /**
   * 根据本轮评估结果更新能力画像
   *
   * <p>将已有的能力画像 JSON 反序列化为对象，按能力分类将本轮得分合并到对应维度，
   * 重新计算各维度得分、置信度和薄弱关键词，生成新的画像对象。
   *
   * <p>首次答题时 previousProfileJson 为 null，方法会创建初始画像。
   *
   * <p><strong>调用入口：</strong>
   * {@code AdaptiveInterviewService.evaluateAndDecide} 每轮答题后的核心编排步骤。
   *
   * @param previousProfileJson 上一轮的能力画像 JSON 字符串，首次调用时为 null
   * @param evaluation 本轮评估结果，包含得分、能力分类、缺失知识点等
   * @return 合并本轮数据后的新能力画像，维度列表按插入顺序排列
   */
  public AbilityProfileDTO updateProfile(String previousProfileJson, TurnEvaluationDTO evaluation) {
    // 使用 LinkedHashMap 保留维度的插入顺序，保证画像输出稳定
    Map<String, AbilityDimensionDTO> dimensions = new LinkedHashMap<>();
    AbilityProfileDTO previous = parseProfile(previousProfileJson);
    if (previous != null && previous.dimensions() != null) {
      for (AbilityDimensionDTO dimension : previous.dimensions()) {
        dimensions.put(dimension.category(), dimension);
      }
    }

    // 标准化分类名称（空值兜底为"综合能力"），按分类更新对应维度
    String category = normalizeCategory(evaluation.category());
    AbilityDimensionDTO current = dimensions.get(category);
    dimensions.put(category, updateDimension(category, current, evaluation));

    // 计算所有维度的平均分作为画像综合得分
    List<AbilityDimensionDTO> updatedDimensions = new ArrayList<>(dimensions.values());
    int averageScore = (int) Math.round(updatedDimensions.stream()
        .mapToInt(AbilityDimensionDTO::score)
        .average()
        .orElse(0));
    return new AbilityProfileDTO(
        updatedDimensions,
        averageScore,
        buildSummary(updatedDimensions)
    );
  }

  /**
   * 将能力画像对象序列化为 JSON 字符串
   *
   * <p>用于持久化存储，将更新后的画像写入数据库的 abilityProfileJson 字段。
   *
   * <p>调用位置：AdaptiveInterviewService.persistResult()
   *
   * @param profile 能力画像对象
   * @return 序列化后的 JSON 字符串，序列化失败时返回 "{}" 空对象
   */
  public String toJson(AbilityProfileDTO profile) {
    try {
      return objectMapper.writeValueAsString(profile);
    } catch (JacksonException e) {
      log.warn("序列化能力画像失败", e);
      return "{}";
    }
  }

  /**
   * 将 JSON 字符串反序列化为能力画像对象
   *
   * <p>用于从数据库读取历史画像 JSON 并恢复为对象结构。
   * 输入为 null 或空白字符串时直接返回 null，避免不必要的解析。
   *
   * <p>调用位置：
   * <ul>
   *   <li>updateProfile() — 读取上轮画像</li>
   *   <li>AdaptiveInterviewService.attachAdaptiveAnalysis() — 生成报告时读取画像</li>
   * </ul>
   *
   * @param profileJson 画像 JSON 字符串，可为 null 或空白
   * @return 反序列化后的能力画像对象，解析失败或输入为空时返回 null
   */
  public AbilityProfileDTO parseProfile(String profileJson) {
    if (profileJson == null || profileJson.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(profileJson, AbilityProfileDTO.class);
    } catch (JacksonException e) {
      log.warn("解析能力画像失败，使用空画像", e);
      return null;
    }
  }

  /**
   * 更新单个能力维度的得分和元数据
   *
   * <p>仅由 {@link #updateProfile(String, TurnEvaluationDTO)} 调用，
   * 内部调用 {@link #mergeWeakKeywords(List, List)} 合并薄弱知识点。
   *
   * <p>核心算法：加权移动平均
   * <pre>
   *   newScore = (previousScore × previousCount + currentScore) / (previousCount + 1)
   * </pre>
   * 历史得分与历史次数加权后合并当前得分，使得早期表现和近期表现均被合理计入。
   *
   * <p>置信度随评估次数递增，起始值 0.25，每次评估 +0.15，上限 1.0。
   * 评估次数越多，对该维度能力的判断越有把握。
   *
   * @param category 能力分类名称（经过标准化处理）
   * @param current 当前维度的历史数据，首次评估时为 null
   * @param evaluation 本轮评估结果
   * @return 合并本轮数据后的维度快照，包含更新后的得分、置信度、薄弱关键词
   */
  private AbilityDimensionDTO updateDimension(
      String category,
      AbilityDimensionDTO current,
      TurnEvaluationDTO evaluation
  ) {
    // 获取历史得分和评估次数；首次评估时直接使用本轮得分，count 从 0 开始
    int previousScore = current == null ? evaluation.score() : current.score();
    int previousCount = current == null ? 0 : current.questionCount();
    int newCount = previousCount + 1;
    // 加权移动平均：历史累计得分 + 本轮得分，除以总次数
    int newScore = (int) Math.round(
        (previousScore * previousCount + evaluation.score()) / (double) newCount
    );
    // 置信度：起始 0.25，每增加一次评估 +0.15，上限 1.0
    double confidence = Math.min(1.0, 0.25 + newCount * 0.15);
    // 合并历史薄弱关键词与本轮缺失知识点，去重保留顺序，最多 8 个
    List<String> weakKeywords = mergeWeakKeywords(
        current == null ? List.of() : current.weakKeywords(),
        evaluation.missingKeyPoints()
    );
    return new AbilityDimensionDTO(
        category,
        newScore,
        confidence,
        newCount,
        performanceLabel(evaluation.score()),
        weakKeywords
    );
  }

  /**
   * 合并历史薄弱关键词与本轮缺失知识点
   *
   * <p>仅由 {@link #updateDimension} 调用，不修改输入列表。
   *
   * <p>使用 LinkedHashMap 实现去重并保留首次出现顺序。
   * 过滤掉 null 和空白字符串，最终截断到最多 8 个关键词。
   *
   * @param existing 历史累计的薄弱关键词列表（来自能力维度 existing.weakKeywords()）
   * @param incoming 本轮评估发现的缺失知识点（来自 TurnEvaluationDTO.missingKeyPoints()）
   * @return 去重合并后的薄弱关键词列表，保留插入顺序，最多 8 个
   */
  private List<String> mergeWeakKeywords(List<String> existing, List<String> incoming) {
    LinkedHashMap<String, String> values = new LinkedHashMap<>();
    if (existing != null) {
      existing.stream()
          .filter(item -> item != null && !item.isBlank())
          .forEach(item -> values.put(item, item));
    }
    if (incoming != null) {
      incoming.stream()
          .filter(item -> item != null && !item.isBlank())
          .forEach(item -> values.put(item, item));
    }
    // 截断到 8 个，避免薄弱关键词列表无限膨胀
    return values.values().stream().limit(8).toList();
  }

  /**
   * 生成能力画像的文字摘要
   *
   * <p>仅由 {@link #updateProfile(String, TurnEvaluationDTO)}
   * 在所有维度更新完成后调用。
   *
   * <p>从所有维度中找出得分最高和最低的维度，拼接为可读的强弱项摘要。
   * 维度列表为空时返回兜底提示。
   *
   * @param dimensions 更新后的能力维度列表
   * @return 形如 "当前强项：算法能力，待加强：系统设计" 的摘要文本
   */
  private String buildSummary(List<AbilityDimensionDTO> dimensions) {
    if (dimensions.isEmpty()) {
      return "暂无能力画像";
    }
    AbilityDimensionDTO weakest = dimensions.stream()
        .min(java.util.Comparator.comparingInt(AbilityDimensionDTO::score))
        .orElse(dimensions.getFirst());
    AbilityDimensionDTO strongest = dimensions.stream()
        .max(java.util.Comparator.comparingInt(AbilityDimensionDTO::score))
        .orElse(dimensions.getFirst());
    return "当前强项：" + strongest.category() + "，待加强：" + weakest.category();
  }

  /**
   * 根据得分映射表现等级标签
   *
   * <p>仅由 {@link #updateDimension} 为本轮维度快照生成标签时调用。
   *
   * <p>阈值划分（闭区间上界）：
   * <ul>
   *   <li>85 分及以上 → "优秀"</li>
   *   <li>70-84 分 → "稳定"</li>
   *   <li>55-69 分 → "一般"</li>
   *   <li>55 分以下 → "薄弱"</li>
   * </ul>
   *
   * @param score 本轮得分（0-100）
   * @return 对应的中文表现等级标签
   */
  private String performanceLabel(int score) {
    if (score >= 85) {
      return "优秀";
    }
    if (score >= 70) {
      return "稳定";
    }
    if (score >= 55) {
      return "一般";
    }
    return "薄弱";
  }

  /**
   * 标准化能力分类名称
   *
   * <p>仅由 {@link #updateProfile(String, TurnEvaluationDTO)} 调用，
   * 确保空分类归入“综合能力”。
   *
   * <p>处理 LLM 可能返回 null 或不规范空格的分类名称，提供兜底默认值。
   *
   * @param category 原始分类名称，可能为 null、空白或含前后空格
   * @return 标准化后的分类名称，null/空白时返回 "综合能力"，否则去除前后空格
   */
  private String normalizeCategory(String category) {
    return category == null || category.isBlank() ? "综合能力" : category.trim();
  }
}
