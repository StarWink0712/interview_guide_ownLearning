```java
interviewSkillsToolCallback
```

 

 核心是agentutilsconfiguration代码（内部有configuration注解，springboot扫描到会自动注册成bean），内部先从agentUtilsProperties配置文件中获取到skill文件所处的目录，然后通过resourceLoader资源加载器定位加载技能文件，拿到这些文件后，通过skillstool的builder方法把这些文件加载进去即可，到这里就把toolcallback注册成了bean'放到spring容器中。

当然也会检查文件所处的目录的有效性。llmproviderregistry注册中心通过依赖注入注入这个bean，获取toolcallback（即封装了各个skill的类），再创建chatclient时，把这个工具添加进去。

<br />

AgentUtilsConfiguration 负责生产 Bean → Spring 容器管理生命周期 → LlmProviderRegistry 通过构造注入消费 Bean → 注册到每个 ChatClient 的 toolCallbacks 上 。全程自动，不需要手动管理。

<br />

```
application.yml
  app.ai.agent-utils.skills-root = classpath:skills
        |
        v
AgentUtilsProperties
        |
        v
AgentUtilsConfiguration.interviewSkillsToolCallback()
        |
        v
SkillsTool.builder().addSkillsResource(skillsRootResource).build()
        |
        v
ToolCallback Bean: interviewSkillsToolCallback
        |
        v
LlmProviderRegistry.createChatClient/createVoiceChatClient
        |
        v
ChatClient.defaultToolCallbacks(interviewSkillsToolCallback)
```

```
Spring Boot 启动
    │
    ▼
扫描 @Configuration → AgentUtilsConfiguration
    │
    ▼
调用 interviewSkillsToolCallback()
    ├── 读取 app.ai.agent-utils.skills-root 配置
    ├── 加载 classpath:skills/ 下所有 SKILL.md
    └── 返回 SkillsTool 实例 → 注册为 Bean("interviewSkillsToolCallback")
    │
    ▼
创建 LlmProviderRegistry（@Component）
    ├── @Autowired(required = false)
    │   @Qualifier("interviewSkillsToolCallback")
    │   ToolCallback callback  ← 注入上面那个 Bean
    │
    ▼
buildDefaultAdvisors() / createVoiceChatClient()
    ├── if (callback != null)
    │       builder.defaultToolCallbacks(callback)
    │
    ▼
ChatClient 构建完成，LLM 可以调用技能工具
```

