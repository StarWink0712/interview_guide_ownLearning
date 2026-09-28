# 03 React 前端闭环

## 本阶段目标

建立一个最小前端，只完成面试日程：

```text
查看列表
新增日程
修改状态
删除日程
```

重点不是页面美观，而是理解：

```text
页面事件
  → API 函数
  → HTTP
  → 后端 Controller
  → Service
  → 数据库
  → JSON
  → 页面状态更新
```

## 1. 创建项目

在 `interview-guide-rebuild` 目录下创建：

```bash
pnpm create vite frontend --template react-ts
cd frontend
pnpm install
pnpm add axios react-router-dom
```

第一版不要加入图表、动画、日历库和状态管理框架。

## 2. 建立最小目录

```text
frontend/src/
├── api/
│   ├── request.ts
│   └── interviewSchedule.ts
├── pages/
│   └── InterviewSchedulePage.tsx
├── types/
│   └── interviewSchedule.ts
├── App.tsx
└── main.tsx
```

## 3. 先写 TypeScript 类型

路径：

```text
src/types/interviewSchedule.ts
```

按后端 JSON 定义：

```ts
export type InterviewStatus =
  | 'PENDING'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'RESCHEDULED'

export interface InterviewSchedule {
  id: number
  companyName: string
  position: string
  interviewTime: string
  interviewType?: string
  meetingLink?: string
  roundNumber?: number
  interviewer?: string
  notes?: string
  status: InterviewStatus
  createdAt: string
  updatedAt: string
}

export interface Result<T> {
  code: number
  message: string
  data: T
}
```

先定义数据形状，再写 API 和页面。

## 4. 写 Axios 实例

路径：

```text
src/api/request.ts
```

职责：

```text
统一 baseURL
统一超时时间
统一判断业务 code
```

开发环境 baseURL：

```ts
baseURL: 'http://localhost:8081/api'
```

响应拦截器中：

```text
code = 0 → 返回 data
code != 0 → Promise.reject(new Error(message))
```

不要让每个页面重复判断 `result.code`。

## 5. 写日程 API

路径：

```text
src/api/interviewSchedule.ts
```

按顺序实现：

```text
getInterviewSchedules
createInterviewSchedule
updateInterviewStatus
deleteInterviewSchedule
```

每个函数只负责 HTTP，不操作 React 状态。

## 6. 处理 CORS

后端增加：

```text
common/config/CorsConfig.java
```

开发环境只允许：

```text
http://localhost:5173
```

允许常用方法：

```text
GET
POST
PUT
PATCH
DELETE
OPTIONS
```

不要为了省事在正式配置中永久使用任意来源。

## 7. 页面按四小步写

路径：

```text
src/pages/InterviewSchedulePage.tsx
```

### 第一步：只显示静态标题

确认 Vite 可以启动：

```bash
pnpm dev
```

### 第二步：加载列表

只实现：

```text
loading
error
schedules
useEffect
loadSchedules()
```

页面先用简单表格，不做日历。

### 第三步：新增表单

第一版字段只保留：

```text
companyName
position
interviewTime
```

提交成功后重新调用 `loadSchedules()`。

### 第四步：状态和删除

实现两个按钮：

```text
标记已完成
删除
```

每完成一步就打开页面验证，不要一次写完整页面。

## 8. 错误和加载状态

页面必须至少显示：

```text
正在加载
加载失败
暂无日程
提交失败
```

不要只在 `console.log` 中记录错误。

## 9. 验证完整闭环

同时启动：

```bash
# 后端
./gradlew bootRun

# 前端
cd frontend
pnpm dev
```

在页面中新增一条记录，然后检查：

1. 浏览器 Network 中请求是否正确。
2. 后端日志是否收到请求。
3. PostgreSQL 是否新增记录。
4. 刷新页面后数据是否仍存在。
5. 参数为空时页面是否展示后端错误消息。

## 10. 本阶段暂时不做

- 全局状态管理
- TailwindCSS
- 动画
- 日历视图
- 表单组件库
- 登录认证
- WebSocket
- 前端复杂目录抽象

## 验收标准

- [ ] 前端能加载真实后端数据。
- [ ] 能新增、修改状态、删除。
- [ ] Axios 统一处理业务错误码。
- [ ] 页面有 loading、empty 和 error 状态。
- [ ] 浏览器无 CORS 错误。
- [ ] 刷新页面后数据库数据仍存在。
- [ ] `pnpm build` 通过。

全部完成后，进入 [04-简历与AI.md](04-简历与AI.md)。
