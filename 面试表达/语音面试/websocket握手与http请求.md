浏览器原生websocket本质是先通过一次http1.1请求，然后再通过upgrade把tcp连接从http升级到websocket长连接，从普通的http的请求响应通信方式变成websocket帧的持续双向通信。

前端先通过一次http upgrade握手建立连接，带有对象头upgrade:websocket表示想把连接从http升级到websocket；还有一个sec-websocket-key字段，是浏览器随机生成的base64字符串，服务端根据这个字符串计算一个accept密钥返回给浏览器，密钥的算法是固定的算法，依据RFC6455，作用是验证服务端确实理解了http要生产成websocket；还会带上originweb页面，浏览器原生websocket可以发起跨域连接，所以需要把发起web连接的页面的网页给服务端，看服务端是否允许其连接。

第二步服务端返回101 switching protocols响应，状态码是101表示接受升级。成功后前后端就是websocket帧双向通信。

浏览器先发送一个特殊的 HTTP/1.1 GET 请求，
通过 Upgrade: websocket 请求协议升级；
服务端校验请求后，计算 Sec-WebSocket-Accept，
返回 HTTP 101 Switching Protocols；
从此这条 TCP/TLS 连接脱离普通 HTTP 请求响应模式，
进入 WebSocket 双向帧通信模式。

<br />

因为两者的**调用模式不同**。

### TTS：请求-响应模式（一问一回）

```
synthesize("你好")
  → 建立 TTS WebSocket → 发送文本 → [等待合成完成] → 返回完整 WAV 字节数组
```

Handler 调用 `synthesize()` 时，必须拿到完整的音频才能推给前端。这是一次函数调用，必须等结果回来才能继续。所以需要 `CountDownLatch` 做异步转同步。

### ASR：持续流式模式（一直跑着）

ASR 的 `startTranscripton()` 只是把连接建好、回调注册好，就立马返回了。之后 DashScope 什么时候推结果完全是**异步事件驱动**的——用户说半句话推 partial，说完一句话推 completed，这个过程可能持续 30 分钟。

对比一下就很清楚了：

```java
// TTS：同步调用，当场要结果
byte[] wav = ttsService.synthesize("你好");  // 此行阻塞，等合完才往下走
sendAudio(session, wav, text);                // 拿到结果才能发

// ASR：异步注册，回调来了再处理
sttService.startTranscription(                // 此行立即返回，不留恋
    sessionId,
    text -> handleSttResult(...),             // 最终结果 → 回调处理
    text -> handleSttResult(...),             // 中间结果 → 回调处理
    error -> sendError(...)                   // 报错 → 回调处理
);
// 方法到这里就结束了，后续结果全部走回调
```

简单说：TTS 是"帮我做一件事，做完告诉我结果"，ASR 是"帮我盯着这个流，有东西了通知我"。前者需要等，后者不需要。
