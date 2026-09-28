浏览器原生 WebSocket 的握手，本质上是：**先发起一次 HTTP/1.1 请求，然后通过 `Upgrade` 机制把这条 TCP 连接从 HTTP 升级成 WebSocket 连接**。握手成功后，这条连接就不再按普通 HTTP 请求/响应通信，而是按 WebSocket 帧持续双向通信。WebSocket 协议由 RFC 6455 标准化，浏览器端通过 `new WebSocket(url, protocols?)` 发起连接。([IETF Datatracker][1])

---

## 1. 浏览器代码层面发生了什么

前端通常这样写：

```js
const ws = new WebSocket("ws://localhost:8080/chat");

ws.onopen = () => {
  ws.send("hello");
};

ws.onmessage = (event) => {
  console.log(event.data);
};
```

这一行代码：

```js
new WebSocket("ws://localhost:8080/chat")
```

执行后，浏览器会立刻尝试建立连接。这个过程不是直接发送 WebSocket 数据帧，而是先走一次 **HTTP Upgrade 握手**。MDN 对 `WebSocket()` 构造函数的说明也是：创建对象后会立即尝试建立连接。([MDN Web Docs][2])

---

## 2. 握手第一步：浏览器发起 HTTP Upgrade 请求

假设前端执行：

```js
const ws = new WebSocket("ws://example.com/chat");
```

浏览器可能会发出类似这样的 HTTP 请求：

```http
GET /chat HTTP/1.1
Host: example.com
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Key: x3JJHMbDL1EzLkh9GBhXDw==
Sec-WebSocket-Version: 13
Origin: http://example.com
```

这仍然是一个 HTTP 请求，但是它的目的不是获取页面或 JSON，而是告诉服务器：

> 我现在想把这条 HTTP 连接升级成 WebSocket 连接。

几个核心字段如下。

### `GET /chat HTTP/1.1`

WebSocket 握手使用 HTTP/1.1 请求。路径 `/chat` 就是服务端 WebSocket 端点地址。

例如：

```js
new WebSocket("ws://example.com/chat");
```

对应的请求路径就是：

```http
GET /chat HTTP/1.1
```

如果 URL 带参数：

```js
new WebSocket("ws://example.com/chat?token=abc");
```

则请求行会变成：

```http
GET /chat?token=abc HTTP/1.1
```

---

### `Upgrade: websocket`

这个字段表示客户端希望升级协议：

```http
Upgrade: websocket
```

它的意思是：

> 服务端，如果你支持 WebSocket，请把当前连接从 HTTP 升级为 WebSocket。

HTTP 的 `Upgrade` 头就是用于在已有连接上切换协议的机制，例如从 HTTP 切到 WebSocket。([MDN Web Docs][3])

---

### `Connection: Upgrade`

这个字段表示当前连接上存在协议升级行为：

```http
Connection: Upgrade
```

`Upgrade: websocket` 和 `Connection: Upgrade` 通常要配合使用。前者说明要升级到什么协议，后者说明这个连接要执行升级动作。MDN 对 HTTP 协议升级机制的说明中，也把这两个头作为 Upgrade 流程的核心部分。([MDN Web Docs][4])

---

### `Sec-WebSocket-Key`

这是 WebSocket 握手中非常关键的字段：

```http
Sec-WebSocket-Key: x3JJHMbDL1EzLkh9GBhXDw==
```

它是浏览器随机生成的一个 Base64 字符串。服务端不能原样返回它，而是要根据它计算一个 `Sec-WebSocket-Accept` 返回给浏览器。RFC 6455 明确规定，`Sec-WebSocket-Key` 用于 WebSocket opening handshake，服务端需要用它生成响应中的 `Sec-WebSocket-Accept`。([IETF Datatracker][1])

注意：`Sec-WebSocket-Key` **不是密码，也不是 token，也不是加密密钥**。它的作用主要是验证服务端确实理解 WebSocket 握手规则，避免普通 HTTP 服务被误当成 WebSocket 服务。MDN 也说明它不提供真正的安全性，而是帮助防止非 WebSocket 客户端意外请求升级。([MDN Web Docs][5])

---

### `Sec-WebSocket-Version: 13`

这个字段表示客户端使用的 WebSocket 协议版本：

```http
Sec-WebSocket-Version: 13
```

现在浏览器基本都使用 RFC 6455 对应的版本，也就是 `13`。服务端通常要检查这个字段，如果不是 `13`，可能会拒绝连接。

---

### `Origin`

浏览器还会带上 `Origin`：

```http
Origin: http://example.com
```

这个字段表示发起 WebSocket 请求的页面来源。它非常重要，因为浏览器原生 WebSocket **可以跨域发起连接**，不像普通 `fetch` 那样依赖完整的 CORS 预检流程。因此，服务端应该主动校验 `Origin`，判断是否允许这个网页来源连接自己的 WebSocket 服务。

例如服务端可以只允许：

```text
https://www.my-site.com
```

拒绝：

```text
https://evil-site.com
```

---

## 3. 握手第二步：服务端返回 101 Switching Protocols

如果服务端接受升级，会返回类似这样的响应：

```http
HTTP/1.1 101 Switching Protocols
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Accept: HSmrc0sMlYUkAGmm5OPpG2HaGWk=
```

核心是状态码：

```http
101 Switching Protocols
```

它表示：

> 协议切换成功，从现在开始，这条连接不再按普通 HTTP 处理，而是按 WebSocket 协议处理。

MDN 的 WebSocket API 文档也说明，`Sec-WebSocket-Accept` 是服务端在 opening handshake 中用来表示愿意升级为 WebSocket 连接的响应头。([MDN Web Docs][6])

---

## 4. `Sec-WebSocket-Accept` 是怎么计算的

服务端收到客户端的：

```http
Sec-WebSocket-Key: x3JJHMbDL1EzLkh9GBhXDw==
```

然后执行固定算法：

```text
Sec-WebSocket-Accept =
Base64(
  SHA1(
    Sec-WebSocket-Key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
  )
)
```

其中这个 GUID 是 WebSocket 协议规定的固定字符串：

```text
258EAFA5-E914-47DA-95CA-C5AB0DC85B11
```

例如 RFC 6455 中的经典例子是：

```http
Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==
```

服务端计算后返回：

```http
Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
```

这个计算规则来自 RFC 6455，服务端需要把客户端的 `Sec-WebSocket-Key` 与固定 GUID 拼接，做 SHA-1，再 Base64 编码后返回。([IETF Datatracker][1])

---

## 5. 为什么要有 `Sec-WebSocket-Key / Accept`

这个机制主要解决两个问题。

第一，**证明服务端真的理解 WebSocket 协议**。如果客户端随便连到一个普通 HTTP 服务，普通服务不会知道如何计算 `Sec-WebSocket-Accept`，浏览器就会认为握手失败。

第二，**防止普通 HTTP 请求被误升级**。WebSocket 握手虽然看起来像 HTTP 请求，但它要求一组特殊字段和特殊计算流程。这样可以减少协议误用。

但是它不解决身份认证问题。也就是说：

```http
Sec-WebSocket-Key
Sec-WebSocket-Accept
```

只能说明“这是一次合法格式的 WebSocket 握手”，不能说明“这个用户是谁”。

用户认证仍然要靠：

```text
Cookie
Session
JWT
Token
自定义业务鉴权
```

---

## 6. 握手成功后发生什么

一旦服务端返回：

```http
HTTP/1.1 101 Switching Protocols
```

浏览器会触发：

```js
ws.onopen = () => {
  console.log("连接成功");
};
```

从这一刻开始，连接进入 WebSocket 通信阶段。

也就是说：

握手前：

```text
浏览器  -- HTTP Upgrade 请求 -->  服务器
浏览器  <-- HTTP 101 响应 ----  服务器
```

握手后：

```text
浏览器  <==== WebSocket 帧双向通信 ====>  服务器
```

这时就不再是传统 HTTP 的“一问一答”模型，而是全双工通信：浏览器可以随时发消息，服务器也可以随时推消息。MDN 对 WebSocket API 的描述也是：它允许浏览器与服务器建立双向交互式通信会话，不需要轮询服务器。([MDN Web Docs][6])

---

## 7. 带子协议的握手：`Sec-WebSocket-Protocol`

浏览器原生 WebSocket 构造函数还支持第二个参数：

```js
const ws = new WebSocket("ws://example.com/chat", "chat.v1");
```

或者：

```js
const ws = new WebSocket("ws://example.com/chat", [
  "chat.v1",
  "chat.v2"
]);
```

浏览器会在请求中带上：

```http
Sec-WebSocket-Protocol: chat.v1, chat.v2
```

这表示：

> 客户端支持这些应用层子协议，服务端请选择一个。

服务端如果选择 `chat.v1`，需要在响应中返回：

```http
Sec-WebSocket-Protocol: chat.v1
```

`Sec-WebSocket-Protocol` 用于在 WebSocket 握手时协商通信子协议，例如自定义聊天协议、消息协议、RPC 协议等。MDN 也说明这个头用于在 opening handshake 中协商要使用的子协议。([MDN Web Docs][7])

注意，子协议不是 HTTP 协议版本，而是你业务层自己约定的协议名称。

---

## 8. 带压缩扩展的握手：`Sec-WebSocket-Extensions`

浏览器可能还会自动带上：

```http
Sec-WebSocket-Extensions: permessage-deflate; client_max_window_bits
```

这表示客户端支持 WebSocket 消息压缩扩展。服务端如果也支持，可以在响应中返回：

```http
Sec-WebSocket-Extensions: permessage-deflate
```

之后双方就可以对 WebSocket 消息进行压缩传输。

这个字段一般由浏览器和 WebSocket 库自动处理，业务代码通常不需要手写。

---

## 9. `ws://` 和 `wss://` 的区别

WebSocket 有两个常见协议头：

```text
ws://   普通 WebSocket，底层是明文 TCP
wss://  加密 WebSocket，底层是 TLS
```

它们和 HTTP/HTTPS 的关系类似：

```text
http://  对应 ws://
https:// 对应 wss://
```

如果页面本身是 HTTPS，那么浏览器通常要求 WebSocket 也使用 `wss://`，否则可能被浏览器当成混合内容拦截。

`wss://` 的流程可以理解为：

```text
先建立 TCP
再进行 TLS 握手
然后在 TLS 加密通道里发送 HTTP Upgrade 请求
最后升级为 WebSocket
```

所以 `wss://` 并不是跳过 WebSocket 握手，而是把 WebSocket 握手放进了 TLS 加密通道中。

---

## 10. 浏览器原生 WebSocket 不能随便设置请求头

很多人会想这样写：

```js
new WebSocket("ws://example.com/chat", {
  headers: {
    Authorization: "Bearer xxx"
  }
});
```

但浏览器原生 WebSocket API 不支持这样传自定义 HTTP 请求头。标准的构造函数参数是：

```js
new WebSocket(url)
new WebSocket(url, protocols)
```

第二个参数是子协议，不是 headers。WHATWG WebSocket 标准也说明，WebSocket 接口不提供底层网络的原始访问能力。([WebSockets 标准][8])

所以浏览器端常见鉴权方式是：

```js
new WebSocket("wss://example.com/chat?token=xxx");
```

或者依赖 Cookie：

```http
Cookie: SESSION=xxxx
```

也可以把 token 放进第一个连接后的业务消息里：

```js
ws.onopen = () => {
  ws.send(JSON.stringify({
    type: "auth",
    token: "xxx"
  }));
};
```

实际项目中更推荐使用：

```text
wss:// + Cookie / 短期 token / 首包鉴权
```

并且服务端必须校验 `Origin`。

---

## 11. 一次完整握手示例

前端代码：

```js
const ws = new WebSocket(
  "wss://api.example.com/ws/chat?roomId=1001",
  ["chat.v1"]
);
```

浏览器可能发出：

```http
GET /ws/chat?roomId=1001 HTTP/1.1
Host: api.example.com
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==
Sec-WebSocket-Version: 13
Sec-WebSocket-Protocol: chat.v1
Sec-WebSocket-Extensions: permessage-deflate; client_max_window_bits
Origin: https://www.example.com
Cookie: SESSION=abc123
```

服务端校验：

```text
1. 请求方法是否是 GET
2. 是否包含 Upgrade: websocket
3. 是否包含 Connection: Upgrade
4. Sec-WebSocket-Version 是否为 13
5. Sec-WebSocket-Key 是否合法
6. Origin 是否允许
7. Cookie / token 是否通过鉴权
8. 子协议是否支持 chat.v1
```

然后服务端返回：

```http
HTTP/1.1 101 Switching Protocols
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
Sec-WebSocket-Protocol: chat.v1
Sec-WebSocket-Extensions: permessage-deflate
```

之后连接升级完成，双方进入 WebSocket 帧通信阶段。

---

## 12. Java 后端视角怎么理解

如果你用 Java 写服务端，比如 Spring WebSocket、Netty、Tomcat WebSocket，本质上框架帮你做了这些事：

```text
接收 HTTP Upgrade 请求
校验 WebSocket 握手头
计算 Sec-WebSocket-Accept
返回 101 Switching Protocols
把连接交给 WebSocket Handler
后续解析 WebSocket frame
```

你业务层通常只看到：

```java
afterConnectionEstablished()
handleTextMessage()
afterConnectionClosed()
```

但底层一定经历了上面的 HTTP Upgrade 握手。

用伪代码表示服务端核心逻辑：

```java
String key = request.getHeader("Sec-WebSocket-Key");

String accept = base64(
    sha1(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
);

response.setStatus(101);
response.setHeader("Upgrade", "websocket");
response.setHeader("Connection", "Upgrade");
response.setHeader("Sec-WebSocket-Accept", accept);
```

真实项目中不建议自己手写完整 WebSocket 协议处理，通常交给框架或 Netty 这类网络库。

---

## 13. 总结成一句话

浏览器原生 WebSocket 握手就是：

```text
浏览器先发送一个特殊的 HTTP/1.1 GET 请求，
通过 Upgrade: websocket 请求协议升级；
服务端校验请求后，计算 Sec-WebSocket-Accept，
返回 HTTP 101 Switching Protocols；
从此这条 TCP/TLS 连接脱离普通 HTTP 请求响应模式，
进入 WebSocket 双向帧通信模式。
```

可以把它理解成：

```text
HTTP 只是负责“开门和确认身份”
WebSocket 才是后面真正“长期通信”的协议
```

[1]: https://datatracker.ietf.org/doc/html/rfc6455?utm_source=chatgpt.com "RFC 6455 - The WebSocket Protocol"
[2]: https://developer.mozilla.org/en-US/docs/Web/API/WebSocket/WebSocket?utm_source=chatgpt.com "WebSocket() constructor - Web APIs - MDN Web Docs"
[3]: https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Upgrade?utm_source=chatgpt.com "Upgrade header - HTTP - MDN Web Docs"
[4]: https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/Protocol_upgrade_mechanism?utm_source=chatgpt.com "Protocol upgrade mechanism - HTTP - MDN Web Docs - Mozilla"
[5]: https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Sec-WebSocket-Key?utm_source=chatgpt.com "Sec-WebSocket-Key header - HTTP - MDN Web Docs"
[6]: https://developer.mozilla.org/en-US/docs/Web/API/WebSockets_API?utm_source=chatgpt.com "WebSocket API (WebSockets) - Web APIs - MDN Web Docs"
[7]: https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Sec-WebSocket-Protocol?utm_source=chatgpt.com "Sec-WebSocket-Protocol header - HTTP - MDN Web Docs"
[8]: https://websockets.spec.whatwg.org/?utm_source=chatgpt.com "WebSockets Standard"
