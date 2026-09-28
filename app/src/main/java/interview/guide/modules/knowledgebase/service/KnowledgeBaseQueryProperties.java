/**
 * 知识库 RAG 查询配置属性
 *
 * 集中管理 RAG 查询相关的所有可配置项，通过 {@code @ConfigurationProperties(prefix = "app.ai.rag")}
 * 绑定 application.yml 中的配置。类中所有字段都有默认值，配置缺失时使用默认值不影响启动。
 *
 * 配置在 application.yml 中的写法示例：
 * <pre>{@code
 * app:
 *   ai:
 *     rag:
 *       rewrite:
 *         enabled: true          # 是否启用 Query Rewrite（LLM 改写问题）
 *       search:
 *         short-query-length: 4  # 短问题判定阈值（字符数）
 *         topk-short: 20         # 短问题的 topK
 *         topk-medium: 12        # 中等长度问题的 topK
 *         topk-long: 8           # 长问题的 topK
 *         min-score-short: 0.25  # 短问题的最低相似度阈值
 *         min-score-default: 0.28  # 默认最低相似度阈值
 *       history:
 *         enabled: true          # 是否启用多轮上下文
 *         max-messages: 10       # 最多携带最近 N 条历史消息
 *       system-prompt-path: classpath:prompts/knowledgebase-query-system.st
 *       user-prompt-path: classpath:prompts/knowledgebase-query-user.st
 *       rewrite-prompt-path: classpath:prompts/knowledgebase-query-rewrite.st
 * }</pre>
 *
 * 联动关系：
 * - 被 KnowledgeBaseQueryService 在构造时注入，用于初始化搜索参数、加载 Prompt 模板
 * - 被 RagChatSessionService 注入，用于读取历史上下文配置（history.enabled / maxMessages）
 * - 配置前缀：app.ai.rag，通过 Spring Boot 的 @ConfigurationProperties 自动绑定
 */
package interview.guide.modules.knowledgebase.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 查询配置
 * <p>
 * 将 RAG 问答过程中的所有可变参数集中到一个配置类中，避免在 Service 中硬编码。
 * 调整检索策略（如 topK、相似度阈值）时只需修改配置文件，无需改代码重新编译。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.ai.rag")
public class KnowledgeBaseQueryProperties {

    // ======================== Prompt 模板路径 ========================

    /**
     * 系统提示词模板路径
     * <p>
     * 用于定义 AI 的角色和行为——告诉 LLM 它是一个基于知识库回答问题的助手，
     * 只能使用提供的文档内容回答，不能编造信息，不知道就说不知道。
     * 模板文件使用 Spring AI 的 PromptTemplate 格式（StringTemplate 语法）。
     */
    private String systemPromptPath = "classpath:prompts/knowledgebase-query-system.st";

    /**
     * 用户提示词模板路径
     * <p>
     * 用于组装完整的用户提示词，模板中包含两个变量：
     * - {context}：从向量检索获取的相关文档片段拼接文本
     * - {question}：用户原始问题
     * 模板将这些变量注入后生成最终的 prompt 发给 LLM。
     */
    private String userPromptPath = "classpath:prompts/knowledgebase-query-user.st";

    /**
     * Query Rewrite 提示词模板路径
     * <p>
     * 用于 LLM 改写用户问题以提升向量检索命中率。模板包含 {question} 和 {history} 两个变量。
     * 改写目的：将口语化/非标准表达转换为更精确的检索查询。
     * 例如：用户说"Java 里那个自动清理内存的是啥" → 改写为 "Java 垃圾回收机制"
     */
    private String rewritePromptPath = "classpath:prompts/knowledgebase-query-rewrite.st";

    // ======================== 子配置组 ========================

    /**
     * Query Rewrite（查询改写）相关配置
     * <p>
     * 控制是否启用 LLM 改写用户问题的功能。启用后，每次 RAG 查询会先调用一次
     * 轻量 LLM 请求改写问题，提升向量检索精度；禁用则直接用原始问题检索。
     * 改写失败时会优雅降级回原始问题，不影响主流程。
     */
    private Rewrite rewrite = new Rewrite();

    /**
     * 向量检索参数配置
     * <p>
     * 根据问题长度动态调整 topK（返回数量）和 minScore（最低相似度阈值）：
     * - 短问题（如关键词查询）→ 高阈值 + 大 topK，保证精确匹配
     * - 长问题（如完整句子）→ 低阈值 + 小 topK，保证召回率
     */
    private Search search = new Search();

    /**
     * 多轮对话历史上下文配置
     * <p>
     * 控制 RAG 聊天模式是否携带历史消息作为上下文。
     * 启用后 LLM 能理解对话背景（如用户追问"那第二种方法呢"能知道指什么），
     * 但会增加 prompt token 消耗。
     */
    private History history = new History();

    // ======================== 内部配置类 ========================

    /**
     * Query Rewrite 配置
     */
    @Data
    public static class Rewrite {
        /**
         * 是否启用 Query Rewrite
         * <p>
         * 默认 true。关闭后所有 RAG 查询直接使用原始问题检索，不再调用 LLM 改写。
         * 关闭的理由可能是：节省 LLM 调用费用、改写质量不佳、或知识库内容本身就是关键词友好的。
         */
        private boolean enabled = true;
    }

    /**
     * 向量检索参数配置
     * <p>
     * 核心设计思想：问题越短越像关键词查询（精确匹配更重要），问题越长越像自然语言（召回率更重要）。
     * 因此短问题用高 minScore + 大 topK，长问题反之。
     */
    @Data
    public static class Search {
        /**
         * 短问题判定阈值（去除空格后的字符数）
         * <p>
         * 问题长度 ≤ 此值时视为"短问题"（如 "Java GC"、"微服务架构"），
         * 使用 topkShort 和 minScoreShort 进行精确检索。
         * 默认 4 字符。
         */
        private int shortQueryLength = 4;

        /**
         * 短问题的 topK（返回向量数量）
         * <p>
         * 短问题使用较大的 topK。因为短查询缺乏上下文，需要更多候选文档
         * 来覆盖可能的语义发散。默认 20。
         */
        private int topkShort = 20;

        /**
         * 中等长度问题的 topK
         * <p>
         * 问题长度在 shortQueryLength+1 到 12 之间时使用。
         * 此时已有一定上下文，topK 适当降低。默认 12。
         */
        private int topkMedium = 12;

        /**
         * 长问题的 topK
         * <p>
         * 问题长度 > 12 时使用。长问题本身包含更多信息，检索结果更聚焦，
         * topK 可以更低。默认 8。
         */
        private int topkLong = 8;

        /**
         * 短问题的最低相似度阈值（COSINE 距离）
         * <p>
         * 短问题使用较高阈值，避免返回大量弱相关文档。只有相似度 ≥ 此值的结果才被采纳。
         * 范围 0~1，越接近 1 越严格。默认 0.25。
         */
        private double minScoreShort = 0.25;

        /**
         * 默认最低相似度阈值
         * <p>
         * 中等和长问题使用此阈值。阈值低于短问题，因为长问题的检索结果更分散，
         * 需要更宽的匹配范围。默认 0.28。
         */
        private double minScoreDefault = 0.28;
    }

    /**
     * 多轮对话历史上下文配置
     * <p>
     * 仅在 RAG 聊天模式（RagChatSessionService）下生效，
     * 知识库直接问答（KnowledgeBaseController.queryKnowledgeBase）不使用此配置。
     */
    @Data
    public static class History {
        /**
         * 是否携带历史消息作为多轮上下文
         * <p>
         * 默认 true。启用后每次 RAG 聊天请求会将最近的 N 条历史消息
         * 作为对话上下文传给 LLM，使其能理解追问和指代。
         */
        private boolean enabled = true;

        /**
         * 最多携带的历史消息数量
         * <p>
         * 限制传入 LLM 的历史消息数量，控制 prompt token 消耗。
         * 只取最近 N 条已完成的消息（user + assistant 成对），
         * 实际方法 loadHistoryMessages 会查 maxMessages+1 条然后排除当前轮问题。
         * 默认 10。
         */
        private int maxMessages = 10;
    }
}
