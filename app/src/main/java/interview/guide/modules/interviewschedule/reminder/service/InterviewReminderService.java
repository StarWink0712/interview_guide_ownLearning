package interview.guide.modules.interviewschedule.reminder.service;

import interview.guide.common.async.AsyncTaskCancelledException;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interviewschedule.model.InterviewScheduleEntity;
import interview.guide.modules.interviewschedule.model.InterviewStatus;
import interview.guide.modules.interviewschedule.reminder.config.InterviewReminderProperties;
import interview.guide.modules.interviewschedule.reminder.dto.InterviewReminderDTO;
import interview.guide.modules.interviewschedule.reminder.model.InterviewReminderEntity;
import interview.guide.modules.interviewschedule.reminder.model.ReminderChannel;
import interview.guide.modules.interviewschedule.reminder.model.ReminderStatus;
import interview.guide.modules.interviewschedule.reminder.model.ReminderTiming;
import interview.guide.modules.interviewschedule.reminder.repository.InterviewReminderRepository;
import interview.guide.modules.interviewschedule.repository.InterviewScheduleRepository;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 面试提醒统一服务
 *
 * <p>负责面试日程的提醒计划生成、状态管理和异步投递协调：</p>
 * <ul>
 *   <li><b>计划同步</b>：根据日程变更同步更新提醒计划（站内通知 + 邮件）</li>
 *   <li><b>站内提醒</b>：查询未读提醒、标记已读、统计未读数</li>
 *   <li><b>投递状态</b>：管理提醒的排队、处理中、已发送、失败、取消等状态流转</li>
 *   <li><b>投递上下文</b>：为异步投递消费者提供完整的提醒+日程信息</li>
 * </ul>
 *
 * <p>提醒生命周期（正常）：PENDING → QUEUED → PROCESSING → SENT</p>
 * <p>提醒生命周期（异常）：PENDING/QUEUED/PROCESSING → FAILED（失败）或 CANCELLED（取消）</p>
 */
@Service
@RequiredArgsConstructor
public class InterviewReminderService {

  /**
   * 站内提醒列表中需要排除的状态
   * 已取消和已失败的提醒不应展示给用户
   */
  private static final EnumSet<ReminderStatus> IN_APP_EXCLUDED_STATUSES =
      EnumSet.of(ReminderStatus.CANCELLED, ReminderStatus.FAILED);

  /**
   * 活跃状态集合：待处理、已排队、处理中的提醒均为活跃态
   * 用于判断哪些提醒可以被取消或需要重新调度
   */
  private static final EnumSet<ReminderStatus> ACTIVE_STATUSES =
      EnumSet.of(
          ReminderStatus.PENDING,
          ReminderStatus.QUEUED,
          ReminderStatus.PROCESSING);

  /** 提醒数据访问层 */
  private final InterviewReminderRepository reminderRepository;

  /** 面试日程数据访问层，用于获取日程详情构建投递上下文 */
  private final InterviewScheduleRepository scheduleRepository;

  /** 提醒配置属性：提前时间、批量大小等 */
  private final InterviewReminderProperties properties;

  /**
   * 根据日程变更同步提醒计划
   *
   * <p>根据日程状态和提醒配置，自动创建或取消提醒：</p>
   * <ol>
   *   <li>如果提醒功能关闭或日程非待面试状态 → 取消所有活跃提醒</li>
   *   <li>否则为站内通知通道生成"提前一天"和"提前一小时"两条提醒</li>
   *   <li>如果配置了提醒邮箱，额外生成两条邮件提醒；否则取消已有邮件提醒</li>
   * </ol>
   *
   * @param schedule 面试日程实体
   */
  @Transactional
  public void synchronizeReminders(InterviewScheduleEntity schedule) {
    if (!properties.isPlanningEnabled()
        || Boolean.FALSE.equals(schedule.getReminderEnabled())
        || schedule.getStatus() != InterviewStatus.PENDING) {
          // 取消所有活跃提醒
      cancelActiveReminders(schedule.getId(), "日程未启用提醒或已不再待面试");
      return;
    }

    if (schedule.getInterviewTime() == null) {
      cancelActiveReminders(schedule.getId(), "面试时间为空");
      return;
    }
    // 为站内通知通道生成"提前一天"和"提前一小时"两条提醒
    upsertPlan(schedule, ReminderChannel.IN_APP, ReminderTiming.DAY_BEFORE, null);
    upsertPlan(schedule, ReminderChannel.IN_APP, ReminderTiming.HOUR_BEFORE, null);
    // 如果配置了提醒邮箱，额外生成两条邮件提醒
    String email = trimOrNull(schedule.getReminderEmail());
    if (email == null) {
      // 取消已有邮件提醒
      cancelChannel(schedule.getId(), ReminderChannel.EMAIL, "未配置提醒邮箱");
      return;
    }
    // 为邮件通道生成"提前一天"和"提前一小时"两条提醒
    upsertPlan(schedule, ReminderChannel.EMAIL, ReminderTiming.DAY_BEFORE, email);
    upsertPlan(schedule, ReminderChannel.EMAIL, ReminderTiming.HOUR_BEFORE, email);
  }

  /**
   * 取消指定日程的所有活跃提醒
   *
   * @param scheduleId 面试日程 ID
   * @param reason     取消原因
   */
  @Transactional
  public void cancelActiveReminders(Long scheduleId, String reason) {
    reminderRepository.findByScheduleId(scheduleId).stream()
        .filter(reminder -> ACTIVE_STATUSES.contains(reminder.getStatus()))
        .forEach(reminder -> cancel(reminder, reason));
  }

  /**
   * 批量取消多个日程的活跃提醒
   *
   * @param scheduleIds 面试日程 ID 集合
   * @param reason      取消原因
   */
  @Transactional
  public void cancelActiveReminders(
      Collection<Long> scheduleIds,
      String reason) {
    if (scheduleIds == null || scheduleIds.isEmpty()) {
      return;
    }
    reminderRepository.findByScheduleIdIn(scheduleIds).stream()
        .filter(reminder -> ACTIVE_STATUSES.contains(reminder.getStatus()))
        .forEach(reminder -> cancel(reminder, reason));
  }

  /**
   * 认领到期的待处理提醒
   *
   * <p>查询所有已到达提醒时间且状态为 PENDING 的提醒，
   * 将其批量更新为 QUEUED 状态，并将 ID 列表返回给调度器进行后续投递。</p>
   *
   * @return 被认领的提醒 ID 列表
   */
  @Transactional
  public List<Long> claimDueReminderIds() {
    // 查询所有已到达提醒时间且状态为 PENDING 的提醒
    // 并批量更新为 QUEUED 状态
    List<InterviewReminderEntity> due = reminderRepository.findDueForUpdate(
        ReminderStatus.PENDING,
        LocalDateTime.now(),
        PageRequest.of(0, Math.max(1, properties.getBatchSize())));
    due.forEach(reminder -> reminder.setStatus(ReminderStatus.QUEUED));

    reminderRepository.saveAll(due);
    // 返回被认领的提醒 ID 列表
    return due.stream().map(InterviewReminderEntity::getId).toList();
  }

  /**
   * 查询站内提醒列表
   *
   * <p>只展示当前时间已到达提醒时间的提醒，排除已取消和已失败的记录。</p>
   *
   * @param unreadOnly 是否只查询未读提醒
   * @return 站内提醒 DTO 列表
   */
  @Transactional(readOnly = true)
  public List<InterviewReminderDTO> listInApp(boolean unreadOnly) {
    return reminderRepository.findVisibleInApp(
            ReminderChannel.IN_APP,
            LocalDateTime.now(),
            IN_APP_EXCLUDED_STATUSES,
            unreadOnly)
        .stream()
        .map(this::toDTO)
        .toList();
  }

  /**
   * 统计未读站内提醒数量
   *
   * @return 未读提醒数量
   */
  @Transactional(readOnly = true)
  public long countUnreadInApp() {
    return reminderRepository.countUnreadInApp(
        ReminderChannel.IN_APP,
        LocalDateTime.now(),
        IN_APP_EXCLUDED_STATUSES);
  }

  /**
   * 标记单条站内提醒为已读
   *
   * <p>仅站内通知通道的提醒支持已读标记，
   * 已标记已读的提醒不会重复标记。</p>
   *
   * @param reminderId 提醒 ID
   * @throws BusinessException 如果提醒不是站内通知类型
   */
  @Transactional
  public void markRead(Long reminderId) {
    InterviewReminderEntity reminder = getReminder(reminderId);
    if (reminder.getChannel() != ReminderChannel.IN_APP) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "只有站内提醒可以标记已读");
    }
    if (reminder.getReadAt() == null) {
      reminder.setReadAt(LocalDateTime.now());
      reminderRepository.save(reminder);
    }
  }

  /**
   * 将所有未读站内提醒标记为已读
   */
  @Transactional
  public void markAllInAppRead() {
    reminderRepository.findVisibleInApp(
            ReminderChannel.IN_APP,
            LocalDateTime.now(),
            IN_APP_EXCLUDED_STATUSES,
            true)
        .forEach(reminder -> reminder.setReadAt(LocalDateTime.now()));
  }

  /**
   * 将提醒标记为处理中
   *
   * <p>由异步投递消费者在开始投递前调用。
   * 如果提醒已被取消，则抛出异常通知消费者放弃投递。</p>
   *
   * @param reminderId 提醒 ID
   * @throws AsyncTaskCancelledException 如果提醒已被取消
   */
  @Transactional
  public void markProcessing(Long reminderId) {

    InterviewReminderEntity reminder = getReminder(reminderId);
    if (reminder.getStatus() == ReminderStatus.CANCELLED) {
      throw new AsyncTaskCancelledException("提醒已取消: reminderId=" + reminderId);
    }
    reminder.setStatus(ReminderStatus.PROCESSING);
    // 清除错误信息
    reminder.setLastError(null);
    reminderRepository.save(reminder);
  }

  /**
   * 获取提醒投递上下文
   *
   * <p>将提醒信息和关联的面试日程信息组装为投递上下文对象，
   * 供邮件/站内通知等投递器使用。如果日程已删除或状态变更，
   * 则抛出异常通知消费者跳过投递。</p>
   *
   * @param reminderId 提醒 ID
   * @return 包含提醒和日程完整信息的投递上下文
   * @throws AsyncTaskCancelledException 如果日程已删除或不再待面试
   */
  @Transactional(readOnly = true)
  public ReminderDeliveryContext getDeliveryContext(Long reminderId) {
    InterviewReminderEntity reminder = getReminder(reminderId);
    InterviewScheduleEntity schedule = scheduleRepository.findById(reminder.getScheduleId())
        .orElseThrow(() -> new AsyncTaskCancelledException(
            "面试日程已删除: scheduleId=" + reminder.getScheduleId()));
    if (schedule.getStatus() != InterviewStatus.PENDING) {
      throw new AsyncTaskCancelledException(
          "面试日程已不再待面试: scheduleId=" + schedule.getId());
    }
    return new ReminderDeliveryContext(
        reminder.getId(),
        schedule.getId(),
        reminder.getChannel(),
        reminder.getRecipient(),
        reminder.getTitle(),
        reminder.getContent(),
        schedule.getCompanyName(),
        schedule.getPosition(),
        schedule.getInterviewTime(),
        schedule.getInterviewType(),
        schedule.getMeetingLink(),
        schedule.getInterviewer(),
        schedule.getNotes());
  }

  /**
   * 标记提醒为已发送
   *
   * <p>投递成功后调用，记录发送时间并清除错误信息。</p>
   *
   * @param reminderId 提醒 ID
   */
  @Transactional
  public void markSent(Long reminderId) {
    InterviewReminderEntity reminder = getReminder(reminderId);
    reminder.setStatus(ReminderStatus.SENT);
    reminder.setSentAt(LocalDateTime.now());
    reminder.setLastError(null);
    reminderRepository.save(reminder);
  }

  /**
   * 标记提醒为重试排队
   *
   * <p>投递失败后，将提醒状态重置为 QUEUED 等待下一次调度重试。</p>
   *
   * @param reminderId 提醒 ID
   * @param retryCount 当前已重试次数
   */
  @Transactional
  public void markRetrying(Long reminderId, int retryCount) {
    InterviewReminderEntity reminder = getReminder(reminderId);
    reminder.setStatus(ReminderStatus.QUEUED);
    reminder.setRetryCount(retryCount);
    reminderRepository.save(reminder);
  }

  /**
   * 标记提醒为发送失败
   *
   * <p>重试次数耗尽后标记为失败，记录截断后的错误信息（最多 1000 字符）。</p>
   *
   * @param reminderId 提醒 ID
   * @param error      错误信息
   */
  @Transactional
  public void markFailed(Long reminderId, String error) {
    InterviewReminderEntity reminder = getReminder(reminderId);
    reminder.setStatus(ReminderStatus.FAILED);
    reminder.setLastError(truncate(error));
    reminderRepository.save(reminder);
  }

  /**
   * 标记提醒为已取消
   *
   * <p>由外部业务触发（如日程删除、提醒关闭等）。</p>
   *
   * @param reminderId 提醒 ID
   * @param reason     取消原因
   */
  @Transactional
  public void markCancelled(Long reminderId, String reason) {
    cancel(getReminder(reminderId), reason);
  }

  /**
   * 创建或更新单条提醒计划
   *
   * <p>根据给定的日程、通道和时机，计算提醒时间并生成提醒内容。
   * 如果已存在相同组合的提醒记录则更新，否则新建。
   * 当提醒时间、收件人变更或原记录已终止时，将状态重置为 PENDING 以便重新投递。</p>
   *
   * @param schedule  面试日程实体
   * @param channel   提醒通道（站内通知/邮件）
   * @param timing    提醒时机（提前一天/提前一小时）
   * @param recipient 收件人地址（邮件通道使用，站内为 null）
   */
  private void upsertPlan(
      InterviewScheduleEntity schedule,
      ReminderChannel channel,
      ReminderTiming timing,
      String recipient) {
        // 计算提醒触发时间
    LocalDateTime remindAt = resolveRemindAt(schedule.getInterviewTime(), timing);
        // 生成提醒标题
    String title = timing == ReminderTiming.DAY_BEFORE
        ? schedule.getCompanyName() + "面试将在明天开始"
        : schedule.getCompanyName() + "面试将在一小时后开始";
        // 生成提醒内容
    String content = "您应聘的“" + schedule.getPosition() + "”面试时间为 "
        + schedule.getInterviewTime() + "，请提前做好准备。";
        // 查找或创建提醒记录到数据库
    InterviewReminderEntity reminder = reminderRepository
        .findByScheduleIdAndChannelAndTiming(schedule.getId(), channel, timing)
        .orElseGet(() -> InterviewReminderEntity.builder()
            .scheduleId(schedule.getId())
            .channel(channel)
            .timing(timing)
            .status(ReminderStatus.PENDING)
            .retryCount(0)
            .build());
        // 检查是否需要更新提醒记录
    boolean deliveryChanged = !Objects.equals(reminder.getRemindAt(), remindAt)
        || !Objects.equals(reminder.getRecipient(), recipient);
    reminder.setRecipient(recipient);
    reminder.setTitle(title);
    reminder.setContent(content);
    reminder.setRemindAt(remindAt);
        // 如果提醒记录已取消或失败，重置状态为 PENDING
    if (deliveryChanged
        || reminder.getStatus() == ReminderStatus.CANCELLED
        || reminder.getStatus() == ReminderStatus.FAILED) {
      reminder.setStatus(ReminderStatus.PENDING);
      reminder.setRetryCount(0);
      reminder.setLastError(null);
      reminder.setSentAt(null);
      reminder.setReadAt(null);
    }
        // 保存更新后的提醒记录
    reminderRepository.save(reminder);
  }

  /**
   * 根据面试时间和提醒时机计算提醒触发时间
   *
   * <p>提醒时间 = 面试时间 - 提前量（由配置文件决定）。
   * 如果计算出的时间已过，则返回当前时间，确保提醒立即触发。</p>
   *
   * @param interviewTime 面试时间
   * @param timing        提醒时机
   * @return 提醒触发时间
   */
  private LocalDateTime resolveRemindAt(
      LocalDateTime interviewTime,
      ReminderTiming timing) {
    LocalDateTime calculated = switch (timing) {
      case DAY_BEFORE -> interviewTime.minusHours(properties.getDayBeforeHours());
      case HOUR_BEFORE -> interviewTime.minusMinutes(properties.getHourBeforeMinutes());
    };
    LocalDateTime now = LocalDateTime.now();
    return calculated.isBefore(now) ? now : calculated;
  }

  /**
   * 取消指定日程下某个通道的所有活跃提醒
   *
   * <p>用于日程更新时，某个通道不再需要提醒（如移除了邮箱），
   * 则仅取消该通道的提醒，不影响其他通道。</p>
   *
   * @param scheduleId 面试日程 ID
   * @param channel    提醒通道
   * @param reason     取消原因
   */
  private void cancelChannel(
      Long scheduleId,
      ReminderChannel channel,
      String reason) {
    reminderRepository.findByScheduleId(scheduleId).stream()
        .filter(reminder -> reminder.getChannel() == channel)
        .filter(reminder -> ACTIVE_STATUSES.contains(reminder.getStatus()))
        .forEach(reminder -> cancel(reminder, reason));
  }

  /**
   * 执行提醒取消操作
   *
   * <p>将提醒状态设为 CANCELLED，并记录截断后的取消原因。</p>
   *
   * @param reminder 提醒实体
   * @param reason   取消原因
   */
  private void cancel(InterviewReminderEntity reminder, String reason) {
    reminder.setStatus(ReminderStatus.CANCELLED);
    reminder.setLastError(truncate(reason));
    reminderRepository.save(reminder);
  }

  /**
   * 根据 ID 获取提醒实体（不存在时抛异常）
   *
   * @param reminderId 提醒 ID
   * @return 提醒实体
   * @throws BusinessException 提醒不存在时抛出
   */
  private InterviewReminderEntity getReminder(Long reminderId) {
    return reminderRepository.findById(reminderId)
        .orElseThrow(() -> new BusinessException(
            ErrorCode.INTERVIEW_REMINDER_NOT_FOUND,
            "面试提醒不存在: " + reminderId));
  }

  /**
   * 将提醒实体转换为 DTO（用于前端展示）
   */
  private InterviewReminderDTO toDTO(InterviewReminderEntity reminder) {
    return new InterviewReminderDTO(
        reminder.getId(),
        reminder.getScheduleId(),
        reminder.getChannel(),
        reminder.getTiming(),
        reminder.getStatus(),
        reminder.getTitle(),
        reminder.getContent(),
        reminder.getRemindAt(),
        reminder.getSentAt(),
        reminder.getReadAt(),
        reminder.getCreatedAt());
  }

  /**
   * 安全修剪字符串：null 或空白字符串统一返回 null
   *
   * @param value 原始字符串
   * @return 修剪后的字符串，空白时返回 null
   */
  private String trimOrNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  /**
   * 截断字符串，防止错误信息过长占用存储
   *
   * @param value 原始字符串
   * @return 截断至最多 1000 字符的字符串
   */
  private String truncate(String value) {
    if (value == null || value.length() <= 1000) {
      return value;
    }
    return value.substring(0, 1000);
  }
}
