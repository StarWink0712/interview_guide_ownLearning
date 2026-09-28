# parse：面试邀约文本解析完整调用链

接口：

```text
POST /api/interview-schedule/parse
```

该接口只负责把邮件、短信或会议邀请解析成 `CreateInterviewRequest`，不会创建数据库记录。用户确认并保存表单后，前端才会再调用创建接口。

---

## 1. 前端触发

添加面试时，`InterviewFormModal` 初始处于 `text` 步骤：

```text
点击“添加面试”
  → InterviewSchedulePage.handleAddClick()
  → mode=create，打开 InterviewFormModal
  → 用户粘贴 rawText
  → 点击“解析文本”
  → InterviewFormModal.handleParse()
```

`handleParse()` 只检查 `rawText.trim()` 非空，然后调用：

```typescript
interviewScheduleApi.parse(rawText)
```

前端 API 组装：

```json
{
  "rawText": "用户粘贴的文本"
}
```

虽然 API 支持 `source`，当前弹窗没有传，所以正常页面请求中的 `source` 是 `undefined`。

---

## 2. Controller 参数绑定与校验

```java
@PostMapping("/parse")
public Result<ParseResponse> parse(
    @Valid @RequestBody ParseRequest request) {
  ParseResponse response = parseService.parse(
      request.getRawText(), request.getSource());
  return Result.success(response);
}
```

`ParseRequest.rawText` 有：

```java
@NotBlank(message = "文本不能为空")
```

空字符串或纯空白会在进入 Controller 方法前触发参数校验异常：

```text
MethodArgumentNotValidException
  → GlobalExceptionHandler
  → Result.error(BAD_REQUEST, "文本不能为空")
  → Axios reject
  → Modal catch
  → 人工构造 success=false 的 ParseResponse
```

Controller 没有限流注解，也没有长度上限校验。

---

## 3. Service 总体降级策略

```text
InterviewParseService.parse(rawText, source)
  → 再次防御性检查空文本
  → tryRuleParsing(rawText, source)
      → isValidResult?
          ├─ 是：rule 成功，confidence=0.95
          └─ 否：进入 AI
  → parseWithAI(rawText, source)
      → isValidResult?
          ├─ 是：ai 成功，confidence=0.8
          └─ 否：返回失败 DTO
```

有效结果要求三个字段都不是 null：

```text
companyName
position
interviewTime
```

这里只检查 null，不检查字符串是否为空白。因此 AI 返回 `"companyName":""` 时可能被判定为解析成功，但后续创建接口的 `@NotBlank` 会拒绝。

---

## 4. 规则解析分发

### 4.1 显式 source

```text
source=feishu  → 只先尝试 parseFeishu
source=tencent → 只先尝试 parseTencent
source=zoom    → 只先尝试 parseZoom
```

规则结果不完整时仍会进入 AI。

### 4.2 没有 source

当前前端走这条路径：

```text
文本包含 飞书/Feishu/meeting.feishu.cn
  → parseFeishu，完整则返回

文本包含 腾讯会议/Tencent Meeting/会议号
  → parseTencent，完整则返回

文本包含 Zoom/zoom.us
  → parseZoom，完整则返回

仍未成功
  → 按 Feishu → Tencent → Zoom 全部尝试
```

不同平台的规则解析均先构造空 `CreateInterviewRequest`，能提取多少就填多少，异常时返回部分对象。

---

## 5. 飞书规则链

`parseFeishu()` 依次提取：

```text
时间/时段：yyyy-MM-dd HH:mm 或 yyyy/MM/dd HH:mm
  → parseDateTime()

https://meeting.feishu.cn/...
  → meetingLink

公司/单位/组织：...
  → companyName

岗位/职位/职务：...
  → position

第N轮/第N场
  → parseRoundNumber()

interviewType 固定设为 VIDEO
```

规则成功必须同时提取公司、岗位、时间；只有会议链接和时间不算成功。

### 5.1 中文轮次的当前实现问题

`parseRoundNumber()` 使用：

```java
CHINESE_NUMBERS.getOrDefault(
    num,
    Integer.parseInt(num.replaceAll("\\D", "")))
```

Java 会先计算 `getOrDefault` 的默认参数。若 `num="二"`，`Integer.parseInt("")` 会先抛 `NumberFormatException`，即使 Map 中存在“二”。异常被 `parseFeishu()` 外层 catch 捕获，已解析字段仍返回，但轮次通常保留请求对象默认值 1。

所以源码注释中的“第二轮 → 2”目前并不可靠；阿拉伯数字“第3轮”可以正常解析。

---

## 6. 腾讯会议规则链

`parseTencent()` 提取：

```text
yyyy-MM-dd HH:mm
  → interviewTime

会议号/ID + 至少 9 位数字
密码 + 至少 4 位数字
  → 拼入 meetingLink 文本

公司/单位：...
岗位/职位：...
interviewType=VIDEO
```

会议号和密码不是转换成 URL，而是组合成描述字符串。代码使用 `matcher.group()` 而不是只取数字的 `group(1)`，因此可能形成：

```text
会议号: 会议号：123456789 密码: 密码：1234
```

若腾讯邀请不包含公司或岗位，规则结果无效，继续调用 AI。

---

## 7. Zoom 规则链

`parseZoom()` 只提取：

```text
https://zoom.us/j/...
日期 yyyy-MM-dd 或 yyyy/MM/dd
时间 H:mm/HH:mm
interviewType=VIDEO
```

它没有公司和岗位正则，所以纯 Zoom 系统邀请通常无法满足三个必填字段，会继续进入 AI。链接正则也只匹配 `zoom.us/j/` 这一种形式。

---

## 8. AI 解析的完整链路

规则结果不完整后：

```text
parseWithAI(rawText, source)
  → currentDate = LocalDateTime.now() 的日期
  → promptSanitizer.sanitize(rawText)
  → PromptSecurityConstants.DATA_BOUNDARY_INSTRUCTION
  → promptSanitizer.wrapWithDelimiters("parse-input", safeText)
  → String.format(PARSE_PROMPT, currentDate, boundedText)
  → llmProviderRegistry.getChatClientOrDefault(source)
  → chatClient.prompt().user(prompt).call().content()
```

Prompt 要求模型返回纯 JSON，包括公司、岗位、ISO 时间、类型、链接、轮次、面试官和备注。

这里使用普通 `ChatClient`，不是 `getPlainChatClient()`，也没有使用 `StructuredOutputInvoker` 做结构化输出重试。

### 8.1 source 被同时当成 LLM Provider ID

`parse()` 的第二个参数语义是来源平台，但它被原样传给 `parseWithAI()` 的 `provider` 参数：

```text
source="feishu"
  → getChatClientOrDefault("feishu")
```

Registry 会把非空字符串当成 Provider ID，而不是回退默认 Provider。如果系统没有名为 `feishu`、`tencent`、`zoom` 或 `other` 的 LLM Provider，AI 分支会抛异常，然后被 catch 转为 null。

当前弹窗不传 source，因此页面正常走 `null → 默认 LLM Provider`，暂时避开了这个问题；其他客户端显式传平台来源时会影响 AI 降级。

---

## 9. AI 响应解析

```text
content 为空
  → 返回 null

content 包含 ```
  → 正则尝试剥离 ```json ... ```

ObjectMapper.readValue(jsonContent, Map<String,Object>)
  → 空 Map 返回 null
  → 逐字段复制到 CreateInterviewRequest
```

字段处理：

- `interviewTime` 长度 16 时补 `:00`，否则直接 `LocalDateTime.parse()`。
- `roundNumber` 解析失败时设置 1。
- 其他字段调用 `toString().trim()`。
- 任意网络、Registry、JSON 或未知异常都在大 catch 中记录并返回 null。

因此 AI 服务失败通常不会走全局异常处理器，而是退化成业务数据 `ParseResponse.success=false`。

---

## 10. ParseResponse 的三类结果

### 10.1 规则成功

```json
{
  "success": true,
  "data": { "companyName": "...", "position": "..." },
  "confidence": 0.95,
  "parseMethod": "rule",
  "log": "规则解析成功"
}
```

置信度是固定值，不是统计模型计算结果。

### 10.2 AI 成功

```json
{
  "success": true,
  "data": { "companyName": "...", "position": "..." },
  "confidence": 0.8,
  "parseMethod": "ai",
  "log": "AI 解析成功"
}
```

### 10.3 全部失败

```json
{
  "success": false,
  "data": null,
  "confidence": 0.0,
  "parseMethod": "none",
  "log": "解析失败"
}
```

三者外层都是 `Result.success(response)`。

---

## 11. 返回弹窗后的闭环

成功：

```text
Modal.setParseResult(result)
  → setFormData({...result.data})
  → step=parse-result
  → 展示公司、岗位、时间、会议和置信度
  → 用户点击“确认并编辑”
  → step=form
  → 用户检查/修改
  → 点击保存
  → 才调用 POST /api/interview-schedule 创建日程
```

失败：

```text
显示解析失败和 log
  → 用户可重新输入
  → 或点击“手动输入”进入表单
```

解析与创建分成两个请求，避免 AI 结果未经用户确认就直接落库。

---

## 12. 边界总结

- 不写数据库，不去重，也不检查同一公司/时间是否已有日程。
- 没有限流和文本长度限制，AI 分支是同步外部调用。
- 规则优先可以减少 LLM 成本和延迟。
- AI 异常被吸收为 `success=false`，前端仍能进入手工录入。
- 当前 source/Provider 参数混用、中文轮次解析和腾讯会议字符串拼接都应按源码真实行为理解。
