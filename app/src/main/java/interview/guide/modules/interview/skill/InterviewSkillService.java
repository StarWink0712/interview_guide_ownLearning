/**
 * 面试方向（Skill）管理服务
 *
 * 负责 Skill 的全生命周期管理：启动时从 classpath:skills/ 加载预设 Skill（SKILL.md + skill.meta.yml）、
 * 构建分类→参考文件映射、提供分类分配算法（ALWAYS_ONE/CORE/Normal 三级优先）、
 * 批量注入参考内容到 Prompt、JD 解析（AI 提取面试分类）、自定义 Skill 构建等。
 * 参考内容按分类加载并缓存，支持 shared 和 skill-local 两种作用域。
 *
 * 联动关系：
 * - 被 InterviewSkillController 调用提供 Skill 查询和 JD 解析 API
 * - 被 InterviewQuestionService 调用获取 Skill 定义、分类分配和参考内容
 * - 被 AnswerEvaluationService 调用构建评估参考基线
 * - 依赖 LlmProviderRegistry 获取 ChatClient（JD 解析）
 * - 依赖 StructuredOutputInvoker（common.ai）做 JD 解析的结构化输出
 * - 依赖 PromptSanitizer（common.ai）清洗 JD 输入
 * - 使用 resources/prompts/jd-parse-system.st 模板
 * - 使用 resources/skills/ 目录下的 SKILL.md 和 skill.meta.yml 配置文件
 */
package interview.guide.modules.interview.skill;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 面试方向 Skill 管理：分类分配、References 注入、自定义 Skill 构建。
 *
 * 与 SkillsTool 互补：SkillsTool 负责 LLM 按需加载 persona（SKILL.md body），
 * 本类负责后端解析分类配置（skill.meta.yml）并批量注入 references 到 Prompt。
 */
@Slf4j
@Service
public class InterviewSkillService {

    public static final String CUSTOM_SKILL_ID = "custom";

    private static final int MIN_JD_LENGTH = 50;
    private static final int MAX_CATEGORY_LABEL_LENGTH = 50;
    private static final int MAX_CATEGORY_KEY_LENGTH = 50;

    private static final Pattern FRONT_MATTER_PATTERN = Pattern.compile("(?s)^---\\s*\\n(.*?)\\n---\\s*\\n?(.*)$");
    private static final Pattern SKILL_ID_PATTERN = Pattern.compile(".*/skills/([^/]+)/SKILL\\.md$");
    private static final String SKILL_META_FILE = "skill.meta.yml";
    private static final String JD_PARSE_SYSTEM_PROMPT_PATH = "classpath:prompts/jd-parse-system.st";

    private static final int MAX_REFERENCE_SECTION_CHARS = 12000;
    private static final int MAX_EVALUATION_REFERENCE_SECTION_CHARS = 6000;
    private static final int MAX_SINGLE_REFERENCE_CHARS = 3000;

    private final LlmProviderRegistry llmProviderRegistry;
    private final StructuredOutputInvoker structuredOutputInvoker;
    private final BeanOutputConverter<CategoryListDTO> jdOutputConverter;
    private final PromptTemplate jdSystemPromptTemplate;
    //资源加载器，负责加载 classpath 资源
    private final ResourceLoader resourceLoader;
    //提示词清理器，负责清理用户输入的提示词，防止注入攻击
    private final PromptSanitizer promptSanitizer;

    /** 预设 Skill 注册表，启动时从 classpath:skills/{skillId}/SKILL.md 加载 */
    private final Map<String, InterviewSkillProperties.SkillDefinition> presetRegistry = new TreeMap<>();

    /** 参考内容缓存（classpath 资源不可变，加载一次后复用） */
    private final Map<String, String> referenceCache = new ConcurrentHashMap<>();

    /** 全局 category key → (ref文件名, 是否shared) 映射，启动时构建，之后只读 */
    private final Map<String, RefMapping> categoryRefIndex = new HashMap<>();

    /** JD 解析用的参考文件清单 Markdown 表格，启动时生成一次 */
    private String cachedReferenceFileList;

    record RefMapping(String ref, boolean shared, String sourceSkillId) {}

    /**
     * 初始化 Skill 服务和 JD 结构化输出组件。
     *
     * <p>由 Spring 创建 Bean 时调用，只加载 JD Prompt 模板；
     * 预设 Skill 注册表由 {@link #loadPresetSkills()} 在构造后建立。
     */
    public InterviewSkillService(LlmProviderRegistry llmProviderRegistry,
                                 StructuredOutputInvoker structuredOutputInvoker,
                                 ResourceLoader resourceLoader,
                                 PromptSanitizer promptSanitizer) throws IOException {
        this.llmProviderRegistry = llmProviderRegistry;
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.resourceLoader = resourceLoader;
        this.promptSanitizer = promptSanitizer;
        this.jdOutputConverter = new BeanOutputConverter<>(CategoryListDTO.class) {};
        this.jdSystemPromptTemplate = new PromptTemplate(loadClasspathPrompt(JD_PARSE_SYSTEM_PROMPT_PATH));
    }

    /**
     * 扫描 classpath 中的预设 Skill 并建立只读索引。
     *
     * <p>由 Spring 通过 {@link PostConstruct} 自动调用。
     * 依次解析 SKILL.md 和 skill-meta.yml，随后调用
     * {@link #buildCategoryRefIndex()} 与 {@link #buildReferenceFileList()}。
     *
     * @throws IOException Skill 资源扫描失败时阻止 Bean 初始化
     */
    @PostConstruct
    void loadPresetSkills() throws IOException {
        var resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:skills/*/SKILL.md");
        Yaml yaml = new Yaml();

        for (Resource resource : resources) {
            String skillId = extractSkillId(resource);
            if (skillId == null || "_shared".equals(skillId)) {
                continue;
            }

            InterviewSkillProperties.SkillDefinition def = parseSkillDefinition(skillId, resource, yaml);
            if (def.getName() == null || def.getName().isBlank()) {
                log.warn("跳过无效 Skill（缺少 name）: {}", skillId);
                continue;
            }

            presetRegistry.put(skillId, def);
            log.info("加载预设 Skill: {} ({})", skillId, def.getName());
        }

        log.info("共加载 {} 个预设 Skill", presetRegistry.size());

        buildCategoryRefIndex();
        cachedReferenceFileList = buildReferenceFileList();
    }

    /**
     * 从预设 Skill 分类中建立 category key 到 reference 的索引。
     *
     * <p>由 {@link #loadPresetSkills()} 调用，后续
     * {@link #buildCustomSkill(List, String)} 使用该索引纠正 LLM 返回的引用信息。
     */
    private void buildCategoryRefIndex() {
        categoryRefIndex.clear();
        for (var entry : presetRegistry.entrySet()) {
            InterviewSkillProperties.SkillDefinition def = entry.getValue();
            if (def.getCategories() == null) continue;
            for (InterviewSkillProperties.CategoryDef cat : def.getCategories()) {
                if (cat.getRef() != null && !cat.getRef().isBlank() && cat.getKey() != null) {
                    categoryRefIndex.putIfAbsent(cat.getKey(),
                        new RefMapping(cat.getRef(), Boolean.TRUE.equals(cat.getShared()), entry.getKey()));
                }
            }
        }
        log.info("构建 category→reference 映射: {} 个条目", categoryRefIndex.size());
    }
    /**
     * 返回全部预设 Skill 的 DTO 快照。
     *
     * <p>调用入口：{@code InterviewSkillController.listSkills}。
     * 通过 {@link #toSkillDTO} 转换启动时加载的注册表。
     */
    public List<SkillDTO> getAllSkills() {
        return presetRegistry.entrySet().stream()
            .map(e -> toSkillDTO(e.getKey(), e.getValue()))
            .toList();
    }
    /**
     * 按 ID 查询一个预设 Skill。
     *
     * <p>由 Skill Controller、题目生成和评估参考构建共同调用。
     *
     * @throws BusinessException Skill 不存在时抛出 BAD_REQUEST
     */
    public SkillDTO getSkill(String skillId) {
        InterviewSkillProperties.SkillDefinition preset = presetRegistry.get(skillId);
        if (preset != null) {
            return toSkillDTO(skillId, preset);
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "未找到面试主题: " + skillId);
    }

    /**
     * 从 JD 解析结果构建自定义 SkillDTO。
     * 遍历 customCategories，尝试在 categoryRefIndex 中匹配参考文件。
     *
     * <p>由 {@code InterviewQuestionService.resolveSkill} 在 custom 模式下调用。
     * 分类 key/label 会先清洗，reference 以服务端索引为准，
     * 避免直接信任 LLM 返回的资源路径。
     */
    public SkillDTO buildCustomSkill(List<CategoryDTO> customCategories, String jdText) {
        List<SkillCategoryDTO> categories = customCategories.stream()
            .filter(cat -> cat.key() != null && cat.label() != null)
            .map(cat -> {
                String safeKey = sanitizeCategoryKey(cat.key());
                String safeLabel = sanitizeCategoryLabel(cat.label());
                RefMapping refMapping = categoryRefIndex.get(safeKey);
                if (refMapping != null) {
                    if (!refMapping.ref().equals(cat.ref())
                        || refMapping.shared() != Boolean.TRUE.equals(cat.shared())) {
                        log.info("JD 分类 reference 已按本地映射纠正: key={}, modelRef={}, modelShared={}, mappedRef={}, mappedShared={}",
                            safeKey, cat.ref(), cat.shared(), refMapping.ref(), refMapping.shared());
                    }
                    return new SkillCategoryDTO(safeKey, safeLabel, cat.priority(),
                        refMapping.ref(), refMapping.shared());
                }
                return new SkillCategoryDTO(safeKey, safeLabel, cat.priority(),
                    cat.ref(), Boolean.TRUE.equals(cat.shared()));
            })
            .toList();

        long matchedCount = categories.stream().filter(c -> c.ref() != null && !c.ref().isBlank()).count();
        log.info("构建自定义 Skill: {} 个分类, {} 个匹配到参考文件", categories.size(), matchedCount);

        return new SkillDTO(CUSTOM_SKILL_ID, "自定义面试（JD 解析）",
            "基于职位描述提取的面试方向", categories,
            false, jdText, null, null);
    }
    /**
     * 通过 LLM 从 JD 中提取结构化面试分类。
     *
     * <p>调用入口：{@code InterviewSkillController.parseJd}。
     * 校验长度后清洗并包裹 JD，调用 StructuredOutputInvoker；
     * 空结果和调用异常统一转换为业务异常。
     */
    public List<CategoryDTO> parseJd(String jdText) {
        if (jdText == null || jdText.length() < MIN_JD_LENGTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "JD 内容太少（至少 " + MIN_JD_LENGTH + " 字），请补充后重试");
        }

        log.info("开始解析 JD，长度: {}", jdText.length());

        ChatClient chatClient = llmProviderRegistry.getDefaultChatClient();
        String systemPrompt = jdSystemPromptTemplate.render(Map.of(
            "referenceFileList", cachedReferenceFileList
        )) + "\n\n" + jdOutputConverter.getFormat();
        String userPrompt = PromptSecurityConstants.DATA_BOUNDARY_INSTRUCTION + "\n" +
            "职位描述：\n" +
            promptSanitizer.wrapWithDelimiters("jd", promptSanitizer.sanitize(jdText));

        try {
            CategoryListDTO result = structuredOutputInvoker.invoke(
                chatClient, systemPrompt, userPrompt, jdOutputConverter,
                ErrorCode.AI_SERVICE_ERROR, "JD 解析失败：", "JD 解析", log
            );

            if (result == null || result.categories() == null || result.categories().isEmpty()) {
                throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "JD 解析结果为空，请重试");
            }

            long refMatched = result.categories().stream().filter(c -> c.ref() != null && !c.ref().isBlank()).count();
            log.info("JD 解析完成: {} 个方向, {} 个匹配到参考文件", result.categories().size(), refMatched);
            return result.categories();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("JD 解析失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "JD 解析失败，请重试或选择预设主题");
        }
    }

    /**
     * 构建 JD 解析 Prompt 使用的 reference 文件清单。
     *
     * <p>仅由 {@link #loadPresetSkills()} 在启动阶段调用，
     * 对文件名去重后生成 Markdown 表格并缓存。
     */
    private String buildReferenceFileList() {
        // 收集所有去重的参考文件（文件名 → Markdown 表格行）
        Map<String, String> refDescriptions = new LinkedHashMap<>();
        for (var entry : presetRegistry.entrySet()) {
            String skillName = entry.getValue().getDisplayName() != null
                ? entry.getValue().getDisplayName() : entry.getValue().getName();
            if (entry.getValue().getCategories() == null) continue;
            for (InterviewSkillProperties.CategoryDef cat : entry.getValue().getCategories()) {
                if (cat.getRef() != null && !cat.getRef().isBlank()) {
                    refDescriptions.putIfAbsent(cat.getRef(),
                        "| " + cat.getRef()
                            + " | " + (Boolean.TRUE.equals(cat.getShared()) ? "shared" : "skill-local")
                            + " | " + skillName
                            + " | " + cat.getLabel() + " |\n");
                }
            }
        }

        if (refDescriptions.isEmpty()) {
            return "（无可用参考文件）";
        }

        StringBuilder sb = new StringBuilder("| 文件名 | 范围 | 来源 Skill | 覆盖内容 |\n");
        sb.append("|--------|------|-------------|----------|\n");
        for (String row : refDescriptions.values()) {
            sb.append(row);
        }
        return sb.toString();
    }

    /**
     * 按 Skill ID 计算分类题量分配。
     *
     * <p>先调用 {@link #getSkill(String)}，再委托分类列表重载方法。
     */
    public Map<String, Integer> calculateAllocation(String skillId, int totalQuestions) {
        return calculateAllocation(getSkill(skillId).categories(), totalQuestions);
    }

    /**
     * 根据分类优先级分配指定数量的主问题。
     *
     * <p>由题目生成服务调用。依次保证 ALWAYS_ONE、CORE 和普通分类覆盖，
     * 剩余名额优先轮转分配给 CORE，返回顺序稳定的 Map。
     */
    public Map<String, Integer> calculateAllocation(List<SkillCategoryDTO> categories, int totalQuestions) {
        //根据分类优先级,将分类分为 ALWAYS_ONE、CORE、NORMAL 三类
        List<SkillCategoryDTO> alwaysOneCats = new ArrayList<>();
        List<SkillCategoryDTO> coreCats = new ArrayList<>();
        List<SkillCategoryDTO> normalCats = new ArrayList<>();

        for (SkillCategoryDTO cat : categories) {
            switch (cat.priority()) {
                case "ALWAYS_ONE" -> alwaysOneCats.add(cat);
                case "CORE" -> coreCats.add(cat);
                default -> normalCats.add(cat);
            }
        }

        Map<String, Integer> allocation = new LinkedHashMap<>();
        int remaining = totalQuestions;

        // Phase 1: ALWAYS_ONE 保底各 1 题
        for (SkillCategoryDTO cat : alwaysOneCats) {
            if (remaining > 0) {
                allocation.put(cat.key(), 1);
                remaining--;
            }
        }

        // Phase 2: 先给所有类目各 1 题（CORE 优先），保证覆盖率
        for (SkillCategoryDTO cat : coreCats) {
            if (remaining > 0) {
                allocation.put(cat.key(), 1);
                remaining--;
            }
        }
        for (SkillCategoryDTO cat : normalCats) {
            if (remaining > 0) {
                allocation.put(cat.key(), 1);
                remaining--;
            }
        }

        // Phase 3: 剩余名额按 CORE 优先轮转分配
        while (remaining > 0) {
            for (SkillCategoryDTO cat : coreCats) {
                if (remaining <= 0) break;
                allocation.merge(cat.key(), 1, Integer::sum);
                remaining--;
            }
            for (SkillCategoryDTO cat : normalCats) {
                if (remaining <= 0) break;
                allocation.merge(cat.key(), 1, Integer::sum);
                remaining--;
            }
            if (coreCats.isEmpty() && normalCats.isEmpty()) break;
        }

        // 确保所有类目都出现在 allocation 中
        for (SkillCategoryDTO cat : coreCats) {
            allocation.putIfAbsent(cat.key(), 0);
        }
        for (SkillCategoryDTO cat : normalCats) {
            allocation.putIfAbsent(cat.key(), 0);
        }

        log.debug("题目分配: total={}, allocation={}", totalQuestions, allocation);
        return allocation;
    }

    /**
     * 把题量分配转换为 Prompt 使用的 Markdown 表格行。
     *
     * <p>由方向题生成方法调用，只输出分配数量大于零的分类。
     */
    public String buildAllocationDescription(Map<String, Integer> allocation, List<SkillCategoryDTO> categories) {
        StringBuilder sb = new StringBuilder();
        for (SkillCategoryDTO cat : categories) {
            int count = allocation.getOrDefault(cat.key(), 0);
            if (count > 0) {
                sb.append("| ").append(cat.label()).append(" | ").append(count).append(" 题 | ").append(cat.priority()).append(" |\n");
            }
        }
        return sb.toString();
    }

    /**
     * 构建出题阶段使用的参考资料片段。
     *
     * <p>由方向题生成方法调用，仅加载本次分配到题量的分类，
     * 最终委托 {@link #buildReferenceSectionInternal} 控制内容长度。
     */
    public String buildReferenceSection(SkillDTO skill, Map<String, Integer> allocation) {
        return buildReferenceSectionInternal(
            skill,
            category -> allocation.getOrDefault(category.key(), 0) > 0,
            MAX_REFERENCE_SECTION_CHARS
        );
    }

    /**
     * 评估阶段参考基线：不限制题量分配，覆盖该 skill 下所有配置了 reference 的分类。
     *
     * <p>由安全包装方法调用，先查询 Skill，再委托内部构建方法加载全部引用。
     */
    public String buildEvaluationReferenceSection(String skillId) {
        SkillDTO skill = getSkill(skillId);
        return buildReferenceSectionInternal(
            skill,
            category -> true,
            MAX_EVALUATION_REFERENCE_SECTION_CHARS
        );
    }

    /**
     * 安全版本的评估参考基线：skillId 为空或加载失败时返回空字符串，不抛异常。
     *
     * <p>由文字和语音面试评估服务调用，确保 reference 故障不会阻断最终评估。
     */
    public String buildEvaluationReferenceSectionSafe(String skillId) {
        if (skillId == null || skillId.isBlank()) {
            return "";
        }
        try {
            return buildEvaluationReferenceSection(skillId);
        } catch (Exception e) {
            log.warn("加载评估参考基线失败，降级为无参考: skillId={}, error={}", skillId, e.getMessage());
            return "";
        }
    }

    /**
     * 按过滤条件加载并拼接 Skill reference 内容。
     *
     * <p>由出题和评估两个公开构建方法调用，内部通过
     * {@link #loadReferenceContent} 查找资源，并按 maxChars 截断总长度。
     */
    private String buildReferenceSectionInternal(SkillDTO skill,
                                                 Predicate<SkillCategoryDTO> categoryFilter,
                                                 int maxChars) {
        StringBuilder sb = new StringBuilder();

        for (SkillCategoryDTO category : skill.categories()) {
            if (!categoryFilter.test(category)) {
                continue;
            }
            if (category.ref() == null || category.ref().isBlank()) {
                continue;
            }

            // custom 模式下非 shared 的 ref 需要查原始 skillId 拼路径
            String effectiveSkillId = skill.id();
            if (CUSTOM_SKILL_ID.equals(skill.id()) && !category.shared() && category.ref() != null) {
                RefMapping mapping = categoryRefIndex.get(category.key());
                if (mapping != null) {
                    effectiveSkillId = mapping.sourceSkillId();
                }
            }
            String referenceContent = loadReferenceContent(effectiveSkillId, category.ref(), category.shared());
            if (referenceContent.isBlank()) {
                continue;
            }

            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append("### ").append(category.label()).append(" (").append(category.key()).append(")\n");
            sb.append(referenceContent);

            if (sb.length() >= maxChars) {
                sb.setLength(maxChars);
                sb.append("\n...（references 已截断）");
                break;
            }
        }

        return sb.isEmpty() ? "未配置 references。" : sb.toString();
    }

    /**
     * 读取 classpath Prompt 文本。
     *
     * <p>仅由构造函数加载 JD 系统模板时调用。
     */
    private String loadClasspathPrompt(String path) throws IOException {
        Resource resource = resourceLoader.getResource(path);
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * 解析一个 Skill 的 front matter、Persona 正文和 meta 配置。
     *
     * <p>由 {@link #loadPresetSkills()} 遍历资源时调用，
     * 并继续调用 {@link #loadSkillMetaDefinition} 合并显示和分类配置。
     */
    private InterviewSkillProperties.SkillDefinition parseSkillDefinition(String skillId, Resource resource, Yaml yaml) {
        try {
            String markdown = resource.getContentAsString(StandardCharsets.UTF_8);
            Matcher matcher = FRONT_MATTER_PATTERN.matcher(markdown);
            if (!matcher.matches()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "Skill 文件格式错误（缺少 front matter）: " + resource.getDescription());
            }

            String frontMatter = matcher.group(1);
            String body = matcher.group(2) != null ? matcher.group(2).trim() : "";

            InterviewSkillProperties.SkillFrontMatterDefinition frontMatterDef =
                yaml.loadAs(frontMatter, InterviewSkillProperties.SkillFrontMatterDefinition.class);

            InterviewSkillProperties.SkillDefinition definition = new InterviewSkillProperties.SkillDefinition();
            if (frontMatterDef != null) {
                definition.setName(frontMatterDef.getName());
                definition.setDescription(frontMatterDef.getDescription());
            }
            if (!body.isBlank()) {
                definition.setPersona(body);
            }

            InterviewSkillProperties.SkillMetaDefinition metaDef = loadSkillMetaDefinition(skillId, yaml);
            if (metaDef != null) {
                definition.setDisplayName(metaDef.getDisplayName());
                definition.setDisplay(metaDef.getDisplay());
                definition.setCategories(metaDef.getCategories());
            }

            if (definition.getCategories() == null) {
                definition.setCategories(List.of());
            }
            return definition;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                "读取 Skill 文件失败: " + resource.getDescription());
        }
    }

    /**
     * 加载指定 Skill 的可选 skill-meta.yml。
     *
     * <p>仅由 {@link #parseSkillDefinition} 调用；文件不存在时返回 {@code null}，
     * 读取失败时转换为业务异常。
     */
    private InterviewSkillProperties.SkillMetaDefinition loadSkillMetaDefinition(String skillId, Yaml yaml) {
        String location = "classpath:skills/" + skillId + "/" + SKILL_META_FILE;
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            log.warn("skill meta 文件不存在，使用默认配置: skillId={}, location={}", skillId, location);
            return null;
        }

        try {
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            InterviewSkillProperties.SkillMetaDefinition meta = yaml.loadAs(content, InterviewSkillProperties.SkillMetaDefinition.class);
            return meta != null ? meta : new InterviewSkillProperties.SkillMetaDefinition();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "读取 skill meta 文件失败: " + location);
        }
    }

    /**
     * 从 SKILL.md 资源 URL 中提取 skillId。
     *
     * <p>仅由 {@link #loadPresetSkills()} 调用，无法识别或读取 URL 时返回 null。
     */
    private String extractSkillId(Resource resource) {
        try {
            String normalized = resource.getURL().toString().replace('\\', '/');
            Matcher matcher = SKILL_ID_PATTERN.matcher(normalized);
            if (matcher.matches()) {
                return matcher.group(1);
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 按候选位置加载并缓存 reference 内容。
     *
     * <p>由内部参考片段构建方法调用。先校验路径安全性，
     * 再通过 {@link #resolveReferenceLocations} 生成查找顺序，返回首个非空资源。
     */
    private String loadReferenceContent(String skillId, String referenceFile, boolean shared) {
        if (!isSafeReferencePath(referenceFile)) {
            log.warn("忽略不安全的 reference 路径: skillId={}, ref={}", skillId, referenceFile);
            return "";
        }

        List<String> candidateLocations = resolveReferenceLocations(skillId, referenceFile, shared);
        for (String location : candidateLocations) {
            String content = referenceCache.computeIfAbsent(location, this::readReferenceContent);
            if (!content.isBlank()) {
                return content;
            }
        }

        log.warn("未找到 reference: skillId={}, ref={}, shared={}, locations={}",
            skillId, referenceFile, shared, candidateLocations);
        return "";
    }

    /**
     * 解析 reference 的有序候选 classpath 位置。
     *
     * <p>仅由 {@link #loadReferenceContent} 调用，综合 shared、当前 Skill、
     * 全局 shared 和其他预设 Skill 路径并去重。
     */
    private List<String> resolveReferenceLocations(String skillId, String referenceFile, boolean shared) {
        LinkedHashSet<String> locations = new LinkedHashSet<>();
        if (shared) {
            locations.add(buildSharedReferenceLocation(referenceFile));
        }

        addSkillReferenceLocations(locations, skillId, referenceFile);

        if (!shared) {
            locations.add(buildSharedReferenceLocation(referenceFile));
        }

        if (CUSTOM_SKILL_ID.equals(skillId) || shared) {
            for (String presetSkillId : presetRegistry.keySet()) {
                addSkillReferenceLocations(locations, presetSkillId, referenceFile);
            }
        }

        return List.copyOf(locations);
    }

    /**
     * 向候选集合追加某个 Skill 的两种 reference 路径。
     *
     * <p>仅由 {@link #resolveReferenceLocations} 调用，忽略空或 custom skillId。
     */
    private void addSkillReferenceLocations(LinkedHashSet<String> locations, String skillId, String referenceFile) {
        if (skillId == null || skillId.isBlank() || CUSTOM_SKILL_ID.equals(skillId)) {
            return;
        }
        locations.add("classpath:skills/" + skillId + "/references/" + referenceFile);
        locations.add("classpath:skills/" + skillId + "/" + referenceFile);
    }

    /**
     * 构建全局 shared reference 路径。
     *
     * <p>仅由 {@link #resolveReferenceLocations} 调用。
     */
    private String buildSharedReferenceLocation(String referenceFile) {
        return "classpath:skills/_shared/references/" + referenceFile;
    }

    /**
     * 读取并限制单个 reference 文件内容长度。
     *
     * <p>作为 {@code referenceCache.computeIfAbsent} 的加载函数调用；
     * 文件不存在或读取失败时返回空字符串。
     */
    private String readReferenceContent(String location) {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            return "";
        }

        try {
            String content = resource.getContentAsString(StandardCharsets.UTF_8).trim();
            if (content.length() > MAX_SINGLE_REFERENCE_CHARS) {
                return content.substring(0, MAX_SINGLE_REFERENCE_CHARS) + "\n...（单文件内容已截断）";
            }
            return content;
        } catch (IOException e) {
            log.warn("读取 reference 失败: location={}", location, e);
            return "";
        }
    }

    /**
     * 校验 LLM 或配置提供的 reference 相对路径。
     *
     * <p>仅由 {@link #loadReferenceContent} 调用，拒绝目录穿越、绝对路径和非法字符。
     */
    private boolean isSafeReferencePath(String referenceFile) {
        return !referenceFile.contains("..")
            && !referenceFile.startsWith("/")
            && !referenceFile.startsWith("\\")
            && referenceFile.matches("[a-zA-Z0-9._/-]+");
    }

    /**
     * 清洗 category key：截断长度，非法字符替换为下划线，转大写。
     * 首字符必须为字母，否则添加 "CAT_" 前缀。
     *
     * <p>仅由 {@link #buildCustomSkill(List, String)} 处理 LLM 分类结果时调用。
     */
    private String sanitizeCategoryKey(String key) {
        if (key == null || key.isBlank()) {
            return "UNKNOWN";
        }
        String trimmed = key.trim();
        if (trimmed.length() > MAX_CATEGORY_KEY_LENGTH) {
            trimmed = trimmed.substring(0, MAX_CATEGORY_KEY_LENGTH);
        }
        String upper = trimmed.toUpperCase().replaceAll("[^A-Z0-9_]", "_");
        if (upper.isEmpty()) {
            return "UNKNOWN";
        }
        // 确保首字符为字母（匹配 category key 命名规范）
        if (!Character.isLetter(upper.charAt(0))) {
            upper = "CAT_" + upper;
        }
        return upper;
    }

    /**
     * 清洗 category label：截断长度，移除换行。
     *
     * <p>仅由 {@link #buildCustomSkill(List, String)} 处理 LLM 分类结果时调用。
     */
    private String sanitizeCategoryLabel(String label) {
        if (label == null || label.isBlank()) {
            return "未命名";
        }
        String trimmed = label.trim().replaceAll("[\\r\\n]+", " ");
        if (trimmed.length() > MAX_CATEGORY_LABEL_LENGTH) {
            trimmed = trimmed.substring(0, MAX_CATEGORY_LABEL_LENGTH);
        }
        return trimmed;
    }

    /**
     * 把启动时加载的配置定义转换为对外 Skill DTO。
     *
     * <p>由 Skill 列表和单项查询调用，隔离可变配置对象。
     */
    private SkillDTO toSkillDTO(String id, InterviewSkillProperties.SkillDefinition def) {
        String skillDisplayName = (def.getDisplayName() != null && !def.getDisplayName().isBlank())
            ? def.getDisplayName()
            : def.getName();

        InterviewSkillProperties.DisplayDef disp = def.getDisplay();
        DisplayDTO displayDTO = disp != null
            ? new DisplayDTO(disp.getIcon(), disp.getGradient(), disp.getIconBg(), disp.getIconColor())
            : null;

        List<SkillCategoryDTO> categories = def.getCategories() == null
            ? List.of()
            : def.getCategories().stream()
                .map(c -> new SkillCategoryDTO(
                    c.getKey(),
                    c.getLabel(),
                    c.getPriority(),
                    c.getRef(),
                    Boolean.TRUE.equals(c.getShared())
                ))
                .toList();

        return new SkillDTO(
            id,
            skillDisplayName,
            def.getDescription(),
            categories,
            true,
            null,
            def.getPersona(),
            displayDTO
        );
    }

    public record SkillDTO(String id, String name, String description,
                           List<SkillCategoryDTO> categories,
                           boolean isPreset, String sourceJd, String persona, DisplayDTO display) {}

    public record DisplayDTO(String icon, String gradient, String iconBg, String iconColor) {}

    /**
     * 预设 Skill 分类（可携带 references 绑定信息）
     */
    public record SkillCategoryDTO(String key, String label, String priority, String ref, boolean shared) {}

    /**
     * JD 解析返回分类（可携带 LLM 匹配的 ref/shared 信息，后端会按本地 categoryRefIndex 纠正）
     */
    public record CategoryDTO(String key, String label, String priority,
                               String ref, Boolean shared) {}

    private record CategoryListDTO(List<CategoryDTO> categories) {}
}
