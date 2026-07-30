# RAG 模块接口设计文档

## 1. 接口概述

本文档定义 RemindMe 项目中 RAG 模块的接口规范。RAG 模块负责：
- 用户私人记忆管理（添加、更新、删除、查询）
- 公共知识库检索（使用手册、系统说明）
- 多用户隔离（每个用户独立的记忆库）

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
- 对队友透明：调用方无需关心动态逻辑，传 null 即可
- 自适应：新用户和老用户都能得到合适的检索量
- 可控：调用方仍可通过显式传 topK 来精确控制（如只想要3条）

### 2.3 用户隔离方案
- 使用 Chroma Collection 级隔离
- 公共知识库：`public_knowledge`（Spring Bean 管理，启动时自动创建）
- 用户私有库：`user_{userId}_knowledge`（懒加载创建）
- 创建方式：使用 `ChromaVectorStore.builder().initializeSchema(true)` + 手动调用 `afterPropertiesSet()`，
  确保 Collection 在首次使用时自动创建，并由 Spring AI 自动缓存 Collection ID

## 3. 枚举定义

### 3.1 MemoryCategory（记忆分类）
```java
public enum MemoryCategory {
    // ========== 用户自己的信息 ==========
    PERSONAL_INFO,    // 用户自己的属性和状态（年级、专业、住址、性别等）
    
    // ========== 活动/事务 ==========
    ACTIVITY,         // 用户要做或正在做的事情（旅游、会议、实习等）
    
    // ========== 重要日期/纪念日 ==========
    EVENT,            // 重要日期/纪念日（生日、结婚纪念日等）
    
    // ========== 学习相关 ==========
    PLAN,             // 长期目标或计划（如"考研究生"、"减肥10斤"、"制定21天四级复习计划"）
    EXAM_SCHEDULE,    // 考试安排（如"6月20日期末考试"）
    
    // ========== 生活相关 ==========
    DAILY_ROUTINE,    // 日常作息习惯（如"每天早上7点起床"）
    HEALTH_CONDITION, // 健康状况（如"用户对海鲜过敏"）
    DIET_PREFERENCE,  // 饮食偏好（如"用户不吃辣"）
    HABIT,            // 个人习惯（如"喜欢睡前看书"）
    INTEREST,         // 兴趣爱好（如"喜欢打篮球"）
    
    // ========== 系统相关 ==========
    LANGUAGE_STYLE,   // 语言风格偏好（如"用户喜欢简洁回答"）
    
    // ========== 其他 ==========
    OTHER             // 兜底：以上都不属于的
}
```

### 3.2 分类详细说明（给队友的调用指南）

> 以下是每个分类的详细说明和示例，帮助调用方（队友的LLM）正确选择分类。

| 分类 | 定义 | 示例 |
|------|------|------|
| **PERSONAL_INFO** | 用户自己的属性和状态 | "用户是大三学生"、"用户是计算机专业"、"用户住在302宿舍" |
| **ACTIVITY** | 用户要做或正在做的事情 | "用户要去旅游"、"用户明天下午有会议"、"用户正在实习" |
| **EVENT** | 重要日期/纪念日 | "女朋友生日5月20日"、"用户妈妈生日10月1日"、"用户生日3月15日" |
| **PLAN** | 长期目标或计划 | "考研究生"、"减肥10斤"、"制定21天四级复习计划" |
| **EXAM_SCHEDULE** | 考试安排 | "6月20日期末考试"、"12月四级考试" |
| **DAILY_ROUTINE** | 日常作息习惯 | "每天早上7点起床"、"晚上11点睡觉" |
| **HEALTH_CONDITION** | 健康状况 | "用户对海鲜过敏"、"用户近视400度" |
| **DIET_PREFERENCE** | 饮食偏好 | "用户不吃辣"、"用户喜欢甜食" |
| **HABIT** | 个人习惯 | "用户喜欢睡前看书"、"用户每天跑步" |
| **INTEREST** | 兴趣爱好 | "用户喜欢打篮球"、"用户喜欢听周杰伦" |
| **LANGUAGE_STYLE** | 语言风格偏好 | "用户喜欢简洁回答"、"用户喜欢幽默风格" |
| **OTHER** | 以上都不属于的内容 | 兜底分类 |

### 3.3 分类使用原则

> 以下原则是给调用方（队友的LLM）看的，指导如何正确调用 `addPrivateMemory` 接口。

| 原则 | 说明 | 示例 |
|------|------|------|
| **1. ACTIVITY vs EVENT** | ACTIVITY是用户参与的事情（行动），EVENT是重要日期/纪念日（日期） | "我要去旅游"→ACTIVITY / "女朋友生日5月20日"→EVENT |
| **2. PLAN vs ACTIVITY** | PLAN是长期目标或计划（几周/几个月），ACTIVITY是具体行动或事务（几天/几周） | "考研究生"→PLAN / "去旅游"→ACTIVITY |
| **3. 一条记录只存一个事实点** | 检索更精确，更新更方便 | 把"大三、计算机专业、302宿舍"拆成3条记录分别调用addPrivateMemory |
| **4. 信息零碎时逐步存储** | 用户不会一次性说所有信息，LLM逐步提取并调用接口 | 第一次"我是大三学生"→PERSONAL_INFO，第二次"计算机专业"→PERSONAL_INFO |
| **5. 只记录事实，不记录情绪** | 情绪宣泄不记录，包含事实的对话才记录 | "今天好累啊"→不记录 / "我明天要考四级"→记录(EXAM_SCHEDULE) |
| **6. 有多少信息就记录多少** | 即使信息很少，只要是事实就记录，后续可以补充 | "我要考研究生"→记录(PLAN)，后续补充"目标院校是XX大学"→记录(PLAN) |

## 4. 数据结构

### 4.1 KnowledgeResult（知识检索结果）
```java
public class KnowledgeResult {
    private String id;                    // 记忆ID（更新/删除时需要）
    private String content;               // 知识内容（LLM拼prompt用）
    private double score;                 // 相似度分数（0~1）
    private String source;                // 来源："public" 或 "private"
    private MemoryCategory category;      // 分类枚举
    private LocalDateTime createTime;     // 创建时间
}
```

## 5. 接口清单

### 5.1 给队友的接口（6个）

| # | 接口名 | 功能说明 | 参数 | 返回值 |
|---|--------|---------|------|--------|
| 1 | `retrievePrivateKnowledge` | 检索私人记忆库：从用户私有库中检索相关记忆 | `userId` (Long)：用户ID<br>`query` (String)：用户问题，如"我明天有什么课"<br>`topK` (Integer)：返回条数，传null动态计算，传具体值用该值 | `List<KnowledgeResult>`：知识列表 |
| 2 | `addPrivateMemory` | 添加私人记忆：把用户的个人信息存入其私有库 | `userId` (Long)：用户ID<br>`content` (String)：记忆内容<br>`category` (MemoryCategory)：分类枚举 | `String`：生成的记忆ID |
| 3 | `updatePrivateMemory` | 更新私人记忆内容 | `memoryId` (String)：要更新的记忆ID<br>`userId` (Long)：用户ID（权限校验）<br>`newContent` (String)：新内容 | `boolean`：是否成功 |
| 4 | `deletePrivateMemory` | 删除私人记忆 | `memoryId` (String)：要删除的记忆ID<br>`userId` (Long)：用户ID（权限校验） | `boolean`：是否成功 |
| 5 | `getAllPrivateMemories` | 获取用户所有私人记忆 | `userId` (Long)：用户ID | `List<KnowledgeResult>`：该用户所有记忆 |
| 6 | `retrievePublicKnowledge` | 检索公共知识库：从公共知识库中检索使用手册、系统说明等内容 | `query` (String)：查询关键词<br>`topK` (Integer)：返回条数 | `List<KnowledgeResult>`：相关知识列表 |

### 5.2 给自己用的接口（2个）

| # | 接口名 | 功能说明 | 参数 | 返回值 |
|---|--------|---------|------|--------|
| 7 | `initPublicKnowledge` | 初始化公共知识库：项目启动时自动执行，检测 `public_knowledge` 是否为空，为空则从本地文件导入预置知识 | 无 | `void` |
| 8 | `createUserCollection` | 创建用户私有库：懒加载模式，首次给某用户存记忆时自动创建 | `userId` (Long)：用户ID | `void` |

## 6. 接口调用流程示例

### 6.1 用户提问"我明天有什么课"

```
用户提问 → 队友LLM判断需查知识库 → 调用 retrievePrivateKnowledge(userId, "我明天有什么课", 5)
         → 返回知识列表 → 队友LLM拼入prompt → 生成最终回答
```

### 6.2 用户说"我这学期要考四级"

```
用户输入 → 队友LLM判断需存记忆 → 调用 addPrivateMemory(userId, "用户2024年秋季学期要考英语四级", EXAM_SCHEDULE)
         → 返回记忆ID → 队友保存用于后续更新
```

### 6.3 用户说"我四级考过了"

```
用户输入 → 队友找到原记忆ID → 调用 updatePrivateMemory(memoryId, userId, "用户2024年秋季学期考英语四级已通过")
         → 记忆内容更新为已完成状态
```

## 7. 异常处理

| 异常场景 | 处理方式 |
|---------|---------|
| Chroma 不可用 | 返回空列表，记录警告日志 |
| Ollama 不可用 | 返回空列表，记录警告日志 |
| 用户私有库不存在 | 自动创建（懒加载） |
| 权限校验失败 | 返回 false，记录错误日志 |
| 参数非法 | 返回空列表或 false |

## 8. 降级策略

```
项目启动时：
  ├─ 检查 Chroma 是否可用
  ├─ 检查 Ollama 是否可用
  └─ 任一不可用 → RAG功能标记为"不可用"

运行时：
  ├─ 队友调用接口
  ├─ 如果RAG不可用 → 返回空列表（不抛异常）
  └─ 队友收到空列表 → 只用LLM回答
```
