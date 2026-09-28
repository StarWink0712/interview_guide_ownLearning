/**
 * 语音面试提示词构建服务
 *
 * 根据技能模板 ID 和简历文本动态生成语音面试的系统提示词。
 * 提示词包含技能角色指令（引导 LLM 调用 Skill 工具加载角色设定）、
 * 语音面试输出约束（短句、口语化、不重复等）、简历上下文注入及防注入安全指令。
 *
 * 联动关系：
 * - 被 DashscopeLlmService 依赖（构建 LLM 调用的系统提示词）
 * - 依赖 PromptSanitizer（清洗简历文本和用户输入）
 * - 使用 PromptSecurityConstants（防注入安全指令）
 */
package interview.guide.modules.voiceinterview.service;

import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.PromptSecurityConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 语音面试提示词构建服务
 *
 * 负责生成符合语音面试场景的 LLM 系统提示词
 */
@Service
@Slf4j
public class VoiceInterviewPromptService {

    /**
     * 提示词清洗器
     * 用于清洗简历文本和用户输入，防止注入攻击
     */
    private final PromptSanitizer promptSanitizer;

    /**
     * 创建提示词服务并注入统一清洗器。
     *
     * 由 Spring 构造 Bean；后续系统提示词和每轮用户输入使用同一套防注入清洗策略。
     *
     * @param promptSanitizer 提示词清洗器
     */
    public VoiceInterviewPromptService(PromptSanitizer promptSanitizer) {
        this.promptSanitizer = promptSanitizer;
    }

    /**
     * 语音面试输出约束
     * 定义 LLM 在语音面试场景下的输出规范
     */
    private static final String VOICE_RESPONSE_CONSTRAINTS = """
            【语音面试输出约束】
            1. 每轮只问 1 个主问题，必要时最多补 1 个短追问。
            2. 总长度控制在 2-4 句，避免长段落、列表、Markdown、代码块。
            3. 不要重复开场白，不要复述上一轮已问过的完整问题。
            4. 若候选人回答过短或含糊，直接追问一个具体的技术细节或给出提示引导，不要简单确认后停止。
            5. 当候选人明确要求换题时，立即切换到新的技术方向，不要停留在当前话题。
            6. 语气简洁直接，适配口语对话。
            """;

    /**
     * 技能工具指令模板
     * 引导 LLM 调用 Skill 工具加载角色设定
     */
    private static final String SKILL_TOOL_INSTRUCTION = """
            你是一位 %s 方向的面试官。
            如果尚未加载完整的角色设定，请调用 Skill 工具（command: %s）加载该技能的 SKILL.md。
            工具输出包含完整的面试官角色和出题规则，后续对话应基于该角色进行。
            """;

    /**
     * 生成一次 LLM 调用所需的语音面试系统提示词。
     *
     * 由 {@code DashscopeLlmService.buildPromptContext} 调用。方法组合技能角色指令、口语输出约束、
     * 经清洗并加边界标记的简历内容，以及统一防注入指令；不负责拼接对话历史和当前用户输入。
     *
     * @param skillId 技能模板 ID
     * @param resumeText 简历文本（可选）
     * @return 完整的系统提示词
     */
    public String generateSystemPromptWithContext(String skillId, String resumeText) {
        StringBuilder prompt = new StringBuilder();

        // 添加技能角色指令
        if (skillId != null && !skillId.isBlank()) {
            prompt.append(String.format(SKILL_TOOL_INSTRUCTION, skillId, skillId));
        }

        // 添加语音面试输出约束
        prompt.append("\n\n").append(VOICE_RESPONSE_CONSTRAINTS);

        // 添加简历上下文
        if (resumeText != null && !resumeText.isEmpty()) {
            String safeResume = promptSanitizer.sanitize(resumeText);
            prompt.append("\n\n【实时语音面试 - 候选人简历内容】\n")
                .append("你已查阅过候选人简历。首轮仅用一句话说明已查阅，并立即进入首个问题。\n\n")
                .append("【简历解析文本】\n")
                .append(promptSanitizer.wrapWithDelimiters("resume", safeResume));
        }

        // 添加防注入安全指令
        prompt.append(PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION);
        return prompt.toString();
    }
}
