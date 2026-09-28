# 触发 `shutdown()` 的 Bean 是谁

`shutdown()` 方法定义在抽象父类 [AbstractStreamConsumer](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/common/async/AbstractStreamConsumer.java#L100-L107) 中，但它不是独立的 Bean（它是 `abstract` 类，无法实例化）。

真正被 Spring 容器管理的 Bean 是 **4 个子类**，每个都用 `@Component` 标注：

---

## 四个具体的 Bean

| Bean 类 | 位置 | 职责 | 线程名 |
|---------|------|------|--------|
| [VectorizeStreamConsumer](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/knowledgebase/listener/VectorizeStreamConsumer.java) | knowledgebase/listener | 知识库文档向量化 | `vectorize-consumer` |
| [AnalyzeStreamConsumer](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/resume/listener/AnalyzeStreamConsumer.java) | resume/listener | 简历 AI 分析评分 | `analyze-consumer` |
| [EvaluateStreamConsumer](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/interview/listener/EvaluateStreamConsumer.java) | interview/listener | 模拟面试 AI 评估 | `evaluate-consumer` |
| [VoiceEvaluateStreamConsumer](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/voiceinterview/listener/VoiceEvaluateStreamConsumer.java) | voiceinterview/listener | 语音面试 AI 评估 | `voice-evaluate-consumer` |

每个类都有 `@Component` 注解，例如：

```java
@Slf4j
@Component                                    // ← 交给 Spring 管理
public class VectorizeStreamConsumer 
    extends AbstractStreamConsumer<VectorizePayload> { ... }
```

---

## Bean 的生命周期链

当 Spring 容器销毁时，**4 个 Bean 的 `shutdown()` 方法各自独立触发**，不存在先后顺序：

```
Spring 容器关闭
        ↓
┌───────────────────────────────────────────────────────┐
│  @PreDestroy 触发（4 个 Bean 各自调用，顺序不保证）         │
│                                                        │
│  VectorizeStreamConsumer.shutdown()     ← Bean 1       │
│       running.set(false)                               │
│       executorService.shutdown()                       │
│                                                        │
│  AnalyzeStreamConsumer.shutdown()       ← Bean 2       │
│       running.set(false)                               │
│       executorService.shutdown()                       │
│                                                        │
│  EvaluateStreamConsumer.shutdown()      ← Bean 3       │
│       running.set(false)                               │
│       executorService.shutdown()                       │
│                                                        │
│  VoiceEvaluateStreamConsumer.shutdown() ← Bean 4       │
│       running.set(false)                               │
│       executorService.shutdown()                       │
└───────────────────────────────────────────────────────┘
        ↓
JVM 退出
```

---

## 继承关系图

```
                    AbstractStreamConsumer<T>  (abstract, 不是 Bean)
                    ├── @PostConstruct init()
                    ├── @PreDestroy shutdown()    ← 定义在这里
                    ├── startConsumer()
                    ├── consumeLoop()
                    │    ...
                    └── 抽象方法（子类实现）
                              │
        ┌─────────────────────┼─────────────────────┐
        │                     │                     │
VectorizeStreamConsumer  AnalyzeStreamConsumer  EvaluateStreamConsumer  VoiceEvaluateStreamConsumer
   @Component              @Component            @Component              @Component
   向量化                   简历分析               面试评估                 语音面试评估
```

---

## 关键点

`@PreDestroy` 注解在父类上，通过 Java 继承机制被子类继承。Spring 发现子类 Bean 继承了该注解，就会在销毁子类 Bean 时自动调用。所以：

> **销毁的是 4 个子类 Bean，每个 Bean 销毁时都会执行同一套 `shutdown()` 逻辑（停止各自的消费循环、关闭各自的单线程守护线程池）。**

这就是为什么项目中有 4 条独立的消费管道（向量化、简历分析、面试评估、语音面试评估），每一条都独立启动、独立关闭、互不干扰。