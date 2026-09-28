package interview.guide.modules.interviewschedule;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.interviewschedule.reminder.dto.InterviewReminderDTO;
import interview.guide.modules.interviewschedule.reminder.dto.UnreadReminderCountResponse;
import interview.guide.modules.interviewschedule.reminder.service.InterviewReminderService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 站内面试提醒查询与已读状态接口。 */
@RestController
@RequestMapping("/api/interview-schedule/reminders")
@RequiredArgsConstructor
public class InterviewReminderController {

  private final InterviewReminderService reminderService;

  @GetMapping("/in-app")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
  public Result<List<InterviewReminderDTO>> listInApp(
      @RequestParam(defaultValue = "false") boolean unreadOnly) {
    return Result.success(reminderService.listInApp(unreadOnly));
  }

  @GetMapping("/unread-count")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
  public Result<UnreadReminderCountResponse> unreadCount() {
    return Result.success(
        new UnreadReminderCountResponse(reminderService.countUnreadInApp()));
  }

  @PatchMapping("/{id}/read")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
  public Result<Void> markRead(@PathVariable Long id) {
    reminderService.markRead(id);
    return Result.success();
  }

  @PatchMapping("/read-all")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
  public Result<Void> markAllRead() {
    reminderService.markAllInAppRead();
    return Result.success();
  }
}
