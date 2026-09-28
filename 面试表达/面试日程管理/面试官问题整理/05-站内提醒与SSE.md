# 站内提醒与 SSE

## 1. 当前站内提醒是怎么展示的？

**回答：** 当前 IN\_APP 分支到达触发时间后只打印日志，并把提醒状态更新为 SENT。前端通过调用 `GET /api/interview-schedule/reminders/in-app` 拉取站内提醒列表进行展示，因此用户需要刷新页面或由前端定时轮询，目前还没有实现后端实时推送弹窗。

## 2. SSE 的底层工作原理是什么？

**回答：** SSE 本质上是前端发起普通 HTTP GET 请求，服务端返回 `Content-Type: text/event-stream` 后不关闭 TCP 连接。之后服务端可以持续写入 `data: {json}\n\n`，浏览器 EventSource 收到数据后自动触发对应事件回调，从效果上实现后端主动推送。

SSE本质也是http请求，只不过是可以通过服务端推送的方式给前端，是tcp长链接，服务端把请求信息封装好给前端，浏览器的eventsource收到数据后可以触发这个事件回调。

## 3. Spring `SseEmitter` 如何保持和写入连接？

**回答：** Controller 返回 SseEmitter 后，Spring 不会通过普通 HttpMessageConverter 一次性完成响应，而是持有 Response 引用并保持连接。后续业务代码取得 Emitter 并调用 `send()`，就相当于继续向未关闭的 HTTP 响应中追加事件数据，前端能够实时收到。

## 4. 如果使用 SSE，后端和前端分别怎么实现？

**回答：** 后端可以建立 `ReminderSseRegistry`，用 `ConcurrentHashMap<userId,SseEmitter>` 保存在线连接，注册 completion、timeout、error 清理回调，并提供 push 方法。Controller 提供 `produces=TEXT_EVENT_STREAM_VALUE` 的订阅接口；Consumer 的 IN\_APP 分支调用 Registry 推送 Context。前端使用 `new EventSource(...)` 建立连接，并监听 reminder 事件，把 JSON 转成页面 Toast 或浏览器通知。

## 5. SSE 是否已经实现，断线后如何恢复？

**回答：** SSE 在当前项目中是可选增强方案，还没有真正接入，当前站内提醒仍然通过接口拉取。后续实现后可以利用浏览器 EventSource 自带的断线重连能力，网络断开时自动重新发起 GET 请求建立连接，不需要前端手动编写重连逻辑。

<br />

即spring是如何保持写入和连接的，后端返回空的sseemitter，前端也保持打开，即维护http长连接。

普通 HTTP 请求是这样的：

```
前端  ──请求──→  后端  ──返回完整响应──→  前端  ← 连接关闭，一次往返结束
```

`SseEmitter` 打破了这个模式：

```
前端  ──请求──→  后端  ──返回空的 SseEmitter（连接不关）──→  前端  ← 连接保持打开
                      │
                      │  emitter.send("第1段数据")  ──────────→  前端收到 "data: 第1段数据"
                      │  emitter.send("第2段数据")  ──────────→  前端收到 "data: 第2段数据"
                      │  emitter.send("第3段数据")  ──────────→  前端收到 "data: 第3段数据"
                      │  emitter.complete()        ──────────→  连接关闭
```

核心区别是：Spring 内部识别到 Controller 返回的是 `SseEmitter` 类型后，**不调用普通的 HttpMessageConverter 去序列化响应体**，而是直接持有 HTTP Response 的引用不关闭。之后每次业务代码调 `emitter.send(data)`，Spring 就沿着那条还没关的 HTTP 连接追加一段 `data: xxx\n\n` 格式的文本。前端 `EventSource` 的 `onmessage` 每收到一段就触发一次，实现了"一个请求、多次响应"的效果。

你可以把它理解成 HTTP 版的"连麦不挂电话"——电话打通了，双方都不挂断，说一段停一段，直到一方说"我说完了"才真正挂断。

<br />

对，SSE 是标准的 HTTP 长连接，不需要升级协议。和 WebSocket 的区别在于：

| <br /> | SSE             | WebSocket            |
| :----- | :-------------- | :------------------- |
| 协议     | 普通 HTTP，连接不关    | HTTP Upgrade → WS 协议 |
| 方向     | 单向：服务端 → 前端     | 双向                   |
| 前端 API | `EventSource`   | `new WebSocket()`    |
| 自动重连   | 浏览器原生支持         | 需要自己写                |
| 适用场景   | 只需要推数据（AI 流式输出） | 需要双向实时通信（语音面试）       |

所以你项目里两种都用到了——知识库的流式问答用 `SseEmitter`（只需推 LLM 生成的文本给前端，前端不回传），语音面试用 WebSocket（前端要不断上传音频）。选哪个取决于要不要双向通信。
