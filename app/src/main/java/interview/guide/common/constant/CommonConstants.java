/**
 * 通用常量定义
 *
 * 包含三组常量：StatusCode（HTTP 状态码）、Pagination（分页默认值）、
 * InterviewDefaults（面试默认配置，如技能 ID 和难度）。
 *
 * 联动关系：
 * - 被 Result 引用 StatusCode.SUCCESS 和 StatusCode.SERVER_ERROR
 * - 被 InterviewSessionService、InterviewQuestionService、InterviewPersistenceService、
 *   VoiceInterviewService 引用 Pagination 和 InterviewDefaults 常量
 */
package interview.guide.common.constant;

/**
 * 通用常量定义
 *
 * 包含三组常量：StatusCode（HTTP 状态码）、Pagination（分页默认值）、
 * InterviewDefaults（面试默认配置，如技能 ID 和难度）。
 *
 * 联动关系：
 * - 被 Result 引用 StatusCode.SUCCESS 和 StatusCode.SERVER_ERROR
 * - 被 InterviewSessionService、InterviewQuestionService 等引用 Pagination 和 InterviewDefaults 常量
 */
public final class CommonConstants {

    private CommonConstants() {}

    /** HTTP 状态码 */
    //任何和前端交互的地方，都需要这些状态码
    //静态嵌套类，在外部可以 StatusCode.SUCCESS这样调用
    public static final class StatusCode {
        public static final int SUCCESS = 200;
        public static final int BAD_REQUEST = 400;
        public static final int UNAUTHORIZED = 401;
        public static final int FORBIDDEN = 403;
        public static final int NOT_FOUND = 404;
        public static final int SERVER_ERROR = 500;
        
        private StatusCode() {}
    }
    
    /**
     * 分页默认值
     * 这里没有被用过，可能是后面开发分页相关的功能时预留的
     */
    public static final class Pagination {
        public static final int DEFAULT_PAGE = 1;
        public static final int DEFAULT_SIZE = 20;
        public static final int MAX_SIZE = 100;

        private Pagination() {}
    }

    /**
     * 面试默认值
     * 指项目启动后，默认面试的是java后端
     * 难度默认是中等
     */
    public static final class InterviewDefaults {
        public static final String SKILL_ID = "java-backend";
        public static final String DIFFICULTY = "mid";

        private InterviewDefaults() {}
    }

    /*
     */
}
