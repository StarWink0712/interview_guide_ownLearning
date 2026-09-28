# 面试邮件与站内提醒框架

## 调用闭环

```text
创建/修改 PENDING 日程
  → InterviewScheduleService
  → InterviewReminderService.synchronizeReminders()
  → 每个日程生成：24 小时前、1 小时前
      ├─ IN_APP 两条
      └─ 配置 reminderEmail 时 EMAIL 两条

到达 remindAt
  → InterviewReminderScheduler 每分钟扫描并加悲观锁认领
  → InterviewReminderStreamProducer 写 Redis Stream
  → InterviewReminderStreamConsumer 消费
      ├─ EMAIL → SmtpReminderEmailSender → JavaMailSender.send()
      └─ IN_APP → 标记 SENT，前端提醒铃查询展示
  → 失败按 AbstractStreamConsumer 规则最多重试 3 次
```

## 日程联动

- 创建或修改日程：同步新增/更新提醒计划。
- 状态恢复为 PENDING：重新启用计划。
- COMPLETED、CANCELLED、RESCHEDULED：取消未完成提醒。
- 删除日程：先取消提醒，再物理删除日程。
- 每小时自动取消过期日程：同步批量取消关联提醒。

## 站内提醒接口

```text
GET   /api/interview-schedule/reminders/in-app?unreadOnly=false
GET   /api/interview-schedule/reminders/unread-count
PATCH /api/interview-schedule/reminders/{id}/read
PATCH /api/interview-schedule/reminders/read-all
```

前端 `ReminderBell` 每分钟查询未读数，打开下拉框时加载已到展示时间的站内提醒。

## 启用真实邮件

自动派发默认关闭。准备好 SMTP 后设置：

```text
APP_INTERVIEW_REMINDER_DISPATCH_ENABLED=true
MAIL_HOST=smtp.example.com
MAIL_PORT=587
MAIL_USERNAME=your-account
MAIL_PASSWORD=your-password-or-app-token
MAIL_SMTP_AUTH=true
MAIL_SMTP_STARTTLS=true
MAIL_FROM_ADDRESS=your-account@example.com
MAIL_FROM_NAME=AI Interview
```

邮件实现位于 `SmtpReminderEmailSender`，会生成 UTF-8 HTML 邮件并调用真实的
`JavaMailSender.send(MimeMessage)`。连接、读取和写入超时均已配置，避免 SMTP 无响应时永久阻塞。

## 当前边界

- 项目尚无用户认证，站内提醒当前是全局可见；接入用户体系后应在日程和提醒表增加 `userId` 并按用户查询。
- 数据表由 JPA Entity 驱动，未增加手工迁移脚本。
- 本次只进行编译与单元测试，没有启动后端，也没有连接真实 SMTP、Redis 或 PostgreSQL。
