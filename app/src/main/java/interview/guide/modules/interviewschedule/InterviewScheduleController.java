/**
 * 面试日程管理控制器
 *
 * <p>提供面试日程的 RESTful API，包括日程的增删改查、状态更新以及面试邀约文本解析。
 * 仅负责路由和请求委托，不包含任何业务逻辑。</p>
 *
 * <p>API 前缀：/api/interview-schedule</p>
 *
 * <p>接口一览：</p>
 * <table>
 *   <tr><th>方法</th><th>路径</th><th>功能</th></tr>
 *   <tr><td>POST</td><td>/parse</td><td>解析面试邀约文本（规则+AI）</td></tr>
 *   <tr><td>POST</td><td>/</td><td>创建面试日程</td></tr>
 *   <tr><td>GET</td><td>/{id}</td><td>查询单个日程</td></tr>
 *   <tr><td>GET</td><td>/</td><td>条件查询日程列表</td></tr>
 *   <tr><td>PUT</td><td>/{id}</td><td>更新日程详情</td></tr>
 *   <tr><td>DELETE</td><td>/{id}</td><td>删除日程</td></tr> 
 *   <tr><td>PATCH/PUT</td><td>/{id}/status</td><td>更新日程状态</td></tr>
 * </table>
 *
 * <p>联动关系：<br>
 * - 依赖 InterviewScheduleService 处理日程 CRUD 和状态变更<br>
 * - 依赖 InterviewParseService 处理面试邀约文本解析（规则+AI 双策略）<br>
 * - 使用 Result&lt;T&gt; 统一响应包装<br>
 * - 使用 @Valid 注解自动校验请求体参数</p>
 */
package interview.guide.modules.interviewschedule;

import interview.guide.common.result.Result;
import interview.guide.modules.interviewschedule.model.CreateInterviewRequest;
import interview.guide.modules.interviewschedule.model.InterviewScheduleDTO;
import interview.guide.modules.interviewschedule.model.InterviewStatus;
import interview.guide.modules.interviewschedule.model.ParseRequest;
import interview.guide.modules.interviewschedule.model.ParseResponse;
import interview.guide.modules.interviewschedule.service.InterviewParseService;
import interview.guide.modules.interviewschedule.service.InterviewScheduleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 面试日程管理控制器 —— 7 个 RESTful 接口，覆盖日程全生命周期管理
 * Interview Schedule Management Controller
 */
@Slf4j
@RestController
@RequestMapping("/api/interview-schedule")
@RequiredArgsConstructor
public class InterviewScheduleController {

    /** 面试日程业务服务 —— 处理 CRUD 和状态变更 */
    private final InterviewScheduleService scheduleService;

    /** 面试邀约文本解析服务 —— 规则解析（三种平台）+ AI 双策略降级 */
    private final InterviewParseService parseService;

    /**
     * 解析面试邀约文本（智能解析）
     *
     * <p>用户粘贴面试邀请邮件/短信/聊天记录，后端自动提取公司名称、岗位、时间等信息。
     * 先尝试正则规则解析（飞书/腾讯会议/Zoom 三种格式），
     * 规则解析失败时降级为 AI 解析（大语言模型）。</p>
     *
     * <p>前端页面：用户点击"解析"按钮 → 弹出框让用户粘贴文本 → 调用此接口 → 回填到表单。</p>
     *
     * @param request 解析请求体，包含 rawText（原始文本）和 source（来源平台，可选）
     * @return 解析结果，含解析成功标志、填充好的 CreateInterviewRequest 和置信度分数
     */
    @PostMapping("/parse")
    public Result<ParseResponse> parse(@Valid @RequestBody ParseRequest request) {
        log.info("接收到解析请求，来源: {}", request.getSource());
        //调用解析服务，解析用户粘贴的文本
        ParseResponse response = parseService.parse(request.getRawText(), request.getSource());
        //返回给前端的是解析后的响应
        return Result.success(response);
    }

    /**
     * 创建面试日程
     *
     * <p>用户在前端表单填写公司、岗位、时间等信息后提交。
     * 系统自动设置状态为 PENDING（待面试），时间戳由 @PrePersist 自动填充。</p>
     *
     * @param request 创建请求体（公司名称、岗位、面试时间为必填字段）
     * @return 创建成功的面试日程 DTO（含自动生成的 id 和创建/更新时间）
     */
    @PostMapping
    public Result<InterviewScheduleDTO> create(@Valid @RequestBody CreateInterviewRequest request) {
        log.info("创建面试记录: {} - {}", request.getCompanyName(), request.getPosition());
        //调用日程业务服务，创建一个面试日程
        InterviewScheduleDTO dto = scheduleService.create(request);
        return Result.success(dto);
    }

    /**
     * 根据 ID 查询单个面试日程
     *
     * <p>用于详情页展示或编辑时的回显。</p>
     *
     * @param id 面试日程 ID
     * @return 面试日程 DTO（含状态和时间戳）
     * @throws interview.guide.common.exception.BusinessException ID 不存在时返回 9001 错误码
     */
    @GetMapping("/{id}")
    public Result<InterviewScheduleDTO> getById(@PathVariable Long id) {
        //日程查询，根据id查询，InterviewScheduleDTO是查询到的返回给前端的对象
        InterviewScheduleDTO dto = scheduleService.getById(id);
        return Result.success(dto);
    }

    /**
     * 按条件查询面试日程列表
     *
     * <p>支持三种查询模式：</p>
     * <ul>
     *   <li><b>时间范围</b>：start + end 同时传 → 日历视图</li>
     *   <li><b>状态筛选</b>：只传 status → 按"待面试/已完成/已取消"等状态过滤</li>
     *   <li><b>全量查询</b>：什么都不传 → 返回所有日程</li>
     * </ul>
     *
     * @param status 面试状态（可选，如 "PENDING"、"COMPLETED"、"CANCELLED"）
     * @param start  时间范围起始（可选，ISO 日期时间格式）
     * @param end    时间范围结束（可选，ISO 日期时间格式）
     * @return 符合条件的面试日程 DTO 列表
     */
    @GetMapping
    public Result<List<InterviewScheduleDTO>> getAll(
            @RequestParam(required = false) String status,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end
    ) {
        //根据状态，起始还有终止时间，查询日程
        List<InterviewScheduleDTO> list = scheduleService.getAll(status, start, end);
        //返回给前端查询到的全部日程
        return Result.success(list);
    }

    /**
     * 更新面试日程
     *
     * <p>注意：此接口不能修改 id 和 status 字段。
     * id 由主键决定，状态变更必须走专用接口。这防止了客户端绕过业务规则修改状态。</p>
     *
     * @param id      要更新的面试日程 ID
     * @param request 更新请求体（字段结构与创建接口一致）
     * @return 更新后的面试日程 DTO
     */
    @PutMapping("/{id}")
    public Result<InterviewScheduleDTO> update(
            @PathVariable Long id,
            @Valid @RequestBody CreateInterviewRequest request
    ) {
        log.info("更新面试记录: ID={}", id);
        //还是调用业务服务，更新日程信息
        InterviewScheduleDTO dto = scheduleService.update(id, request);
        return Result.success(dto);
    }

    /**
     * 删除面试日程
     *
     * <p>物理删除（直接从数据库移除记录）。</p>
     *
     * @param id 要删除的面试日程 ID
     * @return 成功响应（data 为 null）
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        log.info("删除面试记录: ID={}", id);
        //调用业务服务，删除面试记录
        scheduleService.delete(id);
        return Result.success(null);
    }

    /**
     * 更新面试日程状态
     *
     * <p>此接口使用 PATCH 和 PUT 两种 HTTP 方法兼容：
     * PATCH 在语义上更适合"部分更新"（只改状态），
     * 但某些前端框架（如 axios 的 form 提交）不支持 PATCH，所以同时开放 PUT。</p>
     *
     * <p>状态流转示例：PENDING → COMPLETED（面试完成），PENDING → CANCELLED（主动取消）。</p>
     *
     * @param id     要更新状态的面试日程 ID
     * @param status 目标状态 @RequestParam（如 "COMPLETED"、"CANCELLED"）
     * @return 更新状态后的面试日程 DTO
     */
    @RequestMapping(path = "/{id}/status", method = {RequestMethod.PATCH, RequestMethod.PUT})
    public Result<InterviewScheduleDTO> updateStatus(
            @PathVariable Long id,
            @RequestParam InterviewStatus status
    ) {
        log.info("更新面试状态: ID={}, status={}", id, status);
        //更新状态
        InterviewScheduleDTO dto = scheduleService.updateStatus(id, status);
        return Result.success(dto);
    }
}
