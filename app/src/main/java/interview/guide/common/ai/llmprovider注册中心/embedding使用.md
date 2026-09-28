
          
现在有了完整的上下文。Embedding 模型在项目中有**两条完全独立的使用路径**——一条写入、一条读取，下面逐一说明。

---

## 路径一：文档上传 → 异步向量化（写入）

### 前端操作

用户在知识库页面**上传 PDF/文档文件**。

### 完整流程

```
前端                                 后端                               Embedding
─────────────────────────────────────────────────────────────────────────────────
                                                                                
① 用户上传 PDF                                                                  
   POST /api/knowledgebase/upload                                               
   (MultipartFile)                                                              
         ──────────────────────────►                                            
                          ② KnowledgeBaseController                             
                            接收文件，委托                                       
                            KnowledgeBaseUploadService                          
                                                                                
                          ③ 保存文件到 S3，创建                                  
                            KnowledgeBaseEntity                                 
                            vectorStatus = PENDING                              
                                                                                
                          ④ 放入 Redis Stream 消息队列                           
                            "有新的向量化任务"                                    
                                                                                
                          ⑤ 返回前端：                                           
                            "上传成功，正在处理中"                                 
                                                                                
⑥ 前端显示"向量化中..."                                                          
                                                                                
                    ═══════════ 异步处理 ═══════════                              
                                                                                
                          ⑦ VectorizeStreamConsumer                             
                            从 Redis Stream 取消息                               
                            状态: PENDING → PROCESSING                          
                                                                                
                          ⑧ 通过 Tika 解析下载文件内容                            
                            提取纯文本                                           
                                                                                
                          ⑨ KnowledgeBaseVectorService.                         
                            vectorizeAndStore(kbId, content)                     
                                                                                
                          ⑩ TokenTextSplitter 分块                              
                            800 tokens/块，按标点切分                             
                                                                                
                          ⑪ 分批调用 vectorStore.add()                           
                            每批最多 10 个 chunk                                 
                            VectorStore 内部自动调用                              
                            EmbeddingModel.call()                     ────────►  
                                                                 ⑫ LLM 厂商 API  
                                                                   POST /v1/embeddings
                                                                   {input: ["文本1","文本2"...]}
                                                                   ← 返回 1024 维向量
                          ⑬ 向量写入 pgvector                                   
                            vector_store 表                                      
                            (id, content, embedding, metadata)                   
                                                                                
                          ⑭ 状态: PROCESSING → COMPLETED                        
                            chunkCount = N                                      
                                                                                
⑮ 前端轮询或刷新列表，显                                                          
   示"向量化完成"                                                                 
```

### 关键代码对应

| 步骤 | 文件 |
|------|------|
| 上传 | [KnowledgeBaseUploadService](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseUploadService.java) |
| 异步消费 | [VectorizeStreamConsumer](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/knowledgebase/listener/VectorizeStreamConsumer.java) |
| 分块+向量化 | [KnowledgeBaseVectorService.vectorizeAndStore()](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseVectorService.java#L63-L98) |

---

## 路径二：用户提问 → RAG 检索（读取）

### 前端操作

用户在知识库问答页面**输入问题并发送**。

### 完整流程

```
前端                                 后端                               Embedding
─────────────────────────────────────────────────────────────────────────────────
                                                                                
① 用户输入："HashMap底层原理？"                                                   
   POST /api/knowledgebase/query                                                
   {query: "HashMap底层原理？",                                                   
    knowledgeBaseIds: [1, 3]}                                                    
         ──────────────────────────►                                            
                          ② KnowledgeBaseController                              
                            委托 KnowledgeBaseQueryService                       
                                                                                
                          ③ 可选：Query Rewrite                                  
                            用 LLM 改写问题提升检索效果                            
                            "HashMap底层原理？"                                   
                            → "Java HashMap的数据结构和                                                       
                               哈希冲突解决方案"                                   
                                                                                
                          ④ vectorService.similaritySearch()                     
                            参数: query, kbIds, topK, minScore                  
                                                                                
                          ⑤ VectorStore.similaritySearch()                       
                            内部先调用 EmbeddingModel ——►                        
                                        ⑥ POST /v1/embeddings                   
                                          {input: "HashMap底层原理"}             
                                          ← 返回 1024 维向量                     
                                                                                
                          ⑦ pgvector COSINE 距离计算                             
                            SELECT * FROM vector_store                          
                            ORDER BY embedding <=> 问题向量                      
                            LIMIT topK                                          
                            ← 最相似的前 N 个文档块                               
                                                                                
                          ⑧ 构建 Prompt                                          
                            System: 你是知识库助手...                              
                            User: 参考以下资料回答:                               
                               [文档块1]                                          
                               [文档块2]                                          
                               ...                                              
                               问题: HashMap底层原理？                             
                                                                                
                          ⑨ 调用 ChatClient (LLM)                     ────────►  
                                        ⑩ POST /v1/chat/completions             
                                          {model: qwen-plus,                    
                                           messages: [system, user]}             
                                          ← 返回自然语言回答                      
                                                                                
                          ⑪ QueryResponse                                       
                            {answer: "HashMap底层基于                            
                             数组+链表/红黑树...",                                 
                             sources: [{文档名, 相似度}]}                         
                                                                                
                          ⑫ 返回前端                        ◄──────────────────    
                                                                                
⑬ 前端展示回答 + 参考来源                                                         
```

### 关键代码对应

| 步骤 | 文件 |
|------|------|
| 问答 API | [KnowledgeBaseController](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/knowledgebase/KnowledgeBaseController.java) |
| RAG 查询核心 | [KnowledgeBaseQueryService](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseQueryService.java) |
| 向量检索 | [KnowledgeBaseVectorService.similaritySearch()](file:///Users/ogawaaya/Desktop/学习/interview_guide/interview-guide-optimize/app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseVectorService.java) |

---

## 路径对比总结

| | 写入路径（向量化） | 读取路径（RAG 检索） |
|---|---|---|
| **触发器** | 用户上传文档 | 用户提问 |
| **API** | `POST /api/knowledgebase/upload` | `POST /api/knowledgebase/query` |
| **模式** | **异步**（Redis Stream） | **同步**（实时） |
| **Embedding 作用** | 把文档块全部转成向量存库 | 把问题转成向量去库中搜索 |
| **Embedding 调用次数** | N 次（每 10 个 chunk 一批） | 1 次（仅问题向量化） |
| **后续步骤** | 向量写入 pgvector | 相似度搜索 → LLM 生成回答 |
| **前端体验** | 上传 → "处理中" → "完成" | 提问 → 流式回答 + 参考来源 |

**核心要点**：Embedding 是 RAG 的"桥梁"——把文档和问题都转成同一数学空间的向量，然后通过向量距离算相似度，找到最相关的文档喂给 LLM。