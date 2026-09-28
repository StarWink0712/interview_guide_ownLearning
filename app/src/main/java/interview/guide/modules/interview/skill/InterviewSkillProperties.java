/**
 * 面试方向（Skill）数据结构定义
 *
 * 工具类，定义 Skill 相关的配置数据结构：
 * - SkillFrontMatterDefinition：SKILL.md front matter 标准字段（name、description）
 * - SkillMetaDefinition：skill.meta.yml 项目自定义字段（displayName、display、categories）
 * - SkillDefinition：运行时聚合结构，合并标准字段和项目自定义字段
 * - DisplayDef：展示配置（图标、渐变色等）
 * - CategoryDef：分类定义（key、label、priority、ref、shared）
 *
 * 联动关系：
 * - 被 InterviewSkillService 在解析 SKILL.md 和 skill.meta.yml 时使用
 * - 作为 SnakeYAML 反序列化的目标类型
 */
package interview.guide.modules.interview.skill;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

public final class InterviewSkillProperties {

    /**
     * 禁止实例化仅用于承载配置结构的工具类。
     */
    private InterviewSkillProperties() {
    }

    /**
     * SKILL.md front matter（标准字段）。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkillFrontMatterDefinition {
        private String name;
        private String description;
    }

    /**
     * skill.meta.yml（项目自定义字段）。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkillMetaDefinition {
        private String displayName;
        private DisplayDef display;
        private List<CategoryDef> categories = new ArrayList<>();
    }

    /**
     * 运行时聚合结构：标准字段 + 项目自定义字段。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkillDefinition {
        private String name;
        private String description;
        private String persona;
        private String displayName;
        private DisplayDef display;
        private List<CategoryDef> categories = new ArrayList<>();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DisplayDef {
        private String icon;
        private String gradient;
        private String iconBg;
        private String iconColor;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryDef {
        private String key;
        private String label;
        private String priority;
        private String ref;
        private Boolean shared;
    }
}
