```java
 public Long prepareStreamMessage(Long sessionId, String question)
```

这个方法不调用 LLM，但它解决了流式 SSE 场景的一个核心问题：**消息持久化与流式输出之间的时序矛盾**。

## 问题：为什么不能边流式输出边保存消息？

如果直接在 LLM 生成的过程中实时写入数据库，会遇到两个问题：

```
问题 1：流式输出和数据库写入是异步的
  LLM 开始输出 → 还没保存用户消息 → 前端已经收到 AI 回答了 → 用户在消息列表啥也看不到

问题 2：AI 回答是流式到达的，不知道何时才算"完整"
  Flux 每收到一个 chunk 就 update 数据库 → 性能灾难，且半截内容持久化无意义
```

## 方案：三步提交模式

看完整的 `sendMessageStream` 流程就能理解：

```
步骤 1：prepareStreamMessage（同步事务，L138）
  ├── 保存用户消息（completed=true，内容完整）
  ├── 创建 AI 消息占位（completed=false，content=""）
  └── 返回 AI 占位消息的 messageId = 123

步骤 2：Flux<SSE> 流式输出（异步，L142-156）
  ├── traceEvent → 返回 traceId
  ├── LLM 逐块输出 → doOnNext(fullContent::append) 只用 StringBuilder 内存累积
  └── 不碰数据库，不阻塞流

步骤 3：completeStreamMessage（doOnComplete 回调，L157-160）
  ├── 根据 messageId=123 找到占位消息
  ├── 设置 content = fullContent（完整回答）
  ├── 设置 completed = true
  └── 一次性写入数据库
```

数据库视角的时间线：

```
数据库状态变化：
  t1: USER 消息 id=122, content="Java GC 是什么", completed=true    ← 步骤1
      AI 消息   id=123, content="",             completed=false   ← 占位
  t2~10: （LLM 流式生成中，数据库无变化）
  t11: AI 消息  id=123, content="Java GC 是...完整回答", completed=true  ← 步骤3
```

## 为什么占位消息必须 completed=false？

如果用户在 LLM 流式生成中途**刷新页面**，通过 `getSessionDetail` 拉取消息历史时会看到 ID=123 的消息是 `completed=false`，前端就能区分出"这条 AI 回答还没生成完"，从而显示"生成中..."的 loading 状态。

## 如果不做步骤 1，直接流式输出会发生什么？

```
❌ 没有 prepareStreamMessage 的情况：

用户发消息 → 开始流式输出 → 用户刷新页面 → getSessionDetail 查不到刚才的问题
  → 用户："我刚问的问题去哪了？"

或者：

流式输出过程崩溃 → 用户的问题和 AI 的部分回答一起丢失
  → 用户："我问了什么来着？"

✅ 有 prepareStreamMessage 的情况：

用户发消息 → 用户消息已入库 → 流式崩溃 → 用户刷新页面
  → 看到自己的问题（completed=true）+ AI 消息显示"生成失败"
  → 消息不丢失，用户知道发生了什么
```

## 总结

我明白了，就是先把用户问的问题和答案存起来，只不过答案先用空白占位。等到拿到llm给的答案之后，再去填充。避免llm回答的过程中宕机导致用户不知道之前问过什么问题

这个方法的核心价值是 **"在流还没出来之前，先把坑占好"**——用户消息必须提前落地，AI 消息必须有占位记录。这样无论流式输出成功、失败、还是用户中途刷新，消息历史都是完整可追溯的。

好的，让我用最简单的方式重新解释。

## 先看这段代码的执行顺序

`sendMessageStream` 方法是**立刻完整执行完返回的**，而不是"等着 LLM 慢慢生成"：

```java
public Flux<ServerSentEvent<String>> sendMessageStream(...) {

    // ① 这一行立刻执行，同步等待完成（写数据库）
    Long messageId = sessionService.prepareStreamMessage(sessionId, request.question());
    
    // ② 这一行也立刻执行，拿到一个 Flux 对象（还没开始推送数据）
    var streamResult = sessionService.getStreamAnswerWithTrace(sessionId, request.question());
    
    // ③ 返回 Flux — 方法结束了！请求线程释放了！
    return Flux.concat(...)
        .doOnComplete(...)    // 这些回调不会立刻执行
        .doOnError(...);
}
```

**整个方法在几毫秒内就执行完了**。返回的 `Flux` 只是一个"指令清单"，告诉 Spring："当你有订阅者时，按这个顺序推送数据"。

## 用一句话说清楚

假如没有 `prepareStreamMessage`，看会发生什么：

```
时间线 →
  t0: 用户发请求
  t1: 方法返回 Flux（几毫秒）
  t2~t15: Spring 在后台推送 SSE 数据流（LLM 一个字一个字生成）
  t15: doOnComplete 回调写入数据库

  t5: 用户突然关闭浏览器
  → 连接断开 → Flux 中断 → doOnComplete 永远不执行
  → 数据库里什么都没有！
  → 用户打开 APP 看历史记录：空的，刚才问的啥？
```

有了 `prepareStreamMessage`：

```
时间线 →
  t0: 用户发请求
  t1: prepareStreamMessage → 数据库里已经有：
        USER消息: "Java GC 是什么"    completed=true
        AI消息:   ""                  completed=false
  t2~t15: SSE 流式推送（LLM 逐字生成）
  t15: completeStreamMessage → AI消息变为 "Java GC 是..." completed=true

  t5: 用户突然关闭浏览器
  → 流中断，doOnComplete 不执行
  → 但数据库里已经有：用户的提问 + AI消息显示"未完成"
  → 用户打开 APP：看到了刚才问的问题！
```

## 本质

`prepareStreamMessage` 的核心作用就是：**在"不确定结果的事情"发生之前，先把"确定的事情"存下来**。

用户的提问内容是确定的 → 先存。AI 的回答是不确定的（可能成功、失败、中途中断）→ 后存。
