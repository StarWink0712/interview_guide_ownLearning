/**
 * 简历 AI 评分服务
 *
 * 使用 Spring AI 调用 LLM 对简历进行多维度评分和建议生成。
 * 通过 LlmProviderRegistry 获取 ChatClient，使用 StructuredOutputInvoker 做结构化输出重试，
 * 从配置的 Prompt 模板加载系统提示词和用户提示词，将 AI 响应解析为 ResumeAnalysisResponse。
 * 评分维度包括：内容完整性、结构清晰度、技能匹配度、表达专业性、项目经验。
 *
 * 联动关系：
 * - 被 AnalyzeStreamConsumer 调用，执行异步简历分析
 * - 依赖 LlmProviderRegistry 获取 ChatClient（支持多 Provider）
 * - 依赖 StructuredOutputInvoker 做结构化输出重试
 * - 依赖 ResumeAnalysisProperties 获取 Prompt 模板路径
 * - 输出 ResumeAnalysisResponse，由 ResumePersistenceService 持久化
 */
package interview.guide.modules.resume.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.interview.model.ResumeAnalysisResponse.ScoreDetail;
import interview.guide.modules.interview.model.ResumeAnalysisResponse.Suggestion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 简历评分服务
 * 使用Spring AI调用LLM对简历进行评分和建议
 */
@Service
public class ResumeGradingService {
    
    // 日志记录器
    private static final Logger log = LoggerFactory.getLogger(ResumeGradingService.class);
        
    // 依赖注入,LLM提供注册器，结构化输出调用器，简历分析属性，资源加载器

    // 调用LLM提供注册器获取ChatClient，用于调用LLM模型，下方再用llm模型
    private final LlmProviderRegistry llmProviderRegistry;
    // 系统提示词模板，用于设置AI模型的行为和任务
    private final PromptTemplate systemPromptTemplate;
    // 用户提示词模板，用于根据简历内容生成评分和建议
    private final PromptTemplate userPromptTemplate;
    // 输出转换器，用于将AI响应转换为ResumeAnalysisResponseDTO，即将ai分析结果转换为中间DTO对象
    //传给下面的结构化输出调用器，用于解析AI响应将ai分析结果转换为中间DTO对象
    private final BeanOutputConverter<ResumeAnalysisResponseDTO> outputConverter;    

    // 调用结构化输出调用器，用于调用LLM模型，与AI模型交互，
    private final StructuredOutputInvoker structuredOutputInvoker;
    
    // 中间DTO用于接收AI响应
    private record ResumeAnalysisResponseDTO(
        int overallScore,
        ScoreDetailDTO scoreDetail,
        String summary,
        List<String> strengths,
        List<SuggestionDTO> suggestions
    ) {}
    
    // 评分详情DTO，用于接收AI评分结果
    private record ScoreDetailDTO(
        int contentScore,
        int structureScore,
        int skillMatchScore,
        int expressionScore,
        int projectScore
    ) {}
    
    // 建议DTO，用于接收AI建议结果
    private record SuggestionDTO(
        String category,
        String priority,
        String issue,
        String recommendation
    ) {}
    
    // 构造函数，初始化依赖注入
    // 从配置的Prompt模板加载系统提示词和用户提示词
    public ResumeGradingService(
            LlmProviderRegistry llmProviderRegistry,
            StructuredOutputInvoker structuredOutputInvoker,
            ResumeAnalysisProperties properties,
            ResourceLoader resourceLoader) throws IOException {
        this.llmProviderRegistry = llmProviderRegistry;
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.systemPromptTemplate = new PromptTemplate(
            resourceLoader.getResource(properties.getSystemPromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.userPromptTemplate = new PromptTemplate(
            resourceLoader.getResource(properties.getUserPromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );

        this.outputConverter = new BeanOutputConverter<>(ResumeAnalysisResponseDTO.class);
    }
    
    /**
     * 分析简历并返回评分和建议
     * 
     * @param resumeText 简历文本内容
     * @return 分析结果
     */
    public ResumeAnalysisResponse analyzeResume(String resumeText) {
        log.info("开始分析简历，文本长度: {} 字符", resumeText.length());
        
        try {
            // 加载系统提示词
            String systemPrompt = systemPromptTemplate.render();
            
            // 加载用户提示词并填充变量，将简历文本作为参数
            //简历文本拼接在用户提示词中，用于AI模型解析
            Map<String, Object> variables = new HashMap<>();
            variables.put("resumeText", resumeText);
            String userPrompt = userPromptTemplate.render(variables);
            
            // 添加格式指令到系统提示词
            String systemPromptWithFormat = systemPrompt + "\n\n" + outputConverter.getFormat();
            
            // 调用AI模型解析响应，将用户提示词作为输入
            ResumeAnalysisResponseDTO dto;
            try {
                // 调用AI模型解析响应，将用户提示词作为输入,这里就是调用ai模型，解析用户提示词，返回ai分析结果
                ChatClient chatClient = llmProviderRegistry.getDefaultChatClient();
                // 调用结构化输出调用器解析AI响应，将用户提示词作为输入
                dto = structuredOutputInvoker.invoke(
                    chatClient,
                    systemPromptWithFormat,
                    userPrompt,
                    outputConverter,
                    ErrorCode.RESUME_ANALYSIS_FAILED,
                    "简历分析失败：",
                    "简历分析",
                    log
                );
                log.debug("AI响应解析成功: overallScore={}", dto.overallScore());
            } catch (Exception e) {
                log.error("简历分析AI调用失败: {}", e.getMessage(), e);
                throw new BusinessException(ErrorCode.RESUME_ANALYSIS_FAILED, "简历分析失败：" + e.getMessage());
            }
            
            // 转换为业务对象
            ResumeAnalysisResponse result = convertToResponse(dto, resumeText);
            log.info("简历分析完成，总分: {}", result.overallScore());
            
            return result;
            
        } catch (Exception e) {
            log.error("简历分析失败: {}", e.getMessage(), e);
            return createErrorResponse(resumeText, e.getMessage());
        }
    }
    
    /**
     * 转换DTO为业务对象
     */
    private ResumeAnalysisResponse convertToResponse(ResumeAnalysisResponseDTO dto, String originalText) {
        ScoreDetail scoreDetail = new ScoreDetail(
            dto.scoreDetail().contentScore(),
            dto.scoreDetail().structureScore(),
            dto.scoreDetail().skillMatchScore(),
            dto.scoreDetail().expressionScore(),
            dto.scoreDetail().projectScore()
        );
        
        List<Suggestion> suggestions = dto.suggestions().stream()
            .map(s -> new Suggestion(s.category(), s.priority(), s.issue(), s.recommendation()))
            .toList();
        
        return new ResumeAnalysisResponse(
            dto.overallScore(),
            scoreDetail,
            dto.summary(),
            dto.strengths(),
            suggestions,
            originalText
        );
    }
    
    /**
     * 创建错误响应
     */
    private ResumeAnalysisResponse createErrorResponse(String originalText, String errorMessage) {
        return new ResumeAnalysisResponse(
            0,
            new ScoreDetail(0, 0, 0, 0, 0),
            "分析过程中出现错误: " + errorMessage,
            List.of(),
            List.of(new Suggestion(
                "系统",
                "高",
                "AI分析服务暂时不可用",
                "请稍后重试，或检查AI服务是否正常运行"
            )),
            originalText
        );
    }
}
