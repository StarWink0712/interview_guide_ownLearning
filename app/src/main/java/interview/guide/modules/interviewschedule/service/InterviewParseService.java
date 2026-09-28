/**
 * 面试邀约文本解析服务
 *
 * <p>整合规则解析和 AI 解析两种策略：<br>
 * 1. <b>规则解析（优先）</b>：通过正则表达式直接解析飞书、腾讯会议、Zoom 三种格式的邀约文本<br>
 * 2. <b>AI 解析（降级）</b>：规则解析提取不到关键字段时，调用 LLM 解析非结构化文本</p>
 *
 * <p>设计目的：面试邀请文本格式多样（不同平台、不同公司），单一规则无法覆盖所有场景。
 * 规则解析快速且稳定，适用于已知格式；AI 解析灵活，适用于不规则文本。
 * 两者结合可以在大多数场景下实现"无人工干预"的面试邀请自动录入。</p>
 * "早期 Spring 用 JDK 动态代理所以强制接口，现在 Spring Boot 默认用 CGLIB 代理可以直接代理具体类。
 * 我的原则是：有真正的多实现需求才抽接口，没必要为了'看起来规范'而增加一倍的文件数量。接口是为了抽象，不是为了仪式感。"
 * <p>使用场景：用户在面试管理页面上粘贴面试邀请邮件/短信内容，点击"解析"按钮后触发。</p>
 *
 * <p>联动关系：<br>
 * - 被 InterviewScheduleController 的 parse 接口调用<br>
 * - 依赖 LlmProviderRegistry 获取 ChatClient 进行 AI 解析<br>
 * - 依赖 PromptSanitizer 对用户输入进行安全清洗，防止注入攻击<br>
 * - 返回 ParseResponse，其中 data 字段为 CreateInterviewRequest</p>
 */
package interview.guide.modules.interviewschedule.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.modules.interviewschedule.model.CreateInterviewRequest;
import interview.guide.modules.interviewschedule.model.ParseResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一的面试邀约解析服务 —— 整合规则解析和 AI 解析
 * Simplified interview schedule parsing service combining rule-based and AI parsing
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewParseService {

    // ==================== 注入的依赖 ====================

    /** LLM Provider 注册中心，用于 AI 解析时获取指定 Provider 的 ChatClient 实例 */
    private final LlmProviderRegistry llmProviderRegistry;

    /** Jackson JSON 映射器，用于将 AI 返回的 JSON 字符串反序列化为 Java 对象 */
    private final ObjectMapper objectMapper;

    /** Prompt 安全清洗工具，用于移除用户输入中的恶意指令，防止 Prompt 注入攻击 */
    private final PromptSanitizer promptSanitizer;

    // ==================== 日期格式解析器 ====================

    /** 日期时间格式 1：yyyy-MM-dd HH:mm（如 2026-05-21 14:00） */
    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 日期时间格式 2：yyyy/MM/dd HH:mm（如 2026/05/21 14:00） */
    private static final DateTimeFormatter DATE_TIME_FORMATTER_2 =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");

    /** 中文数字 → 阿拉伯数字的映射表，用于解析"二面"等中文数字表述 */
    private static final Map<String, Integer> CHINESE_NUMBERS = Map.of(
            "一", 1, "二", 2, "三", 3, "四", 4, "五", 5,
            "六", 6, "七", 7, "八", 8, "九", 9, "十", 10
    );

    // ==================== 飞书格式正则表达式 ====================

    /** 飞书时间提取正则：匹配"时间：2026-05-21 14:00"或"时段：2026-05-21 14:00" */
    private static final Pattern TIME_PATTERN_FEISHU =
            Pattern.compile("(?:时间|时段)[：:]\\s*(\\d{4}[-/]\\d{2}[-/]\\d{2}\\s+\\d{2}:\\d{2})");

    /** 飞书会议链接提取正则 */
    private static final Pattern LINK_PATTERN_FEISHU =
            Pattern.compile("https://meeting\\.feishu\\.cn/[^\\s\\n]+");

    /** 飞书公司名称提取正则：匹配"公司：xxx"或"单位：xxx" */
    private static final Pattern COMPANY_PATTERN_FEISHU =
            Pattern.compile("(?:公司|单位|组织)[：:]\\s*([^\\s\\n]{1,50})");

    /** 飞书岗位名称提取正则：匹配"岗位：xxx"或"职位：xxx" */
    private static final Pattern POSITION_PATTERN_FEISHU =
            Pattern.compile("(?:岗位|职位|职务)[：:]\\s*([^\\s\\n]{1,50})");

    /** 飞书面试轮次提取正则：匹配"第二轮"或"第3场"等 */
    private static final Pattern ROUND_PATTERN_FEISHU =
            Pattern.compile("第\\s*[一二三四五六七八九十\\d]+\\s*[轮场]");

    // ==================== 腾讯会议格式正则表达式 ====================

    /** 腾讯会议时间提取正则：匹配"2026-05-21 14:00"日期+时间 */
    private static final Pattern TIME_PATTERN_TENCENT =
            Pattern.compile("(\\d{4}[-/]\\d{2}[-/]\\d{2})\\s+(\\d{2}:\\d{2})");

    /** 腾讯会议号提取正则：匹配"会议号：123456789"或"ID：123456789" */
    private static final Pattern MEETING_ID_PATTERN_TENCENT =
            Pattern.compile("(?:会议号|ID)[：:]?\\s*(\\d{9,})");

    /** 腾讯会议密码提取正则：匹配"密码：1234" */
    private static final Pattern PASSWORD_PATTERN_TENCENT =
            Pattern.compile("密码[：:]?\\s*(\\d{4,})");

    /** 腾讯会议公司名称提取正则 */
    private static final Pattern COMPANY_PATTERN_TENCENT =
            Pattern.compile("(?:公司|单位)[：:]\\s*([^\\s\\n]{1,50})");

    /** 腾讯会议岗位名称提取正则 */
    private static final Pattern POSITION_PATTERN_TENCENT =
            Pattern.compile("(?:岗位|职位)[：:]\\s*([^\\s\\n]{1,50})");

    // ==================== Zoom 格式正则表达式 ====================

    /** Zoom 会议链接提取正则 */
    private static final Pattern LINK_PATTERN_ZOOM =
            Pattern.compile("https://zoom\\.us/j/[^\\s\\n]+");

    /** Zoom 日期提取正则 */
    private static final Pattern DATE_PATTERN_ZOOM =
            Pattern.compile("(\\d{4}[-/]\\d{2}[-/]\\d{2})");

    /** Zoom 时间提取正则（时:分格式） */
    private static final Pattern HOUR_PATTERN_ZOOM =
            Pattern.compile("(\\d{1,2}:\\d{2})");

    /** 通用轮次提取正则：提取中文数字或阿拉伯数字 */
    private static final Pattern ROUND_NUMBER_PATTERN =
            Pattern.compile("[一二三四五六七八九十]|\\d");

    // ==================== AI 解析的 Prompt 模板 ====================

    /**
     * AI 解析的 Prompt 模板
     *
     * <p>使用 String.format() 填充两个占位符：</p>
     * <ul>
     *   <li>%s（第1个）：当前日期，用于推算相对时间（如"明天下午2点"）</li>
     *   <li>%s（第2个）：安全清洗后的用户输入文本 + 数据边界指令</li>
     * </ul>
     *
     * <p>设计要点：</p>
     * <ul>
     *   <li>明确要求 LLM 返回纯 JSON，不包含 markdown 代码块标记</li>
     *   <li>要求"面试官是谁不重要"防止 LLM 过度关注次要信息而漏掉关键字段</li>
     *   <li>指定 interviewTime 的格式为 ISO 8601，避免时区歧义</li>
     * </ul>
     */
    private static final String PARSE_PROMPT = """
            你是一个专业的面试邀约信息提取助手。请仔细分析以下文本，提取面试相关信息。

            **提取规则**：
            1. companyName（公司名称）：提取面试公司的全称或简称，**必需字段**
            2. position（岗位名称）：提取面试岗位的名称，**必需字段**
            3. interviewTime（面试时间）：提取面试开始时间并转换为 ISO 8601 格式，**必需字段**
               - 格式：YYYY-MM-DDTHH:MM:SS（例如：2026-04-10T14:00:00）
               - 若只有相对时间（如"明天下午2点"），根据当前日期 %s 推算
            4. interviewType（面试形式）：ONSITE（现场）/ VIDEO（视频）/ PHONE（电话）
            5. meetingLink（会议链接）：提取完整的会议链接或会议号+密码
            6. roundNumber（第几轮面试）：提取数字（1-10），如"二面"提取为2
            7. notes（其他备注）：包含面试官姓名（如果不重要可忽略）、时长（**默认30分钟**）等。

            **重要提示**：
            - 面试官是谁不重要，只需在 notes 中提及。
            - 优先保证 companyName、position、interviewTime 的准确性。
            - 如果文本中没说时长，默认设置为 30 分钟。

            **待解析文本**：
            %s

            **返回格式**：
            纯 JSON 格式，不要包含```json标记，示例：
            {"companyName":"阿里巴巴","position":"Java工程师","interviewTime":"2026-04-10T14:00:00","interviewType":"VIDEO","meetingLink":"https://meeting.feishu.cn/xxx","roundNumber":2,"interviewer":"张三","notes":"技术面"}
            """;

    // ==================== 核心入口方法 ====================

    /**
     * 解析面试邀约文本 —— 三阶段逐级降级策略
     *
     * <p>执行流程：</p>
     * <ol>
     *   <li><b>规则解析</b>：根据 source 参数或文本特征自动识别平台格式，使用正则提取信息</li>
     *   <li><b>AI 解析</b>：规则解析失败或提取不全时，调用 LLM 解析非结构化文本</li>
     *   <li><b>返回失败</b>：两种方式均失败时返回空的 ParseResponse</li>
     * </ol>
     *
     * <p>为什么优先规则解析？规则解析不依赖外部服务，速度更快（毫秒级），且结果确定可预期。
     * AI 解析需要网络请求，耗时长（秒级），且有幻觉风险。</p>
     *
     * @param rawText 用户粘贴的原始面试邀约文本（如邮件/短信/聊天记录）
     * @param source  来源平台标识：feishu / tencent / zoom，传 null 时自动识别
     * @return ParseResponse 包含解析结果和解析方式信息（详见 ParseResponse 字段说明）
     *         - 解析成功：success=true, data=填充好的 CreateInterviewRequest, 置信度分数
     *         - 解析失败：success=false, data=null, reason=失败原因
     */
    public ParseResponse parse(String rawText, String source) {
        log.info("开始解析文本，来源: {}, 文本长度: {}", source,
                rawText != null ? rawText.length() : 0);

        // === 边界条件：空文本直接返回失败 ===
        if (rawText == null || rawText.trim().isEmpty()) {
            log.warn("Input text is null or empty");
            return new ParseResponse(false, null, 0.0, "none", "输入文本为空");
        }

        // Step 1: 尝试规则解析（优先，毫秒级响应）
        CreateInterviewRequest result = tryRuleParsing(rawText, source);
        if (isValidResult(result)) {
            log.info("规则解析成功");
            return new ParseResponse(true, result, 0.95, "rule", "规则解析成功");
        }

        // Step 2: 规则解析失败，尝试 AI 解析（秒级，但更灵活）
        log.info("规则解析失败，尝试 AI 解析");
        result = parseWithAI(rawText, source);
        if (isValidResult(result)) {
            log.info("AI 解析成功");
            return new ParseResponse(true, result, 0.8, "ai", "AI 解析成功");
        }

        // Step 3: 两种方式均失败，返回失败结果
        log.warn("所有解析方式均失败");
        return new ParseResponse(false, null, 0.0, "none", "解析失败");
    }

    // ==================== 规则解析（分发器） ====================

    /**
     * 规则解析分发方法
     *
     * <p>根据 source 参数或文本特征判断平台类型，调用对应的解析方法。
     * 优先级：显式指定 source > 文本特征识别 > 全部尝试。</p>
     *
     * <p>解析逻辑：</p>
     * <ol>
     *   <li>如果 source 明确指定了 feishu/tencent/zoom，只尝试该格式</li>
     *   <li>如果 source 未指定，根据文本特征（如"飞书""会议号""Zoom"等关键词）试探</li>
     *   <li>如果特征识别失败，依次尝试三种格式，任一成功即返回</li>
     * </ol>
     *
     * @param rawText 原始文本
     * @param source  明确指定的来源平台，可为 null
     * @return 解析结果对象，关键字段不全时返回 null
     */
    private CreateInterviewRequest tryRuleParsing(String rawText, String source) {
        // 情况 1：显式指定了来源格式，直接调用对应的解析器
        if ("feishu".equalsIgnoreCase(source)) {
            return parseFeishu(rawText);
        } else if ("tencent".equalsIgnoreCase(source)) {
            return parseTencent(rawText);
        } else if ("zoom".equalsIgnoreCase(source)) {
            return parseZoom(rawText);
        }

        // 情况 2：没有指定来源，通过文本特征自动识别
        // 注意：这里使用 if 而非 if-else，因为文本可能包含多种格式的混合信息
        if (rawText.contains("飞书") || rawText.contains("Feishu")
                || rawText.contains("meeting.feishu.cn")) {
            CreateInterviewRequest result = parseFeishu(rawText);
            if (isValidResult(result)) return result;
        }

        if (rawText.contains("腾讯会议") || rawText.contains("Tencent Meeting")
                || rawText.contains("会议号")) {
            CreateInterviewRequest result = parseTencent(rawText);
            if (isValidResult(result)) return result;
        }

        if (rawText.contains("Zoom") || rawText.contains("zoom.us")) {
            CreateInterviewRequest result = parseZoom(rawText);
            if (isValidResult(result)) return result;
        }

        // 情况 3：特征不匹配，全部依次尝试（兜底策略）
        CreateInterviewRequest result = parseFeishu(rawText);
        if (isValidResult(result)) return result;

        result = parseTencent(rawText);
        if (isValidResult(result)) return result;

        return parseZoom(rawText);
    }

    // ==================== 飞书格式解析 ====================

    /**
     * 解析飞书会议邀请文本
     *
     * <p>飞书邀请通常包含结构化信息，如：<br>
     * "时间：2026-05-21 14:00<br>
     * 公司：字节跳动<br>
     * 岗位：Java 后端工程师<br>
     * 链接：https://meeting.feishu.cn/xxx"</p>
     *
     * <p>使用正则表达式逐个提取各字段。提取失败时该字段保留为 null，
     * 由 isValidResult() 判断是否解析成功。</p>
     *
     * <p>异常处理：整个解析过程包裹在 try-catch 中，任何字段提取失败
     * 不影响已成功提取的其他字段（部分结果仍然可用）。</p>
     *
     * @param rawText 飞书面试邀请原始文本
     * @return 部分或完全填充的 CreateInterviewRequest 对象
     */
    private CreateInterviewRequest parseFeishu(String rawText) {
        log.debug("尝试解析飞书格式");

        CreateInterviewRequest request = new CreateInterviewRequest();

        try {
            // 提取面试时间：匹配"时间：2026-05-21 14:00"
            Matcher timeMatcher = TIME_PATTERN_FEISHU.matcher(rawText);
            if (timeMatcher.find()) {
                String timeStr = timeMatcher.group(1);
                request.setInterviewTime(parseDateTime(timeStr));
            }

            // 提取飞书会议链接
            Matcher linkMatcher = LINK_PATTERN_FEISHU.matcher(rawText);
            if (linkMatcher.find()) {
                request.setMeetingLink(linkMatcher.group());
            }

            // 提取公司名称
            Matcher companyMatcher = COMPANY_PATTERN_FEISHU.matcher(rawText);
            if (companyMatcher.find()) {
                request.setCompanyName(companyMatcher.group(1).trim());
            }

            // 提取岗位名称
            Matcher positionMatcher = POSITION_PATTERN_FEISHU.matcher(rawText);
            if (positionMatcher.find()) {
                request.setPosition(positionMatcher.group(1).trim());
            }

            // 提取面试轮次（如"第二轮"→2）
            Matcher roundMatcher = ROUND_PATTERN_FEISHU.matcher(rawText);
            if (roundMatcher.find()) {
                request.setRoundNumber(parseRoundNumber(roundMatcher.group()));
            }

            // 飞书会议默认为视频面试
            request.setInterviewType("VIDEO");

            return request;

        } catch (Exception e) {
            // 任何异常不阻断解析，已提取的字段仍可返回
            log.error("飞书格式解析异常", e);
            return request;
        }
    }

    // ==================== 腾讯会议格式解析 ====================

    /**
     * 解析腾讯会议邀请文本
     *
     * <p>腾讯会议邀请通常包含时间、会议号和密码：<br>
     * "2026-05-21 14:00 会议号：123456789 密码：1234"</p>
     *
     * <p>与飞书解析的不同点：</p>
     * <ul>
     *   <li>腾讯会议没有直接的公司/岗位信息，需要结合上下文</li>
     *   <li>会议链接是通过会议号+密码拼装而成，不是完整 URL</li>
     * </ul>
     *
     * @param rawText 腾讯会议面试邀请原始文本
     * @return 部分或完全填充的 CreateInterviewRequest 对象
     */
    private CreateInterviewRequest parseTencent(String rawText) {
        log.debug("尝试解析腾讯会议格式");

        CreateInterviewRequest request = new CreateInterviewRequest();

        try {
            // 提取面试时间：匹配"2026-05-21 14:00"
            Matcher timeMatcher = TIME_PATTERN_TENCENT.matcher(rawText);
            if (timeMatcher.find()) {
                String timeStr = timeMatcher.group(1) + " " + timeMatcher.group(2);
                request.setInterviewTime(parseDateTime(timeStr));
            }

            // 提取会议号和密码，拼装成会议链接文本
            Matcher meetingIdMatcher = MEETING_ID_PATTERN_TENCENT.matcher(rawText);
            Matcher passwordMatcher = PASSWORD_PATTERN_TENCENT.matcher(rawText);

            StringBuilder meetingLink = new StringBuilder();
            if (meetingIdMatcher.find()) {
                meetingLink.append("会议号: ").append(meetingIdMatcher.group());
            }
            if (passwordMatcher.find()) {
                meetingLink.append(" 密码: ").append(passwordMatcher.group());
            }
            if (meetingLink.length() > 0) {
                request.setMeetingLink(meetingLink.toString());
            }

            // 提取公司名称（腾讯会议邀请中可能不包含，为可选字段）
            Matcher companyMatcher = COMPANY_PATTERN_TENCENT.matcher(rawText);
            if (companyMatcher.find()) {
                request.setCompanyName(companyMatcher.group(1).trim());
            }

            // 提取岗位名称（同为公司可选字段）
            Matcher positionMatcher = POSITION_PATTERN_TENCENT.matcher(rawText);
            if (positionMatcher.find()) {
                request.setPosition(positionMatcher.group(1).trim());
            }

            // 腾讯会议默认为视频面试
            request.setInterviewType("VIDEO");

            return request;

        } catch (Exception e) {
            log.error("腾讯会议格式解析异常", e);
            return request;
        }
    }

    // ==================== Zoom 格式解析 ====================

    /**
     * 解析 Zoom 会议邀请文本
     *
     * <p>Zoom 邀请通常包含会议链接和日期时间：<br>
     * "https://zoom.us/j/xxx 2026-05-21 14:00"</p>
     *
     * <p>注意事项：Zoom 的解析能力最为有限——只能提取链接和时间两个字段，
     * 公司名称和岗位名称通常需要放大镜看额外的上下文才能获取。</p>
     *
     * @param rawText Zoom 面试邀请原始文本
     * @return 部分填充的 CreateInterviewRequest 对象（可能缺少公司/岗位）
     */
    private CreateInterviewRequest parseZoom(String rawText) {
        log.debug("尝试解析 Zoom 格式");

        CreateInterviewRequest request = new CreateInterviewRequest();

        try {
            // 提取 Zoom 会议链接
            Matcher linkMatcher = LINK_PATTERN_ZOOM.matcher(rawText);
            if (linkMatcher.find()) {
                request.setMeetingLink(linkMatcher.group());
            }

            // 分别匹配日期和时间，然后拼装
            Matcher dateMatcher = DATE_PATTERN_ZOOM.matcher(rawText);
            Matcher hourMatcher = HOUR_PATTERN_ZOOM.matcher(rawText);

            if (dateMatcher.find() && hourMatcher.find()) {
                String timeStr = dateMatcher.group(1) + " " + hourMatcher.group(1);
                request.setInterviewTime(parseDateTime(timeStr));
            }

            request.setInterviewType("VIDEO");

            return request;

        } catch (Exception e) {
            log.error("Zoom 格式解析异常", e);
            return request;
        }
    }

    // ==================== AI 解析 ====================

    /**
     * 使用 LLM 解析面试邀约文本
     *
     * <p>当规则解析无法提取完整信息时，使用大语言模型进行灵活解析。
     * 流程如下：</p>
     * <ol>
     *   <li><b>Prompt 安全清洗</b>：去除用户输入中的恶意指令，防止 Prompt 注入</li>
     *   <li><b>构建 Prompt</b>：将当前日期和清洗后的文本填入 Prompt 模板</li>
     *   <li><b>调用 LLM</b>：通过 LlmProviderRegistry 获取 ChatClient 发起调用</li>
     *   <li><b>提取 JSON</b>：从 LLM 返回内容中提取 JSON（兼容 Markdown 代码块格式）</li>
     *   <li><b>反序列化</b>：将 JSON 转为 CreateInterviewRequest</li>
     * </ol>
     *
     * <p>异常处理：</p>
     * <ul>
     *   <li>LLM 返回空内容 → 返回 null，由上层 tryRuleParsing 继续尝试</li>
     *   <li>JSON 解析失败 → 返回 null（日志记录错误信息）</li>
     *   <li>字段缺失 → 该字段保留为 null，isValidResult 判断是否足够</li>
     *   <li>时间格式异常 → 该字段忽略，日志记录警告</li>
     * </ul>
     *
     * @param rawText 原始用户输入文本
     * @param provider LLM Provider 标识（如 "deepseek"、"openai"），null 时使用默认 Provider
     * @return 解析成功的 CreateInterviewRequest，解析失败返回 null
     */
    private CreateInterviewRequest parseWithAI(String rawText, String provider) {
        try {
            // 获取当前日期，用于 LLM 推算相对时间（如"明天下午2点"应推算为确切的日期）
            String currentDate = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE);

            // 对用户输入进行安全清洗：移除可能的恶意指令
            String safeRawText = promptSanitizer.sanitize(rawText);

            // 构建最终 Prompt：填充当前日期 + 清洗后的文本 + 数据边界指令
            String prompt = String.format(PARSE_PROMPT, currentDate,
                    PromptSecurityConstants.DATA_BOUNDARY_INSTRUCTION + "\n"
                    + promptSanitizer.wrapWithDelimiters("parse-input", safeRawText));

            // 获取指定 Provider 的 ChatClient（获取失败时使用默认 Provider）
            ChatClient chatClient = llmProviderRegistry.getChatClientOrDefault(provider);

            // 调用 LLM
            String content = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            // 检查 LLM 返回是否为空
            if (content == null || content.trim().isEmpty()) {
                log.error("AI 解析返回内容为空");
                return null;
            }

            // 提取 JSON 内容：兼容 LLM 返回 Markdown 代码块格式（```json ... ```）
            /*这段代码不是在"转 JSON"而是在"
             *剥 Markdown 代码块这层壳"——LLM 返回的 JSON 
             *经常包在 ```json 标记里，不剥掉没法反序列化。
             *即去掉llm返回的内容外面的壳： 我来帮你解析
             */
            String jsonContent = content.trim();
            if (jsonContent.contains("```")) {
                Pattern pattern = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)\\s*```");
                Matcher matcher = pattern.matcher(jsonContent);
                if (matcher.find()) {
                    jsonContent = matcher.group(1).trim();
                }
            }

            log.debug("提取到的 JSON 内容: {}", jsonContent);

            // 将 JSON（去壳后的llm返回内容） 反序列化为 Map
            Map<String, Object> result = objectMapper.readValue(
                    jsonContent, new TypeReference<Map<String, Object>>() {});

            if (result == null || result.isEmpty()) {
                log.error("JSON 解析返回空结果");
                return null;
            }

            // 逐字段提取并填充 CreateInterviewRequest
            CreateInterviewRequest request = new CreateInterviewRequest();

            // === 必需字段：companyName ===
            if (result.get("companyName") != null) {
                request.setCompanyName(result.get("companyName").toString().trim());
            }

            // === 必需字段：position ===
            if (result.get("position") != null) {
                request.setPosition(result.get("position").toString().trim());
            }

            // === 必需字段：interviewTime（需校验格式） ===
            if (result.get("interviewTime") != null) {
                try {
                    String timeStr = result.get("interviewTime").toString().trim();
                    // 兼容 YYYY-MM-DDTHH:MM（16位）和 YYYY-MM-DDTHH:MM:SS（19位）两种格式
                    if (timeStr.length() == 16) {
                        request.setInterviewTime(LocalDateTime.parse(timeStr + ":00"));
                    } else {
                        request.setInterviewTime(LocalDateTime.parse(timeStr));
                    }
                } catch (Exception e) {
                    // LLM 返回了不规范的时间格式 → 该字段留 null，不中断解析
                    log.error("AI 返回的时间格式不正确: {}", result.get("interviewTime"));
                }
            }

            // === 可选字段 ===
            if (result.get("interviewType") != null) {
                request.setInterviewType(result.get("interviewType").toString().trim());
            }

            if (result.get("meetingLink") != null) {
                request.setMeetingLink(result.get("meetingLink").toString().trim());
            }

            // roundNumber 需要转为 int
            if (result.get("roundNumber") != null) {
                try {
                    String roundStr = result.get("roundNumber").toString().trim();
                    request.setRoundNumber(Integer.parseInt(roundStr));
                } catch (Exception e) {
                    // LLM 返回了非数字内容 → 默认第 1 轮
                    request.setRoundNumber(1);
                } 
            }

            if (result.get("interviewer") != null) {
                request.setInterviewer(result.get("interviewer").toString().trim());
            }

            if (result.get("notes") != null) {
                request.setNotes(result.get("notes").toString().trim());
            }

            log.info("AI 解析成功: {}", request.getCompanyName());
            return request;

        } catch (Exception e) {
            // 任何 AI 解析异常（网络超时、JSON 解析失败等）都记录日志并返回 null
            log.error("AI 解析异常: {}", e.getMessage(), e);
            return null;
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 解析日期时间字符串
     *
     * <p>支持三种格式（自动识别）：</p>
     * <ol>
     *   <li><b>yyyy-MM-dd HH:mm</b>（16位）：如 "2026-05-21 14:00"</li>
     *   <li><b>yyyy-MM-dd HH:mm:ss</b>（19位）：如 "2026-05-21 14:00:00"</li>
     *   <li><b>其他格式</b>：直接使用 LocalDateTime.parse() 尝试</li>
     * </ol>
     *
     * <p>兼容性处理：输入中的 "/" 自动转换为 "-"，
     * 保证 "2026/05/21" 也能正确解析。</p>
     *
     * @param timeStr 日期时间字符串
     * @return 解析后的 LocalDateTime，解析失败返回 null
     */
    private LocalDateTime parseDateTime(String timeStr) {
        try {
            // 兼容 "/" 分隔符 → 统一转为 "-" 格式
            timeStr = timeStr.replace("/", "-");

            // 根据字符串长度选择对应的解析器
            if (timeStr.length() == 16) {           // yyyy-MM-dd HH:mm
                return LocalDateTime.parse(timeStr, DATE_TIME_FORMATTER);
            } else if (timeStr.length() == 19) {    // yyyy-MM-dd HH:mm:ss
                return LocalDateTime.parse(timeStr,
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            }
            // 兜底：使用 ISO 格式直接解析
            return LocalDateTime.parse(timeStr);

        } catch (Exception e) {
            // 时间解析失败不阻断流程，由调用方判断返回值是否为 null
            log.error("时间解析失败: {}", timeStr, e);
            return null;
        }
    }

    /**
     * 解析面试轮次的中文/数字表述
     *
     * <p>输入可能是以下格式：</p>
     * <ul>
     *   <li>"第二轮" → 提取"二" → 2</li>
     *   <li>"第3轮" → 提取"3" → 3</li>
     *   <li>"第 4 轮" → 提取"4" → 4</li>
     *   <li>"一面" → 提取"一" → 1</li>
     *   <li>"5" → 5</li>
     * </ul>
     *
     * <p>实现逻辑：先尝试阿拉伯数字匹配，再尝试中文数字查找，
     * 两者都失败时默认返回第 1 轮。</p>
     *
     * @param text 包含轮次信息的文本片段
     * @return 解析后的轮次数字（1-10），默认 1
     */
    private int parseRoundNumber(String text) {
        if (text == null) {
            return 1;
        }

        text = text.trim();

        // 情况 1：纯数字（如 "3"）
        if (text.matches("\\d+")) {
            return Integer.parseInt(text);
        }

        // 情况 2：包含中文数字（如 "第二轮" → "二" → 2）
        Matcher matcher = ROUND_NUMBER_PATTERN.matcher(text);
        if (matcher.find()) {
            String num = matcher.group();
            // 优先查中文数字映射表，找不到则尝试提取其中的数字字符
            return CHINESE_NUMBERS.getOrDefault(num,
                    Integer.parseInt(num.replaceAll("\\D", "")));
        }

        // 情况 3：什么都没匹配到，默认第 1 轮
        return 1;
    }

    /**
     * 验证解析结果是否包含必需字段
     *
     * <p>三个必需字段必须同时非空，才算"有效解析结果"：</p>
     * <ul>
     *   <li>companyName（公司名称）</li>
     *   <li>position（岗位名称）</li>
     *   <li>interviewTime（面试时间）</li>
     * </ul>
     *
     * <p>为什么是这三个？因为面试日程的创建要求这三个字段必填
     * （参见 CreateInterviewRequest 中的 @NotBlank/@NotNull 校验注解）。</p>
     *
     * @param result 待验证的解析结果
     * @return true 表示三个必需字段均已提取成功
     */
    private boolean isValidResult(CreateInterviewRequest result) {
        return result != null
                && result.getCompanyName() != null
                && result.getPosition() != null
                && result.getInterviewTime() != null;
    }
}
