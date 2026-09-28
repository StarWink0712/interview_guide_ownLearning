# 依赖分阶段加入说明

## 原则

当前 `build.gradle` 只包含最小后端依赖。

以下版本与参考项目保持一致，不代表最新版本。只有进入对应阶段时才加入。

## 阶段 0：环境与 CRUD

当前已经启用：

```text
Spring Web MVC
Validation
Spring Data JPA
PostgreSQL Driver
Lombok
Spring Boot Test
H2
```

## 阶段 1：对象映射

业务 DTO 数量明显增加后再加入：

```groovy
implementation 'org.mapstruct:mapstruct:1.6.3'
annotationProcessor 'org.mapstruct:mapstruct-processor:1.6.3'
annotationProcessor 'org.projectlombok:lombok-mapstruct-binding:0.2.0'
```

第一遍 CRUD 可以手写转换，先理解 Entity 和 DTO 的边界。

## 阶段 2：文件解析

进入简历上传阶段再加入：

```groovy
implementation 'org.apache.tika:tika-core:2.9.2'
implementation 'org.apache.tika:tika-parsers-standard-package:2.9.2'
```

## 阶段 3：AI

同步简历分析阶段再加入：

```groovy
implementation 'org.springframework.ai:spring-ai-starter-model-openai:2.0.0-M4'
```

先配置一个 Provider，不要立即实现多 Provider 注册中心。

## 阶段 4：Redis

同步流程稳定后再加入：

```groovy
implementation 'org.redisson:redisson-spring-boot-starter:4.0.0'
```

先学习基础数据结构，再实现 Redis Stream。

## 阶段 5：RAG

知识库文本切片稳定后再加入：

```groovy
implementation 'org.springframework.ai:spring-ai-starter-vector-store-pgvector:2.0.0-M4'
```

Docker 数据库已经提前启用了 `vector` 扩展。

## 阶段 6：对象存储

本地文件存储稳定后再加入：

```groovy
implementation 'software.amazon.awssdk:s3:2.29.51'
```

业务层应依赖自己的存储接口，不直接依赖 AWS SDK。

## 阶段 7：WebSocket 和语音

文字面试稳定后再加入：

```groovy
implementation 'org.springframework.boot:spring-boot-starter-websocket'
```

ASR/TTS SDK 必须到语音阶段再选择和配置。

## 阶段 8：PDF

报告 DTO 稳定后再加入：

```groovy
implementation 'com.itextpdf:itext-core:8.0.5'
implementation 'com.itextpdf:font-asian:8.0.5'
```
