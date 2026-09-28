Grafana 是一个开源的**数据可视化与监控平台**，它本身不存数据，而是从数据源（如 Prometheus）拉取指标，以图表、仪表盘的形式展示。

在这个项目里的链路是：

```
Micrometer（内存中累积指标）
    ↓ /actuator/prometheus 暴露端点
Prometheus（定时拉取并存储指标）
    ↓ 作为数据源
Grafana（从 Prometheus 查询，渲染成仪表盘）
```

最终效果就是一块大屏上能看到折线图（某 Provider 的调用量趋势）、柱状图（各错误类型分布）、仪表盘（实时成功率）、P99 延迟曲线等，运维人员不用查数据库就能一眼看到系统是否健康。

**独立的中间件。** Grafana 是一个独立的 Web 服务，有自己的端口（默认 3000），和你的应用完全分离。它通过 HTTP 请求从 Prometheus（默认端口 9090）拉数据渲染图表，不跟前端代码有任何集成。

整个监控栈的结构：

```
你的应用 (:8080)
  → Micrometer 内存指标
  → /actuator/prometheus 暴露
  ↓ HTTP pull
Prometheus (:9090)   ← 独立服务，定时拉取存储
  ↓
Grafana (:3000)      ← 独立服务，从 Prometheus 读数据画图
  ↓
浏览器访问 Grafana 看仪表盘
```

前端想嵌入 Grafana 图表的话，通常是用 `<iframe>` 嵌一个 Grafana 面板链接，或者直接跳转 Grafana 地址，不存在代码层面的集成。