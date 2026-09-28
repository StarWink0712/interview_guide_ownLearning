/**
 * 面试日程业务服务
 *
 * <p>编排面试日程的完整生命周期管理，包括创建、更新、删除、状态变更和条件查询。
 * 通过 BeanUtils.copyProperties 实现 Request/Entity/DTO 之间的数据转换，
 * 使用 BusinessException 处理实体不存在等业务异常。</p>
 *
 * <p>设计原则：</p>
 * <ul>
 *   <li><b>事务管理</b>：写操作（创建/更新/删除/状态变更）都使用 @Transactional 保证原子性</li>
 *   <li><b>数据安全</b>：更新时忽略 id 和 status 字段，防止客户端篡改</li>
 *   <li><b>异常标准化</b>：实体不存在统一抛 BusinessException，由 GlobalExceptionHandler 处理</li>
 *   <li><b>实体隔离</b>：对外只返回 DTO，不直接暴露数据库实体</li>
 * </ul>
 *
 * <p>联动关系：<br>
 * - 被 InterviewScheduleController 调用，处理所有日程相关请求<br>
 * - 依赖 InterviewScheduleRepository 执行数据持久化<br>
 * - 使用 ErrorCode.INTERVIEW_SCHEDULE_NOT_FOUND 抛出业务异常<br>
 * - 使用 BeanUtils.copyProperties 在 Request ↔ Entity ↔ DTO 之间转换</p>
 */
package interview.guide.modules.interviewschedule.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interviewschedule.model.CreateInterviewRequest;
import interview.guide.modules.interviewschedule.model.InterviewScheduleDTO;
import interview.guide.modules.interviewschedule.model.InterviewScheduleEntity;
import interview.guide.modules.interviewschedule.model.InterviewStatus;
import interview.guide.modules.interviewschedule.repository.InterviewScheduleRepository;
import interview.guide.modules.interviewschedule.reminder.service.InterviewReminderService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InterviewScheduleService {

    // ==================== 注入的依赖 ====================

    /** 面试日程数据仓库，提供对 interview_schedule 表的 CRUD 操作 */
    private final InterviewScheduleRepository repository;
    
    private final InterviewReminderService reminderService;

    /**
     * 允许从 Request 复制到 Entity 的业务字段白名单
     *
     * <p>Request 和 Entity 同名字段一一对应：companyName、position、interviewTime、
     * interviewType、meetingLink、roundNumber、interviewer、notes。
     * id/status/createdAt/updatedAt 由系统自动维护，不允许客户端通过请求体修改。
     *
     * <p>（这些字段通过 BeanUtils.copyProperties 的同名匹配策略自动复制，
     * update 时额外显式忽略 "id" 和 "status" 以防客户端篡改。）</p>
     */

    // ==================== 核心 CRUD 方法 ====================

    /**
     * 创建面试日程
     *
     * <p>将前端传来的 CreateInterviewRequest 转为 Entity，设置默认初始状态为 PENDING，
     * 保存到数据库后返回 DTO。</p>
     *
     * <p>执行流程：</p>
     * <ol>
     *   <li>创建新的 Entity 实例</li>
     *   <li>使用 BeanUtils.copyProperties 将 Request 中的字段复制到 Entity</li>
     *   <li>手动设置 status = PENDING（新建的面试默认是"待面试"状态）</li>
     *   <li>repository.save() 持久化（@PrePersist 自动填充 createdAt/updatedAt）</li>
     *   <li>将 Entity 转为 DTO 返回给 Controller</li>
     * </ol>
     *
     * @param request 前端传入的创建请求体，包含公司名、岗位、时间等信息
     * @return 创建成功后的面试日程 DTO（含自动生成的 id 和时间戳）
     */
    @Transactional
    public InterviewScheduleDTO create(CreateInterviewRequest request) {

        InterviewScheduleEntity entity = new InterviewScheduleEntity();
        // Request 和 Entity 中同名的业务字段全部复制，id/createdAt/updatedAt 由系统自动维护

        BeanUtils.copyProperties(request, entity);
        normalizeReminderSettings(entity);
        // 新建的面试默认是"待面试"状态
        entity.setStatus(InterviewStatus.PENDING);
        InterviewScheduleEntity saved = repository.save(entity);
        reminderService.synchronizeReminders(saved);
        return toDTO(saved);
    }

    /**
     * 更新面试日程
     *
     * <p>先根据 id 查找已有记录，找到后用请求体覆盖业务字段。</p>
     *
     * <p>安全性设计：copyProperties 时忽略 "id" 和 "status" 字段——</p>
     * <ul>
     *   <li>忽略 id：防止客户端传一个不同的 id 覆盖现有记录的主键</li>
     *   <li>忽略 status：状态的变更必须通过专门的 updateStatus 方法，
     *       不能通过更新接口修改，防止客户端绕过状态流转逻辑</li>
     * </ul>
     *
     * @param id      要更新的面试日程 ID
     * @param request 包含要更新的字段的新值
     * @return 更新后的面试日程 DTO
     * @throws BusinessException 如果指定 ID 的记录不存在，抛出 INTERVIEW_SCHEDULE_NOT_FOUND
     */
    @Transactional
    public InterviewScheduleDTO update(Long id, CreateInterviewRequest request) {
        InterviewScheduleEntity entity = getByIdOrThrow(id);
        BeanUtils.copyProperties(request, entity, "id", "status");
        normalizeReminderSettings(entity);
        InterviewScheduleEntity saved = repository.save(entity);
        reminderService.synchronizeReminders(saved);
        return toDTO(saved);
    }

    /**
     * 删除面试日程
     *
     * <p>直接按 id 物理删除。如果 id 不存在，JPA 会抛出 EmptyResultDataAccessException，
     * 由 GlobalExceptionHandler 的兜底异常处理捕获。</p>
     *
     * @param id 要删除的面试日程 ID
     */
    @Transactional
    public void delete(Long id) {
        reminderService.cancelActiveReminders(id, "面试日程已删除");
        repository.deleteById(id);
    }

    /**
     * 更新面试日程状态
     *
     * <p>面试状态需要按业务规则流转（如 PENDING → COMPLETED / CANCELLED），
     * 这个独立的方法确保状态变更显式调用、不走通用更新路径。</p>
     *
     * @param id     要更新状态的面试日程 ID
     * @param status 目标状态（PENDING / COMPLETED / CANCELLED / EXPIRED）
     * @return 更新状态后的面试日程 DTO
     * @throws BusinessException 如果指定 ID 的记录不存在
     */
    @Transactional
    public InterviewScheduleDTO updateStatus(Long id, InterviewStatus status) {
        InterviewScheduleEntity entity = getByIdOrThrow(id);
        entity.setStatus(status);
        InterviewScheduleEntity saved = repository.save(entity);
        if (status == InterviewStatus.PENDING) {
            reminderService.synchronizeReminders(saved);
        } else {
            reminderService.cancelActiveReminders(id, "面试日程状态已变更为 " + status);
        }
        return toDTO(saved);
    }

    // ==================== 查询方法 ====================

    /**
     * 按条件查询面试日程列表
     *
     * <p>支持三种查询模式，按参数传值自动切换：</p>
     * <ol>
     *   <li><b>时间范围查询</b>：start 和 end 都非空时，查询该时间段的日程（日历视图）</li>
     *   <li><b>状态筛选</b>：只传 status 不传时间时，按状态过滤</li>
     *   <li><b>全量查询</b>：不传任何条件时，返回所有日程</li>
     * </ol>
     *
     * <p>注意：status 参数从 String 转为 InterviewStatus 枚举，
     * 如果前端传了错误的 status 值会抛出 IllegalArgumentException，
     * 由 GlobalExceptionHandler 的 handleIllegalArgumentException 处理。</p>
     *
     * @param status 面试状态筛选（可选，如 "PENDING"、"COMPLETED"）
     * @param start  查询时间范围起始（可选，与 end 必须同时传）
     * @param end    查询时间范围结束（可选，与 start 必须同时传）
     * @return 符合条件的面试日程 DTO 列表
     */
    public List<InterviewScheduleDTO> getAll(String status, LocalDateTime start, LocalDateTime end) {
        List<InterviewScheduleEntity> entities;

        if (start != null && end != null) {
            // 模式 1：时间范围查询（用于日历视图展示某一天的面试）
            entities = repository.findByInterviewTimeBetween(start, end);
        } else if (status != null) {
            // 模式 2：按状态筛选（用于前端按"待面试/已完成/已取消"等状态过滤）
            entities = repository.findByStatus(InterviewStatus.valueOf(status));
        } else {
            // 模式 3：全量查询
            entities = repository.findAll();
        }

        return entities.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /**
     * 根据 ID 查询单个面试日程
     *
     * @param id 面试日程 ID
     * @return 面试日程 DTO
     * @throws BusinessException 如果记录不存在
     */
    public InterviewScheduleDTO getById(Long id) {
        return toDTO(getByIdOrThrow(id));
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 根据 ID 查找实体，不存在时抛业务异常
     *
     * <p>这是整个 Service 中统一的"查找或失败"方法，所有需要按 ID 查询的
     * 方法都走这里，避免到处写 orElseThrow 产生重复代码。</p>
     *
     * @param id 面试日程 ID
     * @return 找到的实体
     * @throws BusinessException 错误码 INTERVIEW_SCHEDULE_NOT_FOUND(9001)
     */
    private InterviewScheduleEntity getByIdOrThrow(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INTERVIEW_SCHEDULE_NOT_FOUND,
                        "面试日程不存在: " + id));
    }

    /**
     * 将 Entity 转换为 DTO
     *
     * <p>遵循规范中"禁止直接返回 Entity 给前端"的要求，
     * 通过 BeanUtils.copyProperties 将 Entity 的所有字段复制到 DTO 中。</p>
     *
     * <p>为什么不直接用 MapStruct？当前场景下 Entity 和 DTO 字段完全一一对应，
     * BeanUtils.copyProperties 一行代码即可完成，引入 MapStruct 反而增加了
     * 编译期注解处理的复杂度。如果后续字段映射规则变得复杂（如字段名不同、
     * 需要聚合计算等），可以升级为 MapStruct。</p>
     *
     * @param entity 数据库查出的实体
     * @return 返回给前端的 DTO 对象
     */
    private InterviewScheduleDTO toDTO(InterviewScheduleEntity entity) {
        InterviewScheduleDTO dto = new InterviewScheduleDTO();
        BeanUtils.copyProperties(entity, dto);
        return dto;
    }   

    /**
     * 规范提醒设置，确保提醒功能正常
     *
     * <p>如果提醒功能未开启，默认开启；如果提醒邮箱为空，设为 null。</p>
     *
     * @param entity 数据库查出的实体
     */
    private void normalizeReminderSettings(InterviewScheduleEntity entity) {
        if (entity.getReminderEnabled() == null) {
            entity.setReminderEnabled(true);
        }
        if (entity.getReminderEmail() != null) {
            String email = entity.getReminderEmail().trim();
            entity.setReminderEmail(email.isEmpty() ? null : email);
        }
    }
}
