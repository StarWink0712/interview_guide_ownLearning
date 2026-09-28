不是。之前提到的三大 Map 在 `VoiceInterviewWebSocketHandler` 中，用于管理前后端 WebSocket 连接和运行时状态：

| Map                | 位置      | 存什么                                               |
| :----------------- | :------ | :------------------------------------------------ |
| `sessions`         | Handler | sessionId → 并发安全的前后端 WebSocket 连接                 |
| `sessionStates`    | Handler | sessionId → SessionState（mergeBuffer、CAS 锁、回声冷却等） |
| `lastActivityTime` | Handler | sessionId → 时间戳（用于 5 分钟超时检测）                      |

而 `sessionLocks` 在 `QwenAsrService` 里，是一个独立的 Map，专门管理服务端与 ASR 之间的连接生命周期锁。两个类的 Map 互不相关。

QwenAsrService 内部实际有两张 Map：

| Map            | 存什么                                                     |
| :------------- | :------------------------------------------------------ |
| `sessions`     | sessionId → AsrSession（OmniRealtimeConversation + 回调引用） |
| `sessionLocks` | sessionId → Object（纯锁对象，异步操作互斥用）                        |

所以总共是 **五张 Map**，分属两个类，各司其职。
