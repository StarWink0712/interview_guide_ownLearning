package interview.guide.modules.interviewschedule.reminder.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 邮件与站内面试提醒的持久化任务。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
    name = "interview_reminder",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_interview_reminder_plan",
        columnNames = {"schedule_id", "channel", "timing"}),
    indexes = {
        @Index(name = "idx_interview_reminder_due", columnList = "status,remind_at"),
        @Index(name = "idx_interview_reminder_in_app", columnList = "channel,read_at,remind_at")
    })
public class InterviewReminderEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "schedule_id", nullable = false)
  private Long scheduleId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private ReminderChannel channel;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private ReminderTiming timing;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private ReminderStatus status;

  @Column(length = 320)
  private String recipient;

  @Column(nullable = false, length = 200)
  private String title;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String content;

  @Column(name = "remind_at", nullable = false)
  private LocalDateTime remindAt;

  @Column(name = "sent_at")
  private LocalDateTime sentAt;

  @Column(name = "read_at")
  private LocalDateTime readAt;

  @Column(name = "retry_count", nullable = false)
  private int retryCount;

  @Column(name = "last_error", length = 1000)
  private String lastError;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  @PrePersist
  void prePersist() {
    LocalDateTime now = LocalDateTime.now();
    createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = LocalDateTime.now();
  }
}
