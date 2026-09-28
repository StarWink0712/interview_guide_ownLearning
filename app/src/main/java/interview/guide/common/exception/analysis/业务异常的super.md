这两个 `super()` 做的事情完全一样——把异常消息传给父类 `RuntimeException`，设置到 `Throwable.detailMessage` 字段中，让堆栈日志能显示具体原因。

区别在于**消息来源不同**：

```java
// 构造器 1：使用 ErrorCode 内置的默认消息
public BusinessException(ErrorCode errorCode) {
    super(errorCode.getMessage());       // ← 父类 message = ErrorCode 里写好的默认文案
    this.code = errorCode.getCode();     // ← 子类 code = 从 ErrorCode 拿
    this.message = errorCode.getMessage(); // ← 子类 message = 和父类一样（给前端看）
}

// 构造器 2：使用调用方自定义的消息
public BusinessException(ErrorCode errorCode, String message) {
    super(message);                      // ← 父类 message = 你传的自定义文案
    this.code = errorCode.getCode();     // ← 子类 code = 从 ErrorCode 拿
    this.message = message;              // ← 子类 message = 你传的自定义文案（覆盖默认的）
}
```

### 使用场景对比

```java
// 场景 1：默认消息就够了
throw new BusinessException(ErrorCode.RESUME_NOT_FOUND);
// → 日志显示 "简历不存在"
// → 前端收到 "简历不存在"

// 场景 2：需要更具体的消息
throw new BusinessException(ErrorCode.RESUME_NOT_FOUND, "用户 ID=123 的简历不存在");
// → 日志显示 "用户 ID=123 的简历不存在"（方便排查具体问题）
// → 前端收到 "用户 ID=123 的简历不存在"（用户知道是哪个简历出了问题）
```

super让业务异常在终端中也打印一下，给开发者看。

把整个数据流向拆开看就更清楚了：

```
throw new BusinessException(ErrorCode.RESUME_NOT_FOUND, "用户 ID=123 的简历不存在")
    │
    ├── super("用户 ID=123 的简历不存在")  → 写入 Throwable.detailMessage
    │       │                                 ↓
    │       │                         全局异常处理器 catch 到后打日志：
    │       │                         log.error("业务异常", e)
    │       │                                 ↓
    │       │                         控制台输出：
    │       │                         BusinessException: 用户 ID=123 的简历不存在
    │       │                                          ↑ 这就是 super() 提供的内容
    │
    └── this.code = 2001
        this.message = "用户 ID=123 的简历不存在" → 返回给前端的 Result 里
```

**简单来说**：

- `super(message)` → 给**控制台日志**看（开发者排障用）
- `this.code / this.message` → 给**前端 JSON** 看（用户看提示用）

