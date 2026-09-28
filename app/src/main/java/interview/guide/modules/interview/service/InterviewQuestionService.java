/**
 * 面试问题生成服务
 *
 * 核心出题引擎，支持两种模式：无简历时单次 Skill 驱动出题，有简历时并行调用
 * （简历题 60% + 方向题 40%）。通过 StructuredOutputInvoker 调用 LLM 生成结构化题目，
 * 支持追问生成、历史题目去重、题目截断和降级回退。
 * 使用虚拟线程并行执行简历题和方向题的生成。
 *
 * 联动关系：
 * - 被 InterviewSessionService.createSession() 调用生成面试题目
 * - 依赖 InterviewSkillService 获取 Skill 定义、分类分配和参考内容
 * - 依赖 LlmProviderRegistry 获取 ChatClient
 * - 依赖 StructuredOutputInvoker（common.ai）做结构化输出重试
 * - 依赖 PromptSanitizer（common.ai）清洗 Prompt 中的用户输入
 * - 依赖 InterviewQuestionProperties 读取模板路径和追问数量
 * - 使用 resources/prompts/ 下的 .st 模板文件
 */
package interview.guide.modules.interview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.constant.CommonConstants.InterviewDefaults;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.HistoricalQuestion;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.interview.skill.InterviewSkillService.CategoryDTO;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillDTO;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillCategoryDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 面试问题生成服务
 * 无简历：单次 Skill 驱动出题
 * 有简历：并行调用（简历题 60% + 方向题 40%）
 */
@Service
public class InterviewQuestionService {

    private static final Logger log = LoggerFactory.getLogger(InterviewQuestionService.class);

    // 默认问题类型
    private static final String DEFAULT_QUESTION_TYPE = "GENERAL";
    private static final int MAX_FOLLOW_UP_COUNT = 2;
    private static final double RESUME_QUESTION_RATIO = 0.6;
    

    private static final String GENERIC_MODE_SYSTEM_APPEND = """
        # 通用面试模式
        本次面试无候选人简历，请出该方向的标准面试题。
        - 禁止出现"你在简历中提到..."、"你在项目中..."等暗示存在简历的表述
        - 问题表述应与简历无关，直接考察该方向的技术能力
        """;

    private static final Map<String, String> DIFFICULTY_DESCRIPTIONS = Map.of(
        "junior", "校招/0-1年经验。考察基础概念和简单应用。",
        "mid", "1-3年经验。考察原理理解和实战经验。",
        "senior", "3年+经验。考察架构设计和深度调优。"
    );

    private static final String[][] GENERIC_FALLBACK_QUESTIONS = {
        {"请描述一个你主导解决的技术难题，你的分析思路是什么？", "GENERAL", "综合能力"},
        {"你在做技术方案选型时，通常考虑哪些因素？请举例说明。", "GENERAL", "综合能力"},
        {"请分享一次你处理线上故障的经历，从发现到修复的完整过程。", "GENERAL", "综合能力"},
        {"你如何保证代码质量？介绍你实践过的有效手段。", "GENERAL", "综合能力"},
        {"描述一个你做过的技术优化案例，优化的动机、方案和效果。", "GENERAL", "综合能力"},
        {"你在团队协作中遇到过最大的分歧是什么？如何解决的？", "GENERAL", "综合能力"},
    };
    //skillSystemPromptTemplate是技能系统提示模板
    private final PromptTemplate skillSystemPromptTemplate;
    //skillUserPromptTemplate是技能用户提示模板
    private final PromptTemplate skillUserPromptTemplate;
    //resumeSystemPromptTemplate是简历系统提示模板
    private final PromptTemplate resumeSystemPromptTemplate;
    //resumeUserPromptTemplate是简历用户提示模板
    private final PromptTemplate resumeUserPromptTemplate;
    //outputConverter是输出转换器
    private final BeanOutputConverter<QuestionListDTO> outputConverter;
    //structuredOutputInvoker是结构化输出调用器
    private final StructuredOutputInvoker structuredOutputInvoker;
    //skillService是技能服务,调用面试题目服务，根据skill生成对应的面试问题
    private final InterviewSkillService skillService;
    //llmProviderRegistry是LLM提供器注册器
    private final LlmProviderRegistry llmProviderRegistry;
    //promptSanitizer是提示净化器
    private final PromptSanitizer promptSanitizer;
    /** 并行生成简历题和方向题的虚拟线程执行器 */
    private final ExecutorService questionExecutor;
    //followUpCount是跟进次数
    private final int followUpCount;
    //QuestionListDTO是问题列表DTO
   
    private record QuestionListDTO(List<QuestionDTO> questions) {}
    //QuestionDTO是问题DTO
    private record QuestionDTO(String question, String type, String category,
                               String topicSummary, List<String> followUps) {}
    /**
     * 初始化题目生成服务并加载四个 Prompt 模板。
     *
     * <p>由 Spring 创建 Bean 时调用。构造过程读取 classpath 资源、
     * 创建结构化输出转换器和虚拟线程执行器，不调用 LLM。
     *
     * @throws IOException Prompt 模板无法读取时抛出，使应用启动失败
     */
    public InterviewQuestionService(
            StructuredOutputInvoker structuredOutputInvoker,
            InterviewSkillService skillService,
            InterviewQuestionProperties properties,
            ResourceLoader resourceLoader,
            LlmProviderRegistry llmProviderRegistry,
            PromptSanitizer promptSanitizer) throws IOException {
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.skillService = skillService;
        this.llmProviderRegistry = llmProviderRegistry;
        this.promptSanitizer = promptSanitizer;
        // 虚拟线程执行器,每个任务创建一个线程
        this.questionExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.skillSystemPromptTemplate = loadTemplate(resourceLoader, properties.getQuestionSystemPromptPath());
        this.skillUserPromptTemplate = loadTemplate(resourceLoader, properties.getQuestionUserPromptPath());
        this.resumeSystemPromptTemplate = loadTemplate(resourceLoader, properties.getResumeQuestionSystemPromptPath());
        this.resumeUserPromptTemplate = loadTemplate(resourceLoader, properties.getResumeQuestionUserPromptPath());
        this.outputConverter = new BeanOutputConverter<>(QuestionListDTO.class);
        this.followUpCount = Math.max(0, Math.min(properties.getFollowUpCount(), MAX_FOLLOW_UP_COUNT));
    }
    /**
     * 从 Spring Resource 位置读取模板并创建 PromptTemplate。
     *
     * <p>仅由构造函数调用，用于统一模板加载编码。
     */
    private static PromptTemplate loadTemplate(ResourceLoader loader, String location) throws IOException {
        return new PromptTemplate(loader.getResource(location).getContentAsString(StandardCharsets.UTF_8));
    }

    /**
     * 在 Spring 销毁 Bean 前关闭题目生成执行器。
     *
     * <p>由容器通过 {@link PreDestroy} 自动调用，停止尚未结束的并行出题任务。
     */
    @PreDestroy
    void destroy() {
        // 关闭线程执行器,等待所有任务完成
        // 确保所有任务完成,避免资源泄漏
        questionExecutor.shutdownNow();
    }

    /**
     * 根据技能、难度、简历和历史题目生成完整面试题单。
     *
     * <p>调用入口：{@code InterviewSessionService.createSession}。
     * 无简历时只调用 {@link #generateDirectionOnly}；有简历时使用虚拟线程并行调用
     * {@link #generateResumeQuestions} 和 {@link #generateDirectionOnly}，
     * 单路失败时降级到另一题型，两路都为空时使用本地兜底题。
     *
     * @return 已包含稳定索引和追问关系的题目列表
     */
    public List<InterviewQuestionDTO> generateQuestionsBySkill(
            String llmProvider,
            String skillId,
            String difficulty,
            String resumeText,
            int questionCount,
            List<HistoricalQuestion> historicalQuestions,
            List<CategoryDTO> customCategories,
            String jdText) {
        
        //skill实体,根据skillid,自定义分类,岗位描述文本,获取skill实体
        SkillDTO skill = resolveSkill(skillId, customCategories, jdText);
        //难度描述
        String difficultyDesc = resolveDifficulty(difficulty);
        //从LLM提供器注册器中获取LLM模型实例
        //根据LLM提供器,获取LLM模型实例
        ChatClient questionChatClient =
            llmProviderRegistry.getPlainChatClient(llmProvider);
        
        //判断是否有简历文本
        boolean hasResume = resumeText != null && !resumeText.isBlank();
        //历史问题列表,根据历史问题列表,构建历史问题段落
        String historicalSection = buildHistoricalSection(historicalQuestions);
        //如果没有简历文本,则只生成方向题
        if (!hasResume) {
            //这里直接返回方向题列表
            return generateDirectionOnly(questionChatClient, skill, difficultyDesc, questionCount,
                historicalSection);
        }
        //计算简历题数量
        int resumeCount = Math.max(1, (int) Math.round(questionCount * RESUME_QUESTION_RATIO));
        //计算方向题数量
        int directionCount = questionCount - resumeCount;

        log.info("并行出题: skill={}, total={}, resumeCount={}, directionCount={}",
            skillId, questionCount, resumeCount, directionCount);

        //并行生成简历题和方向题
        CompletableFuture<List<InterviewQuestionDTO>> resumeFuture = CompletableFuture.supplyAsync(
            () -> generateResumeQuestions(questionChatClient, resumeText, resumeCount, skill,
                difficultyDesc, historicalSection),
            questionExecutor);
        //并行生成方向题
        CompletableFuture<List<InterviewQuestionDTO>> directionFuture = CompletableFuture.supplyAsync(
            () -> generateDirectionOnly(questionChatClient, skill, difficultyDesc, directionCount,
                historicalSection),
            questionExecutor);
        //等待简历题和方向题生成完成
        List<InterviewQuestionDTO> resumeQuestions;
        //等待方向题生成完成
        List<InterviewQuestionDTO> directionQuestions;
        try {
            //join方法是等待简历题生成完成,并返回简历题列表
            //如果简历题生成失败,则返回全方向题
            resumeQuestions = resumeFuture.join();
        } catch (CompletionException e) {
            log.error("简历题生成失败，降级为全方向题", e.getCause());
            directionFuture.cancel(true);
            return generateDirectionOnly(questionChatClient, skill, difficultyDesc, questionCount,
                historicalSection);
        }

        try {
            directionQuestions = directionFuture.join();
        } catch (CompletionException e) {
            log.error("方向题生成失败，降级为全简历题", e.getCause());
            if (resumeQuestions.isEmpty()) {
                return generateFallbackQuestions(skill, questionCount);
            }
            return resumeQuestions;
        }

        if (resumeQuestions.isEmpty() && directionQuestions.isEmpty()) {
            log.warn("简历题和方向题均为空，回退到默认问题");
            return generateFallbackQuestions(skill, questionCount);
        }
        //合并简历题和方向题
        List<InterviewQuestionDTO> merged = mergeQuestionBatches(resumeQuestions, directionQuestions);
        log.info("并行出题成功: 简历题={}, 方向题={}, 合计={}",
            resumeQuestions.size(), directionQuestions.size(), merged.size());
        return merged;
    }


    /**
     * 调用 LLM 生成与简历经历相关的面试题。
     *
     * <p>仅由 {@link #generateQuestionsBySkill} 的并行简历题分支调用。
     * 渲染简历模板和 Skill Persona 后，通过 {@code StructuredOutputInvoker}
     * 获取结构化题目，再转换并限制主问题数量。
     */
    private List<InterviewQuestionDTO> generateResumeQuestions(
            ChatClient questionClient, String resumeText, int questionCount,
            SkillDTO skill, String difficultyDesc, String historicalSection) {
        try {
            Map<String, Object> variables = new HashMap<>();
            variables.put("questionCount", questionCount);
            variables.put("followUpCount", followUpCount);
            variables.put("skillName", skill.name());
            variables.put("skillDescription", skill.description() != null ? skill.description() : "");
            variables.put("difficultyDescription", difficultyDesc);
            variables.put("resumeText", resumeText);
            variables.put("historicalSection", historicalSection);

            String systemPrompt = resumeSystemPromptTemplate.render()
                + buildSkillPersonaSection(skill)
                + "\n\n" + outputConverter.getFormat();
            String userPrompt = resumeUserPromptTemplate.render(variables);

            QuestionListDTO dto = structuredOutputInvoker.invoke(
                questionClient, systemPrompt, userPrompt, outputConverter,
                ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
                "简历题生成失败：", "简历题", log);

            List<InterviewQuestionDTO> questions = convertToQuestions(dto);
            questions = capToMainCount(questions, questionCount);
            log.info("简历题生成完成: 请求={}, 实际主问题={}",
                questionCount, questions.stream().filter(q -> !q.isFollowUp()).count());
            return questions;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("简历题生成异常: {}", e.getMessage(), e);
            throw e;
        }
    }


    /**
     * 调用 LLM 生成面试方向题。
     *
     * <p>由主生成流程直接或降级调用。先通过 {@code InterviewSkillService}
     * 计算分类配额和参考资料，再调用结构化输出器；
     * LLM 失败或返回空主问题时使用 {@link #generateFallbackQuestions}。
     */
    private List<InterviewQuestionDTO> generateDirectionOnly(
            ChatClient questionClient, SkillDTO skill, String difficultyDesc,
            int questionCount, String historicalSection) {
        
        //调用skillService.calculateAllocation方法,根据技能分类和问题数量,计算每个分类的问题数量分配
        Map<String, Integer> allocation = skillService.calculateAllocation(skill.categories(), questionCount);
        //调用skillService.buildAllocationDescription方法,根据分配结果,构建分配表格
        String allocationTable = skillService.buildAllocationDescription(allocation, skill.categories());

        log.info("方向题生成: skill={}, total={}, allocation={}",
            skill.id(), questionCount, allocation);

        try {
            //构建变量映射
            Map<String, Object> variables = new HashMap<>();
            variables.put("questionCount", questionCount);
            variables.put("followUpCount", followUpCount);
            variables.put("difficultyDescription", difficultyDesc);
            variables.put("skillName", skill.name());
            variables.put("skillDescription", skill.description() != null ? skill.description() : "");
            variables.put("allocationTable", allocationTable);
            variables.put("historicalSection", historicalSection);
            variables.put("referenceSection", skillService.buildReferenceSection(skill, allocation));
            variables.put("jdSection", buildJdSection(skill.sourceJd()));

            //渲染系统提示词和用户提示词
            String systemPrompt = skillSystemPromptTemplate.render()
                + buildSkillPersonaSection(skill)
                + GENERIC_MODE_SYSTEM_APPEND
                + outputConverter.getFormat();
            String userPrompt = skillUserPromptTemplate.render(variables);

            //调用structuredOutputInvoker.invoke方法,根据系统提示词和用户提示词,生成面试问题
            /*
             * 调用structuredOutputInvoker.invoke方法,根据系统提示词和用户提示词,生成面试问题
             * 内部会调用 LLM 模型,根据系统提示词和用户提示词,生成面试问题，
             * 并将生成的问题转换为 QuestionListDTO 对象
             */
            QuestionListDTO dto = structuredOutputInvoker.invoke(
                questionClient, systemPrompt, userPrompt, outputConverter,
                ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
                "方向题生成失败：", "方向题", log);
            
            //将生成的面试问题转换为InterviewQuestionDTO列表
            //并根据问题类型和是否为跟进问题,筛选出主问题
            List<InterviewQuestionDTO> questions = convertToQuestions(dto);
            if (questions.stream().filter(q -> !q.isFollowUp()).count() == 0) {
                log.warn("方向题返回空题单，回退到默认问题");
                return generateFallbackQuestions(skill, questionCount);
            }
            
            //根据问题数量,截取主问题
            questions = capToMainCount(questions, questionCount);
            log.info("方向题生成完成: 请求={}, 实际主问题={}",
                questionCount, questions.stream().filter(q -> !q.isFollowUp()).count());
            return questions;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("方向题生成失败，回退到默认问题: {}", e.getMessage(), e);
            return generateFallbackQuestions(skill, questionCount);
        }
    }

    /**
     * 合并简历题和方向题并重排第二批题目的索引。
     *
     * <p>仅由 {@link #generateQuestionsBySkill} 在两路并行生成成功后调用，
     * 同时修正追问题的 parentQuestionIndex。
     */
    private List<InterviewQuestionDTO> mergeQuestionBatches(
            List<InterviewQuestionDTO> first, List<InterviewQuestionDTO> second) {
        if (second.isEmpty()) {
            return first;
        }
        if (first.isEmpty()) {
            return second;
        }
        int offset = first.size();
        List<InterviewQuestionDTO> merged = new ArrayList<>(first);
        for (InterviewQuestionDTO q : second) {
            int newIndex = q.questionIndex() + offset;
            Integer newParent = q.parentQuestionIndex() != null
                ? q.parentQuestionIndex() + offset : null;
            merged.add(InterviewQuestionDTO.create(
                newIndex, q.question(), q.type(), q.category(),
                q.topicSummary(), q.isFollowUp(), newParent));
        }
        return merged;
    }

    /**
     * 解析当前使用的预设或自定义 Skill。
     *
     * <p>仅由主生成流程调用；自定义模式委托 buildCustomSkill，
     * 其他模式按 skillId 查询预设定义。
     */
    private SkillDTO resolveSkill(String skillId, List<CategoryDTO> customCategories, String jdText) {
        if (InterviewSkillService.CUSTOM_SKILL_ID.equals(skillId)
                && customCategories != null && !customCategories.isEmpty()) {
            return skillService.buildCustomSkill(customCategories, jdText != null ? jdText : "");
        }
        return skillService.getSkill(skillId);
    }

    /**
     * 把难度键转换为 Prompt 使用的中文描述。
     *
     * <p>仅由主生成流程调用，未知或空难度回退到系统默认值。
     */
    private String resolveDifficulty(String difficulty) {
        return DIFFICULTY_DESCRIPTIONS.getOrDefault(
            difficulty != null ? difficulty : InterviewDefaults.DIFFICULTY,
            DIFFICULTY_DESCRIPTIONS.get(InterviewDefaults.DIFFICULTY));
    }

    /**
     * 把 LLM 结构化输出转换为带索引和父子关系的题目 DTO。
     *
     * <p>由简历题和方向题生成方法调用，过滤空题目，
     * 并通过 {@link #sanitizeFollowUps} 清洗追问。
     */
    private List<InterviewQuestionDTO> convertToQuestions(QuestionListDTO dto) {
        List<InterviewQuestionDTO> questions = new ArrayList<>();
        int index = 0;

        if (dto == null || dto.questions() == null) {
            return questions;
        }

        for (QuestionDTO q : dto.questions()) {
            if (q == null || q.question() == null || q.question().isBlank()) {
                continue;
            }
            String type = (q.type() != null && !q.type().isBlank()) ? q.type().toUpperCase() : DEFAULT_QUESTION_TYPE;
            int mainQuestionIndex = index;
            questions.add(InterviewQuestionDTO.create(index++, q.question(), type, q.category(), q.topicSummary(), false, null));

            List<String> followUps = sanitizeFollowUps(q.followUps());
            for (int i = 0; i < followUps.size(); i++) {
                questions.add(InterviewQuestionDTO.create(
                    index++, followUps.get(i), type,
                    buildFollowUpCategory(q.category(), i + 1), null, true, mainQuestionIndex
                ));
            }
        }

        return questions;
    }

    /**
     * 将问题列表截断到指定的主问题数量（AI 多生时截断，少生时保留原样并记录警告）。
     *
     * <p>由两类 LLM 生成方法调用。截断时保留入选主问题之后紧随的追问，
     * 直到遇到超出配额的下一个主问题。
     */
    private List<InterviewQuestionDTO> capToMainCount(
            List<InterviewQuestionDTO> questions, int maxMainCount) {
        long currentMainCount = questions.stream().filter(q -> !q.isFollowUp()).count();

        if (currentMainCount <= maxMainCount) {
            if (currentMainCount < maxMainCount) {
                log.warn("AI 生成主问题不足: 请求={}, 实际={}", maxMainCount, currentMainCount);
            }
            return questions;
        }

        List<InterviewQuestionDTO> capped = new ArrayList<>();
        int mainSeen = 0;
        for (InterviewQuestionDTO q : questions) {
            if (!q.isFollowUp()) {
                mainSeen++;
            }
            if (mainSeen > maxMainCount) {
                break;
            }
            capped.add(q);
        }
        log.info("题目截断: 主问题 {} → {}", currentMainCount, maxMainCount);
        return capped;
    }

    /**
     * 在 LLM 不可用或返回空题单时生成本地兜底题。
     *
     * <p>由主生成流程和方向题生成方法调用；优先按 Skill 分类生成通用问题，
     * 没有分类时使用内置问题，并按配置追加默认追问。
     */
    private List<InterviewQuestionDTO> generateFallbackQuestions(SkillDTO skill, int count) {
        List<SkillCategoryDTO> categories = skill != null ? skill.categories() : List.of();
        List<InterviewQuestionDTO> questions = new ArrayList<>();
        int index = 0;

        if (!categories.isEmpty()) {
            int generated = 0;
            while (generated < count) {
                SkillCategoryDTO cat = categories.get(generated % categories.size());
                String question = "请谈谈你在\"" + cat.label() + "\"方向的技术理解和实践经验。";
                questions.add(InterviewQuestionDTO.create(index++, question, cat.key(), cat.label(), null, false, null));
                int mainIndex = index - 1;
                for (int j = 0; j < followUpCount; j++) {
                    questions.add(InterviewQuestionDTO.create(
                        index++, buildDefaultFollowUp(question, j + 1),
                        cat.key(), buildFollowUpCategory(cat.label(), j + 1), null, true, mainIndex
                    ));
                }
                generated++;
            }
            return questions;
        }

        for (int i = 0; i < Math.min(count, GENERIC_FALLBACK_QUESTIONS.length); i++) {
            String[] q = GENERIC_FALLBACK_QUESTIONS[i];
            questions.add(InterviewQuestionDTO.create(index++, q[0], q[1], q[2], null, false, null));
            int mainIndex = index - 1;
            for (int j = 0; j < followUpCount; j++) {
                questions.add(InterviewQuestionDTO.create(
                    index++, buildDefaultFollowUp(q[0], j + 1),
                    q[1], buildFollowUpCategory(q[2], j + 1), null, true, mainIndex
                ));
            }
        }
        return questions;
    }

    /**
     * 把历史题目按类型压缩为避免重复出题的 Prompt 片段。
     *
     * <p>仅由主生成流程调用；缺少 topicSummary 时截取题目前 30 个字符兜底。
     */
    private String buildHistoricalSection(List<HistoricalQuestion> historicalQuestions) {
        if (historicalQuestions == null || historicalQuestions.isEmpty()) {
            return "暂无历史提问";
        }

        Map<String, List<String>> grouped = new HashMap<>();
        for (HistoricalQuestion hq : historicalQuestions) {
            String type = hq.type() != null && !hq.type().isBlank() ? hq.type() : DEFAULT_QUESTION_TYPE;
            String summary = hq.topicSummary();
            if (summary == null || summary.isBlank()) {
                String q = hq.question();
                summary = q.length() > 30 ? q.substring(0, 30) + "…" : q;
            }
            grouped.computeIfAbsent(type, k -> new ArrayList<>()).add(summary);
        }

        StringBuilder sb = new StringBuilder("已考过的知识点（避免重复出题）：\n");
        for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
            sb.append("- ").append(entry.getKey()).append(": ");
            sb.append(String.join(", ", entry.getValue()));
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * 构建带安全边界的 JD Prompt 片段。
     *
     * <p>由方向题生成方法调用，先经过 PromptSanitizer 清洗并添加分隔符；
     * 空 JD 返回空字符串。
     */
    private String buildJdSection(String sourceJd) {
        if (sourceJd == null || sourceJd.isBlank()) {
            return "";
        }
        return PromptSecurityConstants.DATA_BOUNDARY_INSTRUCTION + "\n" +
            "## 职位描述（JD）\n根据以下 JD 关键要求出题，确保题目与岗位实际需求相关：\n" +
            promptSanitizer.wrapWithDelimiters("jd", promptSanitizer.sanitize(sourceJd));
    }

    /**
     * 构建带安全边界的 Skill Persona Prompt 片段。
     *
     * <p>由简历题和方向题生成方法共同调用；没有 Persona 时返回空字符串。
     */
    private String buildSkillPersonaSection(SkillDTO skill) {
        if (skill == null || skill.persona() == null || skill.persona().isBlank()) {
            return "";
        }
        return "\n\n# Skill Persona\n"
            + "以下内容来自当前面试方向的 SKILL.md，请作为面试官角色、风格与出题约束：\n"
            + promptSanitizer.wrapWithDelimiters("skill_persona", skill.persona());
    }

    /**
     * 清洗并限制 LLM 返回的追问列表。
     *
     * <p>仅由 {@link #convertToQuestions} 调用，过滤空项、去除首尾空白，
     * 并按 followUpCount 截断。
     */
    private List<String> sanitizeFollowUps(List<String> followUps) {
        if (followUpCount == 0 || followUps == null || followUps.isEmpty()) {
            return List.of();
        }
        return followUps.stream()
            .filter(item -> item != null && !item.isBlank())
            .map(String::trim)
            .limit(followUpCount)
            .collect(Collectors.toList());
    }

    /**
     * 为追问题生成带序号的展示分类。
     *
     * <p>由 LLM 输出转换和兜底题生成共同调用。
     */
    private String buildFollowUpCategory(String category, int order) {
        String base = (category == null || category.isBlank()) ? "追问" : category;
        return base + "（追问" + order + "）";
    }

    /**
     * 为本地兜底主问题生成默认追问文本。
     *
     * <p>仅由 {@link #generateFallbackQuestions} 调用，
     * 第一问聚焦真实实践，后续问题聚焦故障定位。
     */
    private String buildDefaultFollowUp(String mainQuestion, int order) {
        if (order == 1) {
            return "基于\"" + mainQuestion + "\"，请结合你亲自做过的一个真实场景展开说明。";
        }
        return "基于\"" + mainQuestion + "\"，如果线上出现异常，你会如何定位并给出修复方案？";
    }
}
