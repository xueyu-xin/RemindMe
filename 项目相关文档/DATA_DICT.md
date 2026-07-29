# 校园生活提醒助手 — 数据字典 v1.1


## 一、文档说明

### 1.1 文档用途

本文档用于：
1. **统一字段命名**：确保前后端、数据库使用完全一致的字段名
2. **明确数据类型**：定义每个字段的Java类型、数据库类型、JSON传输类型
3. **约束与默认值**：明确字段的必填性、唯一性、默认值、取值范围
4. **可扩展性设计**：预留扩展字段，避免后续修改表结构
5. **开发对齐**：后端实体类、前端DTO、数据库DDL三方一一对应

### 1.2 命名规范

| 层级 | 命名规范 | 示例 |
|------|----------|------|
| 数据库表名 | 小写 + 下划线分隔（snake_case） | `user_dialog_stats` |
| 数据库字段名 | 小写 + 下划线分隔（snake_case） | `confirm_status` |
| Java类名 | 大驼峰（PascalCase） | `UserDialogStats` |
| Java字段名 | 小驼峰（camelCase） | `confirmStatus` |
| JSON字段名 | 小驼峰（camelCase） | `confirmStatus` |
| 枚举值 | 大写 + 下划线分隔 | `PERIOD_DEFAULTED` |

### 1.3 通用字段规范

所有表统一包含以下审计字段：

| 字段名 | 数据库字段 | Java类型 | JSON类型 | 必填 | 默认值 | 说明 |
|--------|-----------|----------|----------|------|--------|------|
| createdAt | `created_at` | `LocalDateTime` | `String (ISO-8601)` | 是 | `CURRENT_TIMESTAMP` | 创建时间，不可修改 |
| updatedAt | `updated_at` | `LocalDateTime` | `String (ISO-8601)` | 是 | `CURRENT_TIMESTAMP` | 更新时间，自动更新 |

### 1.4 字段定义与代码生成规范

#### 1.4.1 核心原则

> **数据字典是唯一数据源（Single Source of Truth）** 。所有字段的增、删、改必须先更新数据字典，再同步到代码中。禁止在代码中直接添加数据字典中没有的字段。

#### 1.4.2 字段变更流程

```
变更需求提出 → 更新数据字典 → 更新建表DDL → 更新实体类 → 更新DTO → 更新Mapper XML → 更新前端接口 → 测试验证
```

每一步完成后，在数据字典的"变更记录"表中标记完成状态。

#### 1.4.3 字段对齐检查清单

**每次修改数据库/代码前，必须检查以下项是否完全一致：**

| 层级 | 文件位置 | 字段来源 | 检查重点 |
|------|----------|----------|----------|
| 数据字典 | `DATA_DICT.md` | 唯一权威来源 | 字段名、类型、约束、默认值、说明 |
| 数据库DDL | `schema.sql` 或手动建表 | 与数据字典一致 | 字段名（snake_case）、类型、长度、默认值、索引 |
| Java实体类 | `entity/*.java` | 与数据字典一致 | 字段名（camelCase）、`@Column`注解、类型、`@Enumerated` |
| Java DTO | `dto/*.java` | 与数据字典一致 | 字段名（camelCase）、类型、校验注解 |
| Mapper XML | `mapper/*.xml` | 与实体类一致 | `resultMap`的`column`和`property`映射 |
| Repository | `repository/*.java` | 与实体类一致 | 方法名中的字段名（如`findByUserId`） |
| Service | `service/*.java` | 与实体类一致 | 业务逻辑中使用的字段名 |
| Controller | `controller/*.java` | 与DTO一致 | 请求/响应中的字段名 |
| 前端 | `*.html` / `*.js` | 与DTO一致 | JSON字段名（camelCase） |

#### 1.4.4 命名映射规则（关键）

**数据库 → Java → 前端 的命名转换规则必须统一：**

| 层级 | 命名风格 | 示例 | 转换规则 |
|------|----------|------|----------|
| 数据库字段 | snake_case（小写+下划线） | `confirm_status` | — |
| Java字段 | camelCase（小驼峰） | `confirmStatus` | 去掉下划线，后续单词首字母大写 |
| JSON字段 | camelCase（小驼峰） | `confirmStatus` | 与Java字段完全一致 |
| Java类名 | PascalCase（大驼峰） | `ReminderService` | 首字母大写 |
| 数据库表名 | snake_case（小写+下划线） | `push_logs` | — |

**字段映射速查表（常用规则）：**

| 数据库字段 | Java字段 | JSON字段 | 说明 |
|-----------|----------|----------|------|
| `user_id` | `userId` | `userId` | 外键字段 |
| `created_at` | `createdAt` | `createdAt` | 时间戳字段 |
| `confirm_status` | `confirmStatus` | `confirmStatus` | 状态字段 |
| `default_advance_minutes` | `defaultAdvanceMinutes` | `defaultAdvanceMinutes` | 复合词字段 |
| `enable_period_reminder` | `enablePeriodReminder` | `enablePeriodReminder` | 布尔字段 |
| `last_period_start` | `lastPeriodStart` | `lastPeriodStart` | 日期字段 |

#### 1.4.5 各类文件中的写法规范

##### 1.4.5.1 数据库DDL（SQL）

```sql
-- 字段名：snake_case
-- 表名：snake_case，复数形式
CREATE TABLE push_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    reminder_id BIGINT NOT NULL,          -- snake_case，_分隔
    user_id BIGINT NOT NULL,
    pushed_at DATETIME NOT NULL,           -- 时间字段用_at后缀
    user_read TINYINT(1) NOT NULL DEFAULT 0,
    read_at DATETIME DEFAULT NULL,
    push_channel VARCHAR(20) NOT NULL DEFAULT 'WEBSOCKET',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (reminder_id) REFERENCES reminders(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
```

##### 1.4.5.2 Java实体类（Entity）

```java
@Entity
@Table(name = "push_logs")
public class PushLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 数据库字段 reminder_id → Java字段 reminderId
    @Column(name = "reminder_id", nullable = false)
    private Long reminderId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "pushed_at", nullable = false)
    private LocalDateTime pushedAt;

    @Column(name = "user_read", nullable = false)
    private Boolean userRead = false;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "push_channel", nullable = false)
    @Enumerated(EnumType.STRING)
    private PushChannel pushChannel = PushChannel.WEBSOCKET;

    // 审计字段
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // 必须包含：无参构造器、全参构造器（可选）、Getter/Setter
}
```

##### 1.4.5.3 请求DTO（Request）

```java
public class ReminderCreateRequest {
    @NotNull(message = "用户ID不能为空")
    private Long userId;                    // 与实体类一致

    @NotBlank(message = "事项名称不能为空")
    @Size(max = 255, message = "事项名称不超过255个字符")
    private String title;

    @NotNull(message = "提醒时间不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private LocalDateTime remindTime;

    private String timezone = "Asia/Shanghai";

    @Min(value = 5, message = "提前量至少5分钟")
    @Max(value = 60, message = "提前量最多60分钟")
    private Integer advanceMinutes;

    private String cronExpression;
    private String location;

    @Pattern(regexp = "LOW|NORMAL|HIGH", message = "优先级只能为LOW/NORMAL/HIGH")
    private String priority = "NORMAL";
}
```

##### 1.4.5.4 响应DTO（Response）

```java
public class ReminderResponse {
    private Long id;
    private String title;
    private LocalDateTime remindTime;
    private String location;
    private String priority;
    private String status;
    private String confirmStatus;
    private LocalDateTime createdAt;

    // 只返回必要字段，不返回敏感/内部字段
}
```

##### 1.4.5.5 Mapper XML（MyBatis）

```xml
<!-- resultMap：column必须与数据库一致，property必须与实体类一致 -->
<resultMap id="ReminderResultMap" type="com.reminder.entity.Reminder">
    <id column="id" property="id"/>
    <result column="user_id" property="userId"/>
    <result column="title" property="title"/>
    <result column="remind_time" property="remindTime"/>
    <result column="timezone" property="timezone"/>
    <result column="advance_minutes" property="advanceMinutes"/>
    <result column="cron_expression" property="cronExpression"/>
    <result column="location" property="location"/>
    <result column="priority" property="priority"/>
    <result column="status" property="status"/>
    <result column="confirm_status" property="confirmStatus"/>
    <result column="confirm_attempts" property="confirmAttempts"/>
    <result column="last_confirm_remind_at" property="lastConfirmRemindAt"/>
    <result column="source" property="source"/>
    <result column="created_at" property="createdAt"/>
    <result column="updated_at" property="updatedAt"/>
</resultMap>

<!-- SQL片段：必须与数据库字段完全一致 -->
<sql id="Base_Column_List">
    id, user_id, title, remind_time, timezone, advance_minutes, 
    cron_expression, location, priority, status, confirm_status,
    confirm_attempts, last_confirm_remind_at, source,
    created_at, updated_at
</sql>
```

##### 1.4.5.6 Repository（Spring Data JPA）

```java
@Repository
public interface ReminderRepository extends JpaRepository<Reminder, Long> {
    // 方法名中的字段名必须与实体类字段名一致（camelCase）
    List<Reminder> findByUserIdAndStatus(Long userId, ReminderStatus status);

    List<Reminder> findByUserIdAndRemindTimeBetween(Long userId, LocalDateTime start, LocalDateTime end);

    @Query("SELECT r FROM Reminder r WHERE r.userId = :userId AND r.status = 'PENDING' AND r.remindTime <= :now")
    List<Reminder> findPendingReminders(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
```

##### 1.4.5.7 前端JavaScript

```javascript
// JSON字段名必须与后端DTO一致（camelCase）
const reminderData = {
    userId: 1,
    title: "明天下午3点还书",
    remindTime: "2026-07-19 15:00:00",
    advanceMinutes: 10
};

// 响应解析
fetch('/api/reminders/create', { ... })
    .then(res => res.json())
    .then(data => {
        // data.id, data.conflict.hasConflict, data.conflict.suggestedTime
        if (data.conflict && data.conflict.hasConflict) {
            alert(`冲突检测：${data.conflict.conflicts.map(c => c.title).join(', ')}`);
        }
    });
```

#### 1.4.6 统一命名常量类（强烈推荐）

在项目中创建一个 `FieldConstants.java` 或 `DatabaseConstants.java`，集中定义所有字段名：

```java
package com.reminder.constants;

/**
 * 数据库字段名常量
 * 用于：@Column(name = "xxx")、Mapper XML、动态SQL
 * 优点：IDE自动补全 + 编译期检查，避免手写字符串错误
 */
public final class ReminderFields {
    public static final String TABLE = "reminders";
    public static final String ID = "id";
    public static final String USER_ID = "user_id";
    public static final String TITLE = "title";
    public static final String REMIND_TIME = "remind_time";
    public static final String TIMEZONE = "timezone";
    public static final String ADVANCE_MINUTES = "advance_minutes";
    public static final String CRON_EXPRESSION = "cron_expression";
    public static final String LOCATION = "location";
    public static final String PRIORITY = "priority";
    public static final String STATUS = "status";
    public static final String CONFIRM_STATUS = "confirm_status";
    public static final String CONFIRM_ATTEMPTS = "confirm_attempts";
    public static final String LAST_CONFIRM_REMIND_AT = "last_confirm_remind_at";
    public static final String SOURCE = "source";
    public static final String CREATED_AT = "created_at";
    public static final String UPDATED_AT = "updated_at";

    private ReminderFields() {}
}
```

**使用示例：**

```java
// 实体类中
@Column(name = ReminderFields.USER_ID, nullable = false)
private Long userId;

// Mapper XML中，使用 ${} 引用常量（需要配合MyBatis配置）
// 或在Java代码中使用
String sql = "SELECT * FROM " + ReminderFields.TABLE + " WHERE " + ReminderFields.USER_ID + " = ?";
```

#### 1.4.7 变更记录模板

每次修改字段时，必须按以下格式记录：

| 日期 | 表名 | 变更类型 | 字段名 | 变更说明 | 数据字典更新 | DDL更新 | 实体类更新 | DTO更新 | 前端更新 | 测试状态 |
|------|------|----------|--------|----------|:----:|:----:|:----:|:----:|:----:|:----:|
| 2026-07-20 | users | 新增 | `wechat_openid` | 微信登录预留 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 2026-07-22 | reminders | 修改 | `remind_time` | 类型从DATE改为DATETIME | ✅ | ✅ | ✅ | — | — | ✅ |
| 2026-07-25 | reminders | 删除 | `remind_date` | 合并到remind_time | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

#### 1.4.8 启动前验证脚本

项目启动前，执行以下SQL验证数据库字段与数据字典是否一致：

```sql
-- 验证users表字段
SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_KEY, EXTRA
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = 'reminder' AND TABLE_NAME = 'users'
ORDER BY ORDINAL_POSITION;

-- 验证reminders表字段
SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_KEY, EXTRA
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = 'reminder' AND TABLE_NAME = 'reminders'
ORDER BY ORDINAL_POSITION;

-- 验证所有外键
SELECT 
    TABLE_NAME, CONSTRAINT_NAME, COLUMN_NAME,
    REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME
FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE
WHERE REFERENCED_TABLE_NAME IS NOT NULL AND TABLE_SCHEMA = 'reminder';
```

将查询结果与数据字典逐字段比对，发现不一致时**立即修改代码或数据库，保持三者对齐**。


## 二、枚举类定义

### 2.1 Gender（性别）

| 枚举值 | 说明 | 显示名称 |
|--------|------|----------|
| `MALE` | 男性 | 男 |
| `FEMALE` | 女性 | 女 |
| `PRIVATE` | 不公开（默认） | 不公开 |

**Java定义**：
```java
public enum Gender {
    MALE("男"),
    FEMALE("女"),
    PRIVATE("不公开");

    private final String displayName;
}
```

**数据库存储**：VARCHAR(10)，默认 `'PRIVATE'`

---

### 2.2 ReminderStatus（提醒状态）

| 枚举值 | 说明 | 是否可推送 |
|--------|------|------------|
| `PENDING` | 待推送（默认） | 是 |
| `SENT` | 已推送 | 否 |
| `CANCELLED` | 已取消 | 否 |
| `EXPIRED` | 已过期（未推送） | 否 |

**Java定义**：
```java
public enum ReminderStatus {
    PENDING,
    SENT,
    CANCELLED,
    EXPIRED
}
```

**数据库存储**：VARCHAR(20)，默认 `'PENDING'`

---

### 2.3 ConfirmStatus（确认状态）

| 枚举值 | 说明 | 触发条件 |
|--------|------|----------|
| `PENDING` | 待确认（默认） | 新建提醒时 |
| `CONFIRMED` | 用户主动确认 | 用户点击确认按钮 |
| `CANCELLED` | 用户主动取消 | 用户点击取消按钮 |
| `DEFAULTED` | 默认确认 | 3次提醒后未响应 |

**Java定义**：
```java
public enum ConfirmStatus {
    PENDING,
    CONFIRMED,
    CANCELLED,
    DEFAULTED
}
```

**数据库存储**：VARCHAR(20)，默认 `'PENDING'`

---

### 2.4 Priority（优先级）

| 枚举值 | 说明 | 排序权重 |
|--------|------|----------|
| `LOW` | 低 | 3 |
| `NORMAL` | 普通（默认） | 2 |
| `HIGH` | 高 | 1 |

**Java定义**：
```java
public enum Priority {
    LOW,
    NORMAL,
    HIGH
}
```

**数据库存储**：VARCHAR(20)，默认 `'NORMAL'`

---

### 2.5 ReminderSource（提醒来源）

| 枚举值 | 说明 |
|--------|------|
| `USER_INPUT` | 用户手动输入（默认） |
| `WEATHER_LINK` | 天气联动自动生成 |
| `EXCEL_IMPORT` | Excel批量导入 |
| `PERIOD_REMINDER` | 月经周期自动生成 |

**Java定义**：
```java
public enum ReminderSource {
    USER_INPUT,
    WEATHER_LINK,
    EXCEL_IMPORT,
    PERIOD_REMINDER
}
```

**数据库存储**：VARCHAR(20)，默认 `'USER_INPUT'`

---

### 2.6 PushChannel（推送渠道）

| 枚举值 | 说明 |
|--------|------|
| `WEBSOCKET` | WebSocket推送（默认） |
| `EMAIL` | 邮件推送（预留） |
| `SMS` | 短信推送（预留） |

**Java定义**：
```java
public enum PushChannel {
    WEBSOCKET,
    EMAIL,
    SMS
}
```

**数据库存储**：VARCHAR(20)，默认 `'WEBSOCKET'`

---

### 2.7 IntentType（意图类型，用于对话统计）

| 枚举值 | 说明 |
|--------|------|
| `REMINDER` | 设置提醒 |
| `QUERY` | 查询日程 |
| `WEATHER` | 查询天气 |
| `DELETE` | 删除提醒 |
| `UPDATE` | 修改提醒 |
| `CONFIRM` | 确认操作 |
| `CANCEL` | 取消操作 |
| `UNKNOWN` | 无法识别 |

**Java定义**：
```java
public enum IntentType {
    REMINDER,
    QUERY,
    WEATHER,
    DELETE,
    UPDATE,
    CONFIRM,
    CANCEL,
    UNKNOWN
}
```

**数据库存储**：VARCHAR(20)，可为 `NULL`

---

### 2.8 PeriodStatus（月经状态，计算得出，不持久化）

| 枚举值 | 说明 | 显示名称 |
|--------|------|----------|
| `SAFE` | 安全期 | 安全期 ♀️ |
| `OVULATORY` | 排卵期 | 排卵期 🌸 |
| `PREMENSTRUAL` | 经期前 | 经期前 📋 |
| `MENSTRUATING` | 经期中 | 经期中 🌙 |
| `UNKNOWN` | 未知（未设置） | 未设置 |

**Java定义**：
```java
public enum PeriodStatus {
    SAFE("安全期 ♀️"),
    OVULATORY("排卵期 🌸"),
    PREMENSTRUAL("经期前 📋"),
    MENSTRUATING("经期中 🌙"),
    UNKNOWN("未设置");

    private final String displayName;
}
```

**数据库存储**：不持久化，由 `PeriodService` 实时计算

---

### 2.9 SessionStatus（确认会话状态）

| 枚举值 | 说明 | 触发条件 |
|--------|------|----------|
| `PENDING` | 待确认（默认） | 创建确认会话时 |
| `CONFIRMED` | 用户已确认 | 用户点击确认/覆盖按钮 |
| `CANCELLED` | 用户已取消 | 用户点击取消按钮 |
| `EXPIRED` | 已过期 | 超过超时时间未操作 |

**Java定义**：
```java
public enum SessionStatus {
    PENDING,
    CONFIRMED,
    CANCELLED,
    EXPIRED
}
```

**数据库存储**：VARCHAR(20)，默认 `'PENDING'`

---

### 2.10 ConfirmStage（确认阶段）

| 枚举值 | 说明 |
|--------|------|
| `INFO_CONFIRM` | 信息确认（默认） |
| `CONFLICT_RESOLUTION` | 冲突解决中 |
| `OVERWRITE_CONFIRM` | 覆盖确认 |
| `AUTO_CONFIRM` | 自动确认 |

**Java定义**：使用字符串常量，不使用枚举类
```java
public static final String STAGE_INFO_CONFIRM = "INFO_CONFIRM";
public static final String STAGE_CONFLICT_RESOLUTION = "CONFLICT_RESOLUTION";
public static final String STAGE_OVERWRITE_CONFIRM = "OVERWRITE_CONFIRM";
public static final String STAGE_AUTO_CONFIRM = "AUTO_CONFIRM";
```

**数据库存储**：VARCHAR(30)，默认 `'INFO_CONFIRM'`


## 三、表结构定义

### 3.1 表名：users（用户表）

**用途**：存储所有注册用户的核心信息

#### 3.1.1 字段定义

| 字段名 | 数据库字段 | Java类型 | JSON类型 | 必填 | 默认值 | 约束 | 说明 |
|--------|-----------|----------|----------|------|--------|------|------|
| id | `id` | `Long` | `number` | 是 | 自增 | PK | 用户ID，主键 |
| phone | `phone` | `String` | `string` | 是 | 无 | UNIQUE | 手机号（11位），唯一登录标识 |
| password | `password` | `String` | `string` | 是 | 无 | NOT NULL | BCrypt加密后的密码 |
| nickname | `nickname` | `String` | `string` | 否 | `'用户'+手机号后4位` | 无 | 用户昵称 |
| avatar | `avatar` | `String` | `string` | 否 | `NULL` | 无 | 头像（Base64或URL） |
| gender | `gender` | `Gender` | `string` | 是 | `'PRIVATE'` | 无 | 性别枚举 |
| city | `city` | `String` | `string` | 是 | `'广州'` | 无 | 默认城市（天气查询用） |
| defaultAdvanceMinutes | `default_advance_minutes` | `Integer` | `number` | 是 | `10` | 无 | 默认提前量（分钟），可选值：5,10,15,30,60 |
| remindInterval | `remind_interval` | `Integer` | `number` | 是 | `10` | 无 | 补提醒间隔（分钟），可选值：5,10,15,20,30 |
| cycleDays | `cycle_days` | `Integer` | `number` | 是 | `28` | 无 | 月经周期（天），仅女性用户有意义，范围21-35 |
| lastPeriodStart | `last_period_start` | `LocalDate` | `string` | 否 | `NULL` | 无 | 上次月经开始日期 |
| periodDuration | `period_duration` | `Integer` | `number` | 是 | `5` | 无 | 经期持续天数，范围3-7 |
| enablePeriodReminder | `enable_period_reminder` | `Boolean` | `boolean` | 是 | `TRUE` | 无 | 是否开启经期提醒 |
| periodConfirmStatus | `period_confirm_status` | `ConfirmStatus` | `string` | 是 | `'PENDING'` | 无 | 经期确认状态 |
| periodPostponedUntil | `period_postponed_until` | `LocalDate` | `string` | 否 | `NULL` | 无 | 经期确认推迟到哪天 |
| periodPostponeCount | `period_postpone_count` | `Integer` | `number` | 是 | `0` | 无 | 经期已推迟次数，范围0-3 |
| createdAt | `created_at` | `LocalDateTime` | `string` | 是 | 当前时间 | 无 | 创建时间 |
| updatedAt | `updated_at` | `LocalDateTime` | `string` | 是 | 当前时间 | 无 | 更新时间 |

#### 3.1.2 建表DDL

```sql
CREATE TABLE users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    phone VARCHAR(11) UNIQUE NOT NULL,
    password VARCHAR(255) NOT NULL,
    nickname VARCHAR(50) DEFAULT NULL,
    avatar TEXT DEFAULT NULL,
    gender VARCHAR(10) NOT NULL DEFAULT 'PRIVATE',
    city VARCHAR(50) NOT NULL DEFAULT '广州',
    default_advance_minutes INT NOT NULL DEFAULT 10,
    remind_interval INT NOT NULL DEFAULT 10,
    cycle_days INT NOT NULL DEFAULT 28,
    last_period_start DATE DEFAULT NULL,
    period_duration INT NOT NULL DEFAULT 5,
    enable_period_reminder BOOLEAN NOT NULL DEFAULT TRUE,
    period_confirm_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    period_postponed_until DATE DEFAULT NULL,
    period_postpone_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_users_phone ON users(phone);
CREATE INDEX idx_users_gender ON users(gender);
```

#### 3.1.3 扩展性预留

| 预留字段 | 类型 | 说明 | 计划版本 |
|----------|------|------|----------|
| `email` | VARCHAR(100) | 邮箱（预留） | v2.0 |
| `openid` | VARCHAR(100) | 微信OpenID（预留） | v2.0 |
| `last_login_at` | TIMESTAMP | 最后登录时间 | v1.1 |


### 3.2 表名：reminders（提醒表）

**用途**：存储所有用户设置的提醒

#### 3.2.1 字段定义

| 字段名 | 数据库字段 | Java类型 | JSON类型 | 必填 | 默认值 | 约束 | 说明 |
|--------|-----------|----------|----------|------|--------|------|------|
| id | `id` | `Long` | `number` | 是 | 自增 | PK | 提醒ID，主键 |
| userId | `user_id` | `Long` | `number` | 是 | 无 | FK | 所属用户ID |
| title | `title` | `String` | `string` | 是 | 无 | NOT NULL | 提醒事项 |
| remindTime | `remind_time` | `LocalDateTime` | `string` | 是 | 无 | NOT NULL | 计划推送时间 |
| timezone | `timezone` | `String` | `string` | 是 | `'Asia/Shanghai'` | 无 | 时区 |
| advanceMinutes | `advance_minutes` | `Integer` | `number` | 是 | `10` | 无 | 提前量（分钟） |
| cronExpression | `cron_expression` | `String` | `string` | 否 | `NULL` | 无 | Cron表达式，非NULL时为周期提醒 |
| location | `location` | `String` | `string` | 否 | `NULL` | 无 | 地点 |
| priority | `priority` | `Priority` | `string` | 是 | `'NORMAL'` | 无 | 优先级 |
| status | `status` | `ReminderStatus` | `string` | 是 | `'PENDING'` | 无 | 推送状态 |
| confirmStatus | `confirm_status` | `ConfirmStatus` | `string` | 是 | `'PENDING'` | 无 | 确认状态 |
| confirmAttempts | `confirm_attempts` | `Integer` | `number` | 是 | `0` | 无 | 已确认提醒次数，范围0-3 |
| lastConfirmRemindAt | `last_confirm_remind_at` | `LocalDateTime` | `string` | 否 | `NULL` | 无 | 最后一次确认提醒时间 |
| source | `source` | `ReminderSource` | `string` | 是 | `'USER_INPUT'` | 无 | 来源 |
| createdAt | `created_at` | `LocalDateTime` | `string` | 是 | 当前时间 | 无 | 创建时间 |
| updatedAt | `updated_at` | `LocalDateTime` | `string` | 是 | 当前时间 | 无 | 更新时间 |

#### 3.2.2 建表DDL

```sql
CREATE TABLE reminders (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    remind_time TIMESTAMP NOT NULL,
    timezone VARCHAR(50) NOT NULL DEFAULT 'Asia/Shanghai',
    advance_minutes INT NOT NULL DEFAULT 10,
    cron_expression VARCHAR(100) DEFAULT NULL,
    location VARCHAR(255) DEFAULT NULL,
    priority VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    confirm_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    confirm_attempts INT NOT NULL DEFAULT 0,
    last_confirm_remind_at TIMESTAMP DEFAULT NULL,
    source VARCHAR(20) NOT NULL DEFAULT 'USER_INPUT',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- 索引（性能关键）
CREATE INDEX idx_reminders_user_status_time ON reminders(user_id, status, remind_time);
CREATE INDEX idx_reminders_remind_time ON reminders(remind_time);
CREATE INDEX idx_reminders_confirm_status ON reminders(confirm_status);
CREATE INDEX idx_reminders_user_time ON reminders(user_id, remind_time);
```

#### 3.2.3 扩展性预留

| 预留字段 | 类型 | 说明 | 计划版本 |
|----------|------|------|----------|
| `parent_id` | BIGINT | 周期提醒的父ID | v1.1 |
| `tags` | VARCHAR(255) | 标签（JSON数组） | v2.0 |
| `notes` | TEXT | 备注 | v1.1 |


### 3.3 表名：push_logs（推送日志表）

**用途**：记录每一次推送的详细日志，用于防重复推送和数据分析

#### 3.3.1 字段定义

| 字段名 | 数据库字段 | Java类型 | JSON类型 | 必填 | 默认值 | 约束 | 说明 |
|--------|-----------|----------|----------|------|--------|------|------|
| id | `id` | `Long` | `number` | 是 | 自增 | PK | 日志ID，主键 |
| reminderId | `reminder_id` | `Long` | `number` | 是 | 无 | FK | 关联的提醒ID |
| userId | `user_id` | `Long` | `number` | 是 | 无 | FK | 所属用户ID |
| pushedAt | `pushed_at` | `LocalDateTime` | `string` | 是 | 当前时间 | 无 | 推送时间 |
| userRead | `user_read` | `Boolean` | `boolean` | 是 | `FALSE` | 无 | 用户是否已读 |
| readAt | `read_at` | `LocalDateTime` | `string` | 否 | `NULL` | 无 | 已读时间 |
| pushChannel | `push_channel` | `PushChannel` | `string` | 是 | `'WEBSOCKET'` | 无 | 推送渠道 |

#### 3.3.2 建表DDL

```sql
CREATE TABLE push_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    reminder_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    pushed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    user_read BOOLEAN NOT NULL DEFAULT FALSE,
    read_at TIMESTAMP DEFAULT NULL,
    push_channel VARCHAR(20) NOT NULL DEFAULT 'WEBSOCKET',
    FOREIGN KEY (reminder_id) REFERENCES reminders(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_push_logs_user_pushed ON push_logs(user_id, pushed_at);
CREATE INDEX idx_push_logs_reminder ON push_logs(reminder_id);
```

#### 3.3.3 扩展性预留

| 预留字段 | 类型 | 说明 | 计划版本 |
|----------|------|------|----------|
| `error_message` | TEXT | 推送失败时的错误信息 | v1.1 |
| `retry_count` | INT | 重试次数 | v1.1 |


### 3.4 表名：verification_codes（验证码表）

**用途**：存储手机验证码，支持发送频率控制和过期校验

#### 3.4.1 字段定义

| 字段名 | 数据库字段 | Java类型 | JSON类型 | 必填 | 默认值 | 约束 | 说明 |
|--------|-----------|----------|----------|------|--------|------|------|
| id | `id` | `Long` | `number` | 是 | 自增 | PK | ID，主键 |
| phone | `phone` | `String` | `string` | 是 | 无 | 无 | 手机号 |
| code | `code` | `String` | `string` | 是 | 无 | NOT NULL | 6位验证码 |
| expiresAt | `expires_at` | `LocalDateTime` | `string` | 是 | 无 | NOT NULL | 过期时间（创建+5分钟） |
| used | `used` | `Boolean` | `boolean` | 是 | `FALSE` | 无 | 是否已使用 |
| createdAt | `created_at` | `LocalDateTime` | `string` | 是 | 当前时间 | 无 | 创建时间 |

#### 3.4.2 建表DDL

```sql
CREATE TABLE verification_codes (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    phone VARCHAR(11) NOT NULL,
    code VARCHAR(6) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_verification_codes_phone ON verification_codes(phone);
CREATE INDEX idx_verification_codes_expires ON verification_codes(expires_at);
```

#### 3.4.3 扩展性预留

| 预留字段 | 类型 | 说明 | 计划版本 |
|----------|------|------|----------|
| `ip_address` | VARCHAR(45) | 请求IP | v1.1 |
| `send_count` | INT | 该手机号当日发送次数 | 冗余字段 |


### 3.5 表名：user_dialog_stats（对话统计表）

**用途**：记录用户每一次对话输入，用于动态提示词学习和用户行为分析

#### 3.5.1 字段定义

| 字段名 | 数据库字段 | Java类型 | JSON类型 | 必填 | 默认值 | 约束 | 说明 |
|--------|-----------|----------|----------|------|--------|------|------|
| id | `id` | `Long` | `number` | 是 | 自增 | PK | ID，主键 |
| userId | `user_id` | `Long` | `number` | 是 | 无 | FK | 用户ID |
| inputText | `input_text` | `String` | `string` | 是 | 无 | NOT NULL | 用户输入文本 |
| intentType | `intent_type` | `IntentType` | `string` | 否 | `NULL` | 无 | LLM识别的意图类型 |
| parsedTime | `parsed_time` | `LocalDateTime` | `string` | 否 | `NULL` | 无 | LLM解析出的提醒时间 |
| parsedTitle | `parsed_title` | `String` | `string` | 否 | `NULL` | 无 | LLM解析出的事项标题 |
| createdAt | `created_at` | `LocalDateTime` | `string` | 是 | 当前时间 | 无 | 输入时间 |

#### 3.5.2 建表DDL

```sql
CREATE TABLE user_dialog_stats (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    input_text TEXT NOT NULL,
    intent_type VARCHAR(20) DEFAULT NULL,
    parsed_time TIMESTAMP DEFAULT NULL,
    parsed_title VARCHAR(255) DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_user_dialog_stats_user_time ON user_dialog_stats(user_id, created_at);
CREATE INDEX idx_user_dialog_stats_intent ON user_dialog_stats(intent_type);
```


### 3.6 表名：confirmation_sessions（确认会话表）

**用途**：存储提醒创建确认流程的会话信息，支持冲突检测、超时确认、覆盖操作等

#### 3.6.1 字段定义

| 字段名 | 数据库字段 | Java类型 | JSON类型 | 必填 | 默认值 | 约束 | 说明 |
|--------|-----------|----------|----------|------|--------|------|------|
| sessionId | `session_id` | `String` | `string` | 是 | UUID | PK(36位) | 会话ID，主键，UUID格式 |
| userId | `user_id` | `Long` | `number` | 是 | 无 | FK | 所属用户ID |
| reminderId | `reminder_id` | `Long` | `number` | 否 | `NULL` | FK | 关联的提醒ID（确认后生成） |
| originalInput | `original_input` | `String` | `string` | 否 | `NULL` | TEXT | 用户原始输入文本 |
| parsedData | `parsed_data` | `String` | `object` | 否 | `NULL` | JSON | LLM解析后的完整数据（JSON格式） |
| parsedTitle | `parsed_title` | `String` | `string` | 否 | `NULL` | VARCHAR(200) | 解析出的标题 |
| parsedTime | `parsed_time` | `LocalDateTime` | `string` | 否 | `NULL` | 无 | 解析出的提醒时间 |
| parsedLocation | `parsed_location` | `String` | `string` | 否 | `NULL` | VARCHAR(200) | 解析出的地点 |
| parsedPriority | `parsed_priority` | `String` | `string` | 否 | `NULL` | VARCHAR(20) | 解析出的优先级 |
| parsedAdvanceMinutes | `parsed_advance_minutes` | `Integer` | `number` | 否 | `NULL` | 无 | 解析出的提前量 |
| stage | `stage` | `String` | `string` | 是 | `'INFO_CONFIRM'` | VARCHAR(30) | 确认阶段（见2.10） |
| status | `status` | `SessionStatus` | `string` | 是 | `'PENDING'` | VARCHAR(20) | 会话状态（见2.9） |
| createdAt | `created_at` | `LocalDateTime` | `string` | 是 | 当前时间 | 无 | 创建时间 |
| expiresAt | `expires_at` | `LocalDateTime` | `string` | 否 | `NULL` | 无 | 过期时间（超时自动确认） |
| updatedAt | `updated_at` | `LocalDateTime` | `string` | 是 | 当前时间 | 无 | 更新时间 |
| retryCount | `retry_count` | `Integer` | `number` | 是 | `0` | 无 | 已重试次数 |
| attemptCount | `attempt_count` | `Integer` | `number` | 是 | `0` | 无 | 已尝试次数 |
| maxAttempts | `max_attempts` | `Integer` | `number` | 是 | `3` | 无 | 最大尝试次数 |
| intervalMinutes | `interval_minutes` | `Integer` | `number` | 是 | `10` | 无 | 补提醒间隔（分钟） |

#### 3.6.2 建表DDL

```sql
CREATE TABLE confirmation_sessions (
    session_id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    reminder_id BIGINT DEFAULT NULL,
    original_input TEXT DEFAULT NULL,
    parsed_data JSON DEFAULT NULL,
    parsed_title VARCHAR(200) DEFAULT NULL,
    parsed_time TIMESTAMP DEFAULT NULL,
    parsed_location VARCHAR(200) DEFAULT NULL,
    parsed_priority VARCHAR(20) DEFAULT NULL,
    parsed_advance_minutes INT DEFAULT NULL,
    stage VARCHAR(30) NOT NULL DEFAULT 'INFO_CONFIRM',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP DEFAULT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    retry_count INT NOT NULL DEFAULT 0,
    attempt_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 3,
    interval_minutes INT NOT NULL DEFAULT 10,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (reminder_id) REFERENCES reminders(id) ON DELETE SET NULL
);

CREATE INDEX idx_user_status ON confirmation_sessions(user_id, status);
CREATE INDEX idx_expires_at ON confirmation_sessions(expires_at);
CREATE INDEX idx_stage ON confirmation_sessions(stage);
```

#### 3.6.3 扩展性预留

| 预留字段 | 类型 | 说明 | 计划版本 |
|----------|------|------|----------|
| `conflict_ids` | JSON | 冲突提醒ID列表 | v1.1 |
| `resolved_conflict_ids` | JSON | 已解决的冲突ID列表 | v1.1 |
| `auto_confirmed` | BOOLEAN | 是否自动确认 | v1.1 |


## 四、API接口字段映射

### 4.1 注册接口

| 请求字段 | 类型 | 必填 | 说明 |
|----------|------|------|------|
| phone | string | 是 | 手机号 |
| code | string | 是 | 6位验证码 |
| password | string | 是 | 密码（8位以上，含字母+数字） |

**响应字段**：
| 字段 | 类型 | 说明 |
|------|------|------|
| success | boolean | 是否成功 |
| message | string | 提示信息 |

---

### 4.2 登录接口

| 请求字段 | 类型 | 必填 | 说明 |
|----------|------|------|------|
| phone | string | 是 | 手机号 |
| password | string | 是 | 密码 |

**响应字段**：
| 字段 | 类型 | 说明 |
|------|------|------|
| token | string | JWT Token |
| userId | number | 用户ID |
| nickname | string | 昵称 |
| avatar | string | 头像 |

---

### 4.3 创建提醒接口

| 请求字段 | 类型 | 必填 | 说明 |
|----------|------|------|------|
| userId | number | 是 | 用户ID |
| title | string | 是 | 事项名称 |
| remindTime | string | 是 | ISO-8601格式时间 |
| timezone | string | 否 | 时区，默认Asia/Shanghai |
| advanceMinutes | number | 否 | 提前量，默认用户配置 |
| cronExpression | string | 否 | Cron表达式 |
| location | string | 否 | 地点 |
| priority | string | 否 | 优先级，默认NORMAL |
| source | string | 否 | 来源，默认USER_INPUT |

**响应字段**：
| 字段 | 类型 | 说明 |
|------|------|------|
| id | number | 提醒ID |
| conflict | object | 冲突检测结果（见 ConflictResponse） |
| conflict.hasConflict | boolean | 是否有冲突 |
| conflict.conflicts | array | 冲突的提醒列表 |
| conflict.suggestedTime | string | 建议的时间 |
| success | boolean | 是否创建成功 |
| sessionId | string | 确认会话ID（需进一步确认时返回） |
| redirect | string | 下一步操作（"confirm"需确认，"success"直接成功） |

---

### 4.4 查询日历接口

**请求参数**：
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| userId | number | 是 | 用户ID |
| year | number | 是 | 年份 |
| month | number | 是 | 月份（1-12） |

**响应字段**：
| 字段 | 类型 | 说明 |
|------|------|------|
| year | number | 年份 |
| month | number | 月份 |
| days | array | 每日数据 |
| days[].day | number | 日期号 |
| days[].hasReminder | boolean | 是否有提醒 |
| days[].reminderCount | number | 提醒数量 |
| days[].reminders | array | 提醒列表（点击日期时加载） |

---

### 4.5 确认流程接口（Confirmation）

#### 4.5.1 创建确认会话（触发冲突检测）

`POST /api/confirmations/create`

**请求字段**：
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| userId | number | 是 | 用户ID |
| title | string | 是 | 事项名称 |
| remindTime | string | 是 | ISO-8601格式时间 |
| timezone | string | 否 | 时区，默认Asia/Shanghai |
| advanceMinutes | number | 否 | 提前量，默认10 |
| location | string | 否 | 地点 |
| priority | string | 否 | 优先级，默认NORMAL |
| source | string | 否 | 来源，默认USER_INPUT |

**WebSocket推送** `type: "confirmation_card"`：
| 字段 | 类型 | 说明 |
|------|------|------|
| type | string | 固定 `"confirmation_card"` |
| message | string | 提示文本 |
| expiresAt | string | 过期时间（ISO-8601） |
| timeout | number | 超时秒数 |
| data | object | 卡片数据 |
| data.sessionId | string | 会话ID |
| data.title | string | 提醒标题 |
| data.remindTime | string | 提醒时间 |
| data.priority | string | 优先级 |
| data.location | string | 地点 |
| data.advanceMinutes | number | 提前量 |
| data.confidence | number | LLM解析置信度 |
| data.hasConflict | boolean | 是否有冲突 |
| data.conflictMessage | string | 冲突描述文本 |
| data.conflictId | number | 第一个冲突提醒ID |
| data.conflictList | string(JSON) | 冲突列表JSON字符串 |
| data.suggestedTime | string | 建议的新时间 |
| actions | array | 操作按钮列表 |
| actions[].id | string | 操作ID（confirm/edit/cancel/overwrite） |
| actions[].label | string | 按钮文字 |
| actions[].style | string | 样式类名 |

---

#### 4.5.2 确认创建（带冲突覆盖）

`POST /api/confirmations/{sessionId}/confirm`

**请求字段**：
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| userId | number | 是 | 用户ID（Query参数） |
| conflictIds | array\<number\> | 否 | 要覆盖的冲突提醒ID列表（Body） |

**响应字段**：
| 字段 | 类型 | 说明 |
|------|------|------|
| success | boolean | 是否成功 |
| message | string | 结果提示（含被覆盖标题） |
| reminderId | number | 创建的提醒ID |
| overwrittenTitle | string | 被覆盖的提醒标题（多个用、分隔） |

---

#### 4.5.3 取消确认

`POST /api/confirmations/{sessionId}/cancel`

**请求字段**：
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| userId | number | 是 | 用户ID（Query参数） |

**响应字段**：
| 字段 | 类型 | 说明 |
|------|------|------|
| success | boolean | 是否成功 |
| message | string | 结果提示 |

---

#### 4.5.4 全部覆盖

`POST /api/confirmations/{sessionId}/overwrite`

**请求字段**：
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| userId | number | 是 | 用户ID（Query参数） |

**响应字段**：
| 字段 | 类型 | 说明 |
|------|------|------|
| success | boolean | 是否成功 |
| message | string | 结果提示 |
| reminderId | number | 新提醒ID |
| overwrittenTitle | string | 被覆盖的所有提醒标题 |

---

#### 4.5.5 批量覆盖（选中指定冲突项）

`POST /api/confirmations/{sessionId}/batch-overwrite`

**请求字段**：
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| userId | number | 是 | 用户ID（Query参数） |
| conflictIds | array\<number\> | 是 | 要覆盖的冲突提醒ID列表（Body） |

**响应字段**：
| 字段 | 类型 | 说明 |
|------|------|------|
| success | boolean | 是否成功 |
| message | string | 结果提示 |
| reminderId | number | 新提醒ID |
| overwrittenTitle | string | 被覆盖的提醒标题 |

---

#### 4.5.6 单项覆盖

`POST /api/confirmations/{sessionId}/overwrite/{conflictId}`

**请求字段**：
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| userId | number | 是 | 用户ID（Query参数） |
| conflictId | number | 是 | 要覆盖的冲突提醒ID（Path参数） |

**响应字段**：
| 字段 | 类型 | 说明 |
|------|------|------|
| success | boolean | 是否成功 |
| message | string | 结果提示 |
| reminderId | number | 新提醒ID |
| overwrittenTitle | string | 被覆盖的提醒标题 |

---

#### 4.5.7 修改（重新解析）

`POST /api/confirmations/{sessionId}/reparse`

**请求字段**：
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| userId | number | 是 | 用户ID（Query参数） |
| title | string | 是 | 新标题（Body） |
| remindTime | string | 是 | 新时间（Body） |
| location | string | 否 | 新地点 |
| priority | string | 否 | 新优先级 |
| advanceMinutes | number | 否 | 新提前量 |

**响应字段**：
| 字段 | 类型 | 说明 |
|------|------|------|
| success | boolean | 是否成功 |
| message | string | 结果提示 |

---

#### 4.5.8 智能修改

`POST /api/confirmations/{sessionId}/smart-edit`

**请求字段**：
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| userId | number | 是 | 用户ID（Query参数） |
| userInput | string | 是 | 用户修改意图文本（Body） |
| currentData | object | 是 | 当前卡片数据（Body） |

**响应字段**：
| 字段 | 类型 | 说明 |
|------|------|------|
| success | boolean | 是否成功 |
| message | string | 结果提示（如"已修改时间为：下午3点"） |
| field | string | 修改的字段名 |
| newValue | string | 新值 |

---

#### 4.5.9 获取会话数据

`GET /api/confirmations/{sessionId}/data`

**请求参数**：
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| userId | number | 是 | 用户ID |

**响应字段**：返回 `ConfirmationSession` 全部字段 + 解析后数据

---

#### 4.5.10 手动操作接口

`POST /api/confirmations/action`

**请求字段**：
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| sessionId | string | 是 | 会话ID |
| action | string | 是 | `"confirm"` 或 `"cancel"` |
| userId | number | 是 | 用户ID |

**响应字段**：
| 字段 | 类型 | 说明 |
|------|------|------|
| success | boolean | 是否成功 |
| message | string | 结果提示 |

---

#### 4.5.11 批量操作接口

`POST /api/confirmations/action-all`

**请求字段**：
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| action | string | 是 | `"confirmAll"` 或 `"cancelAll"` |
| userId | number | 是 | 用户ID |

**响应字段**：
| 字段 | 类型 | 说明 |
|------|------|------|
| success | boolean | 是否成功 |
| message | string | 结果提示（含操作数量） |

---

### 4.6 冲突检测接口

#### 4.6.1 检查时间冲突

`POST /api/reminders/check-conflict`

**请求字段**：
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| userId | number | 是 | 用户ID |
| remindTime | string | 是 | ISO-8601格式时间 |
| title | string | 否 | 事项标题（用于排除自身） |

**响应字段**（`ConflictResponse`）：
| 字段 | 类型 | 说明 |
|------|------|------|
| hasConflict | boolean | 是否有冲突 |
| conflicts | array | 冲突列表 |
| conflicts[].id | number | 冲突提醒ID |
| conflicts[].title | string | 冲突提醒标题 |
| conflicts[].remindTime | string | 冲突提醒时间 |
| suggestedTime | string | 建议的新时间（ISO-8601） |


## 五、Java实体类

### 5.1 User.java

```java
package com.reminder.entity;

import javax.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 11)
    private String phone;

    @Column(nullable = false)
    private String password;

    private String nickname;

    @Lob
    private String avatar;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Gender gender = Gender.PRIVATE;

    @Column(nullable = false)
    private String city = "广州";

    @Column(name = "default_advance_minutes", nullable = false)
    private Integer defaultAdvanceMinutes = 10;

    @Column(name = "remind_interval", nullable = false)
    private Integer remindInterval = 10;

    @Column(name = "cycle_days", nullable = false)
    private Integer cycleDays = 28;

    @Column(name = "last_period_start")
    private LocalDate lastPeriodStart;

    @Column(name = "period_duration", nullable = false)
    private Integer periodDuration = 5;

    @Column(name = "enable_period_reminder", nullable = false)
    private Boolean enablePeriodReminder = true;

    @Column(name = "period_confirm_status", nullable = false)
    @Enumerated(EnumType.STRING)
    private ConfirmStatus periodConfirmStatus = ConfirmStatus.PENDING;

    @Column(name = "period_postponed_until")
    private LocalDate periodPostponedUntil;

    @Column(name = "period_postpone_count", nullable = false)
    private Integer periodPostponeCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // Getter / Setter
    // 省略getter和setter方法，实际开发时用Lombok @Data
}
```

### 5.2 Reminder.java

```java
package com.reminder.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "reminders")
public class Reminder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String title;

    @Column(name = "remind_time", nullable = false)
    private LocalDateTime remindTime;

    @Column(nullable = false)
    private String timezone = "Asia/Shanghai";

    @Column(name = "advance_minutes", nullable = false)
    private Integer advanceMinutes = 10;

    @Column(name = "cron_expression")
    private String cronExpression;

    private String location;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Priority priority = Priority.NORMAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReminderStatus status = ReminderStatus.PENDING;

    @Column(name = "confirm_status", nullable = false)
    @Enumerated(EnumType.STRING)
    private ConfirmStatus confirmStatus = ConfirmStatus.PENDING;

    @Column(name = "confirm_attempts", nullable = false)
    private Integer confirmAttempts = 0;

    @Column(name = "last_confirm_remind_at")
    private LocalDateTime lastConfirmRemindAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReminderSource source = ReminderSource.USER_INPUT;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // Getter / Setter
}
```

### 5.3 PushLog.java

```java
package com.reminder.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "push_logs")
public class PushLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reminder_id", nullable = false)
    private Long reminderId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "pushed_at", nullable = false)
    private LocalDateTime pushedAt;

    @Column(name = "user_read", nullable = false)
    private Boolean userRead = false;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "push_channel", nullable = false)
    @Enumerated(EnumType.STRING)
    private PushChannel pushChannel = PushChannel.WEBSOCKET;

    // Getter / Setter
}
```

### 5.4 VerificationCode.java

```java
package com.reminder.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "verification_codes")
public class VerificationCode {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 11)
    private String phone;

    @Column(nullable = false, length = 6)
    private String code;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private Boolean used = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // Getter / Setter
}
```

### 5.5 UserDialogStats.java

```java
package com.reminder.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_dialog_stats")
public class UserDialogStats {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "input_text", nullable = false)
    private String inputText;

    @Column(name = "intent_type")
    @Enumerated(EnumType.STRING)
    private IntentType intentType;

    @Column(name = "parsed_time")
    private LocalDateTime parsedTime;

    @Column(name = "parsed_title")
    private String parsedTitle;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // Getter / Setter
}
```

### 5.6 ConfirmationSession.java

```java
package com.dormrepair.remindme.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "confirmation_sessions", indexes = {
    @Index(name = "idx_user_status", columnList = "user_id, status"),
    @Index(name = "idx_expires_at", columnList = "expires_at"),
    @Index(name = "idx_stage", columnList = "stage")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfirmationSession {
    @Id
    @Column(name = "session_id", length = 36)
    private String sessionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "reminder_id")
    private Long reminderId;

    @Column(name = "original_input", columnDefinition = "TEXT")
    private String originalInput;

    @Column(name = "parsed_data", columnDefinition = "JSON")
    private String parsedData;

    @Column(name = "parsed_title", length = 200)
    private String parsedTitle;

    @Column(name = "parsed_time")
    private LocalDateTime parsedTime;

    @Column(name = "parsed_location", length = 200)
    private String parsedLocation;

    @Column(name = "parsed_priority", length = 20)
    private String parsedPriority;

    @Column(name = "parsed_advance_minutes")
    private Integer parsedAdvanceMinutes;

    @Column(name = "stage", length = 30)
    @Builder.Default
    private String stage = "INFO_CONFIRM";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    @Builder.Default
    private SessionStatus status = SessionStatus.PENDING;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "retry_count")
    @Builder.Default
    private Integer retryCount = 0;

    @Column(name = "attempt_count")
    @Builder.Default
    private Integer attemptCount = 0;

    @Column(name = "max_attempts")
    @Builder.Default
    private Integer maxAttempts = 3;

    @Column(name = "interval_minutes")
    @Builder.Default
    private Integer intervalMinutes = 10;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum SessionStatus {
        PENDING,
        CONFIRMED,
        CANCELLED,
        EXPIRED
    }
}
```

### 5.7 DTO 定义

#### 5.7.1 ConflictResponse.java（冲突检测响应）

```java
package com.dormrepair.remindme.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConflictResponse {
    private boolean hasConflict;
    private List<ConflictItem> conflicts;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private LocalDateTime suggestedTime;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConflictItem {
        private Long id;
        private String title;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
        private LocalDateTime remindTime;
    }
}
```

#### 5.7.2 ConfirmationCardResponse.java（确认卡片响应）

```java
package com.dormrepair.remindme.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfirmationCardResponse {
    private String type;
    private String message;
    @Builder.Default
    private ConfirmationData data = new ConfirmationData();
    private List<Action> actions;
    private Integer timeout;
    private String expiresAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConfirmationData {
        private String sessionId;
        private String title;
        private String remindTime;
        private String priority;
        private String location;
        private Integer advanceMinutes;
        private Double confidence;
        private String weatherSuggestion;
        private Boolean hasConflict;
        private String conflictMessage;
        private Long conflictId;
        private String conflictList;
        private String suggestedTime;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Action {
        private String id;
        private String label;
        private String style;
    }
}
```

#### 5.7.3 ReminderCreateRequest.java（创建提醒请求）

```java
package com.dormrepair.remindme.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ReminderCreateRequest {
    @NotNull(message = "用户ID不能为空")
    private Long userId;

    @NotBlank(message = "事项名称不能为空")
    @Size(max = 255, message = "事项名称不超过255个字符")
    private String title;

    @NotNull(message = "提醒时间不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private LocalDateTime remindTime;

    private String timezone = "Asia/Shanghai";
    private Integer advanceMinutes;
    private String cronExpression;
    private String location;
    private String priority = "NORMAL";
    private String source = "USER_INPUT";
}
```

#### 5.7.4 ReminderResponse.java（提醒响应）

```java
package com.dormrepair.remindme.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReminderResponse {
    private Long id;
    private Long userId;
    private String title;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private LocalDateTime remindTime;
    private String timezone;
    private Integer advanceMinutes;
    private String cronExpression;
    private String location;
    private String priority;
    private String status;
    private String confirmStatus;
    private Integer confirmAttempts;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private LocalDateTime lastConfirmRemindAt;
    private String source;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private LocalDateTime createdAt;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private LocalDateTime updatedAt;
}
```


## 六、字段映射总表（快速查找）

| 数据库字段 | 实体类字段 | JSON字段 | 所属表 | 说明 |
|-----------|-----------|----------|--------|------|
| id | id | id | users | 用户ID |
| phone | phone | phone | users | 手机号 |
| password | password | password | users | 加密密码 |
| nickname | nickname | nickname | users | 昵称 |
| avatar | avatar | avatar | users | 头像 |
| gender | gender | gender | users | 性别 |
| city | city | city | users | 默认城市 |
| default_advance_minutes | defaultAdvanceMinutes | defaultAdvanceMinutes | users | 默认提前量 |
| remind_interval | remindInterval | remindInterval | users | 补提醒间隔 |
| cycle_days | cycleDays | cycleDays | users | 月经周期 |
| last_period_start | lastPeriodStart | lastPeriodStart | users | 上次月经开始 |
| period_duration | periodDuration | periodDuration | users | 经期持续天数 |
| enable_period_reminder | enablePeriodReminder | enablePeriodReminder | users | 开启经期提醒 |
| period_confirm_status | periodConfirmStatus | periodConfirmStatus | users | 经期确认状态 |
| period_postponed_until | periodPostponedUntil | periodPostponedUntil | users | 经期推迟到 |
| period_postpone_count | periodPostponeCount | periodPostponeCount | users | 经期推迟次数 |
| user_id | userId | userId | reminders | 所属用户 |
| title | title | title | reminders | 事项名称 |
| remind_time | remindTime | remindTime | reminders | 推送时间 |
| timezone | timezone | timezone | reminders | 时区 |
| advance_minutes | advanceMinutes | advanceMinutes | reminders | 提前量 |
| cron_expression | cronExpression | cronExpression | reminders | Cron表达式 |
| location | location | location | reminders | 地点 |
| priority | priority | priority | reminders | 优先级 |
| status | status | status | reminders | 推送状态 |
| confirm_status | confirmStatus | confirmStatus | reminders | 确认状态 |
| confirm_attempts | confirmAttempts | confirmAttempts | reminders | 确认提醒次数 |
| last_confirm_remind_at | lastConfirmRemindAt | lastConfirmRemindAt | reminders | 最后确认提醒时间 |
| source | source | source | reminders | 来源 |
| pushed_at | pushedAt | pushedAt | push_logs | 推送时间 |
| user_read | userRead | userRead | push_logs | 用户已读 |
| read_at | readAt | readAt | push_logs | 已读时间 |
| push_channel | pushChannel | pushChannel | push_logs | 推送渠道 |
| code | code | code | verification_codes | 验证码 |
| expires_at | expiresAt | expiresAt | verification_codes | 过期时间 |
| used | used | used | verification_codes | 验证码是否已使用 |
| input_text | inputText | inputText | user_dialog_stats | 输入文本 |
| intent_type | intentType | intentType | user_dialog_stats | 意图类型 |
| parsed_time | parsedTime | parsedTime | user_dialog_stats | 解析的时间 |
| parsed_title | parsedTitle | parsedTitle | user_dialog_stats | 解析的事项 |
| session_id | sessionId | sessionId | confirmation_sessions | 会话ID |
| user_id | userId | userId | confirmation_sessions | 所属用户 |
| reminder_id | reminderId | reminderId | confirmation_sessions | 关联提醒ID |
| original_input | originalInput | originalInput | confirmation_sessions | 原始输入 |
| parsed_data | parsedData | parsedData | confirmation_sessions | 解析数据(JSON) |
| parsed_title | parsedTitle | parsedTitle | confirmation_sessions | 解析标题 |
| parsed_time | parsedTime | parsedTime | confirmation_sessions | 解析时间 |
| parsed_location | parsedLocation | parsedLocation | confirmation_sessions | 解析地点 |
| parsed_priority | parsedPriority | parsedPriority | confirmation_sessions | 解析优先级 |
| parsed_advance_minutes | parsedAdvanceMinutes | parsedAdvanceMinutes | confirmation_sessions | 解析提前量 |
| stage | stage | stage | confirmation_sessions | 确认阶段 |
| status | status | status | confirmation_sessions | 会话状态 |
| expires_at | expiresAt | expiresAt | confirmation_sessions | 过期时间 |
| retry_count | retryCount | retryCount | confirmation_sessions | 重试次数 |
| attempt_count | attemptCount | attemptCount | confirmation_sessions | 尝试次数 |
| max_attempts | maxAttempts | maxAttempts | confirmation_sessions | 最大尝试次数 |
| interval_minutes | intervalMinutes | intervalMinutes | confirmation_sessions | 补提醒间隔 |
| created_at | createdAt | createdAt | 所有表 | 创建时间 |
| updated_at | updatedAt | updatedAt | users/reminders/confirmation_sessions | 更新时间 |


## 七、版本记录

| 版本 | 日期 | 修改内容 | 作者 |
|------|------|----------|------|
| v1.0 | 2026-07-18 | 初始版本，完整数据字典 | — |
| v1.1 | 2026-07-28 | 新增 SessionStatus/ConfirmStage 枚举、confirmation_sessions 表、确认流程 API（4.5节）、冲突检测 API（4.6节）、DTO 定义（5.7节）、ConfirmationSession 实体类（5.6节）、更新字段映射总表 | — |

---

## 八、文件新建前置检查规范

**适用项目**：校园生活提醒助手（life-reminder）
**版本**：v1.0
**生效日期**：2026-07-18

### 8.1 核心原则

> **每次新建文件之前，必须先执行前置检查，确认依赖和相关文件齐备后再创建。**

### 8.2 前置检查流程

```
Step 1: 明确要创建的文件类型和路径
    ↓
Step 2: 检查技术依赖是否已添加（pom.xml）
    ↓
Step 3: 检查相关配置文件是否存在（application.yml）
    ↓
Step 4: 检查关联的实体类/DTO是否存在
    ↓
Step 5: 如检查通过 → 创建文件
Step 6: 如检查不通过 → 记录缺失项 → 请示确认 → 补全后再创建
```

### 8.3 检查清单模板

| 检查项 | 检查内容 | 通过条件 | 不通过时的处理 |
|--------|----------|----------|----------------|
| 依赖检查 | 创建该文件所需的Maven依赖是否已在pom.xml中声明 | 所有必需依赖已存在 | 先添加依赖，再创建文件 |
| 配置检查 | 创建该文件所需的配置项是否已在application.yml中定义 | 所有必需配置已存在 | 先添加配置，再创建文件 |
| 关联文件检查 | 该文件依赖的实体类/DTO/常量等是否已存在 | 关联文件已存在或本次同步创建 | 先创建关联文件，再创建目标文件 |
| 数据字典检查 | 涉及的字段是否已在DATA_DICT.md中定义 | 所有字段已在数据字典中记录 | 先更新数据字典，再创建文件 |

### 8.4 分类检查清单（按文件类型）

#### 8.4.1 新建实体类（Entity）前

```markdown
## 新建实体类前检查清单

### 依赖检查
- [ ] spring-boot-starter-data-jpa 已在 pom.xml 中
- [ ] lombok 已在 pom.xml 中
- [ ] jackson-datatype-jsr310 已在 pom.xml 中

### 配置检查
- [ ] spring.jpa 配置已在 application.yml 中
- [ ] spring.datasource 配置已在 application.yml 中

### 数据字典检查
- [ ] 对应表的字段已在 DATA_DICT.md 的"表结构定义"章节中记录
- [ ] 所有枚举值已在 DATA_DICT.md 的"枚举类定义"章节中记录

### 关联文件检查
- [ ] 引用的其他实体类已存在
- [ ] 使用的枚举类已存在
```

#### 8.4.2 新建Repository前

```markdown
## 新建Repository前检查清单

### 依赖检查
- [ ] spring-boot-starter-data-jpa 已在 pom.xml 中
- [ ] 对应的实体类已存在

### 关联文件检查
- [ ] 对应的Entity已创建
- [ ] 实体类的@Table注解中表名与数据库一致
```

#### 8.4.3 新建Service前

```markdown
## 新建Service前检查清单

### 依赖检查
- [ ] spring-boot-starter-web 已在 pom.xml 中
- [ ] 对应的Repository已存在

### 配置检查
- [ ] 如涉及LLM调用，spring.ai 配置已在 application.yml 中
- [ ] 如涉及JWT，jwt 配置已在 application.yml 中
- [ ] 如涉及天气API，weather 配置已在 application.yml 中

### 关联文件检查
- [ ] 对应的Repository已创建
- [ ] 使用的DTO已创建
- [ ] 使用的常量类已创建
```

#### 8.4.4 新建Controller前

```markdown
## 新建Controller前检查清单

### 依赖检查
- [ ] spring-boot-starter-web 已在 pom.xml 中
- [ ] spring-boot-starter-validation 已在 pom.xml 中
- [ ] 对应的Service已存在

### 关联文件检查
- [ ] 对应的Service已创建
- [ ] 请求DTO已创建
- [ ] 响应DTO已创建
- [ ] 全局异常处理器已创建
```

#### 8.4.5 新建配置类前

```markdown
## 新建配置类前检查清单

### 依赖检查
- [ ] spring-boot-configuration-processor 已在 pom.xml 中
- [ ] 对应的Starter已在pom.xml中

### 关联文件检查
- [ ] application.yml 中已有对应的配置项占位
```

### 8.5 新建文件请示模板

当检查发现缺失项时，必须按以下格式请示：

```markdown
## 📋 文件新建请示

### 拟创建文件
- 文件路径：`src/main/java/com/dormrepair/remindme/xxx/XxxXxx.java`
- 文件用途：简要说明

### 前置检查结果

#### ❌ 缺失项
| 序号 | 缺失类型 | 缺失内容 | 建议处理方式 |
|------|----------|----------|--------------|
| 1 | 依赖缺失 | xxx 未在pom.xml中声明 | 添加依赖，版本xx |
| 2 | 配置缺失 | xxx 未在application.yml中定义 | 添加配置项 |
| 3 | 关联文件缺失 | XxxEntity 不存在 | 先创建XxxEntity |
| 4 | 数据字典缺失 | xxx字段未在DATA_DICT.md中定义 | 先更新数据字典 |

#### ✅ 已通过项
| 序号 | 检查项 | 状态 |
|------|--------|------|
| 1 | spring-boot-starter-web | ✅ 已存在 |
| 2 | 数据库连接配置 | ✅ 已存在 |

### 请示结论
- [ ] 补全所有缺失项后创建
- [ ] 先补全以下项，其余后续补充：
- [ ] 放弃本次创建

### 拟补充的内容
（提供所有需要补充的代码/配置的完整内容）
```

### 8.6 完整示例

#### 示例：创建 ReminderService

```markdown
## 📋 文件新建请示

### 拟创建文件
- 文件路径：`src/main/java/com/dormrepair/remindme/service/ReminderService.java`
- 文件用途：提醒业务逻辑处理，包含CRUD、冲突检测、时间推断

### 前置检查结果

#### ❌ 缺失项
| 序号 | 缺失类型 | 缺失内容 | 建议处理方式 |
|------|----------|----------|--------------|
| 1 | 关联文件缺失 | ReminderRepository 不存在 | 先创建 ReminderRepository |
| 2 | 关联文件缺失 | ReminderCreateRequest DTO 不存在 | 先创建请求DTO |
| 3 | 关联文件缺失 | ReminderResponse DTO 不存在 | 先创建响应DTO |
| 4 | 关联文件缺失 | FieldConstants 常量类不存在 | 先创建常量类 |

#### ✅ 已通过项
| 序号 | 检查项 | 状态 |
|------|--------|------|
| 1 | spring-boot-starter-data-jpa | ✅ 已存在 |
| 2 | spring-boot-starter-web | ✅ 已存在 |
| 3 | Reminder 实体类 | ✅ 已存在 |
| 4 | UserService（被引用） | ✅ 已存在 |

### 请示结论
- [x] 补全所有缺失项后创建

### 拟补充的内容

#### 1. 先创建 FieldConstants.java
（提供完整代码）

#### 2. 再创建 ReminderRepository.java
（提供完整代码）

#### 3. 再创建 ReminderCreateRequest.java
（提供完整代码）

#### 4. 再创建 ReminderResponse.java
（提供完整代码）

#### 5. 最后创建 ReminderService.java
（提供完整代码）
```

### 8.7 生效范围

| 操作 | 是否适用 |
|------|----------|
| 新建实体类（Entity） | ✅ 是 |
| 新建Repository | ✅ 是 |
| 新建Service | ✅ 是 |
| 新建Controller | ✅ 是 |
| 新建DTO | ✅ 是 |
| 新建配置类（Config） | ✅ 是 |
| 新建工具类（Utils） | ✅ 是 |
| 新建常量类（Constants） | ✅ 是 |
| 新建异常类（Exception） | ✅ 是 |
| 新建枚举类（Enums） | ✅ 是 |
| 修改已有文件 | ⚠️ 仅检查依赖 |
| 删除已有文件 | ⚠️ 检查是否有引用 |

### 8.8 执行确认

```markdown
## ✅ 规范确认

本人已阅读并理解上述"项目文件新建前置检查规范"，在后续开发中：

1. 每次新建文件前，先执行前置检查
2. 发现缺失项时，先请示确认再补全
3. 不绕过检查直接创建文件
4. 检查结果和请示记录在对话中留存

确认人：___________  日期：___________
```
