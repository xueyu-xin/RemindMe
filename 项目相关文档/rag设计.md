# RAG 模块接口设计文档

## 1. 接口概述

本文档定义 RemindMe 项目中 RAG 模块的接口规范。RAG 模块负责：
- 用户私有记忆管理（Agent 对话中自动提取事实存储，记忆库页面提供浏览、备注编辑、删除、导出）
- 公共知识库检索（使用手册、系统说明）
- 多用户隔离（每个用户独立的记忆库）

记忆内容由 AI 在对话中按事实自动提取写入，用户不手动录入；分类与标题由 AI 自动生成，备注是用户唯一可手动编辑的字段。

## 2. 核心设计决策

### 2.1 知识检索策略
- 分库检索：公共知识库与用户私有记忆库分开检索，各司其职
  - 私有记忆检索（`retrievePrivateKnowledge`）：仅从用户私有库检索个人相关记忆
  - 公共知识检索（`retrievePublicKnowledge`）：仅从公共知识库检索使用手册相关内容（系统说明等）

### 2.2 topK 设定（动态 topK 策略）

**核心原则**：调用方指定 topK 时用调用方的；调用方传 null 时由 RAG 模块动态计算。

| 参数 | 值 | 说明 |
|------|-----|------|
| 调用方传 topK | 用调用方的值 | 完全尊重调用方指定，不截断 |
| 调用方传 null | 动态计算 | 根据用户记忆总数自动计算 |

**动态计算规则**（调用方传 null 时生效）：

| 用户记忆总数 | 动态 topK | 说明 |
|-------------|----------|------|
| < 20 条 | 5 | 新用户，记忆少，少量检索即可 |
| 20 ~ 100 条 | 10 | 正常使用阶段，多捞一些保证覆盖 |
| > 100 条 | 15 | 老用户，记忆多，但仍控制在合理范围 |

**处理流程**：
```
retrievePrivateKnowledge(userId, query, topK)
  ├─ topK != null → 使用调用方的 topK
  └─ topK == null → 查询用户记忆总数 → 按上表动态计算
```

**设计理由**：
- 对调用方透明：调用方无需关心动态逻辑，传 null 即可
- 自适应：新用户和老用户都能得到合适的检索量
- 可控：调用方仍可通过显式传 topK 来精确控制（如只想要3条）

### 2.3 用户隔离方案
- 使用 Chroma Collection 级隔离
- 公共知识库：`public_knowledge`（Spring Bean 管理，启动时自动创建）
- 用户私有库：`user_{userId}_knowledge`（懒加载创建）
- 创建方式：使用 `ChromaVectorStore.builder().initializeSchema(true)` + 手动调用 `afterPropertiesSet()`，
  确保 Collection 在首次使用时自动创建，并由 Spring AI 自动缓存 Collection ID

### 2.4 相似度门槛

私有记忆语义检索设置相似度门槛 `0.35`（`RETRIEVE_SIMILARITY_THRESHOLD`）：
- 低于 0.35 的弱相关结果直接过滤，避免"问 A 答 B"式罗列无关记忆
- 公共知识库检索不设门槛（手册内容与问题总是强相关）

### 2.5 记忆变更事件

记忆写入/删除成功后发布 `PrivateMemoryChangedEvent`（ADD/DELETE），由 `agent/event` 包下的监听器异步通过 WebSocket 推送前端，实现记忆库页面实时刷新。

## 3. 枚举定义

### 3.1 MemoryCategory（记忆分类）

共 11 类。历史版本的 DAILY_ROUTINE（日常作息）已并入 HABIT。

```java
public enum MemoryCategory {
    // ---------- 用户自身信息 ----------
    PERSONAL_INFO,    // 用户自身属性与状态（年级、专业、住址、性别等）

    // ---------- 活动/事务 ----------
    ACTIVITY,         // 近期或远期的事务（旅游、会议、实习、搬家、面试等）

    // ---------- 重要日期/纪念日 ----------
    EVENT,            // 重要日期与纪念日（生日、纪念日、考试日等）

    // ---------- 学习相关 ----------
    PLAN,             // 长期目标或计划（如"考研"、"减重 10 斤"）
    EXAM_SCHEDULE,    // 考试安排（如"6 月 20 日期末考试"）

    // ---------- 生活相关 ----------
    HEALTH_CONDITION, // 健康状况（如"对海鲜过敏"、"高血压"）
    DIET_PREFERENCE,  // 饮食偏好（如"不吃辣"、"素食"）
    HABIT,            // 个人习惯：有固定时间/频率规律的行为（如"每天早上 7 点起床"、"睡前看书"）
    INTEREST,         // 兴趣爱好：只是喜欢、无固定时间规律（如"喜欢打篮球"、"画画"）

    // ---------- 系统相关 ----------
    LANGUAGE_STYLE,   // 语言风格偏好（如"喜欢简洁回答"、"语气可爱"）

    // ---------- 其他 ----------
    OTHER             // 兜底：以上都不匹配的
}
```

### 3.2 分类详细说明

| 分类 | 定义 | 示例 |
|------|------|------|
| **PERSONAL_INFO** | 用户自身属性与状态 | "大三学生"、"计算机专业"、"住302宿舍" |
| **ACTIVITY** | 近期或远期的事务 | "国庆去长沙旅游"、"下周三社团摆摊" |
| **EVENT** | 重要日期/纪念日 | "妈妈生日10月16日"、"恋爱纪念日5月20日" |
| **PLAN** | 长期目标或计划 | "考研"、"减重10斤" |
| **EXAM_SCHEDULE** | 考试安排 | "6月20日期末考试"、"12月四级考试" |
| **HEALTH_CONDITION** | 健康状况 | "对海鲜过敏"、"近视400度" |
| **DIET_PREFERENCE** | 饮食偏好 | "不吃辣"、"喜欢甜食" |
| **HABIT** | 个人习惯：有固定时间/频率规律的行为 | "每天早上7点起床"、"睡前看书"、"每周三下午高数课" |
| **INTEREST** | 兴趣爱好：只是喜欢，无固定时间规律 | "喜欢打篮球"、"喜欢听周杰伦" |
| **LANGUAGE_STYLE** | 语言风格偏好 | "喜欢简洁回答"、"喜欢幽默风格" |
| **OTHER** | 以上都不匹配的内容 | 兜底分类 |

### 3.3 分类使用原则

分类由后端 AI（`LLMService.classifyMemoryCategory`）从枚举中自动选定，调用方只需传 content，无需选择分类。以下原则用于指导对话 LLM 拆分记忆内容：

| 原则 | 说明 | 示例 |
|------|------|------|
| **1. ACTIVITY vs EVENT** | ACTIVITY 是要做的事情（行动），EVENT 是重要日期（日期） | "要去旅游"→ACTIVITY / "妈妈生日10月16日"→EVENT |
| **2. PLAN vs ACTIVITY** | PLAN 是长期目标（几周/几个月），ACTIVITY 是具体事务（几天/几周） | "考研"→PLAN / "去旅游"→ACTIVITY |
| **3. HABIT vs INTEREST** | HABIT 有固定时间/频率规律，INTEREST 只是喜欢、无固定规律 | "每天12点半睡"→HABIT / "喜欢打篮球"→INTEREST |
| **4. 一条记忆只存一个事实点** | 检索更精确，更新更方便 | 把"大三、计算机专业、302宿舍"拆成3条记忆分别存储 |
| **5. 只记录事实，不记录情绪** | 情绪宣泄不记录，包含事实的对话才记录 | "今天好累啊"→不记录 / "明天要考四级"→记录 |
| **6. 人称规范** | 一般直接记录事实本身，不写人称；必须涉及时用"你"称呼用户，严禁"用户/我" | "大三学生" / "你喜欢打篮球" |

## 4. 数据结构

### 4.1 KnowledgeResult（知识检索结果）

```java
public class KnowledgeResult {
    private String id;                    // Chroma 文档 id（更新/删除时唯一定位）
    private String content;               // 记忆正文
    private String title;                 // AI 生成标题（≤15 字，生成失败降级为正文截断）
    private double score;                 // 相似度分数（0~1）
    private String category;              // 分类编码（MemoryCategory 枚举名）
    private String remark;                // 用户手写备注（唯一允许用户编辑的字段）
    private Long userId;                  // 所属用户 id；公共知识为 null
    private String source;                // 来源："public" 或 "private"
    private LocalDateTime createTime;     // 创建时间
    private LocalDateTime updateTime;     // 最后更新时间（新建时等于创建时间）
    private Map<String, Object> metadata; // 扩展元数据（原始 Chroma metadata）
}
```

## 5. 接口清单

### 5.1 Agent 工具（6 个，@AgentTool 注册）

| # | 工具名 | 功能说明 | 参数 | 返回值 |
|---|--------|---------|------|--------|
| 1 | `retrieve_private_knowledge` | 语义检索私有记忆；返回记忆含分类、标题、内容、备注 | `userId` (Long)<br>`query` (String)：用户问题<br>`topK` (Integer)：null 时动态计算 | `{success, message, count, memories}` |
| 2 | `add_private_memory` | 把用户说的事实信息存入记忆；只需传 content，分类与标题由 AI 自动生成 | `userId` (Long)<br>`content` (String)：记忆内容 | `{success, message, memoryId}` |
| 3 | `update_private_memory` | 更新记忆内容；先删后写，复用原 id 与创建时间，备注保留 | `userId` (Long)<br>`memoryId` (String)<br>`newContent` (String) | `{success, message, memoryId}` |
| 4 | `delete_private_memory` | 删除一条记忆 | `userId` (Long)<br>`memoryId` (String) | `{success, message}` |
| 5 | `get_all_private_memories` | 全量拉取私有记忆（按创建时间倒序） | `userId` (Long) | `{success, message, count, memories}` |
| 6 | `retrieve_public_knowledge` | 检索公共知识库（系统使用手册） | `userId` (Long)<br>`query` (String)<br>`topK` (Integer)：默认 5 | `{success, message, count, knowledge}` |

### 5.2 服务层方法（供 Controller 与 Agent 工具复用）

| # | 方法 | 功能说明 | 返回值 |
|---|------|---------|--------|
| 1 | `retrievePrivateKnowledge(userId, query, topK)` | 私有记忆语义检索，相似度 <0.35 过滤 | `List<KnowledgeResult>` |
| 2 | `addPrivateMemory(userId, content)` | 新增记忆，AI 分类+标题，发布 ADD 事件 | `String` 文档 id，失败 null |
| 3 | `updatePrivateMemory(memoryId, userId, newContent)` | 先删后写，复用原 id 与创建时间，备注保留 | `String` 文档 id，失败 null |
| 4 | `updateMemoryRemark(memoryId, userId, remark)` | 仅更新备注并刷新更新时间 | `boolean` |
| 5 | `deletePrivateMemory(memoryId, userId)` | 删除记忆，发布 DELETE 事件 | `boolean` |
| 6 | `getAllPrivateMemories(userId)` | 全量拉取，按创建时间倒序 | `List<KnowledgeResult>` |
| 7 | `retrievePublicKnowledge(query, topK)` | 公共手册检索，默认 topK=5 | `List<KnowledgeResult>` |

### 5.3 记忆库页面接口（MemoryController，/api/memory）

| # | 端点 | 功能说明 |
|---|------|---------|
| 1 | `GET /api/memory/list` | 全量记忆列表（按创建时间倒序） |
| 2 | `POST /api/memory/update-remark` | 更新备注（用户唯一可编辑字段） |
| 3 | `POST /api/memory/delete` | 删除单条记忆 |
| 4 | `POST /api/memory/batch-delete` | 批量删除，返回成功/失败计数 |
| 5 | `GET /api/memory/export` | 导出记忆（format=json/txt，文件下载由前端处理） |

### 5.4 系统内部方法（2 个）

| # | 方法 | 功能说明 | 参数 | 返回值 |
|---|------|---------|------|--------|
| 1 | `initPublicKnowledge` | 初始化公共知识库：项目启动时自动执行，检测 `public_knowledge` 是否为空，为空则从 `public_knowledge.json` 导入预置知识 | 无 | `void` |
| 2 | `getUserVectorStore(userId)` | 获取用户私有库：懒加载模式，首次访问某用户时自动创建 collection 并缓存 | `userId` (Long) | `ChromaVectorStore` |

## 6. 接口调用流程示例

### 6.1 用户提问"我明天有什么课"

```
用户提问 → Agent 判断需查记忆 → 调用 retrieve_private_knowledge(userId, "我明天有什么课", null)
         → 动态 topK 检索（相似度 <0.35 过滤）→ 返回候选记忆（含备注）
         → Agent 连同备注一起阅读 → 生成最终回答
```

### 6.2 用户说"我这学期要考四级"

```
用户输入 → Agent 判断需存记忆 → 调用 add_private_memory(userId, "6月20日期末考试")
         → 后端 AI 自动生成分类（EXAM_SCHEDULE）与标题 → 写入用户私有库 → 返回记忆 ID
```

### 6.3 用户说"我四级考过了"

```
用户输入 → Agent 先检索拿到原记忆 ID → 调用 update_private_memory(userId, memoryId, "四级考试已通过")
         → 先删后写，id 与创建时间不变 → 分类与标题按新内容重新生成 → 备注保留
```

## 7. 异常处理

| 异常场景 | 处理方式 |
|---------|---------|
| Chroma 不可用 | 自动重试（最多 3 次），仍失败返回空列表/null/false，记录错误日志 |
| Ollama（Embedding）不可用 | 返回空列表，记录警告日志 |
| AI 分类调用失败 | 降级为 OTHER |
| AI 标题生成失败 | 降级为正文开头截断（≤20 字） |
| 用户私有库不存在 | 自动创建（懒加载） |
| 权限校验失败（memoryId 不属于该用户） | 返回 null/false，记录错误日志 |
| 参数非法 | 返回空列表/null/false |

## 8. 降级策略

```
项目启动时：
  ├─ 检查 Chroma 是否可用
  ├─ 检查 Ollama 是否可用
  └─ 任一不可用 → RAG功能标记为"不可用"

运行时：
  ├─ Agent 调用工具
  ├─ 如果RAG不可用 → 返回空列表/失败提示（不抛异常）
  └─ Agent 收到空列表 → 只用 LLM 自身知识回答

AI 能力降级：
  ├─ AI 分类失败 → 归入 OTHER
  └─ AI 标题失败 → 正文截断兜底
```
