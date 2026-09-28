/**
 * 面试方向（Skill）REST 控制器
 *
 * 提供面试方向的查询和 JD 解析 API：列出所有预设 Skill、获取单个 Skill 详情、
 * 通过 AI 解析 JD 文本提取面试分类。JD 解析接口使用 @RateLimit 做 IP 限流保护。
 *
 * 联动关系：
 * - 委托 InterviewSkillService 处理所有业务逻辑
 * - 使用 @RateLimit 注解（common.annotation）对 JD 解析接口限流
 */
package interview.guide.modules.interview.skill;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/interview/skills")
public class InterviewSkillController {

    private final InterviewSkillService skillService;

    /**
     * 注入面试 Skill 服务。
     *
     * <p>由 Spring 创建 Controller 时调用，不加载资源或调用 LLM。
     */
    public InterviewSkillController(InterviewSkillService skillService) {
        this.skillService = skillService;
    }

    /**
     * 查询全部预设面试 Skill。
     *
     * <p>调用入口：前端加载面试方向选择器时通过
     * {@code GET /api/interview/skills} 调用。
     * 该方法委托 {@link InterviewSkillService#getAllSkills()} 返回配置快照。
     */
    @GetMapping
    public Result<List<InterviewSkillService.SkillDTO>> listSkills() {
        return Result.success(skillService.getAllSkills());
    }

    /**
     * 查询指定 Skill 详情。
     *
     * <p>调用入口：前端查看方向配置时通过
     * {@code GET /api/interview/skills/{id}} 调用。
     * 该方法委托 {@link InterviewSkillService#getSkill(String)} 完成查询和兜底。
     */
    @GetMapping("/{id}")
    public Result<InterviewSkillService.SkillDTO> getSkill(@PathVariable String id) {
        return Result.success(skillService.getSkill(id));
    }
    /**
     * 通过 LLM 从职位描述中提取面试分类。
     *
     * <p>调用入口：前端提交 JD 时通过
     * {@code POST /api/interview/skills/parse-jd} 调用，并按 IP 限流。
     * 请求体校验后委托 {@link InterviewSkillService#parseJd(String)}；
     * Service 负责 Prompt 安全处理、结构化输出和失败兜底。
     */
    @PostMapping("/parse-jd")
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
    public Result<List<InterviewSkillService.CategoryDTO>> parseJd(@Valid @RequestBody ParseJdRequest request) {
        return Result.success(skillService.parseJd(request.jdText()));
    }

    public record ParseJdRequest(@NotBlank String jdText) {}
}
