/**
 * 语音面试 REST 控制器
 *
 * 提供语音面试会话的 HTTP API，包括会话创建/查询/暂停/恢复/删除、
 * 对话历史获取、异步评估触发与状态轮询。评估任务通过 Redis Stream 异步执行，
 * 前端通过轮询 GET /evaluation 获取评估结果。
 *
 * 联动关系：
 * - 依赖 VoiceInterviewService（会话生命周期管理）
 * - 依赖 VoiceInterviewEvaluationService（评估结果查询）
 * - 依赖 VoiceEvaluateStreamProducer（异步评估任务入队）
 * - 被 WebSocketConfig 间接关联（WebSocket 处理实时通话，REST 处理会话管理）
 */
package interview.guide.modules.voiceinterview.controller;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.common.result.Result;
import interview.guide.modules.voiceinterview.dto.CreateSessionRequest;
import interview.guide.modules.voiceinterview.dto.SessionMetaDTO;
import interview.guide.modules.voiceinterview.dto.SessionResponseDTO;
import interview.guide.modules.voiceinterview.dto.VoiceEvaluationDetailDTO;
import interview.guide.modules.voiceinterview.dto.VoiceEvaluationStatusDTO;
import interview.guide.modules.voiceinterview.dto.VoiceInterviewMessageDTO;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.service.VoiceInterviewEvaluationService;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import jakarta.validation.Valid; 
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 语音面试 REST API 控制器
 *
 * 提供语音面试会话管理的所有 HTTP 接口
 * 注意：实时语音通信由 WebSocket 处理，不在此控制器
 */
@RestController
@RequestMapping("/api/voice-interview")
@RequiredArgsConstructor
@Slf4j
public class VoiceInterviewController {

    /**
     * 语音面试会话服务
     * 负责会话的创建、查询、暂停、恢复、删除等生命周期管理
     */
    private final VoiceInterviewService voiceInterviewService;

    /**
     * 语音面试评估服务
     * 负责面试结束后的 AI 评估功能
     */
    private final VoiceInterviewEvaluationService evaluationService;



    /**
     * 创建语音面试会话并返回实时通信入口。
     *
     * 前端在进入语音面试页时调用。方法将请求委托给
     * {@link VoiceInterviewService#createSession(CreateSessionRequest)} 持久化会话并初始化阶段，
     * 返回的 {@code sessionId} 和 WebSocket 地址用于随后建立实时语音连接。
     *
     * @param request 创建会话请求（包含技能方向、难度、简历信息等）
     * @return 会话响应（包含 sessionId 用于 WebSocket 连接）
     */
    @PostMapping("/sessions")
    public Result<SessionResponseDTO> createSession(@Valid @RequestBody CreateSessionRequest request) {
        log.info("Creating voice interview session for role: {}", request.getRoleType());
        //根据前端传入的request请求信息，创建一个语音面试会话。
        SessionResponseDTO session = voiceInterviewService.createSession(request);
        return Result.success(session);
    }

    /**
     * 查询会话详情。
     *
     * 前端刷新或恢复面试页面时调用；下游通过会话服务先读 Redis 缓存、未命中再查数据库，
     * 最终只返回 DTO，避免暴露持久化实体。
     *
     * @param sessionId 会话ID
     * @return 会话详情
     */
    @GetMapping("/sessions/{sessionId}")
    public Result<SessionResponseDTO> getSession(@PathVariable Long sessionId) {
        log.info("Getting session details for: {}", sessionId);
        SessionResponseDTO session = voiceInterviewService.getSessionDTO(sessionId);
        if (session == null) {
            return Result.error("Session not found: " + sessionId);
        }
        return Result.success(session);
    }

    /**
     * 结束语音面试会话并启动异步评估。
     *
     * 该 HTTP 入口用于管理页或 WebSocket 不可用时兜底；正常实时通话也可通过
     * {@code end_interview} 控制消息进入同一服务方法。服务会完成会话落库，再向 Redis Stream
     * 投递评估任务。
     *
     * @param sessionId 会话ID
     * @return 成功响应
     */
    @PostMapping("/sessions/{sessionId}/end")
    public Result<Void> endSession(@PathVariable Long sessionId) {
        log.info("Ending session: {}", sessionId);
        voiceInterviewService.endSession(sessionId.toString());
        return Result.success();
    }

    /**
     * 暂停进行中的语音面试会话。
     *
     * 前端主动暂停时调用；控制器提取可选原因，服务校验状态后记录暂停时间、更新数据库并清除
     * Redis 缓存。会话不存在或状态不允许暂停时由服务抛出业务异常。
     *
     * @param sessionId 会话ID
     * @param request 暂停原因（可选，默认 user_initiated）
     * @return 成功响应
     */
    @PutMapping("/sessions/{sessionId}/pause")
    public Result<Void> pauseSession(
        @PathVariable Long sessionId,
        @RequestBody Map<String, String> request
    ) {
        log.info("Pausing session: {}", sessionId);
        String reason = request.getOrDefault("reason", "user_initiated");
        voiceInterviewService.pauseSession(sessionId.toString(), reason);
        return Result.success();
    }

    /**
     * 恢复已暂停的语音面试会话。
     *
     * 前端从暂停页继续面试时调用；服务恢复状态并刷新缓存，响应中的 WebSocket 地址供前端
     * 重新建立实时连接。
     *
     * @param sessionId 会话ID
     * @return 包含更新后会话信息的响应
     */
    @PutMapping("/sessions/{sessionId}/resume")
    public Result<SessionResponseDTO> resumeSession(@PathVariable Long sessionId) {
        log.info("Resuming session: {}", sessionId);
        SessionResponseDTO session = voiceInterviewService.resumeSession(sessionId.toString());
        return Result.success(session);
    }

    /**
     * 按用户和可选状态查询会话列表。
     *
     * 会话历史页调用；服务执行按更新时间倒序的 Repository 查询，并逐条统计消息数量后组装
     * {@link SessionMetaDTO}。
     * @param userId 用户ID（可选，为空则返回所有会话）
     * @param status 会话状态过滤（可选，如 IN_PROGRESS, COMPLETED）
     * @return 会话元信息列表
     */
    @GetMapping("/sessions")
    public Result<List<SessionMetaDTO>> getAllSessions(
        @RequestParam(required = false) String userId,
        @RequestParam(required = false) String status
    ) {
        log.info("Getting sessions for user: {}, status: {}", userId, status);
        List<SessionMetaDTO> sessions = voiceInterviewService.getAllSessions(userId, status);
        return Result.success(sessions);
    }

    /**
     * 删除会话及关联的消息、评估数据。
     *
     * 会话管理页调用；实际删除顺序由服务控制，避免遗留孤立的评估和对话记录。
     *
     * @param sessionId 会话ID
     * @return 成功响应
     */
    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable Long sessionId) {
        log.info("Deleting voice interview session: {}", sessionId);
        voiceInterviewService.deleteSession(sessionId);
        return Result.success();
    }

    /**
     * 获取按轮次排序的会话消息历史。
     *
     * 回放页或恢复面试上下文时调用；服务查询消息实体并转换为 DTO 后返回。
     *
     * @param sessionId 会话ID
     * @return 消息DTO列表（包含用户说的话和AI回复）
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public Result<List<VoiceInterviewMessageDTO>> getMessages(@PathVariable Long sessionId) {
        log.info("Getting messages for session: {}", sessionId);
        List<VoiceInterviewMessageDTO> messages =
                voiceInterviewService.getConversationHistoryDTO(sessionId.toString());
        return Result.success(messages);
    }

    /**
     * 查询异步评估状态，并在完成时附带评估详情。
     *
     * 前端结束面试后轮询此接口。控制器先从会话读取任务状态；仅当状态为
     * {@link AsyncTaskStatus#COMPLETED} 时才调用评估服务加载并反序列化结果。
     *
     * @param sessionId 会话ID
     * @return 评估状态DTO（包含状态和结果）
     */
    @GetMapping("/sessions/{sessionId}/evaluation")
    public Result<VoiceEvaluationStatusDTO> getEvaluation(@PathVariable Long sessionId) {
        log.info("Getting evaluation status for session: {}", sessionId);

        VoiceInterviewSessionEntity session = voiceInterviewService.getSession(sessionId);
        if (session == null) {
            throw new BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND, "会话不存在: " + sessionId);
        }

        AsyncTaskStatus status = session.getEvaluateStatus();
        VoiceEvaluationStatusDTO.VoiceEvaluationStatusDTOBuilder builder = VoiceEvaluationStatusDTO.builder()
                .evaluateStatus(status != null ? status.name() : null)
                .evaluateError(session.getEvaluateError());

        if (status == AsyncTaskStatus.COMPLETED) {
            VoiceEvaluationDetailDTO evaluation = evaluationService.getEvaluation(sessionId);
            builder.evaluation(evaluation);
        }

        return Result.success(builder.build());
    }

    /**
     * 幂等地触发语音面试评估。
     *
     * 评估页手动重试时调用。已完成时直接返回结果，正在排队或处理中时返回当前状态；仅其他状态
     * 会调用 {@link VoiceInterviewService#triggerEvaluation(Long)} 创建或复用任务并投递 Redis Stream。
     *
     * @param sessionId 会话ID
     * @return 评估状态DTO
     */
    @PostMapping("/sessions/{sessionId}/evaluation")
    public Result<VoiceEvaluationStatusDTO> generateEvaluation(@PathVariable Long sessionId) {
        log.info("Triggering async evaluation for session: {}", sessionId);

        // 检查会话是否存在,sessionid是当前会话id，代表当前会话的评估任务
        VoiceInterviewSessionEntity session = voiceInterviewService.getSession(sessionId);
        if (session == null) {
            throw new BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND, "会话不存在: " + sessionId);
        }

        // 如果已完成，直接返回缓存结果
        if (session.getEvaluateStatus() == AsyncTaskStatus.COMPLETED) {
            VoiceEvaluationDetailDTO evaluation = evaluationService.getEvaluation(sessionId);
            return Result.success(VoiceEvaluationStatusDTO.builder()
                    .evaluateStatus(AsyncTaskStatus.COMPLETED.name())
                    .evaluation(evaluation)
                    .build());
        }

        // 如果已在进行中，返回当前状态
        if (session.getEvaluateStatus() == AsyncTaskStatus.PENDING
                || session.getEvaluateStatus() == AsyncTaskStatus.PROCESSING) {
            return Result.success(VoiceEvaluationStatusDTO.builder()
                    .evaluateStatus(session.getEvaluateStatus().name())
                    .build());
        }

        // 触发新评估任务，调用异步任务服务
        voiceInterviewService.triggerEvaluation(sessionId);

        return Result.success(VoiceEvaluationStatusDTO.builder()
                .evaluateStatus(AsyncTaskStatus.PENDING.name())
                .build());
    }
}
