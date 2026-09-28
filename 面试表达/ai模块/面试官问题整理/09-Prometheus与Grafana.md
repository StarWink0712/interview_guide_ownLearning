# Prometheus 与 Grafana

## 1. Grafana 是什么？它是否保存监控数据？

**回答：** Grafana 是开源的数据可视化和监控平台，本身不负责保存项目指标，而是从 Prometheus 等数据源查询数据，再以折线图、柱状图和仪表盘等方式展示。

## 2. Micrometer、Prometheus 和 Grafana 的关系是什么？

**回答：** 应用先通过 Micrometer 在内存中累计指标，再通过 `/actuator/prometheus` 暴露；Prometheus 定时通过 HTTP Pull 拉取并保存这些指标；Grafana 把 Prometheus 配置成数据源，查询指标并渲染成监控仪表盘。完整链路是“应用 Micrometer → Actuator 端点 → Prometheus → Grafana”。

## 3. Grafana 是项目内部组件还是独立中间件？

**回答：** Grafana 是独立部署的 Web 服务，默认端口是 3000；Prometheus 也是独立服务，默认端口是 9090；业务应用运行在自己的端口，例如 8080。Grafana 通过 HTTP 从 Prometheus 查询数据，与项目前端代码没有直接依赖关系。

## 4. 运维大盘可以展示哪些 LLM 指标？

**回答：** 可以展示某个 Provider 的调用量趋势、不同错误类型分布、实时成功率、HTTP 延迟和 P99 延迟曲线、重试次数等信息。这样运维人员不需要逐条查询数据库，也能快速判断模型调用链路是否健康。

## 5. 前端如何嵌入 Grafana 面板？

**回答：** 前端通常使用 `<iframe>` 嵌入某个 Grafana 面板链接，或者直接跳转到 Grafana 地址查看。它不是把 Grafana 代码集成进前端，而是展示独立 Grafana 服务提供的页面。
