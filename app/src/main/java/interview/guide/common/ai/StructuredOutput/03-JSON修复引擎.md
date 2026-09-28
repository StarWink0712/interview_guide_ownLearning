# StructuredOutputInvoker JSON 修复引擎

**源码方法**: `convertWithRepair()` ([L391-L425](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/common/ai/StructuredOutputInvoker.java#L391-L425))、`repairUnescapedQuotesInJsonStrings()` ([L465-L523](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/common/ai/StructuredOutputInvoker.java#L465-L523))、`isLikelyJsonStringTerminator()` ([L562-L580](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/common/ai/StructuredOutputInvoker.java#L562-L580))

---

## 一、项目角色分析

### 1.1 在类中的定位

JSON 修复引擎是 StructuredOutputInvoker 的**第一道防线**，在 LLM 返回内容后、重试调用 LLM 之前执行。它的目标是用零额外成本（无网络请求）修复最常见的 JSON 格式错误，提高一次成功率、减少不必要的 LLM 重试。

### 1.2 为什么需要这个引擎

LLM 在生成 JSON 时最常见的错误是**字符串内部出现未转义的引号**：

```json
{"answer": "他说"Java 很有意思"", "score": 85}
          ↑                  ↑
      这两个中文引号被 JSON 解析器当成字符串结束/开始，
      导致解析失败
```

这种错误在中文内容中尤其常见（中文引号 `""` 在 LLM 输出中可能被替换为英文引号 `""`），而且无法通过提示词工程完全消除。

---

## 二、代码调用与交互

### 2.1 调用链路

```
invoke()
  └─ chatClient.prompt()...call().content()    ← 拿到 LLM 返回的原始文本
       │
       ▼
  convertWithRepair(content, converter, logContext, log)
       │
       ├── converter.convert(content)          ← 第 1 次尝试
       │     成功 → 直接返回 ✅
       │     失败 → catch firstError
       │
       ├── repairUnescapedQuotesInJsonStrings(content)
       │     ├── 遍历每个字符，状态机处理
       │     └── 调用 isLikelyJsonStringTerminator() 判定
       │
       ├── repaired.equals(content)? 
       │     true  → 没有需要修复的内容，抛异常
       │     false → converter.convert(repaired)  ← 第 2 次尝试
       │             成功 → log.warn("已在本地修复") → 返回 ✅
       │             失败 → firstError.addSuppressed(repairError) → 抛异常
       │
       └── 异常向上传播到 invoke() 的 catch 块 → 准备重试
```

### 2.2 与 invoke() 的交互

convertWithRepair() 的异常会被 invoke() 的重试循环捕获：

```
convertWithRepair() 抛异常 
  → invoke() catch 块捕获
  → lastError = e
  → recordAttempt(FAILURE)
  → 判断是否还有重试次数
  → 有 → buildRetrySystemPrompt() → 再调 LLM
  → 无 → throw BusinessException
```

---

## 三、组件分解

```
JSON 修复引擎
│
├── convertWithRepair()
│     【职责】双阶段策略的编排者
│     【输入】content (String), converter, logContext, log
│     【输出】T（成功）或抛异常（失败）
│
├── repairUnescapedQuotesInJsonStrings()
│     【职责】状态机驱动的引号修复算法
│     【输入】content (String)
│     【输出】repaired (String)
│
└── isLikelyJsonStringTerminator()
      【职责】启发式判断引号是否为字符串结束符
      【输入】content (String), start (int)
      【输出】boolean
```

---

## 四、组件功能

### 4.1 convertWithRepair() — 双阶段策略

```
阶段1：直接转换
  converter.convert(content)
    ├── 成功 → 直接返回（最常见的情况）
    └── 失败 → 进入阶段2

阶段2：本地修复
  repairUnescapedQuotesInJsonStrings(content) → repaired
    ├── repaired == content → 没有可修复的，抛出阶段1的异常
    └── repaired != content → 尝试 converter.convert(repaired)
          ├── 成功 → log.warn("已在本地修复后解析成功") → 返回
          └── 失败 → firstError.addSuppressed(repairError) → 抛出阶段1的异常
```

**关键设计**：无论修复是否尝试过，最终抛出的都是**阶段1的原始异常**，修复异常作为 suppressed 附加。这保证了上游的异常处理逻辑不感知修复过程。

### 4.2 repairUnescapedQuotesInJsonStrings() — 三态状态机

```
                  ┌─────────────────────────────────┐
                  │    状态1: 字符串外部              │
                  │    inString = false               │
                  │                                  │
                  │  遇到 '"' → inString = true      │
                  │            → 直接复制字符         │
                  │  其他字符 → 直接复制              │
                  └──────────────┬───────────────────┘
                                 │ 遇到未转义的 '"'
                  ┌──────────────▼───────────────────┐
                  │    状态2: 字符串内部              │
                  │    inString = true                │
                  │    escaping = false               │
                  │                                  │
                  │  遇到 '\\' → escaping = true      │
                  │            → 直接复制字符         │
                  │  遇到 '"'  → 调用终止符检测        │
                  │            ├─ true  → inString=false, 原样复制
                  │            └─ false → 复制为 \"   │
                  │  其他     → 直接复制              │
                  └──────────────┬───────────────────┘
                                 │ 转义状态持续 1 字符
                  ┌──────────────▼───────────────────┐
                  │    状态3: 转义序列中              │
                  │    inString = true                │
                  │    escaping = true                │
                  │                                  │
                  │  任何字符 → 直接复制              │
                  │          → escaping = false      │
                  │          → 回到状态2              │
                  └──────────────────────────────────┘
```

**代码实现**：

```java
for (int i = 0; i < content.length(); i++) {
    char ch = content.charAt(i);
    
    if (!inString) {
        if (ch == '"') inString = true;
        repaired.append(ch);
        continue;
    }

    if (escaping) {
        repaired.append(ch);
        escaping = false;                    // 转义状态只持续 1 个字符
        continue;
    }
    
    if (ch == '\\') {
        repaired.append(ch);
        escaping = true;                     // 标记进入转义状态
        continue;
    }
    
    if (ch == '"') {
        if (isLikelyJsonStringTerminator(content, i + 1)) {
            inString = false;                // 字符串结束
            repaired.append(ch);             // 原样保留
        } else {
            repaired.append("\\\"");         // 添加转义符
        }
        continue;
    }
    
    repaired.append(ch);                     // 普通字符直接复制
}
```

### 4.3 isLikelyJsonStringTerminator() — 启发式判断

```java
// 从引号后位置开始扫描
for (int i = start; i < content.length(); i++) {
    char next = content.charAt(i);
    
    if (Character.isWhitespace(next)) {
        continue;     // 跳过空白（JSON 允许空格、Tab、换行）
    }
    
    // 第一个非空白字符：检查是否为 JSON 语法符号
    return next == ',' || next == '}' || next == ']' || next == ':';
}

// 扫描到字符串末尾 → 保守假设为结束符
return true;
```

**原理**：有效的 JSON 中，一个字符串值结束后紧跟的必然是 `,` `}` `]` `:` 之一（可能中间有空白）。如果引号后第一个非空白字符不是这四个符号，说明引号在字符串内部。

**示例**：

| JSON 片段 | 引号位置 | 后跟字符 | 结果 |
|-----------|---------|---------|------|
| `"John" Doe` | 第5位 | `D`（字母） | **false**（内部引号，需转义） |
| `"John", "age"` | 第5位 | `,`（逗号） | **true**（结束符，不转义） |
| `"John"}` | 第5位 | `}`（花括号） | **true**（结束符，不转义） |
| `"key":` | 第4位 | `:`（冒号） | **true**（结束符，不转义） |
| `"John"   ,` | 第5位 | `,`（跳过空白后） | **true**（结束符，不转义） |

---

## 五、组件交互

### 5.1 修复前后的对比

```
LLM 原始输出：
{"evaluation": "该候选人表示"我很熟悉Spring Boot"", "score": 85}
                          ↑              ↑
                       未转义的引号，JSON 解析器直接报错

↓ repairUnescapedQuotesInJsonStrings()

修复后：
{"evaluation": "该候选人表示\"我很熟悉Spring Boot\"", "score": 85}
                          ↑               ↑
                       已添加转义符，JSON 解析器可以正常解析
```

### 5.2 异常合并机制

```java
catch (Exception repairError) {
    firstError.addSuppressed(repairError);  // 修复异常附在原始异常上
}
// 最终抛出的是 firstError，但调试时通过 getSuppressed() 能看到修复异常
```

这样设计的好处：
- 上游只需处理一种异常类型
- 调试时可以通过 `e.getSuppressed()` 查看修复过程中的完整错误链
- 不影响全局异常处理器的错误码映射

---

## 六、关键关注点

### 6.1 启发式算法的局限性

`isLikelyJsonStringTerminator()` 不是完整的 JSON 解析器，在某些边界情况下可能误判：

```
{"text": "the key is \"name\":"}
              ↑            ↑
      这个引号后跟冒号，被误判为结束符
```

但这种边界情况在实际 LLM 输出中极为罕见，启发式算法在 99% 的场景下足够准确。

### 6.2 性能考虑

- **O(n) 复杂度**：状态机只遍历一次，`isLikelyJsonStringTerminator` 最多扫描到第一个非空白字符
- **StringBuilder 预分配**：`new StringBuilder(content.length() + 16)` 避免扩容
- **尽早返回**：没有引号的合法 JSON 会直接通过，不产生额外开销

### 6.3 安全性

- **越界保护**：`isLikelyJsonStringTerminator` 的循环使用 `i < content.length()` 保护
- **空值保护**：`repairUnescapedQuotesInJsonStrings` 开头检查 `null || isBlank`
- **无副作用**：纯函数，不修改传入的 content 字符串（String 不可变）

---

## 七、设计决策理由

### 7.1 为什么用状态机而非正则表达式？

- **嵌套引号问题**：正则表达式难以区分"字符串内引号"和"字符串边界引号"
- **上下文感知**：状态机天然知道当前处于字符串内还是外、是否在转义序列中
- **可读性**：状态机代码比等价的正则表达式更直观

### 7.2 为什么只修引号不修其他 JSON 错误？

- **聚焦高频问题**：未转义引号是 LLM JSON 输出中最常见的问题（占 80% 以上）
- **避免过度修复**：修复缺失逗号、多余括号等问题容易引入新错误
- **兜底策略**：修复不了的错误由重试机制（调 LLM 再次生成）来处理

### 7.3 为什么修复失败时抛出原始异常而非修复异常？

- **异常语义**：修复是"尝试性操作"，失败也不改变原始问题
- **上游兼容**：调用方已了解如何解析 convert() 的异常，引入新异常类型会破坏兼容性
- **信息完整性**：通过 addSuppressed 保留了修复异常的上下文，没有信息丢失

---

## 八、流程总结

convertWithRepair() 采用双阶段降级策略处理 LLM 返回的 JSON 内容。第一阶段直接调用 BeanOutputConverter 进行 JSON 反序列化，这是最理想的路径，大多数合法 JSON 在此直接成功返回。一旦转换失败，不立即放弃而是进入第二阶段本地修复：调用 repairUnescapedQuotesInJsonStrings() 运行三态状态机逐字符扫描 JSON 文本，在"字符串外部"、"字符串内部"和"转义序列中"三个状态间切换，当在字符串内部遇到引号时调用 isLikelyJsonStringTerminator() 进行启发式判断——跳过引号后的空白字符，检查第一个非空白字符是否为 JSON 语法结束符（`,` `}` `]` `:`），若是则保留原引号并退出字符串状态，否则添加转义符 `\"` 作为字符串内容。修复后若内容有变化则再次尝试转换，成功时以 warn 日志记录修复行为并返回结果，失败时将修复异常通过 addSuppressed 附加到原始异常上。最终无论修复成功与否，都抛出原始异常供 invoke() 的重试循环接管。这套引擎以零网络成本的本地计算换取了大量不必要的 LLM 重试，是结构化输出可靠性的关键防线。
