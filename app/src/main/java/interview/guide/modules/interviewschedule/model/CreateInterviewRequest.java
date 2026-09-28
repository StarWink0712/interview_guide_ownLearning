/**
 * 创建/更新面试日程请求体
 * 不是数据库实体，是"前端发来的表单"
 * 封装前端提交的面试日程数据，包含公司名称、岗位、面试时间、面试形式、会议链接、
 * 轮次、面试官和备注等字段。用于创建和更新面试记录时的参数校验。
 *
 * 联动关系：
 * - 被 InterviewScheduleController 用作创建和更新接口的请求参数
 * - 被 InterviewScheduleService 在 create/update 方法中接收并转换为 Entity
 * - 被 InterviewParseService 的解析结果填充后返回给前端
 */
package interview.guide.modules.interviewschedule.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateInterviewRequest {
    /** 公司名称 —— 必填，标明面试的招聘公司 */
    @NotBlank(message = "公司名称不能为空")
    private String companyName;

    /** 应聘岗位 —— 必填，如 "Java 后端工程师" */
    @NotBlank(message = "岗位不能为空")
    private String position;

    /** 面试时间 —— 必填，ISO-8601 格式，支持精确到分或秒 */
    @NotNull(message = "面试时间不能为空")
    @com.fasterxml.jackson.annotation.JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss]")
    private java.time.LocalDateTime interviewTime;

    /** 面试形式 —— 可选枚举值：ONSITE（现场）、VIDEO（视频）、PHONE（电话） */
    private String interviewType;

    /** 会议链接 —— 视频面试时填写，如腾讯会议/飞书会议链接 */
    private String meetingLink;

    /** 面试轮次 —— 默认第 1 轮，如 "第 2 轮面试" 则传 2 */
    private Integer roundNumber = 1;

    /** 面试官姓名或身份标识 */
    private String interviewer;

    /** 面试备注 —— 如需要准备的资料、特殊要求等补充信息 */
    private String notes;

    /** 是否为该日程生成提醒计划，默认开启。 */
    private Boolean reminderEnabled = true;

    /** 邮件提醒收件地址；为空时仍生成站内提醒，但不生成邮件提醒。 */
    @Email(message = "提醒邮箱格式不正确")
    private String reminderEmail;
}
