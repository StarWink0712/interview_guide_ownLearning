/**
 * 面试邀约解析响应体
 *
 * 封装解析结果，包含是否成功、提取的面试数据（CreateInterviewRequest）、
 * 置信度、解析方式（rule/AI）和日志信息。
 *
 * 联动关系：
 * - 由 InterviewParseService.parse() 方法构建并返回
 * - 被 InterviewScheduleController 的 parse 接口作为响应返回给前端
 * - data 字段类型为 CreateInterviewRequest，可直接用于后续创建日程
 * CreateInterviewRequest 在这个流程里就是一个 
 * 数据传递的载体 ——后端解析出来是什么，就装进去，
 * 前端拿着就用。不经过数据库，不经过 Entity 转换，干干净净。
 */
package interview.guide.modules.interviewschedule.model;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ParseResponse {
    private Boolean success;
    /** 后端解析成功后构造的 CreateInterviewRequest 对象，
     * 把这个对象封装到ParseResponse中，返回给前端。
     * 可直接用于创建日程或回填表单 
     * 返回给前端后，日程并没有被创建，因为到这一步只是解析面试
     * 邀约文本，所以是返回的是request对象
     * 等后面前端再点击创建日程时，才会把这个信息传给后端创建日程
     * 创建日程的service方法里面的入参也是这个*/
    private CreateInterviewRequest data;
    private Double confidence;
    private String parseMethod; // rule, ai
    private String log;
}
/*
用户粘贴 "【字节跳动】面试邀请：Java 后端..."
    ↓
前端 POST /api/interview-schedule/parse { rawText: "...", source: null }
    ↓
InterviewParseService 规则解析 / AI 解析
    ↓
构造 CreateInterviewRequest { companyName:"字节跳动", position:"Java 后端", ... }
    ↓
封装进 ParseResponse { success:true, data: CreateInterviewRequest, confidence:0.95, ... }
    ↓
前端拿到 data，直接回填到创建表单 → 用户点确认 → 再调 POST /api/interview-schedule 创建
 */