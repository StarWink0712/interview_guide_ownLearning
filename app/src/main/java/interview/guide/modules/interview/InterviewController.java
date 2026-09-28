/**
 * 模拟面试 REST 控制器
 *
 * 提供面试会话的完整生命周期 API：创建会话、获取当前题目、提交/暂存答案、
 * 提前交卷、生成报告、导出 PDF、查看详情、删除会话等。
 * 使用 @RateLimit 对创建和提交接口做全局限流保护。
 *
 * 联动关系：
 * - 委托 InterviewSessionService 处理会话创建、答题、交卷、报告生成
 * - 委托 InterviewHistoryService 获取面试详情和导出 PDF
 * - 委托 InterviewPersistenceService 删除会话和列表查询
 * - 使用 @RateLimit 注解（common.annotation）做限流
 */
package interview.guide.modules.interview;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.interview.model.CreateInterviewRequest;
import interview.guide.modules.interview.model.InterviewDetailDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.model.SessionListItemDTO;
import interview.guide.modules.interview.model.SubmitAnswerRequest;
import interview.guide.modules.interview.model.SubmitAnswerResponse;
import interview.guide.modules.interview.service.InterviewHistoryService;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.interview.service.InterviewSessionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
 
/**
 * 面试控制器
 * 提供模拟面试相关的API接口
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "模拟面试", description = "面试会话创建、问答交互与报告生成")
public class InterviewController {
    
    //session是面试会话服务
    private final InterviewSessionService sessionService;
    //history是面试历史服务
    private final InterviewHistoryService historyService;
    //persistence是面试持久化服务
    private final InterviewPersistenceService persistenceService;
    
    /**
     * 列出所有面试会话（用于面试记录页）
     *
     * <p>调用入口：前端面试记录页通过 {@code GET /api/interview/sessions} 调用。
     * 主要调用 {@link InterviewPersistenceService#findAll()} 查询会话，
     * 再通过 {@link SessionListItemDTO#from} 转换为列表 DTO。
     */
    @GetMapping("/api/interview/sessions")
    public Result<List<SessionListItemDTO>> listSessions() {
        List<SessionListItemDTO> items = persistenceService.findAll().stream()
            .map(SessionListItemDTO::from)
            .toList();
        return Result.success(items);
    }

    /**
     * 创建面试会话
     *
     * <p>调用入口：前端开始模拟面试时通过
     * {@code POST /api/interview/sessions} 调用，并经过全局与 IP 两个维度的限流。
     *
     * <p>主要调用 {@link InterviewSessionService#createSession(CreateInterviewRequest)}；
     * Service 负责复用未完成会话、调用 LLM 出题以及写入 Redis 和数据库。
     */
    @PostMapping("/api/interview/sessions")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
    public Result<InterviewSessionDTO> createSession(@RequestBody CreateInterviewRequest request) {
        log.info("创建面试会话，题目数量: {}", request.questionCount());
        //创建面试会话
        //返回会话 ID
        InterviewSessionDTO session = sessionService.createSession(request);
        return Result.success(session);
    }
    
    /**
     * 获取会话信息
     *
     * <p>调用入口：前端恢复或刷新面试页面时通过
     * {@code GET /api/interview/sessions/{sessionId}} 调用。
     * 该方法委托 {@link InterviewSessionService#getSession(String)}，
     * 由 Service 处理 Redis 缓存命中和数据库恢复。
     */
    @GetMapping("/api/interview/sessions/{sessionId}")
    public Result<InterviewSessionDTO> getSession(@PathVariable String sessionId) {
        InterviewSessionDTO session = sessionService.getSession(sessionId);
        return Result.success(session);
    }
    
    /**
     * 获取当前问题
     *
     * <p>调用入口：前端请求当前答题进度时通过
     * {@code GET /api/interview/sessions/{sessionId}/question} 调用。
     * 该方法委托 {@link InterviewSessionService#getCurrentQuestionResponse(String)}
     * 组装题目、索引和进度信息。
     */
    @GetMapping("/api/interview/sessions/{sessionId}/question")
    public Result<Map<String, Object>> getCurrentQuestion(@PathVariable String sessionId) {
        return Result.success(sessionService.getCurrentQuestionResponse(sessionId));
    }
    
    /**
     * 提交答案
     *
     * <p>调用入口：前端确认当前答案并进入下一题时，通过
     * {@code POST /api/interview/sessions/{sessionId}/answers} 调用。
     *
     * <p>主要调用 {@link InterviewSessionService#submitAnswer(SubmitAnswerRequest)}；
     * Service 会保存答案、推进索引，并在最后一题后投递异步评估任务。
     */
    @PostMapping("/api/interview/sessions/{sessionId}/answers")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
    public Result<SubmitAnswerResponse> submitAnswer(
            @PathVariable String sessionId,
            @RequestBody Map<String, Object> body) {
        Integer questionIndex = (Integer) body.get("questionIndex");
        String answer = (String) body.get("answer");
        log.info("提交答案: 会话{}, 问题{}", sessionId, questionIndex);
        SubmitAnswerRequest request = new SubmitAnswerRequest(sessionId, questionIndex, answer);
        SubmitAnswerResponse response = sessionService.submitAnswer(request);
        return Result.success(response);
    }
    
    /**
     * 生成面试报告
     *
     * <p>调用入口：前端请求查看报告时通过
     * {@code GET /api/interview/sessions/{sessionId}/report} 调用。
     * 该方法同步委托 {@link InterviewSessionService#generateReport(String)}，
     * 触发 LLM 评估并持久化结果。
     */
    @GetMapping("/api/interview/sessions/{sessionId}/report")
    public Result<InterviewReportDTO> getReport(@PathVariable String sessionId) {
        log.info("生成面试报告: {}", sessionId);
        InterviewReportDTO report = sessionService.generateReport(sessionId);
        return Result.success(report);
    }
    
    /**
     * 查找未完成的面试会话
     *
     * <p>调用入口：前端开始基于简历的面试前，通过
     * {@code GET /api/interview/sessions/unfinished/{resumeId}} 调用。
     * 该方法委托 {@link InterviewSessionService#findUnfinishedSessionOrThrow(Long)}；
     * 不存在时由全局异常处理器返回业务错误。
     */
    @GetMapping("/api/interview/sessions/unfinished/{resumeId}")
    public Result<InterviewSessionDTO> findUnfinishedSession(@PathVariable Long resumeId) {
        return Result.success(sessionService.findUnfinishedSessionOrThrow(resumeId));
    }
    
    /**
     * 暂存答案（不进入下一题）
     *
     * <p>调用入口：前端自动保存或用户暂存当前输入时，通过
     * {@code PUT /api/interview/sessions/{sessionId}/answers} 调用。
     * 主要调用 {@link InterviewSessionService#saveAnswer(SubmitAnswerRequest)}，
     * 只更新答案，不推进题目索引，也不触发评估。
     */
    @PutMapping("/api/interview/sessions/{sessionId}/answers")
    public Result<Void> saveAnswer(
            @PathVariable String sessionId,
            @RequestBody Map<String, Object> body) {
        Integer questionIndex = (Integer) body.get("questionIndex");
        String answer = (String) body.get("answer");
        log.info("暂存答案: 会话{}, 问题{}", sessionId, questionIndex);
        SubmitAnswerRequest request = new SubmitAnswerRequest(sessionId, questionIndex, answer);
        sessionService.saveAnswer(request);
        return Result.success(null);
    }
    
    /**
     * 提前交卷
     *
     * <p>调用入口：用户在题目未全部完成时通过
     * {@code POST /api/interview/sessions/{sessionId}/complete} 调用。
     * 该方法委托 {@link InterviewSessionService#completeInterview(String)}
     * 更新会话状态并投递异步评估任务。
     */
    @PostMapping("/api/interview/sessions/{sessionId}/complete")
    public Result<Void> completeInterview(@PathVariable String sessionId) {
        log.info("提前交卷: {}", sessionId);
        sessionService.completeInterview(sessionId);
        return Result.success(null);
    }
    
    /**
     * 获取面试会话详情
     *
     * <p>调用入口：前端打开历史面试详情时通过
     * {@code GET /api/interview/sessions/{sessionId}/details} 调用。
     * 该方法委托 {@link InterviewHistoryService#getInterviewDetail(String)}
     * 合并会话、全部题目和已保存答案。
     */
    @GetMapping("/api/interview/sessions/{sessionId}/details")
    public Result<InterviewDetailDTO> getInterviewDetail(@PathVariable String sessionId) {
        InterviewDetailDTO detail = historyService.getInterviewDetail(sessionId);
        return Result.success(detail);
    }
    
    /**
     * 导出面试报告为PDF
     *
     * <p>调用入口：前端下载报告时通过
     * {@code GET /api/interview/sessions/{sessionId}/export} 调用。
     * 主要调用 {@link InterviewHistoryService#exportInterviewPdf(String)} 生成 PDF，
     * 再设置下载响应头；导出异常在本方法内转换为 HTTP 500。
     */
    @GetMapping("/api/interview/sessions/{sessionId}/export")
    public ResponseEntity<byte[]> exportInterviewPdf(@PathVariable String sessionId) {
        try {
            byte[] pdfBytes = historyService.exportInterviewPdf(sessionId);
            String filename = URLEncoder.encode("模拟面试报告_" + sessionId + ".pdf", 
                StandardCharsets.UTF_8);
            
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfBytes);
        } catch (Exception e) {
            log.error("导出PDF失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * 删除面试会话
     *
     * <p>调用入口：前端删除历史记录时通过
     * {@code DELETE /api/interview/sessions/{sessionId}} 调用。
     * 该方法委托 {@link InterviewPersistenceService#deleteSessionBySessionId(String)}
     * 删除会话及其关联答案。
     */
    @DeleteMapping("/api/interview/sessions/{sessionId}")
    public Result<Void> deleteInterview(@PathVariable String sessionId) {
        log.info("删除面试会话: {}", sessionId);
        persistenceService.deleteSessionBySessionId(sessionId);
        return Result.success(null);
    }
}
