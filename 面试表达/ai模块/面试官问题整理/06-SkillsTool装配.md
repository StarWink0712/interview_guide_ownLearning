# SkillsTool 装配

## 1. `interviewSkillsToolCallback` 是怎么生成的？

**回答：** 核心在 `AgentUtilsConfiguration`。Spring Boot 扫描到这个 Configuration 后，会执行 `interviewSkillsToolCallback()`，读取 Skill 根目录、加载资源，再通过 `SkillsTool.builder().addSkillsResource(skillsRootResource).build()` 创建 ToolCallback，并以 `interviewSkillsToolCallback` 的名称注册到 Spring 容器。

## 2. `AgentUtilsConfiguration`、`AgentUtilsProperties` 和 ResourceLoader 分别负责什么？

**回答：** `AgentUtilsProperties` 负责从配置中读取 `app.ai.agent-utils.skills-root`；`ResourceLoader` 根据这个路径定位和加载 Skill 资源；`AgentUtilsConfiguration` 负责校验目录有效性、调用 SkillsTool Builder，并把最终 ToolCallback 注册成 Spring Bean。

## 3. Spring 启动时如何加载 `classpath:skills`？

**回答：** 启动时先从 application.yml 读取 `app.ai.agent-utils.skills-root=classpath:skills`，再由 ResourceLoader 获取对应资源。SkillsTool Builder 会加载该目录下的所有 `SKILL.md`，最终形成封装各个 Skill 的 ToolCallback。

## 4. SkillsTool 如何注册到 Spring 容器？

**回答：** `AgentUtilsConfiguration.interviewSkillsToolCallback()` 是一个 Bean 工厂方法，它返回构建完成的 SkillsTool 实例，并注册成名为 `interviewSkillsToolCallback` 的 ToolCallback Bean。之后由 Spring 容器统一管理它的生命周期，不需要业务代码手动创建或维护。

## 5. LlmProviderRegistry 如何把 SkillsTool 挂到 ChatClient？

**回答：** LlmProviderRegistry 通过构造注入和 `@Qualifier("interviewSkillsToolCallback")` 获得这个 ToolCallback。在创建 Default 或 Voice ChatClient 时，如果 Callback 不为空，就调用 `builder.defaultToolCallbacks(callback)` 把它注册到 ChatClient，之后模型才能在对话过程中发起 Skill 工具调用。
