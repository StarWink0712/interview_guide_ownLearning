/**
 * 语音面试对话消息 JPA 实体
 *
 * 持久化语音面试中的每轮对话记录，包括消息类型（USER_SPEECH/AI_SPEECH/SYSTEM）、
 * 所属面试阶段、用户语音识别文本、AI 生成文本及序号。
 * 按 sessionId + sequenceNum 排序可还原完整对话时序。
 *
 * 联动关系：
 * - 被 VoiceInterviewMessageRepository 管理（CRUD）
 * - 被 VoiceInterviewService 保存和查询（saveMessage、getConversationHistory）
 * - 被 VoiceInterviewWebSocketHandler 间接使用（通过 Service 持久化对话）
 * - 被 VoiceInterviewEvaluationService 读取（构建评估用的 QaRecord）
 * - 被 VoiceInterviewService.deleteSession 级联删除
 * - 对应数据库表 voice_interview_messages
 */
package interview.guide.modules.voiceinterview.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "voice_interview_messages")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoiceInterviewMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id")
    private Long sessionId;

    @Column(name = "message_type", nullable = false)
    private String messageType; // USER_SPEECH, AI_SPEECH, SYSTEM

    @Column(name = "phase")
    @Enumerated(EnumType.STRING)
    private VoiceInterviewSessionEntity.InterviewPhase phase;

    @Column(name = "user_recognized_text", columnDefinition = "TEXT")
    private String userRecognizedText;

    @Column(name = "ai_generated_text", columnDefinition = "TEXT")
    private String aiGeneratedText;

    @Column(name = "timestamp")
    private LocalDateTime timestamp;

    @Column(name = "sequence_num")
    private Integer sequenceNum;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /**
     * 在首次持久化消息前统一填充创建时间和对话时间。
     *
     * 由 JPA {@link PrePersist} 生命周期自动调用，使开场消息和普通问答使用同一套时间初始化规则。
     */
    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.timestamp = LocalDateTime.now();
    }
}
