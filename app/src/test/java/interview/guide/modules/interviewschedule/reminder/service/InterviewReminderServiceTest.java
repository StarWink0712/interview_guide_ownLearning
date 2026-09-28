package interview.guide.modules.interviewschedule.reminder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.modules.interviewschedule.model.InterviewScheduleEntity;
import interview.guide.modules.interviewschedule.model.InterviewStatus;
import interview.guide.modules.interviewschedule.reminder.config.InterviewReminderProperties;
import interview.guide.modules.interviewschedule.reminder.model.InterviewReminderEntity;
import interview.guide.modules.interviewschedule.reminder.model.ReminderChannel;
import interview.guide.modules.interviewschedule.reminder.model.ReminderStatus;
import interview.guide.modules.interviewschedule.reminder.model.ReminderTiming;
import interview.guide.modules.interviewschedule.reminder.repository.InterviewReminderRepository;
import interview.guide.modules.interviewschedule.repository.InterviewScheduleRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InterviewReminderServiceTest {

  @Mock
  private InterviewReminderRepository reminderRepository;

  @Mock
  private InterviewScheduleRepository scheduleRepository;

  private InterviewReminderService reminderService;

  @BeforeEach
  void setUp() {
    InterviewReminderProperties properties = new InterviewReminderProperties();
    reminderService = new InterviewReminderService(
        reminderRepository,
        scheduleRepository,
        properties);
  }

  @Test
  @DisplayName("开启提醒并配置邮箱时生成两条邮件和两条站内计划")
  void shouldCreateEmailAndInAppPlans() {
    when(reminderRepository.findByScheduleIdAndChannelAndTiming(
        any(), any(), any())).thenReturn(Optional.empty());

    InterviewScheduleEntity schedule = new InterviewScheduleEntity();
    schedule.setId(10L);
    schedule.setCompanyName("示例公司");
    schedule.setPosition("Java 工程师");
    schedule.setInterviewTime(LocalDateTime.now().plusDays(3));
    schedule.setStatus(InterviewStatus.PENDING);
    schedule.setReminderEnabled(true);
    schedule.setReminderEmail("candidate@example.com");

    reminderService.synchronizeReminders(schedule);

    ArgumentCaptor<InterviewReminderEntity> captor =
        ArgumentCaptor.forClass(InterviewReminderEntity.class);
    verify(reminderRepository, times(4)).save(captor.capture());

    assertThat(captor.getAllValues())
        .extracting(InterviewReminderEntity::getChannel)
        .containsExactlyInAnyOrder(
            ReminderChannel.EMAIL,
            ReminderChannel.EMAIL,
            ReminderChannel.IN_APP,
            ReminderChannel.IN_APP);
    assertThat(captor.getAllValues())
        .extracting(InterviewReminderEntity::getTiming)
        .containsExactlyInAnyOrder(
            ReminderTiming.DAY_BEFORE,
            ReminderTiming.DAY_BEFORE,
            ReminderTiming.HOUR_BEFORE,
            ReminderTiming.HOUR_BEFORE);
    assertThat(captor.getAllValues())
        .allMatch(reminder -> reminder.getStatus() == ReminderStatus.PENDING);
  }
}
